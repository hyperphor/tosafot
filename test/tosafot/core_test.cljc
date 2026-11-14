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

(deftest markdown-test
  (let [md (core/thread->markdown
            {:annotation/target {:quote {:exact "the\n text"}}
             :annotation/thread [(core/message {:type :human} "Why?" :kind :ask)
                                 (core/message llm "Because." :status :done)
                                 (core/message {:type :human} "Hm." :kind :comment)]})]
    (is (= "> the text\n\n**Q:** Why?\n\n**m:** Because.\n\n**Note:** Hm." md))))

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
  (testing "a reply saved mid-stream revives as stopped"
    (let [ann (core/add-message (core/new-annotation {:target {}})
                                (core/message llm "part" :status :streaming))]
      (is (= :stopped (-> (codec/roundtrip ann) core/revive :annotation/thread first :msg/status))))))
