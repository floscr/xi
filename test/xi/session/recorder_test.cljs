(ns xi.session.recorder-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.session.claude-store :as claude-store]
            [xi.session.recorder :as recorder]))

(defn- run [events completed?]
  (recorder/finish (reduce recorder/step recorder/empty-turn events) completed?))

(deftest text-only-turn
  (testing "streamed deltas are one text block, closed at end_turn"
    (is (= [{:role "assistant" :content [{:type "text" :text "Hello there"}]
             :stop_reason "end_turn"}]
           (run [[:text "Hel"] [:text "lo "] [:text "there"]] true)))))

(deftest tool-loop-turn
  (testing "text + tool_use, then the results as a user message, then the answer"
    (is (= [{:role "assistant"
             :content [{:type "text" :text "Looking"}
                       {:type "tool_use" :id "c1" :name "ls" :input {:path "."}}
                       {:type "tool_use" :id "c2" :name "read" :input {:path "a"}}]}
            {:role "user"
             :content [{:type "tool_result" :tool_use_id "c1" :content "a b" :is_error false}
                       {:type "tool_result" :tool_use_id "c2" :content "x" :is_error true}]}
            {:role "assistant" :content [{:type "text" :text "Done"}]
             :stop_reason "end_turn"}]
           (run [[:text "Looking"]
                 [:tool-start {:id "c1" :name "ls" :arguments {}}]
                 [:tool-args {:id "c1" :arguments {:path "."}}]
                 [:tool-start {:id "c2" :name "read" :arguments {:path "a"}}]
                 [:tool-result {:id "c1" :content "a b" :is-error false}]
                 [:tool-result {:id "c2" :content "x" :is-error true}]
                 [:text "Done"]]
                true)))))

(deftest cut-off-turn-has-no-end-marker
  (testing "an aborted turn ends without end_turn, after the pending results"
    (let [msgs (run [[:tool-start {:id "c1" :name "ls" :arguments {}}]
                     [:tool-result {:id "c1" :content "a" :is-error false}]]
                    false)]
      (is (= "user" (:role (last msgs))))
      (is (not-any? :stop_reason msgs)))))

(deftest error-is-an-api-error-message
  (is (= [{:role "assistant" :content [{:type "text" :text "partial"}]}
          {:role "assistant" :api-error? true
           :content [{:type "text" :text "429 Too Many Requests"}]}]
         (run [[:text "partial"] [:error "429 Too Many Requests"]] false))))

(deftest with-transcripts-wraps-only-sessionless-providers
  (let [start (fn [_] {:promise (js/Promise.resolve {})})
        own   {:id :anthropic :start-turn! start :store {:rewind! identity}}
        bare  {:id :ollama :start-turn! start}]
    (testing "a provider with its own store is left alone"
      (is (identical? own (recorder/with-transcripts own))))
    (testing "a sessionless one is recorded and gets the Claude store"
      (let [w (recorder/with-transcripts bare)]
        (is (= claude-store/store (:store w)))
        (is (not (identical? start (:start-turn! w))))))))

(deftest completed-result
  (is (recorder/completed? {:stop-reason "stop"}))
  (is (recorder/completed? {:stop-reason "max_iterations"}))
  (is (not (recorder/completed? {:stop-reason "error"})))
  (is (not (recorder/completed? {:stop-reason "aborted" :aborted true})))
  (is (not (recorder/completed? {:aborted true}))))

(deftest prompt-message-with-images
  (testing "plain prompts stay a string"
    (is (= {:role "user" :content "hi"} (recorder/prompt-message "hi" nil))))
  (testing "images become base64 blocks after the text"
    (is (= {:role "user"
            :content [{:type "text" :text "look"}
                      {:type "image" :source {:type "base64" :media_type "image/png" :data "AAA"}}]}
           (recorder/prompt-message "look" [{:media-type "image/png" :data "AAA"}
                                            {:media-type "application/zip" :data "ZZZ"}])))))

(deftest entries-read-back-as-a-transcript
  (testing "the recorded lines are prompts claude-store can cut at"
    (let [entries (mapv #(recorder/entry "sid" "/w" "t" %)
                        (into [(recorder/prompt-message "first" nil)]
                              (run [[:text "one"]] true)))]
      (is (= "first" (claude-store/prompt-text (first entries))))
      (is (= "end_turn" (get-in (last entries) [:message :stop_reason])))
      (is (= 0 (claude-store/rewind-cut entries [])))
      (is (= 2 (claude-store/rewind-cut entries ["first"]))))))
