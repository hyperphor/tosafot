(ns tosafot.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyperphor.tunamelt.codec :as codec]
            [tosafot.core :as core]))

(def llm {:type :llm :provider "Anthropic" :model "m"})

(defn- thread [& msgs]
  (:annotation/thread (reduce core/add-message
                              (core/new-annotation {:target {:exact "x"}})
                              msgs)))

(deftest thread-test
  (let [ann (-> (core/new-annotation {:url "u" :title "t" :target {:exact "x"}})
                (core/add-message (core/message {:type :human} "q" :kind :ask))
                (core/add-message (core/message llm "a" :status :streaming)))
        [q a] (:annotation/thread ann)
        ann (core/update-message ann (:msg/id a) assoc :msg/text "done" :msg/status :done)]
    (is (false? (:annotation/saved? ann)))
    (is (nil? (:msg/parent q)))
    (is (= (:msg/id q) (:msg/parent a)))
    (is (= "done" (:msg/text (second (:annotation/thread ann)))))))

(deftest prompt-turns-test
  (testing "alternating roles, comments marked and merged with the next question"
    (is (= [{:role "user" :content "Why?"}
            {:role "assistant" :content "Because."}
            {:role "user" :content "[note] I doubt it\n\nSource?"}]
           (core/prompt-turns
            (thread (core/message {:type :human} "Why?" :kind :ask)
                    (core/message llm "Because." :status :done)
                    (core/message {:type :human} "I doubt it" :kind :comment)
                    (core/message {:type :human} "Source?" :kind :ask))))))
  (testing "refused, failed, and empty replies are dropped"
    (is (= [{:role "user" :content "A\n\nB"}]
           (core/prompt-turns
            (thread (core/message {:type :human} "A" :kind :ask)
                    (core/message llm "partial" :status :refused)
                    (core/message llm "" :status :stopped)
                    (core/message {:type :human} "B" :kind :ask)))))))

(deftest instructions-test
  (testing "instructions go to the model with their question, and comments still merge"
    (is (= [{:role "user" :content "[note] hm\n\nArgue.\n<instructions>Push back.</instructions>"}]
           (core/prompt-turns
            (thread (core/message {:type :human} "hm" :kind :comment)
                    (core/message {:type :human} "Argue." :kind :ask :instructions "Push back."))))))
  (testing "blank instructions are dropped; markdown omits them"
    (let [m (core/message {:type :human} "Q" :kind :ask :instructions "")]
      (is (not (contains? m :msg/instructions))))
    (is (= "> x\n\n**Q:** Argue."
           (core/thread->markdown
            {:annotation/target {:quote {:exact "x"}}
             :annotation/thread [(core/message {:type :human} "Argue." :kind :ask :instructions "Push back.")]})))))

