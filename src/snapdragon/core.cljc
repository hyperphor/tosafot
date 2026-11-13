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
  "A thread message. `author` is {:type :human} or {:type :llm :provider .. :model ..}.
  Human messages have `kind` :ask or :comment; LLM messages have a `status`
  (:streaming, :done, :stopped, :refused, :error)."
  [author text & {:keys [kind status parent]}]
  (cond-> {:msg/id      (random-uuid)
           :msg/author  author
           :msg/text    text
           :msg/created (now)}
    kind   (assoc :msg/kind kind)
    status (assoc :msg/status status)
    parent (assoc :msg/parent parent)))

(defn human? [msg] (= :human (get-in msg [:msg/author :type])))

(defn add-message
  "Append `msg` to the annotation's thread, parenting it on the last message."
  [ann msg]
  (let [parent (:msg/id (peek (:annotation/thread ann)))]
    (update ann :annotation/thread conj
            (cond-> msg (and parent (not (:msg/parent msg))) (assoc :msg/parent parent)))))

(defn update-message
  "Apply `f` to the thread message with id `id`."
  [ann id f & args]
  (update ann :annotation/thread
          (fn [thread] (mapv #(if (= id (:msg/id %)) (apply f % args) %) thread))))

(defn- usable?
  "Whether a message belongs in the history sent to the model."
  [msg]
  (or (human? msg)
      (and (#{:done :stopped} (:msg/status msg))
           (not (str/blank? (:msg/text msg))))))

(defn- human-text [msg]
  (if (= :comment (:msg/kind msg))
    (str "[note] " (:msg/text msg))
    (:msg/text msg)))

(defn prompt-turns
  "Thread -> alternating [{:role :content}] turns. Consecutive human messages merge;
  comments are marked [note]; refused, failed, and empty LLM replies are dropped."
  [thread]
  (->> (filter usable? thread)
       (partition-by human?)
       (mapv (fn [group]
               (if (human? (first group))
                 {:role "user" :content (str/join "\n\n" (map human-text group))}
                 {:role "assistant" :content (str/join "\n\n" (map :msg/text group))})))))

(defn thread->markdown
  "The thread as Markdown, for copying out."
  [{:annotation/keys [target thread]}]
  (str/join "\n\n"
            (cons (str "> " (str/replace (get-in target [:quote :exact] "") #"\s+" " "))
                  (for [m thread :when (usable? m)]
                    (if (human? m)
                      (str "**" (if (= :comment (:msg/kind m)) "Note" "Q") ":** " (:msg/text m))
                      (str "**" (get-in m [:msg/author :model] "LLM") ":** " (:msg/text m)))))))

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
  (str "The user is reading a web page. They selected a passage and are asking about it, "
       "possibly over several turns. Answer about the selected passage, using the surrounding "
       "context to work out what it refers to. Be direct and reasonably brief; this is shown in "
       "a small sidenote. Use Markdown where it helps. Text starting with [note] is the user's "
       "own commentary: take it into account, but don't reply to it unless asked."))

(defn build-prompt
  "Return {:system s :messages [...]}. `turns` is from `prompt-turns`; the page context is
  prepended to the first. `before`/`after` are the page text around the selection."
  [{:keys [selection before after title url turns extra-system]}]
  (let [preamble (str "<page title=\"" title "\" url=\"" url "\"/>\n"
                      "<context>\n"
                      (clip-before (or before "") context-chars)
                      "<selection>" selection "</selection>"
                      (clip-after (or after "") context-chars)
                      "\n</context>\n\n")]
    {:system   (cond-> system-prompt
                 (not (str/blank? extra-system)) (str "\n\n" extra-system))
     :messages (update-in (vec turns) [0 :content] #(str preamble %))}))
