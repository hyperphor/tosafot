(ns tosafot.anchor-test
  (:require [clojure.test :refer [deftest is testing]]
            [tosafot.anchor :as a]))

(def text "The cat sat. Then the cat ran. Finally the cat slept.")

(defn- sel-at [s i n] (a/selector s i (+ i n)))

(deftest selector-test
  (is (= {:quote {:exact "cat" :prefix "The " :suffix " sat. Then the cat ran. Finally "}
          :pos {:start 4 :end 7}}
         (a/selector text 4 7))))

(deftest locate-test
  (let [second-cat (sel-at text 22 3)]
    (testing "unchanged text: stored position"
      (is (= [22 25] (a/locate text second-cat))))
    (testing "text inserted before: found by quote + context, not position"
      (let [t2 (str "Preface. " text)]
        (is (= [31 34] (a/locate t2 second-cat)))))
    (testing "context disambiguates repeated quotes even with a stale position"
      (let [t3 (str "Finally the cat slept. " text)
            sel (assoc second-cat :pos {:start 0 :end 3})]
        (is (= "the cat ran" (subs t3 (- (first (a/locate t3 sel)) 4) (+ (second (a/locate t3 sel)) 4))))))
    (testing "quote gone: nil"
      (is (nil? (a/locate "The dog sat." second-cat))))
    (testing "empty quote: nil"
      (is (nil? (a/locate text {:quote {:exact ""}}))))))
