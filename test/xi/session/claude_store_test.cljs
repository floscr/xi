(ns xi.session.claude-store-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.session.claude-store :as store]
            [xi.util :as util]))

(defn- prompt [text & {:as extra}]
  (merge {:type "user" :uuid (str "u-" text)
          :message {:role "user" :content [{:type "text" :text text}]}}
         extra))

(defn- reply [text]
  {:type "assistant" :uuid (str "a-" text)
   :message {:role "assistant" :content [{:type "text" :text text}]
             :stop_reason "end_turn"}})

(def ^:private tool-result
  {:type "user" :message {:role "user"
                          :content [{:type "tool_result" :tool_use_id "t1" :content "ok"}]}})

(def ^:private queue-op {:type "queue-operation"})

(deftest prompt-text-recognises-typed-prompts-only
  (is (= "hello" (store/prompt-text (prompt "hello"))))
  (is (= "hello" (store/prompt-text {:type "user" :message {:content "hello"}}))
      "string content (older transcripts, the fake provider)")
  (testing "the provider-only parts of a prompt are not what the user typed"
    (is (= "look" (store/prompt-text
                   (prompt (str (util/with-collapse-marker "look" "Skill")
                                "\n[Attached image: /tmp/a.png]"))))))
  (testing "not prompts"
    (is (nil? (store/prompt-text tool-result)))
    (is (nil? (store/prompt-text (prompt "[Image: source: /tmp/a.png]" :isMeta true))))
    (is (nil? (store/prompt-text (prompt "[Request interrupted by user]"))))
    (is (nil? (store/prompt-text (prompt "[Request interrupted by user for tool use]"))))
    (is (nil? (store/prompt-text (prompt "side" :isSidechain true))))
    (is (nil? (store/prompt-text (reply "hi"))))))

(def ^:private transcript
  [(prompt "one") (reply "r1")
   (prompt "two") {:type "assistant" :uuid "a-tool"
                   :message {:content [{:type "tool_use" :id "t1" :name "read"}]}}
   tool-result
   (prompt "[Image: source: /tmp/a.png]" :isMeta true)
   (reply "r2")
   queue-op queue-op
   (prompt "three") (reply "r3")])

(deftest rewind-cut-keeps-the-kept-prompts-and-their-turns
  (testing "cut before the first dropped prompt, its queue bookkeeping dropped with it"
    (is (= 7 (store/rewind-cut transcript ["one" "two"]))))
  (is (= 2 (store/rewind-cut transcript ["one"])))
  (is (= 0 (store/rewind-cut transcript [])))
  (is (= (count transcript) (store/rewind-cut transcript ["one" "two" "three"]))
      "keeping everything cuts nothing")
  (testing "prompts typed with trailing whitespace still match"
    (is (= 2 (store/rewind-cut transcript ["one  "])))))

(deftest rewind-cut-matches-prompts-in-order
  (testing "a transcript prompt the history lacks (auto-resume continue) is skipped"
    (let [t [(prompt "one") (reply "r1") (prompt "continue") (reply "r1b")
             (prompt "two") (reply "r2") (prompt "drop") (reply "r3")]]
      (is (= 6 (store/rewind-cut t ["one" "two"])))))
  (testing "repeated texts match the earliest occurrence"
    (let [t [(prompt "again") (reply "r1") (prompt "again") (reply "r2")]]
      (is (= 2 (store/rewind-cut t ["again"])))))
  (testing "a kept prompt that never reached the transcript can't be cut safely"
    (is (nil? (store/rewind-cut transcript ["one" "never sent"])))
    (is (nil? (store/rewind-cut transcript ["three" "one"])))))
