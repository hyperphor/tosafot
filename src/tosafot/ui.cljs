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
button { font: inherit; font-size: 12px; padding: 3px 9px; border: 1px solid #ccc; border-radius: 5px;
         background: #f7f7f9; color: inherit; cursor: pointer; }
button:hover { background: #ecebf5; }
button:disabled { opacity: .45; cursor: default; }
button.primary { background: #ece6ff; border-color: #b9a8f0; }
textarea { width: 100%; min-height: 3.2em; resize: vertical; font: inherit; padding: 5px;
           border: 1px solid #ccc; border-radius: 5px; background: #fff; color: inherit; }
.actions { display: flex; gap: 5px; align-items: center; margin-top: 5px; }
.note { width: var(--w, 360px); max-height: 75vh; display: flex; flex-direction: column; }
.note-head { display: flex; align-items: center; gap: 6px; padding: 5px 8px; cursor: move;
             border-bottom: 1px solid #eee; color: #777; font-size: 11px; user-select: none; }
a.home { display: flex; align-items: center; gap: 6px; color: inherit; text-decoration: none; }
a.home:hover .title { text-decoration: underline; }
.note-head .home { margin-right: auto; }
.note-head .title { color: #6a5acd; font-weight: 600; }
.note-head button { padding: 0 6px; border: none; background: none; font-size: 11px; color: inherit; }
.note-head button.close { font-size: 14px; }
.orphans { position: fixed; right: 16px; bottom: 16px; width: 300px; padding: 0; }
.orphans .badge { display: flex; align-items: center; gap: 6px; padding: 6px 10px; cursor: pointer;
                  font-size: 12px; }
.orphans ul { list-style: none; margin: 0; padding: 0 0 4px; border-top: 1px solid #eee;
              max-height: 40vh; overflow-y: auto; }
.orphans li { padding: 5px 10px; cursor: pointer; }
.orphans li:hover { background: #f3f0ff; }
.orphans .first { color: #888; font-size: 12px; }
.note-body { padding: 7px 10px; overflow-y: auto; flex: 1; min-height: 0; }
/* Compact: just the comments, until hovered or focused. */
.note.compact:not(:hover):not(:focus-within) { width: min(220px, var(--w, 360px)); box-shadow: 0 1px 5px rgba(0,0,0,.12); }
.note.compact:not(:hover):not(:focus-within) :is(.note-head, .note-msg, .quote, .composer,
  .turn:not(.comment), .turn .meta, .turn .status) { display: none; }
.note.compact:not(:hover):not(:focus-within) .note-body { padding: 4px; max-height: 5.6em; overflow: hidden; }
.note.compact:not(:hover):not(:focus-within) .turn { margin: 0; font-size: 12px; }
.note.compact:not(:hover):not(:focus-within) .turn + .turn { margin-top: 3px; }
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
.prompts { position: relative; margin-left: auto; }
.prompts .menu { top: auto; bottom: calc(100% + 4px); min-width: 190px; max-height: 50vh;
                 overflow-y: auto; background: #fff; color: #1d1d1f; }
.prompts .save-form { display: flex; gap: 4px; padding: 4px; }
.prompts .save-form input { flex: 1; min-width: 0; font: inherit; padding: 3px 5px;
                            border: 1px solid #ccc; border-radius: 5px; }
.turn .meta { display: flex; align-items: center; gap: 4px; }
.turn .edit-turn { padding: 0 3px; border: none; background: none; font-size: 11px; color: #6a5acd; }
.turn .edit-turn:hover { text-decoration: underline; background: none; }
.turn .editor .actions { margin-top: 4px; }
.turn .copy-turn { padding: 0 3px; border: none; background: none; opacity: 0; line-height: 0; }
.turn:hover .copy-turn { opacity: 1; }
.composer textarea { min-height: 2.4em; }
.share { position: relative; }
.share .menu { min-width: 170px; background: #fff; color: #1d1d1f; cursor: default; }
.menu button.item { display: flex; align-items: center; gap: 9px; width: 100%; text-align: left;
                    border: none; background: none;
                    border-radius: 5px; font-size: 13px; }
.menu button.item:hover { background: #f0ecff; }
.icon { flex: none; display: block; }
.icon.copy { fill: none; stroke: #777; stroke-width: 2; }
.icon.notebook { fill: none; stroke: #6a5acd; stroke-width: 2; }
.note-msg { padding: 5px 10px; font-size: 12px; color: #2a7; border-bottom: 1px solid #eee; }
.note-msg.error { color: #c0392b; }
.note-msg button { margin-left: 6px; font-size: 11px; padding: 1px 6px; }
.icon.x { fill: #000; } .icon.bluesky { fill: #1185fe; } .icon.mastodon { fill: #6364ff; }
@media (prefers-color-scheme: dark) {
  .sd { background: #232326; color: #e8e8ea; border-color: #444; }
  .toolbar .item:hover, .toolbar .item.open, button:hover { background: #34343a; }
  .toolbar .sep, .menu hr { background: #3a3a3e; border-color: #3a3a3e; }
  .menu { border-color: #444; }
  .share .menu, .prompts .menu { background: #232326; color: #e8e8ea; }
  .prompts .save-form input { background: #2c2c30; color: inherit; border-color: #555; }
  .icon.x { fill: #e8e8ea; }
  .menu button.item:hover { background: #34343a; }
  button, textarea { background: #2c2c30; border-color: #555; }
  button.primary { background: #3b3360; border-color: #6c5cb5; }
  .note-head, .composer, .orphans ul { border-color: #3a3a3e; }
  .orphans li:hover { background: #34343a; }
  .turn.comment .text { background: #3a3424; }
  .note-head .title { color: #b3a8f5; }
  .text pre { background: #2c2c30; }
}
")

(def home-url "https://github.com/hyperphor/tosafot")

(defn- home-link
  "Link to the Tosafot home page, opening in a new tab."
  [& children]
  (into [:a {:class "home" :href home-url :target "_blank" :rel "noopener" :title "Tosafot home page"}]
        children))

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
  "Selection toolbar: Comment, Ask…, the button prompts, and ⋯ for every enabled prompt.
  (on-action action) opens a note with that prompt; on-ask / on-comment open an empty note."
  [{:keys [actions on-ask on-comment on-action on-edit] :as pos}]
  (let [enabled (core/actions-at actions "toolbar" "menu")
        menu (h [:div {:class "menu" :style "display:none"}
                 (for [a enabled]
                   (item "action" (:label a) (:prompt a) #(on-action a)))
                 (when (seq enabled) [:hr])
                 (item "dim edit" "Edit prompts…" "Open Tosafot settings" on-edit)])
        more (h (item "more" "⋯" "More" (fn [])))
        el (h [:div {:class "sd toolbar"}
               [:span {:class "ic"} (home-link (logo 18))]
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

(defn- draggable!
  "Drag `el` by `handle`; after a drag that moved it, calls (on-move {:x :y}) in document coords."
  [^js el ^js handle on-move]
  (.addEventListener
   handle "mousedown"
   (fn [^js e]
     (when-not (.closest (.-target e) "button, a, .menu")
       (.preventDefault e)
       (let [x0 (- (.-pageX e) (.-offsetLeft el))
             y0 (- (.-pageY e) (.-offsetTop el))
             moved? (volatile! false)
             move (fn [^js m]
                    (vreset! moved? true)
                    (at el {:x (- (.-pageX m) x0) :y (- (.-pageY m) y0)}))]
         (js/document.addEventListener "mousemove" move)
         (js/document.addEventListener "mouseup"
                                       (fn [_]
                                         (js/document.removeEventListener "mousemove" move)
                                         (when (and @moved? on-move)
                                           (on-move {:x (.-offsetLeft el) :y (.-offsetTop el)})))
                                       #js {:once true}))))))

;;; Service logos from simple-icons (CC0), 24x24 paths.
(def ^:private icon-paths
  {:x "M14.234 10.162 22.977 0h-2.072l-7.591 8.824L7.251 0H.258l9.168 13.343L.258 24H2.33l8.016-9.318L16.749 24h6.993zm-2.837 3.299-.929-1.329L3.076 1.56h3.182l5.965 8.532.929 1.329 7.754 11.09h-3.182z"
   :bluesky "M5.202 2.857C7.954 4.922 10.913 9.11 12 11.358c1.087-2.247 4.046-6.436 6.798-8.501C20.783 1.366 24 .213 24 3.883c0 .732-.42 6.156-.667 7.037-.856 3.061-3.978 3.842-6.755 3.37 4.854.826 6.089 3.562 3.422 6.299-5.065 5.196-7.28-1.304-7.847-2.97-.104-.305-.152-.448-.153-.327 0-.121-.05.022-.153.327-.568 1.666-2.782 8.166-7.847 2.97-2.667-2.737-1.432-5.473 3.422-6.3-2.777.473-5.899-.308-6.755-3.369C.42 10.04 0 4.615 0 3.883c0-3.67 3.217-2.517 5.202-1.026"
   :mastodon "M23.268 5.313c-.35-2.578-2.617-4.61-5.304-5.004C17.51.242 15.792 0 11.813 0h-.03c-3.98 0-4.835.242-5.288.309C3.882.692 1.496 2.518.917 5.127.64 6.412.61 7.837.661 9.143c.074 1.874.088 3.745.26 5.611.118 1.24.325 2.47.62 3.68.55 2.237 2.777 4.098 4.96 4.857 2.336.792 4.849.923 7.256.38.265-.061.527-.132.786-.213.585-.184 1.27-.39 1.774-.753a.057.057 0 0 0 .023-.043v-1.809a.052.052 0 0 0-.02-.041.053.053 0 0 0-.046-.01 20.282 20.282 0 0 1-4.709.545c-2.73 0-3.463-1.284-3.674-1.818a5.593 5.593 0 0 1-.319-1.433.053.053 0 0 1 .066-.054c1.517.363 3.072.546 4.632.546.376 0 .75 0 1.125-.01 1.57-.044 3.224-.124 4.768-.422.038-.008.077-.015.11-.024 2.435-.464 4.753-1.92 4.989-5.604.008-.145.03-1.52.03-1.67.002-.512.167-3.63-.024-5.545zm-3.748 9.195h-2.561V8.29c0-1.309-.55-1.976-1.67-1.976-1.23 0-1.846.79-1.846 2.35v3.403h-2.546V8.663c0-1.56-.617-2.35-1.848-2.35-1.112 0-1.668.668-1.67 1.977v6.218H4.822V8.102c0-1.31.337-2.35 1.011-3.12.696-.77 1.608-1.164 2.74-1.164 1.311 0 2.302.5 2.962 1.498l.638 1.06.638-1.06c.66-.999 1.65-1.498 2.96-1.498 1.13 0 2.043.395 2.74 1.164.675.77 1.012 1.81 1.012 3.12z"
   :notebook "M6 2h11l3 3v17H6zM9 7h8M9 11h8M9 15h5"
   :copy "M9 9h11v11H9zM5 15H4V4h11v1"})

(defn- icon
  "Inline SVG for `k` in icon-paths, 15px."
  [k]
  (let [ns "http://www.w3.org/2000/svg"
        svg (js/document.createElementNS ns "svg")
        path (js/document.createElementNS ns "path")]
    (doseq [[a v] {"viewBox" "0 0 24 24" "width" 15 "height" 15 "aria-hidden" "true"
                   "class" (str "icon " (name k))}]
      (.setAttribute svg a v))
    (.setAttribute path "d" (icon-paths k))
    (.appendChild svg path)
    svg))

(defn- share-menu
  "Share button with a menu: Copy Markdown, the notebook export (if `on-export`, named
  `export-label`), then (on-share service) for each share-services key."
  [on-copy on-share on-export export-label]
  (let [menu (h [:div {:class "menu" :style "display:none"}])
        show! #(set! (.. menu -style -display) (if % "" "none"))
        pick (fn [f] (fn [] (show! false) (f)))]
    (.append menu
             (h [:button {:class "item" :title "Copy thread as Markdown" :on-click (pick on-copy)}
                 (icon :copy) "Copy Markdown"]))
    (when on-export
      (.append menu (h [:button {:class "item export" :title "Add this note to your notebook's latest note"
                                 :on-click (pick on-export)}
                        (icon :notebook) export-label])))
    (.append menu (h [:hr]))
    (doseq [[k {:keys [label]}] core/share-services]
      (.append menu (h [:button {:class "item" :title (str "Open a " label " post with this note")
                                 :on-click (pick #(on-share k))}
                        (icon k) (str "Post to " label "…")])))
    (h [:span {:class "share"}
        [:button {:class "share-btn" :title "Copy or post this note"
                  :on-click #(show! (= "none" (.. menu -style -display)))}
         "Share ▾"]
        menu])))

(defn- prompt-menu
  "Prompts ▾ button for the composer. Picking a prompt puts it in `ta` without asking and calls
  (on-pick action). Save prompt… calls (on-save label text) with the composer's text."
  [^js ta actions on-pick on-save on-edit]
  (let [menu (h [:div {:class "menu" :style "display:none"}])
        show! #(set! (.. menu -style -display) (if % "" "none"))
        save-form (fn []
                    (let [input (h [:input {:type "text" :placeholder "Name for this prompt"}])
                          done #(let [label (str/trim (.-value input))]
                                  (when (seq label)
                                    (on-save label (str/trim (.-value ta)))
                                    (show! false)))]
                      (.addEventListener input "keydown"
                                         #(when (= "Enter" (.-key %)) (.preventDefault %) (done)))
                      (.replaceChildren menu (h [:div {:class "save-form"} input
                                                 [:button {:on-click done} "Save"]]))
                      (.focus input)))
        fill! (fn []
                (.replaceChildren menu)
                (.append menu (h [:button {:class "item save-prompt" :disabled (str/blank? (.-value ta))
                                           :title "Keep what's in the box as a prompt in your menus"
                                           :on-click save-form}
                                  "Save prompt…"]))
                (.append menu (h [:hr]))
                (doseq [a (core/actions-at actions "toolbar" "menu")]
                  (.append menu (h [:button {:class "item action" :title (:prompt a)
                                             :on-click (fn []
                                                         (show! false)
                                                         (set! (.-value ta) (:prompt a))
                                                         (on-pick a)
                                                         (.focus ta))}
                                    (:label a)])))
                (.append menu (h [:hr]))
                (.append menu (h [:button {:class "item dim edit" :title "Open Tosafot settings"
                                           :on-click #(do (show! false) (on-edit))}
                                  "Edit prompts…"])))]
    (h [:span {:class "prompts"}
        [:button {:class "prompts-btn" :title "Fill in a saved prompt, or save this one"
                  :on-click #(if (= "none" (.. menu -style -display))
                               (do (fill!) (show! true))
                               (show! false))}
         "Prompts ▾"]
        menu])))

(defn set-compact!
  "Shrink `note` to its comments, expanding on hover or focus; false restores it."
  [^js note compact?]
  (.toggle (.-classList note) "compact" (boolean compact?)))

(defn sidenote
  "Ephemeral note holding a thread, with a composer for follow-ups and comments.
  Submitting clears the composer; asking is refused (text kept) while an answer streams."
  [{:keys [quote on-close on-copy on-share on-export export-label on-ask on-comment on-stop on-delete
           actions on-save-prompt on-edit on-edit-comment on-move mode prefill width] :as pos}]
  (let [comment-first (atom (= mode :comment))
        pending (atom prefill)   ; the prompt last picked, for its instructions
        ta (h [:textarea {:placeholder (if @comment-first "Write a comment…" "Ask about this, or comment…")}])
        ask-btn (atom nil)
        note-el (atom nil)
        settle! #(do (reset! comment-first false)
                     (set! (.-value ta) "")
                     (set! (.-placeholder ta) "Follow up, or comment…"))
        ask (fn [t] (when-not (.-disabled @ask-btn)
                      (let [instructions (:instructions @pending)]
                        (reset! pending nil)
                        (settle!)
                        (on-ask t instructions))))
        comment (fn [t] (reset! pending nil) (settle!) (on-comment t) (set-compact! @note-el true))
        el (h [:div {:class "sd note"}
               [:div {:class "note-head"}
                (home-link (logo 14) [:span {:class "title"} "Tosafot"])
                (share-menu on-copy on-share on-export export-label)
                [:button {:class "delete" :title "Delete this note" :on-click on-delete
                          :style "display:none"} "Delete"]
                [:button {:class "close" :title "Close" :on-click on-close} "×"]]
               [:div {:class "note-msg" :style "display:none"}]
               [:div {:class "note-body"}
                [:div {:class "quote"} (short-quote quote 200)]
                [:div {:class "thread"}]]
               [:div {:class "composer"}
                ta
                [:div {:class "actions"}
                 (submit-button "Ask" ta ask "primary ask")
                 (submit-button "Comment" ta comment)
                 [:button {:class "stop" :on-click on-stop :style "display:none"} "Stop"]
                 (prompt-menu ta actions #(reset! pending %)
                              #(on-save-prompt %1 %2 (:instructions @pending)) on-edit)]]])]
    (reset! ask-btn (qs el "button.ask"))
    (reset! note-el el)
    (when width (.setProperty (.-style el) "--w" (px width)))
    ;; Pressing anywhere in a compact note keeps it expanded.
    (.addEventListener el "mousedown" #(set-compact! el false))
    (when prefill (set! (.-value ta) (:prompt prefill)))
    (.addEventListener el "tosafot-edit"
                       (fn [^js e] (on-edit-comment (.. e -detail -id) (.. e -detail -text))))
    (.addEventListener ta "input" #(when (str/blank? (.-value ta)) (reset! pending nil)))
    ;; Started from Comment, Enter comments until the first submission.
    (composer-keys! ta #(if @comment-first (comment %) (ask %)) #(if @comment-first (ask %) (comment %)))
    (isolate-keys! el)
    (draggable! el (qs el ".note-head") on-move)
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
      (set! (.. t -dataset -md) md)
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

(defn- edit-turn!
  "Swap comment turn `t`'s text for an editor; saving dispatches tosafot-edit {id, text} on `note`."
  [^js note ^js t id]
  (let [text-el (qs t ".text")
        ta (h [:textarea {}])
        close! #(do (.remove (qs t ".editor")) (set! (.. text-el -style -display) ""))
        save! #(let [v (str/trim (.-value ta))]
                 (when (seq v)
                   (close!)
                   (.dispatchEvent note (js/CustomEvent. "tosafot-edit"
                                                         #js {:detail #js {:id id :text v}}))))]
    (when-not (qs t ".editor")
      (set! (.-value ta) (or (.. t -dataset -md) ""))
      (set! (.. text-el -style -display) "none")
      (.addEventListener ta "keydown"
                         (fn [^js e]
                           (case (.-key e)
                             "Enter" (when-not (.-shiftKey e) (.preventDefault e) (save!))
                             "Escape" (do (.preventDefault e) (close!))
                             nil)))
      (.after text-el (h [:div {:class "editor"} ta
                          [:div {:class "actions"}
                           [:button {:class "primary save-edit" :on-click save!} "Save"]
                           [:button {:class "cancel-edit" :on-click close!} "Cancel"]]]))
      (.focus ta))))

(defn- who-text [who created edited]
  (str who " · " (hhmm created) (when edited " · edited")))

(defn mark-edited!
  "Show turn `id` as edited."
  [^js note id]
  (when-let [w (some-> (turn-el note id) (qs ".meta .who"))]
    (when-not (str/ends-with? (.-textContent w) " · edited")
      (set! (.-textContent w) (str (.-textContent w) " · edited")))))

(defn append-turn!
  "Add a thread message to the note. Comments get an edit button."
  [^js note {:msg/keys [id author kind text created edited]}]
  (some-> (qs note ".starters") .remove)
  (let [human? (= :human (:type author))
        who (cond (= kind :comment) "Note"
                  human? "You"
                  :else (or (:model author) "…"))
        el (h [:div {:class ["turn" (if human? "human" "llm") (when (= kind :comment) "comment")]
                     :data-id (str id)}
               [:div {:class "meta-row meta"}
                [:span {:class "who"} (who-text who created edited)]
                (when (= kind :comment)
                  [:button {:class "edit-turn" :title "Edit this comment"
                            :on-click (fn [^js e]
                                        (edit-turn! note (.. e -currentTarget (closest ".turn"))
                                                    (str id)))}
                   "edit"])
                (when-not human?
                  [:button {:class "copy-turn" :title "Copy this answer as Markdown"
                            :on-click (fn [^js e]
                                        (js/navigator.clipboard.writeText
                                         (or (.. e -currentTarget (closest ".turn") -dataset -md) "")))}
                   (icon :copy)])]
               [:div {:class "text"}]
               [:div {:class "status"}]])]
    (.append (qs note ".thread") el)
    (set-turn-text! note (str id) text)
    (scroll-to-end! note)))

(defn set-turn-who! [^js note id who]
  (when-let [m (some-> (turn-el note id) (qs ".meta .who"))]
    (set! (.-textContent m) (str who " · " (last (str/split (.-textContent m) #" · "))))))

(defn set-busy!
  "While an answer streams: disable Ask, show Stop."
  [^js note busy?]
  (set! (.-disabled (qs note "button.ask")) busy?)
  (set! (.. (qs note "button.stop") -style -display) (if busy? "" "none")))

(defn note-message!
  "Show `text` under the note's header (nil hides it). `action` is [label f]."
  [^js note text & {:keys [error? action]}]
  (let [m (qs note ".note-msg")]
    (set! (.. m -style -display) (if text "" "none"))
    (.toggle (.-classList m) "error" (boolean error?))
    (.replaceChildren m (or text ""))
    (when-let [[label f] action]
      (.append m (h [:button {:on-click f} label])))))

(defn set-saved!
  "Show Delete once the note is saved."
  [^js note saved?]
  (set! (.. (qs note "button.delete") -style -display) (if saved? "" "none")))

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
  "Place a note in the margin beside the selection's block (right, else left), narrowed to fit
  down to 240px; else below the selection. Rects are viewport rects; returns document coords and :width."
  [{:keys [^js sel-rect ^js block-rect doc-width view-width scroll-x scroll-y]}]
  (let [w 360 min-w 240 gap 16 edge 8
        top (+ (.-top sel-rect) scroll-y)
        right-room (- (min doc-width view-width) (.-right block-rect) gap edge)
        left-room (- (.-left block-rect) gap edge)]
    (cond
      (>= right-room min-w)
      {:x (+ (.-right block-rect) gap scroll-x) :y top :width (min w right-room)}

      (>= left-room min-w)
      (let [w (min w left-room)]
        {:x (+ scroll-x (- (.-left block-rect) gap w)) :y top :width w})

      :else
      {:x (+ scroll-x (max edge (min (.-left sel-rect) (- view-width w edge))))
       :y (+ (.-bottom sel-rect) scroll-y edge)
       :width w})))
