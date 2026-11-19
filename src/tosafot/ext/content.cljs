(ns tosafot.ext.content
  "Content script: selection capture, toolbar, sidenotes, and saved notes on revisit."
  (:require [clojure.string :as str]
            [hyperphor.tunamelt.codec :as codec]
            [tosafot.anchor :as anchor]
            [tosafot.config :as config]
            [tosafot.core :as core]
            [tosafot.ui :as ui]))

;; :host/:root are the shadow host and root; :bubble is the transient selection toolbar.
;; :notes maps id -> {:annotation :range (nil for orphans) :el (when open) :port :streaming}.
;; Unsaved notes exist only while open; saved ones stay registered when closed.
(defonce state (atom {:notes {} :show-bubble? true :actions core/builtin-actions}))

;;; Highlights (CSS Custom Highlight API; leaves page DOM untouched).
;;; "active" marks open notes; "saved" marks closed saved notes.

(def ^:private hl-styles
  {"tosafot"       "background-color: rgba(250, 200, 40, .38);"
   "tosafot-saved" "background-color: rgba(250, 200, 40, .16); text-decoration: underline dotted rgba(200, 150, 0, .9);"})

(defn- highlight ^js [hl-name]
  (when (exists? js/CSS.highlights)
    (or (.get js/CSS.highlights hl-name)
        (let [hl (js/Highlight.)
              style (js/document.createElement "style")]
          (.set js/CSS.highlights hl-name hl)
          (set! (.-textContent style) (str "::highlight(" hl-name ") { " (hl-styles hl-name) " }"))
          (.append (or js/document.head js/document.documentElement) style)
          hl))))

(defn- highlight! [range & [saved?]]
  (when range
    (some-> (highlight (if saved? "tosafot" "tosafot-saved")) (.delete range))
    (some-> (highlight (if saved? "tosafot-saved" "tosafot")) (.add range))))

(defn- unhighlight! [range]
  (when range
    (doseq [n (keys hl-styles)] (some-> (highlight n) (.delete range)))))

;;; Page text index: one string over body's text nodes, mapped back to nodes.

(def ^:private skip-selector "script,style,noscript,template,textarea")

(defn- text-index []
  (let [walker (js/document.createTreeWalker
                js/document.body js/NodeFilter.SHOW_TEXT
                #js {"acceptNode" (fn [^js n]
                                    (if (some-> (.-parentElement n) (.closest skip-selector))
                                      js/NodeFilter.FILTER_REJECT
                                      js/NodeFilter.FILTER_ACCEPT))})
        nodes #js [] starts #js [] parts #js [] pos (volatile! 0) where (js/Map.)]
    (loop []
      (when-let [n (.nextNode walker)]
        (.set where n (.-length nodes))
        (.push nodes n)
        (.push starts @pos)
        (.push parts (.-data n))
        (vswap! pos + (.. n -data -length))
        (recur)))
    {:nodes nodes :starts starts :where where :text (.join parts "")}))

(defn- last-index
  "Largest i with (pred (aget arr i)), assuming pred is true then false; -1 if none."
  [^js arr pred]
  (loop [lo 0 hi (dec (.-length arr)) best -1]
    (if (> lo hi)
      best
      (let [mid (quot (+ lo hi) 2)]
        (if (pred (aget arr mid)) (recur (inc mid) hi mid) (recur lo (dec mid) best))))))

