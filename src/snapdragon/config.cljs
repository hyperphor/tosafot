(ns snapdragon.config
  "Settings stored in chrome.storage.local.")

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
     :show-bubble?  true}))

(defn load-settings
  "Promise of the settings map, merged over defaults."
  []
  (-> (.get js/chrome.storage.local storage-key)
      (.then (fn [^js r]
               (merge defaults (some-> (aget r storage-key) (js->clj :keywordize-keys true)))))))

(defn save! [settings]
  (.set js/chrome.storage.local (js-obj storage-key (clj->js settings))))

(defn active-provider [{:keys [providers active]}]
  (or (some #(when (= active (:id %)) %) providers) (first providers)))
