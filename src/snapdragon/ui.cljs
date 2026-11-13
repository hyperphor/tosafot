(ns snapdragon.ui
  "Popover and sidenote components. Rendered inside a shadow root; positions are document coords."
  (:require ["dompurify" :as DOMPurify]
            ["marked" :refer [marked]]
            [clojure.string :as str]
            [snapdragon.core :as core]
            [snapdragon.dom :refer [h qs]]))

(def styles "
:host { all: initial; }
* { box-sizing: border-box; }
.sd { position: absolute; font: 13px/1.45 system-ui, -apple-system, sans-serif; color: #1d1d1f;
      background: #fff; border: 1px solid #d8d8de; border-radius: 8px;
      box-shadow: 0 4px 18px rgba(0,0,0,.14); }
.bubble { padding: 2px 9px; cursor: pointer; font-weight: 600; font-size: 12px; user-select: none; }
.bubble:hover { background: #f3f0ff; }
.popover { width: 320px; padding: 8px; }
.quote { color: #666; font-style: italic; border-left: 3px solid #e3c35a; padding: 1px 0 1px 7px;
         margin: 0 0 7px; max-height: 4.4em; overflow: hidden; }
.presets { display: flex; gap: 5px; margin-bottom: 6px; }
button { font: inherit; font-size: 12px; padding: 3px 9px; border: 1px solid #ccc; border-radius: 5px;
         background: #f7f7f9; color: inherit; cursor: pointer; }
button:hover { background: #ecebf5; }
textarea { width: 100%; min-height: 3.2em; resize: vertical; font: inherit; padding: 5px;
           border: 1px solid #ccc; border-radius: 5px; background: #fff; color: inherit; }
.hint { color: #999; font-size: 11px; margin-top: 3px; }
.note { width: 360px; max-height: 70vh; display: flex; flex-direction: column; }
.note-head { display: flex; align-items: center; gap: 6px; padding: 5px 8px; cursor: move;
             border-bottom: 1px solid #eee; color: #777; font-size: 11px; user-select: none; }
.note-head .who { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.note-head button { padding: 0 6px; border: none; background: none; font-size: 14px; }
.note-body { padding: 7px 10px; overflow-y: auto; }
.question { font-weight: 600; margin-bottom: 4px; }
.answer > :first-child { margin-top: 0; }
.answer > :last-child { margin-bottom: 0; }
.answer pre { background: #f5f5f7; padding: 6px; border-radius: 4px; overflow-x: auto; }
.answer code { font-size: 12px; }
.answer p, .answer ul, .answer ol { margin: .45em 0; }
.answer ul, .answer ol { padding-left: 1.3em; }
.status { color: #888; font-size: 12px; margin-top: 4px; }
.status.error { color: #c0392b; }
.note-foot { display: flex; gap: 5px; padding: 5px 8px; border-top: 1px solid #eee; }
@media (prefers-color-scheme: dark) {
  .sd { background: #232326; color: #e8e8ea; border-color: #444; }
  .bubble:hover, button:hover { background: #34343a; }
  button, textarea { background: #2c2c30; border-color: #555; }
  .note-head, .note-foot { border-color: #3a3a3e; }
  .answer pre { background: #2c2c30; }
}
")

(defn- px [n] (str (js/Math.round n) "px"))

(defn- at [el {:keys [x y]}]
  (set! (.. el -style -left) (px x))
  (set! (.. el -style -top) (px y))
  el)

(defn- short-quote [s n]
  (core/clip-after (str/replace s #"\s+" " ") n))

(defn bubble [{:keys [on-ask] :as pos}]
  (at (h [:div {:class "sd bubble"
                ;; preventDefault keeps the page selection alive
                :on-mousedown #(.preventDefault %)
                :on-click on-ask}
          "Ask"])
      pos))

(defn popover
  "Question box for a selection. Calls (on-ask question)."
  [{:keys [quote on-ask] :as pos}]
  (let [el (h [:div {:class "sd popover"}
               [:div {:class "quote"} (short-quote quote 160)]
               [:div {:class "presets"}
                (for [{:keys [label question]} core/presets]
                  [:button {:on-click #(on-ask question)} label])]
               [:textarea {:placeholder "Ask about this…"}]
               [:div {:class "hint"} "Enter to ask · Shift+Enter for newline · Esc to close"]])
        ta (qs el "textarea")]
    (.addEventListener ta "keydown"
                       (fn [^js e]
                         (when (and (= "Enter" (.-key e)) (not (.-shiftKey e)))
                           (.preventDefault e)
                           (let [q (str/trim (.-value ta))]
                             (when (seq q) (on-ask q))))))
    ;; Keep page hotkeys from firing while typing
    (doseq [t ["keydown" "keyup" "keypress"]]
      (.addEventListener el t #(.stopPropagation %)))
    (at el pos)))

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
  "Ephemeral note showing a question and its streamed answer."
  [{:keys [quote question on-close on-copy] :as pos}]
  (let [el (h [:div {:class "sd note"}
               [:div {:class "note-head"}
                [:span {:class "who"} "Snapdragon"]
                [:button {:title "Close" :on-click on-close} "×"]]
               [:div {:class "note-body"}
                [:div {:class "quote"} (short-quote quote 200)]
                [:div {:class "question"} question]
                [:div {:class "answer"}]
                [:div {:class "status"} "Thinking…"]]
               [:div {:class "note-foot"}
                [:button {:on-click on-copy} "Copy"]]])]
    (draggable! el (qs el ".note-head"))
    (at el pos)))

(defn- render-markdown [md]
  (.sanitize DOMPurify (.parse marked md #js {:async false})))

(defn set-answer! [^js note md]
  (let [ans (qs note ".answer")]
    (set! (.-innerHTML ans) (render-markdown md))
    (doseq [a (array-seq (.querySelectorAll ans "a"))]
      (.setAttribute a "target" "_blank")
      (.setAttribute a "rel" "noopener noreferrer"))))

(defn set-who! [^js note s]
  (set! (.-textContent (qs note ".who")) s))

(defn set-status!
  "Show `s` in the status line; nil hides it. Optional `action` is [label f]."
  [^js note s & {:keys [error? action]}]
  (let [st (qs note ".status")]
    (set! (.-textContent st) (or s ""))
    (set! (.. st -style -display) (if s "" "none"))
    (set! (.-className st) (if error? "status error" "status"))
    (when-let [[label f] action]
      (.append st " " (h [:button {:on-click f} label])))))

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
