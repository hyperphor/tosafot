(ns tosafot.config
  "Settings stored in chrome.storage.local."
  (:require [tosafot.core :as core]))

;; Named for the extension's old name; kept so existing settings survive.
(def ^:private storage-key "snapdragon/settings")

(def api-styles
  {"anthropic" {:label "Anthropic" :base-url "https://api.anthropic.com" :model "claude-opus-5-5"}
   "openai"    {:label "OpenAI-compatible" :base-url "https://api.openai.com/v1" :model ""}})

(defn new-provider [api]
  (let [{:keys [label base-url model]} (api-styles api)]
    (cond-> {:id (str (random-uuid)) :name label :api api
             :base-url base-url :api-key "" :model model}
      (= api "anthropic") (assoc :effort "low"))))

(def defaults
  (let [p (new-provider "anthropic")]
    {:providers     [p]
     :active        (:id p)
     :system-prompt ""
     :show-bubble?  true
     :pkg-tag       "tosafot"
     :pkg-label     "Export to notebook"}))

(defn load-settings
  "Promise of the settings map, merged over defaults; :actions includes any new built-ins."
  []
  (-> (.get js/chrome.storage.local storage-key)
      (.then (fn [^js r]
               (-> (merge defaults (some-> (aget r storage-key) (js->clj :keywordize-keys true)))
                   (update :actions core/reconcile-actions))))))

(defn save! [settings]
  (.set js/chrome.storage.local (js-obj storage-key (clj->js settings))))

(defn active-provider [{:keys [providers active]}]
  (or (some #(when (= active (:id %)) %) providers) (first providers)))
