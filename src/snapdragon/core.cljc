(ns snapdragon.core
  "Annotation data model, threads, and prompt construction. Platform-neutral."
  (:require [clojure.string :as str]))

(defn- now [] #?(:clj (java.util.Date.) :cljs (js/Date.)))

(def presets
  "Canned questions offered in the popover, in display order."
  [{:id :explain :label "Explain" :question "Explain this."}
   {:id :eli5    :label "ELI5"    :question "Explain this like I'm five."}
   {:id :define  :label "Define"  :question "Define the key terms in this."}])

(def ^:private context-chars 2000)

(defn new-annotation
  "Build an ephemeral annotation for a selection. `target` is {:exact :prefix :suffix}."
  [{:keys [url title target kind] :or {kind :ask}}]
  {:annotation/id      (random-uuid)
   :annotation/url     url
   :annotation/title   title
   :annotation/target  {:quote target}
   :annotation/kind    kind
   :annotation/saved?  false
   :annotation/created (now)
   :annotation/thread  []})

(defn message
  "A thread message. `author` is {:type :human} or {:type :llm :provider .. :model ..}."
  [author text & [parent]]
  (cond-> {:msg/id      (random-uuid)
           :msg/author  author
           :msg/text    text
           :msg/created (now)}
    parent (assoc :msg/parent parent)))

(defn add-message
  "Append `msg` to the annotation's thread, parenting it on the last message."
  [ann msg]
  (let [parent (:msg/id (peek (:annotation/thread ann)))]
    (update ann :annotation/thread conj
            (cond-> msg (and parent (not (:msg/parent msg))) (assoc :msg/parent parent)))))

(defn clip-before
  "Last `n` chars of `s`, cut back to a word boundary when clipped."
  [s n]
  (if (<= (count s) n)
    s
    (let [t (subs s (- (count s) n))]
      (str "…" (str/replace-first t #"^\S*\s" "")))))

(defn clip-after
  "First `n` chars of `s`, cut back to a word boundary when clipped."
  [s n]
  (if (<= (count s) n)
    s
    (let [t (subs s 0 n)]
      (str (str/replace t #"\s\S*$" "") "…"))))

(def system-prompt
  (str "The user is reading a web page. They selected a passage and asked a question about it. "
       "Answer the question about the selected passage, using the surrounding context to work out "
       "what it refers to. Be direct and reasonably brief; this is shown in a small sidenote. "
       "Use Markdown where it helps."))

(defn build-prompt
  "Return {:system s :messages [...]} for a question about a selection.
  `before`/`after` are the page text around the selection."
  [{:keys [selection before after title url question extra-system]}]
  {:system   (cond-> system-prompt
               (not (str/blank? extra-system)) (str "\n\n" extra-system))
   :messages [{:role    "user"
               :content (str "<page title=\"" title "\" url=\"" url "\"/>\n"
                             "<context>\n"
                             (clip-before (or before "") context-chars)
                             "<selection>" selection "</selection>"
                             (clip-after (or after "") context-chars)
                             "\n</context>\n\n"
                             question)}]})
