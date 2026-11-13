(ns snapdragon.anchor
  "Text-quote/position selectors over a page's text, and re-anchoring them.")

(def ^:private context-len 32)

(defn selector
  "Selector for text[start, end): {:quote {:exact :prefix :suffix} :pos {:start :end}}."
  [text start end]
  {:quote {:exact  (subs text start end)
           :prefix (subs text (max 0 (- start context-len)) start)
           :suffix (subs text end (min (count text) (+ end context-len)))}
   :pos   {:start start :end end}})

(defn- common-suffix-len [a b]
  (let [n (min (count a) (count b))]
    (loop [i 0]
      (if (and (< i n) (= (nth a (- (count a) 1 i)) (nth b (- (count b) 1 i)))) (recur (inc i)) i))))

(defn- common-prefix-len [a b]
  (let [n (min (count a) (count b))]
    (loop [i 0]
      (if (and (< i n) (= (nth a i) (nth b i))) (recur (inc i)) i))))

(defn- occurrences [text s]
  (loop [from 0 acc []]
    (let [i #?(:clj (.indexOf ^String text ^String s (int from)) :cljs (.indexOf text s from))]
      (if (neg? i) acc (recur (inc i) (conj acc i))))))

(defn locate
  "[start end] of the selector's text in `text`, or nil if it can't be found.
  Uses the stored position if it still holds; otherwise the occurrence of the quote whose
  surrounding text best matches prefix/suffix, nearest the old position on ties."
  [text {{:keys [exact prefix suffix]} :quote {:keys [start]} :pos}]
  (when (seq exact)
    (let [n (count exact)
          before #(subs text (max 0 (- % context-len)) %)
          after #(subs text (+ % n) (min (count text) (+ % n context-len)))
          context-score #(+ (common-suffix-len (or prefix "") (before %))
                            (common-prefix-len (or suffix "") (after %)))]
      (if (and start (<= (+ start n) (count text))
               (= exact (subs text start (+ start n)))
               (= (count prefix) (common-suffix-len prefix (before start))))
        [start (+ start n)]
        (when-let [hits (seq (occurrences text exact))]
          (let [best (first (sort-by (juxt (comp - context-score) #(abs (- % (or start %)))) hits))]
            [best (+ best n)]))))))
