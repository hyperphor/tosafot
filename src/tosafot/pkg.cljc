(ns tosafot.pkg
  "Export a note to a Logseq graph as an outline block. Pure: text in, text out."
  (:require [clojure.string :as str]
            [tosafot.core :as core]))

;;; Markdown -> Logseq blocks, after logseq-claude's markdownToBlocks: paragraphs, headings,
;;; code blocks and tables become blocks; list items become blocks nested by indentation.

(def ^:private list-re #"^(\s*)(?:[-*+]|\d+\.)\s+(.*)")

(defn- nest-items
  "[{:indent :content}] -> nested [{:content :children}]."
  [items]
  (loop [items items out []]
    (if-let [[{:keys [indent content]} & more] (seq items)]
      (let [[kids tail] (split-with #(> (:indent %) indent) more)]
        (recur tail (conj out (cond-> {:content content}
                                (seq kids) (assoc :children (nest-items kids))))))
      out)))

(defn- fence? [l] (str/starts-with? (str/triml l) "```"))
(defn- table? [l] (str/starts-with? (str/triml l) "|"))
(defn- heading? [l] (str/starts-with? l "#"))
(defn- para-end? [l] (or (str/blank? l) (re-find list-re l) (heading? l) (fence? l)))

(defn- join-para
  "Soft-wrapped lines join with a space; a hard break (two spaces or \\) keeps the newline."
  [lines]
  (reduce (fn [acc l]
            (if (re-find #"(  |\\)$" acc)
              (str (str/replace acc #"[ \t\\]+$" "") "\n" (str/trim l))
              (str (str/trimr acc) " " (str/trim l))))
          lines))

(defn markdown->blocks
  "Markdown text -> [{:content s :children [...]}]."
  [md]
  (loop [lines (str/split-lines (or md "")) out []]
    (if-let [[l & more] (seq lines)]
      (cond
        (str/blank? l) (recur more out)
        (fence? l) (let [[body tail] (split-with (complement fence?) more)]
                     (recur (rest tail) (conj out {:content (str/join "\n" (concat [l] body (take 1 tail)))})))
        (table? l) (let [[rows tail] (split-with table? lines)]
                     (recur tail (conj out {:content (str/join "\n" (map str/trim rows))})))
        (re-find list-re l) (let [[items tail] (split-with #(re-find list-re %) lines)]
                              (recur tail (into out (nest-items
                                                     (for [i items :let [[_ ind c] (re-find list-re i)]]
                                                       {:indent (count ind) :content c})))))
        (heading? l) (recur more (conj out {:content l}))
        :else (let [[para tail] (split-with (complement para-end?) more)]
                (recur tail (conj out {:content (join-para (cons l para))}))))
      out)))

;;; Rendering an outline as Logseq markdown: tab indentation, continuation lines and
;;; properties indented two spaces past the bullet.

(defn- render-block [depth {:keys [content props children]}]
  (let [ind (apply str (repeat depth "\t"))
        [first-line & more] (str/split-lines (str content))]
    (str/join "\n"
              (concat [(str ind "- " first-line)]
                      (for [l more] (str ind "  " l))
                      (for [[k v] props] (str ind "  " (name k) ":: " v))
                      (map #(render-block (inc depth) %) children)))))

(defn render [blocks] (str/join "\n" (map #(render-block 0 %) blocks)))

(def response-tag "#AskClaude")

(defn tag-ref
  "Logseq tag for `tag`, given with or without its #."
  [tag]
  (let [t (str/trim (str/replace-first (str/trim tag) #"^#" ""))]
    (if (re-find #"\s" t) (str "#[[" t "]]") (str "#" t))))

(defn- thread-blocks
  "Thread messages -> blocks: questions with their answers under a response-tag block, comments
  as plain blocks. Hidden instructions are never exported."
  [thread]
  (loop [msgs (filter core/usable? thread) out []]
    (if-let [[m & more] (seq msgs)]
      (if (core/human? m)
        (let [[answers tail] (split-with (complement core/human?) more)
              kids (when (and (= :ask (:msg/kind m)) (seq answers))
                     [{:content response-tag
                       :children (vec (mapcat #(markdown->blocks (:msg/text %)) answers))}])]
          (recur tail (conj out (cond-> {:content (:msg/text m)} kids (assoc :children kids)))))
        ;; An answer with no question before it.
        (recur more (into out (markdown->blocks (:msg/text m)))))
      out)))

(defn note-block
  "The note as one top-level block: page link and tag, the quote, then the thread.
  Carries id:: (the annotation's uuid) so a re-export can find and replace it."
  [{:annotation/keys [id url title target thread]} tag]
  (let [quote (str/trim (str/replace (get-in target [:quote :exact] "") #"\s+" " "))
        label (str/trim (str/replace (or (not-empty title) url) #"[\[\]]" ""))]
    {:content (str "[" label "](" url ") " (tag-ref tag))
     :props {:id (str id)}
     :children (vec (concat (when (seq quote) [{:content (str "> " quote)}])
                            (thread-blocks thread)))}))

;;; Placing the block in a journal file.

(defn latest-journal
  "The lexically last .md name in `names`, or nil."
  [names]
  (last (sort (filter #(str/ends-with? % ".md") names))))

(defn journal-name
  "Logseq's default journal file name: yyyy_MM_dd.md. `month` is 1-12."
  [year month day]
  (let [p #(if (< % 10) (str "0" %) (str %))]
    (str year "_" (p month) "_" (p day) ".md")))

(defn- top-level? [l] (str/starts-with? l "-"))

(defn- block-range
  "[start end) line indexes of the top-level block holding `id:: id`, or nil."
  [lines id]
  (when-let [i (first (keep-indexed #(when (= (str "id:: " id) (str/trim %2)) %1) lines))]
    (let [start (or (last (filter #(top-level? (lines %)) (range (inc i)))) i)
          end (or (first (filter #(top-level? (lines %)) (range (inc start) (count lines))))
                  (count lines))]
      [start end])))

(defn upsert
  "File `text` with `block-text` replacing the block whose id:: is `id`, or appended as a new
  top-level block. A journal holding only Logseq's empty bullet is replaced."
  [text block-text id]
  (let [lines (vec (str/split-lines (or text "")))]
    (if-let [[s e] (block-range lines id)]
      (str (str/join "\n" (concat (subvec lines 0 s) [block-text] (subvec lines e))) "\n")
      (let [body (str/trimr (or text ""))]
        (if (contains? #{"" "-"} body)
          (str block-text "\n")
          (str body "\n" block-text "\n"))))))

(defn contains-block? [text id] (some? (block-range (vec (str/split-lines (or text ""))) id)))