(deftest actions-test
  (testing "nil stored list gives the built-ins"
    (is (= core/builtin-actions (core/reconcile-actions nil))))
  (testing "stored edits and order are kept; missing built-ins appended"
    (let [stored [{:id "argue" :label "Fight" :place "toolbar" :prompt "Fight me." :instructions ""}
                  {:id "c1" :label "Mine" :place "menu" :prompt "Mine?" :instructions ""}]
          r (core/reconcile-actions stored)]
      (is (= stored (take 2 r)))
      (is (= (dec (+ 2 (count core/builtin-actions))) (count r)))
      (is (= 1 (count (filter #(= "argue" (:id %)) r))))
      (is (= ["Fight" "Explain"] (map :label (core/actions-at r "toolbar"))))
      (is (core/builtin? {:id "argue"}))
      (is (not (core/builtin? {:id "c1"})))))
  (testing "default placement"
    (is (= ["Explain"] (map :label (core/actions-at core/builtin-actions "toolbar"))))
    (is (= ["Define" "Argue" "ELI5"] (map :label (core/actions-at core/builtin-actions "menu"))))))

(deftest markdown-test
  (let [md (core/thread->markdown
            {:annotation/target {:quote {:exact "the\n text"}}
             :annotation/thread [(core/message {:type :human} "Why?" :kind :ask)
                                 (core/message llm "Because." :status :done)
                                 (core/message {:type :human} "Hm." :kind :comment)]})]
    (is (= "> the text\n\n**Q:** Why?\n\n**m:** Because.\n\n**Note:** Hm." md))))

(deftest share-test
  (let [ann {:annotation/url "https://x.org/a" :annotation/title "Page"
             :annotation/target {:quote {:exact "one two"}}
             :annotation/thread [(core/message {:type :human} "Why?" :kind :ask)
                                 (core/message llm "**Because** so." :status :done)]}]
    (testing "first answer, quote, title, deep link"
      (is (= "Because so.\n\n“one two”\nPage https://x.org/a#:~:text=one%20two"
             (core/share-text ann :x))))
    (testing "the user's notes win over answers"
      (is (str/starts-with?
           (core/share-text (update ann :annotation/thread conj
                                    (core/message {:type :human} "Mine." :kind :comment)) :x)
           "Mine.\n\n")))
    (testing "long bodies are clipped to the service limit"
      (let [long (update ann :annotation/thread conj
                         (core/message {:type :human} (str/join " " (repeat 200 "word")) :kind :comment))]
        (is (<= (count (core/share-text long :bluesky)) 300))
        (is (str/ends-with? (core/share-text long :bluesky) "#:~:text=one%20two")))))
  (is (= "https://x.org/a#:~:text=a%20b%20c%20d,f%20g%20h%20i%2Dj"
         (core/text-fragment-url "https://x.org/a" "a b c d e f g h i-j")))
  (is (= "https://bsky.app/intent/compose?text=a%20b" (core/share-url :bluesky "a b"))))

(deftest clip-test
  (is (= "short" (core/clip-before "short" 10)))
  (is (= "…three four" (core/clip-before "one two three four" 12)))
  (is (= "one two…" (core/clip-after "one two three four" 10))))

(deftest prompt-test
  (let [{:keys [system messages]} (core/build-prompt
                                   {:selection "sel" :before "pre " :after " post"
                                    :title "T" :url "U" :extra-system "Be terse."
                                    :turns [{:role "user" :content "Why?"}
                                            {:role "assistant" :content "So."}
                                            {:role "user" :content "More?"}]})]
    (is (str/ends-with? system "Be terse."))
    (is (= ["user" "assistant" "user"] (map :role messages)))
    (is (str/includes? (:content (first messages)) "pre <selection>sel</selection> post"))
    (is (str/ends-with? (:content (first messages)) "Why?"))
    (is (= "More?" (:content (last messages))))
    (testing "blank extra system prompt is omitted"
      (is (= core/system-prompt (:system (core/build-prompt {:extra-system "  " :turns [{:role "user" :content "q"}]})))))))

(deftest url-test
  (is (= "https://x.org/a?b=1" (core/normalize-url "https://x.org/a?b=1#sec")))
  (is (= "https://x.org/a" (core/normalize-url "https://x.org/a"))))

(deftest revive-roundtrip-test
  (testing "an annotation survives tunamelt's JSON codec via revive"
    (let [ann (-> (core/new-annotation {:url "https://x.org/a#frag" :title "T"
                                        :target {:quote {:exact "x" :prefix "" :suffix ""}
                                                 :pos {:start 0 :end 1}}})
                  (assoc :annotation/saved? true)
                  (core/add-message (core/message {:type :human} "Why?" :kind :ask))
                  (core/add-message (core/message llm "So." :status :done)))]
      (is (= "https://x.org/a" (:annotation/url ann)))
      (is (= ann (core/revive (codec/roundtrip ann))))))
  (testing "instructions survive saving, so follow-ups keep them"
    (let [ann (core/add-message (core/new-annotation {:target {:exact "x"}})
                                (core/message {:type :human} "Argue." :kind :ask :instructions "Push back."))]
      (is (= "Argue.\n<instructions>Push back.</instructions>"
             (-> (codec/roundtrip ann) core/revive :annotation/thread core/prompt-turns first :content)))))
  (testing "a reply saved mid-stream revives as stopped"
    (let [ann (core/add-message (core/new-annotation {:target {}})
                                (core/message llm "part" :status :streaming))]
      (is (= :stopped (-> (codec/roundtrip ann) core/revive :annotation/thread first :msg/status))))))