(defn- boundary->offset
  "Offset in the index text of a DOM boundary point."
  [{:keys [^js nodes ^js starts ^js where text]} ^js container offset]
  (if-let [i (and (= 3 (.-nodeType container)) (.get where container))]
    (+ (aget starts i) offset)
    (let [r (doto (js/document.createRange) (.setStart container offset))
          ;; nodes before the boundary point form a prefix of the array
          i (last-index nodes #(neg? (.comparePoint r % 0)))]
      (if (< (inc i) (.-length nodes)) (aget starts (inc i)) (count text)))))

(defn- offsets->range [{:keys [^js nodes ^js starts]} start end]
  (let [si (max 0 (last-index starts #(<= % start)))
        ei (max 0 (last-index starts #(< % end)))]
    (doto (js/document.createRange)
      (.setStart (aget nodes si) (- start (aget starts si)))
      (.setEnd (aget nodes ei) (- end (aget starts ei))))))

(defn- whitespace? [c] (and c (re-matches #"\s" c)))

(defn- range-target
  "Selector for `range`, trimmed of surrounding whitespace, or nil if it has no text."
  [idx ^js range]
  (let [text (:text idx)
        s0 (boundary->offset idx (.-startContainer range) (.-startOffset range))
        e0 (boundary->offset idx (.-endContainer range) (.-endOffset range))
        s (loop [s s0] (if (and (< s e0) (whitespace? (get text s))) (recur (inc s)) s))
        e (loop [e e0] (if (and (> e s) (whitespace? (get text (dec e)))) (recur (dec e)) e))]
    (when (< s e) (anchor/selector text s e))))

;;; LLM context around a range

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

(defn- range-context
  "Text before and after `range` within its widened block."
  [^js range]
  (let [ctx (context-block (nearest-block range))]
    {:before (.toString (doto (js/document.createRange)
                          (.selectNodeContents ctx)
                          (.setEnd (.-startContainer range) (.-startOffset range))))
     :after  (.toString (doto (js/document.createRange)
                          (.selectNodeContents ctx)
                          (.setStart (.-endContainer range) (.-endOffset range))))}))

(defn- in-host? [^js node]
  (when-let [host (:host @state)] (.contains host node)))

(defn- snapshot
  "Capture the current selection as {:range :target :text}, or nil."
  []
  (let [sel (js/window.getSelection)]
    (when (and sel (pos? (.-rangeCount sel)) (not (.-isCollapsed sel)))
      (let [range (.cloneRange (.getRangeAt sel 0))]
        (when-not (in-host? (.-commonAncestorContainer range))
          (when-let [target (range-target (text-index) range)]
            {:range range :target target :text (get-in target [:quote :exact])}))))))

(defn- end-pos
  "Document coords just below the end of `range`."
  [^js range]
  (let [rects (.getClientRects range)
        r (if (pos? (.-length rects)) (aget rects (dec (.-length rects))) (.getBoundingClientRect range))]
    {:x (+ (.-right r) js/window.scrollX)
     :y (+ (.-bottom r) js/window.scrollY 4)}))

;;; Shadow host

(defn- root ^js []
  (or (:root @state)
      (let [host (js/document.createElement "tosafot-host")
            root (.attachShadow host #js {:mode "open"})
            style (js/document.createElement "style")]
        (set! (.. host -style -cssText)
              "all: initial; position: absolute; top: 0; left: 0; width: 0; height: 0; z-index: 2147483647;")
        (set! (.-textContent style) ui/styles)
        (.append root style)
        (.append js/document.documentElement host)
        (swap! state assoc :host host :root root)
        root)))

(defn- remove-key! [k]
  (some-> (get @state k) .remove)
  (swap! state dissoc k))

(defn- close-toolbar! [] (remove-key! :bubble))

;;; Storage (via the service worker)

(defn- send!
  "Promise of the service worker's reply :value to a storage message."
  [m]
  (-> (js/chrome.runtime.sendMessage (clj->js (codec/jsonable m)))
      (.then (fn [^js r]
               (if (and r (.-ok r))
                 (.-value r)
                 (throw (doto (js/Error. (if r (.-error r) "No reply"))
                          (aset "code" (some-> r .-code)))))))))

(defn- note [id] (get-in @state [:notes id]))
(defn- saved? [id] (get-in (note id) [:annotation :annotation/saved?]))

(defn- persist! [id]
  (when (saved? id)
    (-> (send! {:type "ann/put" :annotation (:annotation (note id))})
        (.catch #(js/console.error "Tosafot: saving note failed" %)))))

;;; Orphans: saved notes whose text wasn't found on the page

(declare open-note!)

(defn- render-orphans! []
  (remove-key! :orphans-el)
  (let [orphans (->> (vals (:notes @state))
                     (filter #(and (nil? (:range %)) (get-in % [:annotation :annotation/saved?])))
                     (map :annotation)
                     (sort-by :annotation/created))]
    (when (seq orphans)
      (let [el (ui/orphan-panel orphans #(open-note! %))]
        (.append (root) el)
        (swap! state assoc :orphans-el el)))))

;;; Notes

(defn- update-ann! [id f & args] (apply swap! state update-in [:notes id :annotation] f args))

(defn- msg-text [id mid]
  (some #(when (= mid (:msg/id %)) (:msg/text %)) (get-in (note id) [:annotation :annotation/thread])))

(defn- schedule-render!
  "Re-render a streaming message at most once per frame."
  [id mid]
  (when-not (:render-pending? (note id))
    (swap! state assoc-in [:notes id :render-pending?] true)
    (js/requestAnimationFrame
     (fn []
       (swap! state assoc-in [:notes id :render-pending?] false)
       (when-let [el (:el (note id))]
         (ui/set-turn-text! el (str mid) (msg-text id mid)))))))

(defn- end-stream!
  "Finish streaming message `mid` with `status`, showing `text` under it."
  [id mid status text & {:keys [error? action]}]
  (when-let [{:keys [el port]} (note id)]
    (some-> port .disconnect)
    (swap! state update-in [:notes id] dissoc :port :streaming)
    (update-ann! id core/update-message mid assoc :msg/status status)
    (when el
      (ui/set-turn-text! el (str mid) (msg-text id mid))
      (ui/set-turn-status! el (str mid) text :error? error? :action action)
      (ui/set-busy! el false))
    (persist! id)))

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

(defn- save! [id]
  (update-ann! id assoc :annotation/saved? true)
  (some-> (:el (note id)) (ui/set-saved! true))
  (persist! id))

(defn- open-options! [] (js/chrome.runtime.sendMessage #js {:type "open-options"}))

(defn- export!
  "Write the note into the notebook's latest note, and save it."
  [id]
  (let [el (:el (note id))]
    (-> (send! {:type "pkg/export" :annotation (:annotation (note id))})
        (.then (fn [file]
                 (update-ann! id assoc :annotation/pkg-file file)
                 (save! id)
                 (some-> el (ui/note-message! (str "Added to " file)))))
        (.catch (fn [^js e]
                  (some-> el (ui/note-message! (.-message e) :error? true
                                               :action ["Open settings" open-options!])))))))

(defn- comment!
  "Add a comment. Notes with your own writing in them are saved automatically."
  [id text]
  (add-turn! id (core/message {:type :human} text :kind :comment))
  (if (saved? id) (persist! id) (save! id)))

(defn- edit-comment!
  "Replace comment `mid` (a string id) in note `id` with `text`."
  [id mid text]
  (when-let [el (:el (note id))]
    (update-ann! id core/edit-message (parse-uuid mid) text)
    (ui/set-turn-text! el mid text)
    (ui/mark-edited! el mid)
    (persist! id)))

(defn- prompt-context
  "Selection text and surrounding page text for a note's prompt."
  [{:keys [range annotation]}]
  (let [{:keys [exact prefix suffix]} (get-in annotation [:annotation/target :quote])]
    (assoc (if range (range-context range) {:before prefix :after suffix})
           :selection exact)))

(defn- ask!
  "Ask `question` in note `id`; `instructions` go to the model without being shown."
  [id question & [instructions]]
  (when-not (:streaming (note id))
    (add-turn! id (core/message {:type :human} question :kind :ask :instructions instructions))
    (persist! id)
    (let [reply (core/message {:type :llm} "" :status :streaming)
          mid (:msg/id reply)]
      (add-turn! id reply)
      (let [{:keys [el annotation] :as n} (note id)]
        (ui/set-turn-status! el (str mid) "Thinking…")
        (ui/set-busy! el true)
        (swap! state assoc-in [:notes id :streaming] mid)
        (try
          (let [port (js/chrome.runtime.connect #js {:name "tosafot/ask"})]
            (swap! state assoc-in [:notes id :port] port)
            (.addListener (.-onMessage port) #(on-port-message id mid %))
            (.addListener (.-onDisconnect port)
                          #(when (= mid (:streaming (note id)))
                             (end-stream! id mid :error "Connection to the extension was lost." :error? true)))
            (.postMessage port (clj->js (merge (prompt-context n)
                                               {:title js/document.title
                                                :url   js/location.href
                                                :turns (core/prompt-turns (:annotation/thread annotation))}))))
          (catch :default _
            (end-stream! id mid :error "Tosafot was reloaded. Refresh the page to use it." :error? true)))))))

(defn- stop! [id]
  (when-let [mid (:streaming (note id))]
    (end-stream! id mid :stopped "Stopped.")))

(defn- close-note!
  "Close a note's sidenote. Saved notes stay on the page as a highlight; unsaved ones are discarded."
  [id]
  (stop! id)
  (when-let [{:keys [el range]} (note id)]
    (some-> el .remove)
    (if (saved? id)
      (do (swap! state update-in [:notes id] dissoc :el)
          (highlight! range true))
      (do (unhighlight! range)
          (swap! state update :notes dissoc id)))))

(defn- delete! [id]
  (let [{:keys [el range annotation]} (note id)]
    (stop! id)
    (some-> el .remove)
    (unhighlight! range)
    (swap! state update :notes dissoc id)
    (render-orphans!)
    (-> (send! {:type "ann/delete" :id (str (:annotation/id annotation))})
        (.catch #(js/console.error "Tosafot: deleting note failed" %)))))

(defn- note-position [{:keys [range]}]
  (if range
    (ui/sidenote-position {:sel-rect   (.getBoundingClientRect range)
                           :block-rect (.getBoundingClientRect (nearest-block range))
                           :doc-width  (.. js/document -documentElement -clientWidth)
                           :view-width js/window.innerWidth
                           :scroll-x   js/window.scrollX
                           :scroll-y   js/window.scrollY})
    ;; Orphans open near the top right of the viewport.
    {:x (+ js/window.scrollX (max 8 (- js/window.innerWidth 380)))
     :y (+ js/window.scrollY 60)}))

(defn- save-prompt!
  "Add a prompt to the menus, saved in settings."
  [label prompt instructions]
  (let [a {:id (str (random-uuid)) :label label :place "menu" :prompt prompt
           :instructions (or instructions "")}]
    (swap! state update :actions (fnil conj []) a)
    (-> (config/load-settings)
        (.then #(config/save! (update % :actions conj a))))))

(defn- pkg-ready?
  "Whether Export to notebook is set up: a folder chosen and a tag."
  []
  (let [{:keys [pkg-folder pkg-tag]} @state]
    (and (seq pkg-folder) (not (str/blank? pkg-tag)))))

(defn- open-note!
  "Show the sidenote for registered note `id`, or bring it to the front if it's open.
  `mode` :comment makes Enter comment in a new note's composer; `prefill` is an action whose
  prompt starts in the composer."
  [id & [mode prefill]]
  (when-let [{:keys [el annotation range] :as n} (note id)]
    (if el
      (.append (root) el)
      (let [el (ui/sidenote (assoc (note-position n)
                                   :quote (get-in annotation [:annotation/target :quote :exact])
                                   :on-close #(close-note! id)
                                   :on-save #(save! id)
                                   :on-delete #(delete! id)
                                   :on-ask #(ask! id %1 %2)
                                   :on-comment #(comment! id %)
                                   :on-edit-comment #(edit-comment! id %1 %2)
                                   :on-stop #(stop! id)
                                   :mode mode
                                   :prefill prefill
                                   :actions (:actions @state)
                                   :on-save-prompt save-prompt!
                                   :on-edit open-options!
                                   :on-export (when (pkg-ready?) #(export! id))
                                   :export-label (:pkg-label @state)
                                   :on-copy #(js/navigator.clipboard.writeText
                                              (core/thread->markdown (:annotation (note id))))
                                   :on-share #(js/window.open
                                               (core/share-url % (core/share-text (:annotation (note id)) %))
                                               "_blank" "noopener")))]
        (ui/render-thread! el (:annotation/thread annotation))
        (ui/set-saved! el (boolean (:annotation/saved? annotation)))
        (highlight! range)
        (.append (root) el)
        (swap! state assoc-in [:notes id :el] el)))))

(defn- new-note!
  "Register and open a note for selection `snap`; return its id."
  [snap kind & [prefill]]
  (close-toolbar!)
  (let [ann (core/new-annotation {:url js/location.href :title js/document.title
                                  :target (:target snap) :kind kind})
        id (str (:annotation/id ann))]
    (swap! state assoc-in [:notes id] {:annotation ann :range (:range snap)})
    (open-note! id kind prefill)
    id))

(defn- open-empty-note!
  "Start a note on `snap` with nothing in it yet and focus its composer, holding `prefill`'s
  prompt if given."
  [snap kind & [prefill]]
  (let [id (new-note! snap kind prefill)]
    (when-let [^js ta (some-> (:el (note id)) (.querySelector "textarea"))]
      (.focus ta)
      (let [n (count (.-value ta))] (.setSelectionRange ta n n)))
    id))

(defn- empty-unsaved? [{:keys [annotation]}]
  (and (empty? (:annotation/thread annotation)) (not (:annotation/saved? annotation))))

;;; Saved notes on load

(defn- anchor-saved!
  "Try to place saved notes that have no range yet. Returns the number still unplaced."
  []
  (let [idx (text-index)
        placed (for [[id {:keys [range annotation]}] (:notes @state)
                     :when (and (nil? range) (:annotation/saved? annotation))
                     :let [[s e] (anchor/locate (:text idx) (:annotation/target annotation))]
                     :when s]
                 [id (offsets->range idx s e)])]
    (doseq [[id range] (doall placed)]
      (swap! state assoc-in [:notes id :range] range)
      (highlight! range (not (:el (note id)))))
    (render-orphans!)
    (count (filter #(and (nil? (:range %)) (get-in % [:annotation :annotation/saved?]))
                   (vals (:notes @state))))))

(defn- watch-for-orphans!
  "Pages that render late leave notes unplaced; retry on DOM changes for a while."
  []
  (let [deadline (+ (js/Date.now) 15000)
        timer (volatile! nil)
        obs (volatile! nil)
        retry (fn []
                (when (or (zero? (anchor-saved!)) (> (js/Date.now) deadline))
                  (some-> @obs .disconnect)))]
    (vreset! obs (js/MutationObserver.
                  (fn [_]
                    (js/clearTimeout @timer)
                    (vreset! timer (js/setTimeout retry 300)))))
    (.observe @obs js/document.body #js {:childList true :subtree true :characterData true})
    (js/setTimeout #(some-> @obs .disconnect) (- deadline (js/Date.now)))))

(defn- load-saved! []
  (-> (send! {:type "ann/list" :url (core/normalize-url js/location.href)})
      (.then (fn [anns]
               (doseq [a (js->clj anns :keywordize-keys true)
                       :let [ann (core/revive a)]]
                 (swap! state assoc-in [:notes (str (:annotation/id ann))] {:annotation ann}))
               (when (pos? (anchor-saved!))
                 (watch-for-orphans!))))
      (.catch #(js/console.error "Tosafot: loading saved notes failed" %))))

;;; Toolbar

(defn- show-toolbar! [snap]
  (remove-key! :bubble)
  (let [el (ui/toolbar (assoc (end-pos (:range snap))
                              :actions (:actions @state)
                              :on-ask #(open-empty-note! snap :ask)
                              :on-comment #(open-empty-note! snap :comment)
                              :on-action #(open-empty-note! snap :ask %)
                              :on-edit #(do (close-toolbar!) (open-options!))))]
    (.append (root) el)
    (ui/clamp-x! el)
    (swap! state assoc :bubble el)))

;;; Events

(defn- from-host? [^js e]
  (when-let [host (:host @state)]
    (some #(identical? host %) (.composedPath e))))

(defn- caret-at [x y]
  (if (.-caretPositionFromPoint js/document)
    (when-let [p (.caretPositionFromPoint js/document x y)] [(.-offsetNode p) (.-offset p)])
    (when-let [r (.caretRangeFromPoint js/document x y)] [(.-startContainer r) (.-startOffset r)])))

(defn- on-click
  "Clicking a saved note's highlighted text opens it."
  [^js e]
  (when (and (not (from-host? e)) (.-isCollapsed (js/window.getSelection)))
    (when-let [[node off] (caret-at (.-clientX e) (.-clientY e))]
      (when-let [id (->> (:notes @state)
                         (filter (fn [[_ {:keys [^js range]}]]
                                   (and range (try (.isPointInRange range node off) (catch :default _ false)))))
                         (sort-by (fn [[_ {:keys [^js range]}]] (count (.toString range))))
                         ffirst)]
        (open-note! id)))))

(defn- dismiss-unused!
  "Close notes that were opened but never written in, like a popover would be."
  []
  (doseq [[id n] (:notes @state) :when (and (:el n) (empty-unsaved? n))]
    (close-note! id)))

(defn- on-mousedown [e]
  (when-not (from-host? e)
    (close-toolbar!)
    (dismiss-unused!)))

(defn- on-mouseup [e]
  (when (and (not (from-host? e)) (:show-bubble? @state))
    ;; Let the selection settle after the click.
    (js/setTimeout #(when-let [snap (snapshot)] (show-toolbar! snap)) 10)))

(defn- on-keydown [^js e]
  (when (= "Escape" (.-key e))
    (close-toolbar!)
    (dismiss-unused!)))

(defn- on-runtime-message
  "Handles \"open-note\"; replies whether there was a selection to open one on."
  [^js msg _sender respond]
  (when (= "open-note" (.-type msg))
    (let [snap (snapshot)]
      (when snap (open-empty-note! snap :ask))
      (respond (boolean snap))))
  false)

(defn- load-prefs! []
  (-> (config/load-settings)
      (.then #(swap! state merge (select-keys % [:show-bubble? :actions :pkg-folder :pkg-tag :pkg-label])))
      (.catch (fn [_] nil))))

(defn init []
  (when-not (.-__tosafot js/window)
    (set! (.-__tosafot js/window) true)
    (load-prefs!)
    (load-saved!)
    (.addListener js/chrome.storage.onChanged load-prefs!)
    (.addListener js/chrome.runtime.onMessage on-runtime-message)
    (js/document.addEventListener "mousedown" on-mousedown true)
    (js/document.addEventListener "mouseup" on-mouseup)
    (js/document.addEventListener "click" on-click)
    (js/document.addEventListener "keydown" on-keydown true)))
