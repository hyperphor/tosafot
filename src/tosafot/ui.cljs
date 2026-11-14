(ns tosafot.ui
  "Selection toolbar and sidenote components. Rendered inside a shadow root; positions are document coords."
  (:require ["dompurify" :as DOMPurify]
            ["marked" :refer [marked]]
            [clojure.string :as str]
            [tosafot.core :as core]
            [tosafot.dom :refer [h qs]]))

(def styles "
:host { all: initial; }
* { box-sizing: border-box; }
.sd { position: absolute; font: 13px/1.45 system-ui, -apple-system, sans-serif; color: #1d1d1f;
      background: #fff; border: 1px solid #d8d8de; border-radius: 8px;
      box-shadow: 0 4px 18px rgba(0,0,0,.14); }
.logo { flex: none; display: block; }
.toolbar { display: flex; align-items: center; gap: 1px; padding: 3px; font-weight: 600; font-size: 12px;
           line-height: 1; user-select: none; white-space: nowrap; }
.toolbar .ic { padding: 2px 3px; }
.toolbar .item { padding: 5px 8px; border-radius: 5px; cursor: pointer; }
.toolbar .item:hover, .toolbar .item.open { background: #f0ecff; }
.toolbar .more { color: #777; padding: 5px 7px; }
.toolbar .sep { width: 1px; align-self: stretch; background: #e4e4ea; margin: 3px 2px; }
.menu { position: absolute; top: calc(100% + 4px); right: 0; min-width: 150px; padding: 4px;
        background: inherit; border: 1px solid #d8d8de; border-radius: 8px;
        box-shadow: 0 4px 18px rgba(0,0,0,.14); font-weight: normal; }
.menu .item { display: block; padding: 7px 9px; }
.menu hr { border: none; border-top: 1px solid #eee; margin: 4px 2px; }
.menu .dim { color: #888; }

.bubble:hover { background: #f3f0ff; }
.quote { color: #666; font-style: italic; border-left: 3px solid #e3c35a; padding: 1px 0 1px 7px;
         margin: 0 0 7px; max-height: 4.4em; overflow: hidden; }
.starters { display: flex; flex-wrap: wrap; gap: 5px; margin: 2px 0 4px; }
button { font: inherit; font-size: 12px; padding: 3px 9px; border: 1px solid #ccc; border-radius: 5px;
         background: #f7f7f9; color: inherit; cursor: pointer; }
button:hover { background: #ecebf5; }
button:disabled { opacity: .45; cursor: default; }
button.primary { background: #ece6ff; border-color: #b9a8f0; }
textarea { width: 100%; min-height: 3.2em; resize: vertical; font: inherit; padding: 5px;
           border: 1px solid #ccc; border-radius: 5px; background: #fff; color: inherit; }
.actions { display: flex; gap: 5px; align-items: center; margin-top: 5px; }
.actions .spacer { flex: 1; }
.note { width: 360px; max-height: 75vh; display: flex; flex-direction: column; }
.note-head { display: flex; align-items: center; gap: 6px; padding: 5px 8px; cursor: move;
             border-bottom: 1px solid #eee; color: #777; font-size: 11px; user-select: none; }
.note-head .title { flex: 1; color: #6a5acd; font-weight: 600; }
.note-head button { padding: 0 6px; border: none; background: none; font-size: 11px; color: inherit; }
.note-head button.close { font-size: 14px; }
.note-head button.save:disabled { opacity: 1; color: #2a7; }
.orphans { position: fixed; right: 16px; bottom: 16px; width: 300px; padding: 0; }
.orphans .badge { display: flex; align-items: center; gap: 6px; padding: 6px 10px; cursor: pointer;
                  font-size: 12px; }
.orphans ul { list-style: none; margin: 0; padding: 0 0 4px; border-top: 1px solid #eee;
              max-height: 40vh; overflow-y: auto; }
.orphans li { padding: 5px 10px; cursor: pointer; }
.orphans li:hover { background: #f3f0ff; }
.orphans .first { color: #888; font-size: 12px; }
.note-body { padding: 7px 10px; overflow-y: auto; flex: 1; min-height: 0; }
.turn { margin: 0 0 9px; }
.turn .meta { font-size: 11px; color: #999; margin-bottom: 1px; }
.turn.human .text { font-weight: 600; }
.turn.comment .text { font-weight: normal; background: #fff8dc; border-left: 3px solid #e3c35a;
                      padding: 3px 7px; border-radius: 0 4px 4px 0; }
.text > :first-child { margin-top: 0; }
.text > :last-child { margin-bottom: 0; }
.text pre { background: #f5f5f7; padding: 6px; border-radius: 4px; overflow-x: auto; }
.text code { font-size: 12px; }
.text p, .text ul, .text ol { margin: .45em 0; }
.text ul, .text ol { padding-left: 1.3em; }
.status { color: #888; font-size: 12px; margin-top: 2px; }
.status:empty { display: none; }
.status.error { color: #c0392b; }
.composer { padding: 6px 8px; border-top: 1px solid #eee; }
.composer textarea { min-height: 2.4em; }
@media (prefers-color-scheme: dark) {
  .sd { background: #232326; color: #e8e8ea; border-color: #444; }
  .toolbar .item:hover, .toolbar .item.open, button:hover { background: #34343a; }
  .toolbar .sep, .menu hr { background: #3a3a3e; border-color: #3a3a3e; }
  .menu { border-color: #444; }
  button, textarea { background: #2c2c30; border-color: #555; }
  button.primary { background: #3b3360; border-color: #6c5cb5; }
  .note-head, .composer, .orphans ul { border-color: #3a3a3e; }
  .orphans li:hover { background: #34343a; }
  .turn.comment .text { background: #3a3424; }
  .note-head .title { color: #b3a8f5; }
  .text pre { background: #2c2c30; }
}
")

(defn- px [n] (str (js/Math.round n) "px"))

;;; Same art as assets/icon-small.svg, built as DOM so page CSPs can't block it.
(def ^:private logo-rects
  [[4 4 120 120 24 "#5a4abb"]
   [20 20 88 12 3 "#f0ebff"] [20 38 20 70 3 "#f0ebff"] [88 38 20 70 3 "#f0ebff"]
   [46 38 36 52 3 "#ffffff"] [46 96 36 12 3 "#f0ebff"]
   [46 58 36 13 0 "#ffb81c"] [54 43 20 8 2 "#3d2f8f"]])

(defn logo
  "The Tosafot icon as an inline SVG element, `size` px square."
  [size]
  (let [ns "http://www.w3.org/2000/svg"
        svg (js/document.createElementNS ns "svg")]
    (doseq [[k v] {"viewBox" "0 0 128 128" "width" size "height" size
                   "class" "logo" "aria-hidden" "true"}]
      (.setAttribute svg k v))
    (doseq [[x y w hh r fill] logo-rects]
      (let [el (js/document.createElementNS ns "rect")]
        (doseq [[k v] {"x" x "y" y "width" w "height" hh "rx" r "fill" fill}]
          (.setAttribute el k v))
        (.appendChild svg el)))
    svg))

(defn- at [el {:keys [x y]}]
  (set! (.. el -style -left) (px x))
  (set! (.. el -style -top) (px y))
  el)

(defn- short-quote [s n]
  (core/clip-after (str/replace s #"\s+" " ") n))

(defn- isolate-keys!
  "Keep page hotkeys from firing while typing in `el`."
  [^js el]
  (doseq [t ["keydown" "keyup" "keypress"]]
    (.addEventListener el t #(.stopPropagation %))))

(defn- composer-keys!
  "Enter calls `on-enter`, Cmd/Ctrl+Enter `on-mod-enter`, Shift+Enter is a newline.
  Handlers get trimmed text."
  [^js ta on-enter on-mod-enter]
  (.addEventListener ta "keydown"
                     (fn [^js e]
                       (when (and (= "Enter" (.-key e)) (not (.-shiftKey e)))
                         (.preventDefault e)
                         (let [t (str/trim (.-value ta))]
                           (when (seq t)
                             (if (or (.-metaKey e) (.-ctrlKey e)) (on-mod-enter t) (on-enter t))))))))

(defn- submit-button
  "Button that submits the textarea's text to `f`."
  [label ^js ta f & [cls]]
  [:button {:class cls
            :on-click #(let [t (str/trim (.-value ta))] (when (seq t) (f t)))}
   label])


(defn- item
  "Toolbar/menu entry. mousedown is cancelled so the page selection survives the click."
  [cls label title f]
  [:div {:class (str "item " cls) :title title
         :on-mousedown #(.preventDefault %)
         :on-click (fn [^js e] (.stopPropagation e) (f))}
   label])

(defn toolbar
  "Selection toolbar: Comment, Ask…, the \"toolbar\" actions, and ⋯ for the \"menu\" ones.
  (on-action action) runs a preset; on-ask / on-comment open an empty note."
  [{:keys [actions on-ask on-comment on-action on-edit] :as pos}]
  (let [menu (h [:div {:class "menu" :style "display:none"}
                 (for [a (core/actions-at actions "menu")]
                   (item "action" (:label a) (:prompt a) #(on-action a)))
                 (when (seq (core/actions-at actions "menu")) [:hr])
                 (item "dim edit" "Edit actions…" "Open Tosafot settings" on-edit)])
        more (h (item "more" "⋯" "More" (fn [])))
        el (h [:div {:class "sd toolbar"}
               [:span {:class "ic" :title "Tosafot"} (logo 18)]
               [:span {:class "sep"}]
               (item "comment" "Comment" "Write a note on this" on-comment)
               (item "ask" "Ask…" "Ask a question about this" on-ask)
               (when-let [as (seq (core/actions-at actions "toolbar"))]
                 (cons [:span {:class "sep"}]
                       (for [a as] (item "action" (:label a) (:prompt a) #(on-action a)))))
               [:span {:class "sep"}]
               more
               menu])]
    (.addEventListener more "click"
                       #(let [open? (= "none" (.. menu -style -display))]
                          (set! (.. menu -style -display) (if open? "" "none"))
                          (.toggle (.-classList more) "open" open?)))
    (at el pos)))

(defn clamp-x!
  "Shift an absolutely positioned `el` left so it fits in the viewport."
  [^js el]
  (let [max-x (- (+ js/window.scrollX js/document.documentElement.clientWidth) (.-offsetWidth el) 8)]
    (when (> (.-offsetLeft el) max-x)
      (set! (.. el -style -left) (px (max js/window.scrollX max-x))))
    el))

(defn- draggable! [^js el ^js handle]
  (.addEventListener
   handle "mousedown"
   (fn [^js e]
     (when-not (= "BUTTON" (.. e -target -tagName))
       (.preventDefault e)
       (let [x0 (- (.-pageX e) (.-offsetLeft el))
             y0 (- (.-pageY e) (.-offsetTop el))
             move (fn [^js m] (at el {:x (- (.-pageX m) x0) :y (- (.-pageY m) y0)}))]
         (js/document.addEventListener "mousemove" move)
         (js/document.addEventListener "mouseup"
                                       #(js/document.removeEventListener "mousemove" move)
                                       #js {:once true}))))))

(defn sidenote
  "Ephemeral note holding a thread, with a composer for follow-ups and comments.
  Submitting clears the composer; asking is refused (text kept) while an answer streams."
  [{:keys [quote on-close on-copy on-ask on-comment on-stop on-save on-delete
           actions on-action mode] :as pos}]
  (let [comment-first (atom (= mode :comment))
        ta (h [:textarea {:placeholder (if @comment-first "Write a comment…" "Ask about this, or comment…")}])
        ask-btn (atom nil)
        settle! #(do (reset! comment-first false)
                     (set! (.-value ta) "")
                     (set! (.-placeholder ta) "Follow up, or comment…"))
        ask (fn [t] (when-not (.-disabled @ask-btn)
                      (settle!)
                      (on-ask t)))
        comment (fn [t] (settle!) (on-comment t))
        el (h [:div {:class "sd note"}
               [:div {:class "note-head"}
                (logo 14)
                [:span {:class "title"} "Tosafot"]
                [:button {:class "save" :title "Keep this note on this page" :on-click on-save} "Save"]
                [:button {:class "delete" :title "Delete this saved note" :on-click on-delete
                          :style "display:none"} "Delete"]
                [:button {:class "close" :title "Close" :on-click on-close} "×"]]
               [:div {:class "note-body"}
                [:div {:class "quote"} (short-quote quote 200)]
                (when-let [as (seq (core/actions-at actions "toolbar" "menu"))]
                  [:div {:class "starters"}
                   (for [a as]
                     [:button {:title (:prompt a) :on-click #(on-action a)} (:label a)])])
                [:div {:class "thread"}]]
               [:div {:class "composer"}
                ta
                [:div {:class "actions"}
                 (submit-button "Ask" ta ask "primary ask")
                 (submit-button "Comment" ta comment)
                 [:button {:class "stop" :on-click on-stop :style "display:none"} "Stop"]
                 [:span {:class "spacer"}]
                 [:button {:title "Copy thread as Markdown" :on-click on-copy} "Copy"]]]])]
    (reset! ask-btn (qs el "button.ask"))
    ;; Started from Comment, Enter comments until the first submission.
    (composer-keys! ta #(if @comment-first (comment %) (ask %)) #(if @comment-first (ask %) (comment %)))
    (isolate-keys! el)
    (draggable! el (qs el ".note-head"))
    (at el pos)))

(defn- render-markdown [md]
  (.sanitize DOMPurify (.parse marked md #js {:async false})))

(defn- hhmm [^js d]
  (.toLocaleTimeString d js/undefined #js {:hour "numeric" :minute "2-digit"}))

(defn- turn-el ^js [^js note id]
  (qs note (str ".turn[data-id=\"" id "\"]")))

(defn- scroll-to-end! [^js note]
  (let [body (qs note ".note-body")]
    (set! (.-scrollTop body) (.-scrollHeight body))))

(defn set-turn-text! [^js note id md]
  (when-let [t (turn-el note id)]
    (let [body (qs note ".note-body")
          at-end? (< (- (.-scrollHeight body) (.-scrollTop body) (.-clientHeight body)) 30)
          text (qs t ".text")]
      (set! (.-innerHTML text) (render-markdown md))
      (doseq [a (array-seq (.querySelectorAll text "a"))]
        (.setAttribute a "target" "_blank")
        (.setAttribute a "rel" "noopener noreferrer"))
      (when at-end? (scroll-to-end! note)))))

(defn set-turn-status!
  "Show `s` under a turn; nil clears it. Optional `action` is [label f]."
  [^js note id s & {:keys [error? action]}]
  (when-let [st (some-> (turn-el note id) (qs ".status"))]
    (set! (.-textContent st) (or s ""))
    (set! (.-className st) (if error? "status error" "status"))
    (when-let [[label f] action]
      (.append st " " (h [:button {:on-click f} label])))))

(defn append-turn!
  "Add a thread message to the note."
  [^js note {:msg/keys [id author kind text created]}]
  (some-> (qs note ".starters") .remove)
  (let [human? (= :human (:type author))
        who (cond (= kind :comment) "Note"
                  human? "You"
                  :else (or (:model author) "…"))
        el (h [:div {:class ["turn" (if human? "human" "llm") (when (= kind :comment) "comment")]
                     :data-id (str id)}
               [:div {:class "meta"} (str who " · " (hhmm created))]
               [:div {:class "text"}]
               [:div {:class "status"}]])]
    (.append (qs note ".thread") el)
    (set-turn-text! note (str id) text)
    (scroll-to-end! note)))

(defn set-turn-who! [^js note id who]
  (when-let [m (some-> (turn-el note id) (qs ".meta"))]
    (set! (.-textContent m) (str who " · " (last (str/split (.-textContent m) #" · "))))))

(defn set-busy!
  "While an answer streams: disable Ask, show Stop."
  [^js note busy?]
  (set! (.-disabled (qs note "button.ask")) busy?)
  (set! (.. (qs note "button.stop") -style -display) (if busy? "" "none")))

(defn set-saved!
  "Reflect whether the note is saved: Save becomes a disabled \"Saved\" label, Delete appears."
  [^js note saved?]
  (let [save (qs note "button.save")]
    (set! (.-disabled save) saved?)
    (set! (.-textContent save) (if saved? "Saved ✓" "Save"))
    (set! (.. (qs note "button.delete") -style -display) (if saved? "" "none"))))

(def ^:private status-text
  {:refused "The model declined to answer."
   :stopped "Stopped."
   :error   "This answer failed."})

(defn render-thread!
  "Append all of `thread`'s messages to a fresh note."
  [^js note thread]
  (doseq [m thread]
    (append-turn! note m)
    (when-let [s (status-text (:msg/status m))]
      (set-turn-status! note (str (:msg/id m)) s :error? (= :error (:msg/status m))))))

(defn orphan-panel
  "Corner badge listing notes that couldn't be placed on the page; (on-open id) opens one."
  [orphans on-open]
  (let [list-el (h [:ul {:style "display:none"}
                    (for [{:annotation/keys [id target thread]} orphans]
                      [:li {:on-click #(on-open (str id))}
                       [:div {:class "quote"} (short-quote (get-in target [:quote :exact] "") 100)]
                       [:div {:class "first"} (short-quote (or (:msg/text (first thread)) "") 80)]])])
        n (count orphans)]
    (h [:div {:class "sd orphans"}
        [:div {:class "badge"
               :title "The text these notes were attached to wasn't found on this page"
               :on-click #(set! (.. list-el -style -display)
                                (if (= "none" (.. list-el -style -display)) "" "none"))}
         (logo 16)
         (str n " Tosafot note" (when (> n 1) "s") " couldn't be placed")]
        list-el])))

(defn sidenote-position
  "Place a note right of the selection's block if there's room, else below the selection.
  Rects are viewport rects; returns document coords."
  [{:keys [^js sel-rect ^js block-rect doc-width view-width scroll-x scroll-y]}]
  (let [w 360 gap 16
        right (+ (.-right block-rect) gap)]
    (if (<= (+ right w) (min doc-width view-width))
      {:x (+ right scroll-x) :y (+ (.-top sel-rect) scroll-y)}
      {:x (+ scroll-x (max 8 (min (.-left sel-rect) (- view-width w 8))))
       :y (+ (.-bottom sel-rect) scroll-y 8)})))
