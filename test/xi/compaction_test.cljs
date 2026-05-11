(ns xi.compaction-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.compaction :as compaction]))

(deftest needs-compaction-below-threshold
  (testing "short conversation does not need compaction"
    (is (not (compaction/needs-compaction?
              [{:role "user" :content "Hello"}
               {:role "assistant" :content "Hi there!"}])))))

(deftest needs-compaction-above-threshold
  (testing "huge conversation triggers compaction"
    (let [;; 200k tokens * 4 chars/token = 800k chars; threshold is 75% = 600k chars
          big-msg (apply str (repeat 700000 "x"))
          messages [{:role "user" :content big-msg}]]
      (is (compaction/needs-compaction? messages)))))

(deftest needs-compaction-sequential-content
  (testing "sequential content blocks are counted"
    (let [big-text (apply str (repeat 700000 "x"))
          messages [{:role "assistant"
                     :content [{:type "text" :text big-text}]}]]
      (is (compaction/needs-compaction? messages)))))

(deftest needs-compaction-thinking-content
  (testing "thinking blocks are counted"
    (let [big-text (apply str (repeat 700000 "x"))
          messages [{:role "assistant"
                     :content [{:type "thinking" :thinking big-text}]}]]
      (is (compaction/needs-compaction? messages)))))
