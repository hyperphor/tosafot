(ns snapdragon.ext.background
  "Service worker: runs LLM requests for content scripts over a port."
  (:require [clojure.string :as str]
            [snapdragon.config :as config]
            [snapdragon.core :as core]
            [snapdragon.llm :as llm]))

(defn- post! [^js port m]
  ;; The port may already be gone if the sidenote was closed.
  (try (.postMessage port (clj->js m)) (catch :default _ nil)))

(defn- ask! [^js port ^js ctrl req]
  (-> (config/load-settings)
      (.then
       (fn [settings]
         (let [{:keys [api-key model api] :as provider} (config/active-provider settings)]
           (if (and (= api "anthropic") (str/blank? api-key))
             (post! port {:type "error" :needs-config true
                          :message "No API key set. Add one in Snapdragon settings."})
             (let [prompt (core/build-prompt (assoc req :extra-system (:system-prompt settings)))]
               (post! port {:type "meta" :provider (:name provider) :model model})
               (llm/stream! provider prompt
                            {:on-text #(post! port {:type "delta" :text %})
                             :signal  (.-signal ctrl)}))))))
      (.then (fn [result]
               (when result
                 (post! port {:type "done" :stop-reason (:stop-reason result)}))))
      (.catch (fn [^js e]
                (when-not (= "AbortError" (.-name e))
                  (post! port {:type "error" :message (.-message e)}))))))

(defn- on-connect [^js port]
  (when (= "snapdragon/ask" (.-name port))
    (let [ctrl (js/AbortController.)]
      (.addListener (.-onDisconnect port) #(.abort ctrl))
      (.addListener (.-onMessage port)
                    (fn [msg] (ask! port ctrl (js->clj msg :keywordize-keys true)))))))

(defn- on-message [^js msg _sender _respond]
  (when (= "open-options" (.-type msg))
    (.openOptionsPage js/chrome.runtime))
  false)

(defn- on-command [cmd]
  (when (= cmd "ask-selection")
    (-> (.query js/chrome.tabs #js {:active true :currentWindow true})
        (.then (fn [^js tabs]
                 (when-let [tab (aget tabs 0)]
                   (-> (.sendMessage js/chrome.tabs (.-id tab) #js {:type "open-popover"})
                       (.catch (fn [_] nil)))))))))

(defn- on-installed [^js details]
  (when (= "install" (.-reason details))
    (.openOptionsPage js/chrome.runtime)))

(defn init []
  (.addListener js/chrome.runtime.onConnect on-connect)
  (.addListener js/chrome.runtime.onMessage on-message)
  (.addListener js/chrome.runtime.onInstalled on-installed)
  (.addListener js/chrome.commands.onCommand on-command))
