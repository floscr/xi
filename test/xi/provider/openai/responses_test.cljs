(ns xi.provider.openai.responses-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.provider.openai.responses :as responses]))

;; ── history->responses-input ──
;;
;; Neutral history (from xi.agent/history->messages) → OpenAI Responses `input`
;; items. One neutral message can expand into several items.

(deftest user-message->input-text
  (testing "a user turn becomes an input_text content block"
    (is (= [{:role "user" :content [{:type "input_text" :text "hello"}]}]
           (responses/history->responses-input
            [{:role :user :text "hello"}])))))

(deftest assistant-text->message-item
  (testing "an assistant text turn becomes a completed message item"
    (is (= [{:type "message" :role "assistant"
             :content [{:type "output_text" :text "hi there" :annotations []}]
             :status "completed"}]
           (responses/history->responses-input
            [{:role :assistant :text "hi there" :tool-calls []}])))))

(deftest assistant-toolcall->function-call-item
  (testing "assistant text + tool call expand to message + function_call items"
    (let [out (responses/history->responses-input
               [{:role :assistant
                 :text "let me check"
                 :tool-calls [{:id "call_1" :name "bash"
                               :arguments {:command "ls"}}]}])]
      (is (= 2 (count out)))
      (is (= "message" (:type (first out))))
      (let [fc (second out)]
        (is (= "function_call" (:type fc)))
        (is (= "call_1" (:call_id fc)))
        (is (= "bash" (:name fc)))
        ;; arguments are serialized to a JSON string
        (is (= {"command" "ls"} (js->clj (js/JSON.parse (:arguments fc)))))
        ;; no item id, so replayed calls don't trip pairing validation
        (is (nil? (:id fc)))))))

(deftest tool-result->function-call-output
  (testing "a tool turn becomes function_call_output items; errors are marked"
    (is (= [{:type "function_call_output" :call_id "call_1" :output "done"}
            {:type "function_call_output" :call_id "call_2" :output "[error] boom"}]
           (responses/history->responses-input
            [{:role :tool
              :results [{:id "call_1" :content "done" :is-error false}
                        {:id "call_2" :content "boom" :is-error true}]}])))))
