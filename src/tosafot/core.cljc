(ns tosafot.core
  "Annotation data model, threads, and prompt construction. Platform-neutral."
  (:require [clojure.string :as str]))

(defn- now [] #?(:clj (java.util.Date.) :cljs (js/Date.)))

(defn- parse-inst [s]
  #?(:clj (java.util.Date/from (java.time.Instant/parse s)) :cljs (js/Date. s)))

;; Old name, kept: saved annotations' URIs include it.
(def realm "snapdragon")

(defn annotation-uri [id] (str "hyperphor://" realm "/annotation/" id))

(defn normalize-url
  "Page identity for annotations: the URL without its fragment."
  [url]
  (first (str/split (str url) #"#" 2)))

;;; Actions: canned questions offered on the selection toolbar and in new notes.
;;; :place is "toolbar", "menu" (under ⋯), or "off". Strings, not keywords, since
;;; they round-trip through chrome.storage. :instructions go to the model but aren't shown.

(defn- action [id label place prompt & [instructions]]
  {:id id :label label :place place :prompt prompt :instructions (or instructions "")})

(def builtin-actions
  "The built-in library, in default order."
  [(action "explain" "Explain" "toolbar" "Explain this.")
   (action "define" "Define" "menu" "Define the key terms in this.")
   (action "argue" "Argue" "menu" "Argue with this."
           "Take the other side. Give the strongest objections or counterarguments to the passage, most serious first. Don't summarize it or hedge.")
   (action "eli5" "ELI5" "menu" "Explain this like I'm five.")
   (action "steelman" "Steelman" "off" "Steelman this."
           "Give the strongest, most charitable version of the passage's claim or argument, filling in unstated premises.")
   (action "summarize" "Summarize" "off" "Summarize this." "One short paragraph at most.")
   (action "flaw" "Find the flaw" "off" "What's wrong with this?"
           "Look for errors, unstated assumptions, gaps in reasoning, or misleading claims. If it holds up, say so.")
   (action "example" "Example" "off" "Give a concrete example of this.")
   (action "counterexample" "Counterexample" "off" "Is there a counterexample to this?")
   (action "analogy" "Analogy" "off" "Explain this with an analogy.")
   (action "prereqs" "Prerequisites" "off" "What do I need to know to understand this?"
           "List the concepts the passage assumes, briefly explaining each.")
   (action "simplify" "Simplify" "off" "Say this more simply." "Rewrite it in plain language, keeping the meaning.")
   (action "deeper" "Go deeper" "off" "Go deeper on this." "Assume I understand the basics; give the more advanced picture.")
   (action "quiz" "Quiz me" "off" "Quiz me on this."
           "Ask me two or three questions that test understanding of the passage. Don't give the answers until I reply.")
   (action "translate" "Translate" "off" "Translate this into English.")
   (action "etymology" "Etymology" "off" "Where do the key words here come from?")
   (action "history" "Historical context" "off" "What's the historical context of this?")])

(defn reconcile-actions
  "The stored action list, plus any built-ins it lacks (appended at their default place)."
  [stored]
  (if (nil? stored)
    builtin-actions
    (let [have (set (map :id stored))]
      (into (vec stored) (remove #(have (:id %)) builtin-actions)))))

(defn builtin? [{:keys [id]}] (boolean (some #(= id (:id %)) builtin-actions)))

(defn actions-at
  "Actions with :place in `places` (strings), in order."
  [actions & places]
  (filter #((set places) (:place %)) actions))

(def ^:private context-chars 2000)

(defn new-annotation
  "Build an ephemeral annotation. `target` is {:quote {:exact :prefix :suffix} :pos {:start :end}}."
  [{:keys [url title target kind] :or {kind :ask}}]
  {:annotation/id      (random-uuid)
   :annotation/url     (normalize-url url)
   :annotation/title   title
   :annotation/target  target
   :annotation/kind    kind
   :annotation/saved?  false
   :annotation/created (now)
   :annotation/thread  []})

(defn message
  "A thread message. `author` is {:type :human} or {:type :llm :provider .. :model ..}.
  Human messages have `kind` :ask or :comment, and may carry hidden `instructions` for the
  model; LLM messages have a `status` (:streaming, :done, :stopped, :refused, :error)."
  [author text & {:keys [kind status parent instructions]}]
  (cond-> {:msg/id      (random-uuid)
           :msg/author  author
           :msg/text    text
           :msg/created (now)}
    kind   (assoc :msg/kind kind)
    status (assoc :msg/status status)
    parent (assoc :msg/parent parent)
    (seq instructions) (assoc :msg/instructions instructions)))

(defn human? [msg] (= :human (get-in msg [:msg/author :type])))

(defn add-message
  "Append `msg` to the annotation's thread, parenting it on the last message."
  [ann msg]
  (let [parent (:msg/id (peek (:annotation/thread ann)))]
    (update ann :annotation/thread conj
            (cond-> msg (and parent (not (:msg/parent msg))) (assoc :msg/parent parent)))))

(defn- kw [x] (some-> x keyword))
(defn- ->uuid [x] (if (string? x) (parse-uuid x) x))
(defn- ->inst [x] (if (string? x) (parse-inst x) x))

(defn- revive-message [m]
  (cond-> (-> m
              (update :msg/id ->uuid)
              (update :msg/created ->inst)
              (update-in [:msg/author :type] kw))
    (:msg/parent m) (update :msg/parent ->uuid)
    (:msg/kind m)   (update :msg/kind kw)
    (:msg/status m) (update :msg/status kw)
    ;; A reply still streaming when it was saved was cut off.
    (= "streaming" (name (or (:msg/status m) ""))) (assoc :msg/status :stopped)))

(defn revive
  "Restore an annotation read back from storage (where keywords, uuids, and dates are strings)."
  [ann]
  (-> ann
      (update :annotation/id ->uuid)
      (update :annotation/kind kw)
      (update :annotation/created ->inst)
      (update :annotation/thread #(mapv revive-message %))))

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

(defn- human-text [{:msg/keys [kind text instructions]}]
  (cond
    (= :comment kind)   (str "[note] " text)
    (seq instructions)  (str text "\n<instructions>" instructions "</instructions>")
    :else               text))

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

;;; Sharing to social media via each service's compose page; no accounts or config.

(def share-services
  "Compose URL prefix, length limit, and the length a URL counts as (nil: its real length)."
  {:x        {:label "X"        :limit 280 :url-len 23  :base "https://x.com/intent/post?text="}
   :bluesky  {:label "Bluesky"  :limit 300 :url-len nil :base "https://bsky.app/intent/compose?text="}
   :mastodon {:label "Mastodon" :limit 500 :url-len 23  :base "https://share.joinmastodon.org/#text="}})

(defn- enc [s] #?(:clj (-> (java.net.URLEncoder/encode (str s) "UTF-8") (str/replace "+" "%20"))
                  :cljs (js/encodeURIComponent s)))

(defn- plain
  "Markdown reduced to plain text, roughly."
  [md]
  (-> md
      (str/replace #"(?m)^#+\s*" "")
      (str/replace #"\*\*|__|`" "")
      (str/replace #"(?m)^\s*[-*]\s+" "• ")
      (str/replace #"\n{3,}" "\n\n")
      str/trim))

(declare clip-after)

(defn share-text
  "A post for `service` (a key of share-services): the quote, the user's notes (else the
  first answer), and the page title and URL, clipped to fit."
  [{:annotation/keys [target thread title url]} service]
  (let [{:keys [limit url-len]} (share-services service)
        quote (str/trim (str/replace (get-in target [:quote :exact] "") #"\s+" " "))
        notes (filter #(and (human? %) (= :comment (:msg/kind %))) thread)
        body (plain (if (seq notes)
                      (str/join "\n\n" (map :msg/text notes))
                      (or (some #(when (and (not (human? %)) (usable? %)) (:msg/text %)) thread) "")))
        title (clip-after (str/trim (or title "")) 60)
        head (when (seq quote) (str "“" (clip-after quote 140) "”\n\n"))
        tail (str (when (seq title) (str title " ")) url)
        room (- limit (count head) (count tail) (if url-len (- url-len (count url)) 0) 2)
        body (when (and (seq body) (>= room 20)) (str (clip-after body (dec room)) "\n\n"))]
    (str head body tail)))

(defn share-url [service text] (str (:base (share-services service)) (enc text)))

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
       "own commentary: take it into account, but don't reply to it unless asked. "
       "Text in <instructions> tags is the user's guidance on how to answer that question."))

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
