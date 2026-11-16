(ns tosafot.pkg-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tosafot.core :as core]
            [tosafot.pkg :as pkg]))

(def llm {:type :llm :provider "Anthropic" :model "m"})

(deftest markdown->blocks-test
  (is (= [{:content "Para one continues."}
          {:content "## Head"}
          {:content "a" :children [{:content "b"}]}
          {:content "c"}
          {:content "```\nx\n```"}
          {:content "| a | b |\n|-|-|"}]
         (pkg/markdown->blocks
          "Para one\ncontinues.\n\n## Head\n- a\n  - b\n- c\n\n```\nx\n```\n| a | b |\n|-|-|"))))

(deftest render-test
  (is (= "- top\n  second line\n  id:: 1\n\t- kid\n\t\t- grandkid"
         (pkg/render [{:content "top\nsecond line" :props {:id "1"}
                       :children [{:content "kid" :children [{:content "grandkid"}]}]}]))))

(deftest note-block-test
  (let [id (random-uuid)
        ann {:annotation/id id :annotation/url "https://x.org/a" :annotation/title "A [page]"
             :annotation/target {:quote {:exact "the\n passage"}}
             :annotation/thread [(core/message {:type :human} "Why?" :kind :ask :instructions "secret")
                                 (core/message llm "Because:\n- one\n- two" :status :done)
                                 (core/message {:type :human} "Hm." :kind :comment)
                                 (core/message llm "failed" :status :error)]}
        text (pkg/render [(pkg/note-block ann "tosafot")])]
    (is (= (str "- [A page](https://x.org/a) #tosafot\n  id:: " id "\n"
                "\t- > the passage\n"
                "\t- Why?\n\t\t- #AskClaude\n\t\t\t- Because:\n\t\t\t- one\n\t\t\t- two\n"
                "\t- Hm.")
           text))
    (testing "instructions and failed replies stay out"
      (is (not (str/includes? text "secret")))
      (is (not (str/includes? text "failed")))))
  (is (= "#[[my notes]]" (pkg/tag-ref "#my notes")))
  (is (= "#blyg" (pkg/tag-ref "blyg"))))

(deftest journal-test
  (is (= "2026_11_16.md" (pkg/latest-journal ["2026_11_15.md" "2026_11_16.md" "2025_12_31.md" ".DS_Store"])))
  (is (nil? (pkg/latest-journal [])))
  (is (= "2026_01_05.md" (pkg/journal-name 2026 1 5))))

(deftest upsert-test
  (let [b1 "- note\n  id:: abc\n\t- v1"
        b2 "- note\n  id:: abc\n\t- v2"]
    (testing "appended after existing blocks"
      (is (= (str "- first\n\t- kid\n" b1 "\n") (pkg/upsert "- first\n\t- kid\n" b1 "abc"))))
    (testing "an empty journal is replaced"
      (is (= (str b1 "\n") (pkg/upsert "-\n" b1 "abc")))
      (is (= (str b1 "\n") (pkg/upsert nil b1 "abc"))))
    (testing "re-export replaces the block in place"
      (is (= (str "- first\n" b2 "\n- last\n")
             (pkg/upsert (str "- first\n" b1 "\n- last\n") b2 "abc")))
      (is (pkg/contains-block? (str "- first\n" b1) "abc"))
      (is (not (pkg/contains-block? "- first" "abc"))))))
