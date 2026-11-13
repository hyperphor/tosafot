(ns snapdragon.llm
  "Provider-agnostic streaming chat over fetch + SSE."
  (:require [clojure.string :as str]
            [goog.object :as gobj]))

;; String-keyed access to parsed JSON; immune to advanced-mode renaming.
(defn- jget [o & ks] (when o (apply gobj/getValueByKeys o ks)))

;; Models that accept server-side refusal fallbacks (`fallbacks: "default"`).
(def ^:private fallback-models
  #{"claude-fable-5-2" "claude-fable-5-1" "claude-opus-5-5" "claude-opus-5" "claude-sonnet-5-5"})

(defmulti request-spec
  "Return {:url :headers :body} for `provider` and prompt {:system :messages}."
  (fn [provider _] (:api provider)))

(defmethod request-spec "anthropic"
  [{:keys [base-url api-key model effort]} {:keys [system messages]}]
  (let [fallback? (fallback-models model)]
    {:url     (str (str/replace base-url #"/+$" "") "/v1/messages")
     :headers (cond-> {"content-type"      "application/json"
                       "x-api-key"         api-key
                       "anthropic-version" "2023-06-01"
                       "anthropic-dangerous-direct-browser-access" "true"}
                fallback? (assoc "anthropic-beta" "server-side-fallback-2026-07-01"))
     :body    (cond-> {:model model :max_tokens 64000 :stream true
                       :system system :messages messages}
                (not (str/blank? effort)) (assoc :output_config {:effort effort})
                fallback?                 (assoc :fallbacks "default"))}))

(defmethod request-spec "openai"
  [{:keys [base-url api-key model]} {:keys [system messages]}]
  {:url     (str (str/replace base-url #"/+$" "") "/chat/completions")
   :headers (cond-> {"content-type" "application/json"}
              (not (str/blank? api-key)) (assoc "authorization" (str "Bearer " api-key)))
   :body    {:model model :stream true
             :messages (into [{:role "system" :content system}] messages)}})

(defmulti handle-event
  "Process one parsed SSE data payload; call (on-text s) for text, return stop reason or nil."
  (fn [api _ _] api))

(defmethod handle-event "anthropic" [_ ev on-text]
  (case (jget ev "type")
    "content_block_delta" (do (when (= "text_delta" (jget ev "delta" "type"))
                                (on-text (jget ev "delta" "text")))
                              nil)
    "message_delta"       (jget ev "delta" "stop_reason")
    "error"               (throw (js/Error. (or (jget ev "error" "message") "Stream error")))
    nil))

(defmethod handle-event "openai" [_ ev on-text]
  (when-let [err (jget ev "error")]
    (throw (js/Error. (or (jget err "message") (str err)))))
  (when-let [t (jget ev "choices" 0 "delta" "content")] (on-text t))
  (jget ev "choices" 0 "finish_reason"))

(defn split-events
  "Split SSE `buf` into [data-payloads remainder]; with `final?` the remainder is consumed too."
  [buf final?]
  (let [chunks (str/split (str/replace buf "\r\n" "\n") #"\n\n" -1)
        [complete rest] (if final? [chunks ""] [(butlast chunks) (last chunks)])
        data (keep (fn [chunk]
                     (let [lines (->> (str/split-lines chunk)
                                      (filter #(str/starts-with? % "data:"))
                                      (map #(str/replace-first % #"^data: ?" "")))]
                       (when (seq lines) (str/join "\n" lines))))
                   complete)]
    [data (or rest "")]))

(defn- error-message [^js res body]
  (let [parsed (try (js/JSON.parse body) (catch :default _ nil))]
    (str "HTTP " (.-status res) ": "
         (or (jget parsed "error" "message") (jget parsed "error") body))))

(defn stream!
  "Stream a completion. Calls (on-text delta) as text arrives.
  Returns a promise of {:stop-reason s}. `signal` is an AbortSignal."
  [provider prompt {:keys [on-text signal]}]
  (let [{:keys [url headers body]} (request-spec provider prompt)
        api (:api provider)
        stop (volatile! nil)
        process (fn [payloads]
                  (doseq [p payloads :when (not= p "[DONE]")]
                    (when-let [r (handle-event api (js/JSON.parse p) on-text)]
                      (vreset! stop r))))]
    (-> (js/fetch url #js {:method "POST"
                           :headers (clj->js headers)
                           :body (js/JSON.stringify (clj->js body))
                           :signal signal})
        (.then (fn [^js res]
                 (if-not (.-ok res)
                   (.then (.text res) #(throw (js/Error. (error-message res %))))
                   (let [reader (.getReader (.-body res))
                         decoder (js/TextDecoder.)
                         buf (volatile! "")]
                     (letfn [(step []
                               (.then (.read reader)
                                      (fn [^js r]
                                        (let [done? (.-done r)]
                                          (when-not done?
                                            (vswap! buf str (.decode decoder (.-value r) #js {:stream true})))
                                          (let [[payloads remainder] (split-events @buf done?)]
                                            (vreset! buf remainder)
                                            (process payloads))
                                          (if done? {:stop-reason @stop} (step))))))]
                       (step)))))))))
