(ns snapdragon.ext.content
  "Content script: selection capture, popover, and ephemeral sidenotes."
  (:require [clojure.string :as str]
            [snapdragon.config :as config]
            [snapdragon.core :as core]
            [snapdragon.ui :as ui]))

;; :host/:root are the shadow host and root; :bubble/:popover are transient elements;
;; :pending is the selection snapshot the popover is for; :notes maps id -> note state.
(defonce state (atom {:notes {} :show-bubble? true}))

;;; Highlights (CSS Custom Highlight API; leaves page DOM untouched)

(def ^:private hl-name "snapdragon")

(defn- highlight ^js []
  (when (exists? js/CSS.highlights)
    (or (.get js/CSS.highlights hl-name)
        (let [hl (js/Highlight.)]
          (.set js/CSS.highlights hl-name hl)
          (let [style (js/document.createElement "style")]
            (set! (.-textContent style)
                  (str "::highlight(" hl-name ") { background-color: rgba(250, 200, 40, .38); }"))
            (.append js/document.head style))
          hl))))

(defn- highlight! [range] (some-> (highlight) (.add range)))
(defn- unhighlight! [range] (some-> (highlight) (.delete range)))

;;; Selection snapshot

(def ^:private block-selector
  "p,li,dd,dt,td,th,blockquote,pre,h1,h2,h3,h4,h5,h6,figcaption,section,article,main")

(defn- nearest-block [^js range]
  (let [n (.-commonAncestorContainer range)
        el (if (= 1 (.-nodeType n)) n (.-parentElement n))]
    (or (.closest el block-selector) el)))

(defn- context-block
  "`block` widened while its text stays small."
  [^js block]
  (loop [el block]
    (let [p (.-parentElement el)]
      (if (and p (not= p js/document.documentElement) (< (count (.-textContent p)) 6000))
        (recur p)
        el))))

(defn- in-host? [^js node]
  (when-let [host (:host @state)] (.contains host node)))

(defn- snapshot
  "Capture the current selection with surrounding text, or nil."
  []
  (let [sel (js/window.getSelection)]
    (when (and sel (pos? (.-rangeCount sel)) (not (.-isCollapsed sel)))
      (let [range (.cloneRange (.getRangeAt sel 0))
            text (str/trim (.toString range))]
        (when (and (seq text) (not (in-host? (.-commonAncestorContainer range))))
          (let [block (nearest-block range)
                ctx (context-block block)
                before (doto (js/document.createRange)
                         (.selectNodeContents ctx)
                         (.setEnd (.-startContainer range) (.-startOffset range)))
                after (doto (js/document.createRange)
                        (.selectNodeContents ctx)
                        (.setStart (.-endContainer range) (.-endOffset range)))
                before (.toString before)
                after (.toString after)]
            {:range  range
             :block  block
             :text   text
             :before before
             :after  after
             :target {:exact  text
                      :prefix (subs before (max 0 (- (count before) 32)))
                      :suffix (subs after 0 (min 32 (count after)))}}))))))

(defn- end-pos
  "Document coords just below the end of the selection."
  [^js range]
  (let [rects (.getClientRects range)
        r (if (pos? (.-length rects)) (aget rects (dec (.-length rects))) (.getBoundingClientRect range))]
    {:x (+ (.-right r) js/window.scrollX)
     :y (+ (.-bottom r) js/window.scrollY 4)}))

;;; Shadow host

