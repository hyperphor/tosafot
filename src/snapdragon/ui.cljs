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
button:disabled { opacity: .45; cursor: default; }
button.primary { background: #ece6ff; border-color: #b9a8f0; }
textarea { width: 100%; min-height: 3.2em; resize: vertical; font: inherit; padding: 5px;
           border: 1px solid #ccc; border-radius: 5px; background: #fff; color: inherit; }
.actions { display: flex; gap: 5px; align-items: center; margin-top: 5px; }
.actions .spacer { flex: 1; }
.hint { color: #999; font-size: 11px; margin-top: 3px; }
.note { width: 360px; max-height: 75vh; display: flex; flex-direction: column; }
.note-head { display: flex; align-items: center; gap: 6px; padding: 5px 8px; cursor: move;
             border-bottom: 1px solid #eee; color: #777; font-size: 11px; user-select: none; }
.note-head .title { flex: 1; }
.note-head button { padding: 0 6px; border: none; background: none; font-size: 14px; }
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
  .bubble:hover, button:hover { background: #34343a; }
  button, textarea { background: #2c2c30; border-color: #555; }
  button.primary { background: #3b3360; border-color: #6c5cb5; }
  .note-head, .composer { border-color: #3a3a3e; }
  .turn.comment .text { background: #3a3424; }
  .text pre { background: #2c2c30; }
}
")

(defn- px [n] (str (js/Math.round n) "px"))

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
  "Enter asks, Cmd/Ctrl+Enter comments, Shift+Enter is a newline. Handlers get trimmed text."
  [^js ta on-ask on-comment]
  (.addEventListener ta "keydown"
                     (fn [^js e]
                       (when (and (= "Enter" (.-key e)) (not (.-shiftKey e)))
                         (.preventDefault e)
                         (let [t (str/trim (.-value ta))]
                           (when (seq t)
                             (if (or (.-metaKey e) (.-ctrlKey e)) (on-comment t) (on-ask t))))))))

(defn- submit-button
  "Button that submits the textarea's text to `f`."
  [label ^js ta f & [cls]]
  [:button {:class cls
            :on-click #(let [t (str/trim (.-value ta))] (when (seq t) (f t)))}
   label])

(def ^:private hint "Enter to ask · ⌘/Ctrl+Enter to comment · Shift+Enter for newline")

(defn bubble [{:keys [on-ask] :as pos}]
  (at (h [:div {:class "sd bubble"
                ;; preventDefault keeps the page selection alive
                :on-mousedown #(.preventDefault %)
                :on-click on-ask}
          "Ask"])
      pos))

(defn popover
  "Question box for a selection. Calls (on-ask text) or (on-comment text)."
  [{:keys [quote on-ask on-comment] :as pos}]
  (let [ta (h [:textarea {:placeholder "Ask about this, or write a comment…"}])
        el (h [:div {:class "sd popover"}
               [:div {:class "quote"} (short-quote quote 160)]
               [:div {:class "presets"}
                (for [{:keys [label question]} core/presets]
                  [:button {:on-click #(on-ask question)} label])]
               ta
               [:div {:class "actions"}
                (submit-button "Ask" ta on-ask "primary")
                (submit-button "Comment" ta on-comment)]
               [:div {:class "hint"} (str hint " · Esc to close")]])]
    (composer-keys! ta on-ask on-comment)
    (isolate-keys! el)
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
  "Ephemeral note holding a thread, with a composer for follow-ups and comments.
  Submitting clears the composer; asking is refused (text kept) while an answer streams."
  [{:keys [quote on-close on-copy on-ask on-comment on-stop] :as pos}]
  (let [ta (h [:textarea {:placeholder "Follow up, or comment…"}])
        ask-btn (atom nil)
        ask (fn [t] (when-not (.-disabled @ask-btn)
                      (set! (.-value ta) "")
                      (on-ask t)))
        comment (fn [t] (set! (.-value ta) "") (on-comment t))
        el (h [:div {:class "sd note"}
               [:div {:class "note-head"}
                [:span {:class "title"} "Snapdragon"]
                [:button {:title "Close" :on-click on-close} "×"]]
               [:div {:class "note-body"}
                [:div {:class "quote"} (short-quote quote 200)]
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
    (composer-keys! ta ask comment)
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
