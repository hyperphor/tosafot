(ns snapdragon.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [snapdragon.core :as core]))

(deftest thread-test
  (let [ann (-> (core/new-annotation {:url "u" :title "t" :target {:exact "x"}})
                (core/add-message (core/message {:type :human} "q"))
                (core/add-message (core/message {:type :llm :model "m"} "a")))
        [q a] (:annotation/thread ann)]
    (is (false? (:annotation/saved? ann)))
    (is (= :ask (:annotation/kind ann)))
    (is (nil? (:msg/parent q)))
    (is (= (:msg/id q) (:msg/parent a)))))

(deftest clip-test
  (is (= "short" (core/clip-before "short" 10)))
  (is (= "…three four" (core/clip-before "one two three four" 12)))
  (is (= "one two…" (core/clip-after "one two three four" 10))))

(deftest prompt-test
  (let [{:keys [system messages]} (core/build-prompt {:selection "sel" :before "pre " :after " post"
                                                      :title "T" :url "U" :question "Why?"
                                                      :extra-system "Be terse."})
        content (:content (first messages))]
    (is (str/ends-with? system "Be terse."))
    (is (str/includes? content "pre <selection>sel</selection> post"))
    (is (str/ends-with? content "Why?"))
    (testing "blank extra system prompt is omitted"
      (is (= core/system-prompt (:system (core/build-prompt {:extra-system "  "})))))))