(defn- root ^js []
  (or (:root @state)
      (let [host (js/document.createElement "snapdragon-host")
            root (.attachShadow host #js {:mode "open"})]
        (set! (.. host -style -cssText)
              "all: initial; position: absolute; top: 0; left: 0; width: 0; height: 0; z-index: 2147483647;")
        (let [style (js/document.createElement "style")]
          (set! (.-textContent style) ui/styles)
          (.append root style))
        (.append js/document.documentElement host)
        (swap! state assoc :host host :root root)
        root)))

(defn- remove-key! [k]
  (some-> (get @state k) .remove)
  (swap! state dissoc k))

(defn- close-popover! []
  (remove-key! :bubble)
  (remove-key! :popover)
  (when-let [{:keys [range]} (:pending @state)]
    (unhighlight! range))
  (swap! state dissoc :pending))

;;; Notes
;; Note state: {:el :snap :annotation :port :streaming (id of the LLM message being streamed)}

(defn- note [id] (get-in @state [:notes id]))
(defn- update-ann! [id f & args] (apply swap! state update-in [:notes id :annotation] f args))
(defn- msg-text [id mid]
  (some #(when (= mid (:msg/id %)) (:msg/text %)) (get-in (note id) [:annotation :annotation/thread])))

(defn- close-note! [id]
  (when-let [{:keys [el port snap]} (note id)]
    (.remove el)
    (unhighlight! (:range snap))
    (some-> port .disconnect)
    (swap! state update :notes dissoc id)))

(defn- schedule-render!
  "Re-render a streaming message at most once per frame."
  [id mid]
  (when-not (:render-pending? (note id))
    (swap! state assoc-in [:notes id :render-pending?] true)
    (js/requestAnimationFrame
     (fn []
       (when-let [{:keys [el]} (note id)]
         (swap! state assoc-in [:notes id :render-pending?] false)
         (ui/set-turn-text! el (str mid) (msg-text id mid)))))))

(defn- end-stream!
  "Finish streaming message `mid` with `status`, showing `text` under it."
  [id mid status text & {:keys [error? action]}]
  (when-let [{:keys [el port]} (note id)]
    (some-> port .disconnect)
    (swap! state update-in [:notes id] dissoc :port :streaming)
    (update-ann! id core/update-message mid assoc :msg/status status)
    (ui/set-turn-text! el (str mid) (msg-text id mid))
    (ui/set-turn-status! el (str mid) text :error? error? :action action)
    (ui/set-busy! el false)))

(defn- on-port-message [id mid msg]
  (let [{:keys [type provider model text stop-reason message needs-config]}
        (js->clj msg :keywordize-keys true)
        {:keys [el streaming]} (note id)]
    (when (and el (= mid streaming))
      (case type
        "meta"  (do (update-ann! id core/update-message mid assoc
                                 :msg/author {:type :llm :provider provider :model model})
                    (ui/set-turn-who! el (str mid) model))
        "delta" (do (when (= "" (msg-text id mid))
                      (ui/set-turn-status! el (str mid) nil))
                    (update-ann! id core/update-message mid update :msg/text str text)
                    (schedule-render! id mid))
        "done"  (case stop-reason
                  "refusal"    (end-stream! id mid :refused "The model declined to answer.")
                  "max_tokens" (end-stream! id mid :done "(Answer cut off at the token limit.)")
                  (end-stream! id mid :done nil))
        "error" (end-stream! id mid :error message :error? true
                             :action (when needs-config
                                       ["Open settings"
                                        #(js/chrome.runtime.sendMessage #js {:type "open-options"})]))
        nil))))

(defn- add-turn! [id msg]
  (update-ann! id core/add-message msg)
  (ui/append-turn! (:el (note id)) msg))

(defn- comment! [id text]
  (add-turn! id (core/message {:type :human} text :kind :comment)))

(defn- ask! [id question]
  (when-not (:streaming (note id))
    (add-turn! id (core/message {:type :human} question :kind :ask))
    (let [reply (core/message {:type :llm} "" :status :streaming)
          mid (:msg/id reply)
          {:keys [el snap annotation]} (note id)]
      (add-turn! id reply)
      (ui/set-turn-status! el (str mid) "Thinking…")
      (ui/set-busy! el true)
      (swap! state assoc-in [:notes id :streaming] mid)
      (try
        (let [port (js/chrome.runtime.connect #js {:name "snapdragon/ask"})]
          (swap! state assoc-in [:notes id :port] port)
          (.addListener (.-onMessage port) #(on-port-message id mid %))
          (.addListener (.-onDisconnect port)
                        #(when (= mid (:streaming (note id)))
                           (end-stream! id mid :error "Connection to the extension was lost." :error? true)))
          (.postMessage port (clj->js {:selection (:text snap)
                                       :before    (:before snap)
                                       :after     (:after snap)
                                       :title     js/document.title
                                       :url       js/location.href
                                       :turns     (core/prompt-turns (:annotation/thread annotation))})))
        (catch :default _
          (end-stream! id mid :error "Snapdragon was reloaded. Refresh the page to use it." :error? true))))))

(defn- stop! [id]
  (when-let [mid (:streaming (note id))]
    (end-stream! id mid :stopped "Stopped.")))

(defn- open-note!
  "Create a note for `snap` and return its id."
  [snap kind]
  (close-popover!)
  (let [ann (core/new-annotation {:url js/location.href :title js/document.title
                                  :target (:target snap) :kind kind})
        id (str (:annotation/id ann))
        range (:range snap)
        pos (ui/sidenote-position {:sel-rect   (.getBoundingClientRect range)
                                   :block-rect (.getBoundingClientRect (:block snap))
                                   :doc-width  (.. js/document -documentElement -clientWidth)
                                   :view-width js/window.innerWidth
                                   :scroll-x   js/window.scrollX
                                   :scroll-y   js/window.scrollY})
        el (ui/sidenote (assoc pos
                               :quote (:text snap)
                               :on-close #(close-note! id)
                               :on-ask #(ask! id %)
                               :on-comment #(comment! id %)
                               :on-stop #(stop! id)
                               :on-copy #(js/navigator.clipboard.writeText
                                          (core/thread->markdown (:annotation (note id))))))]
    (highlight! range)
    (.append (root) el)
    (swap! state assoc-in [:notes id] {:el el :snap snap :annotation ann})
    id))

;;; Popover

(defn- open-popover! [snap]
  (close-popover!)
  (let [el (ui/popover (assoc (end-pos (:range snap))
                              :quote (:text snap)
                              :on-ask #(ask! (open-note! snap :ask) %)
                              :on-comment #(comment! (open-note! snap :comment) %)))]
    (highlight! (:range snap))
    (.append (root) el)
    (swap! state assoc :popover el :pending snap)
    (.focus (.querySelector el "textarea"))))

(defn- show-bubble! [snap]
  (remove-key! :bubble)
  (let [el (ui/bubble (assoc (end-pos (:range snap)) :on-ask #(open-popover! snap)))]
    (.append (root) el)
    (swap! state assoc :bubble el)))

;;; Events

(defn- from-host? [^js e]
  (when-let [host (:host @state)]
    (some #(identical? host %) (.composedPath e))))

(defn- on-mousedown [e]
  (when-not (from-host? e)
    (close-popover!)))

(defn- on-mouseup [e]
  (when (and (not (from-host? e)) (:show-bubble? @state))
    ;; Let the selection settle after the click.
    (js/setTimeout #(when-let [snap (snapshot)] (show-bubble! snap)) 10)))

(defn- on-keydown [^js e]
  (when (= "Escape" (.-key e))
    (close-popover!)))

(defn- on-runtime-message [^js msg _sender _respond]
  (when (= "open-popover" (.-type msg))
    (when-let [snap (snapshot)] (open-popover! snap)))
  false)

(defn- load-prefs! []
  (-> (config/load-settings)
      (.then #(swap! state assoc :show-bubble? (:show-bubble? %)))
      (.catch (fn [_] nil))))

(defn init []
  (when-not (.-__snapdragon js/window)
    (set! (.-__snapdragon js/window) true)
    (load-prefs!)
    (.addListener js/chrome.storage.onChanged load-prefs!)
    (.addListener js/chrome.runtime.onMessage on-runtime-message)
    (js/document.addEventListener "mousedown" on-mousedown true)
    (js/document.addEventListener "mouseup" on-mouseup)
    (js/document.addEventListener "keydown" on-keydown true)))
