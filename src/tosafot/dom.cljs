(ns tosafot.dom
  "Minimal hiccup -> DOM element builder."
  (:require [clojure.string :as str]))

(declare h)

(defn- append-child! [^js el child]
  (cond
    (nil? child)    nil
    (string? child) (.appendChild el (js/document.createTextNode child))
    (vector? child) (.appendChild el (h child))
    (seq? child)    (doseq [c child] (append-child! el c))
    (instance? js/Node child) (.appendChild el child)
    :else (.appendChild el (js/document.createTextNode (str child)))))

(defn h
  "Build an Element from hiccup `[tag attrs? & children]`.
  Attrs: `:on-<event>` fns become listeners, `:class` may be a seq, false/nil attrs are skipped."
  [[tag & body]]
  (let [[attrs children] (if (map? (first body)) [(first body) (rest body)] [{} body])
        el (js/document.createElement (name tag))]
    (doseq [[k v] attrs :when (some? v) :when (not (false? v))]
      (let [k (name k)]
        (cond
          (str/starts-with? k "on-") (.addEventListener el (subs k 3) v)
          (= k "class") (set! (.-className el) (if (string? v) v (str/join " " (remove nil? v))))
          (#{"value" "checked"} k) (aset el k v)
          :else (.setAttribute el k (if (true? v) "" (str v))))))
    (append-child! el children)
    el))

(defn qs [^js root sel] (.querySelector root sel))
