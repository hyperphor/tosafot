(ns tosafot.llm-test
  (:require [clojure.test :refer [deftest is]]
            [tosafot.llm :as llm]))

(deftest split-events-test
  (is (= [["a" "b"] "data: c"] (llm/split-events "data: a\n\nevent: x\ndata: b\n\ndata: c" false)))
  (is (= [["a" "c"] ""] (llm/split-events "data: a\r\n\r\ndata: c" true))))

(deftest anthropic-spec-test
  (let [{:keys [url headers body]} (llm/request-spec {:api "anthropic" :base-url "https://api.anthropic.com/"
                                                      :api-key "k" :model "claude-opus-5-5" :effort "low"}
                                                     {:system "s" :messages []})]
    (is (= "https://api.anthropic.com/v1/messages" url))
    (is (= "true" (headers "anthropic-dangerous-direct-browser-access")))
    (is (= "default" (:fallbacks body)))
    (is (= {:effort "low"} (:output_config body)))))

(deftest openai-spec-test
  (let [{:keys [url headers body]} (llm/request-spec {:api "openai" :base-url "http://localhost:11434/v1"
                                                      :api-key "" :model "llama"}
                                                     {:system "s" :messages [{:role "user" :content "q"}]})]
    (is (= "http://localhost:11434/v1/chat/completions" url))
    (is (nil? (headers "authorization")))
    (is (= "system" (:role (first (:messages body)))))))

(deftest handle-event-test
  (let [out (atom [])
        on-text #(swap! out conj %)]
    (is (nil? (llm/handle-event "anthropic" (js/JSON.parse "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}") on-text)))
    (is (= "refusal" (llm/handle-event "anthropic" (js/JSON.parse "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\"}}") on-text)))
    (is (= "stop" (llm/handle-event "openai" (js/JSON.parse "{\"choices\":[{\"delta\":{\"content\":\"yo\"},\"finish_reason\":\"stop\"}]}") on-text)))
    (is (= ["hi" "yo"] @out))
    (is (thrown? js/Error (llm/handle-event "anthropic" (js/JSON.parse "{\"type\":\"error\",\"error\":{\"message\":\"bad\"}}") on-text)))))
