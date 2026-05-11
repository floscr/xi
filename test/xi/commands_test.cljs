(ns xi.commands-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.runtime.commands :as commands]))

(deftest parse-input-prompt
  (testing "plain text returns a prompt"
    (is (= {:type :prompt :text "hello world"}
           (commands/parse-input "hello world"))))

  (testing "leading/trailing whitespace is trimmed"
    (is (= {:type :prompt :text "hello"}
           (commands/parse-input "  hello  ")))))

(deftest parse-input-nil-on-empty
  (testing "empty string returns nil"
    (is (nil? (commands/parse-input ""))))

  (testing "whitespace-only returns nil"
    (is (nil? (commands/parse-input "   ")))))

(deftest parse-input-command
  (testing "slash command without args"
    (is (= {:type :command :name "quit" :args nil}
           (commands/parse-input "/quit"))))

  (testing "slash command with args"
    (is (= {:type :command :name "model" :args "claude-opus-4-6"}
           (commands/parse-input "/model claude-opus-4-6"))))

  (testing "slash command with multi-word args"
    (is (= {:type :command :name "resume" :args "my session name"}
           (commands/parse-input "/resume my session name"))))

  (testing "args are trimmed"
    (is (= {:type :command :name "model" :args "sonnet"}
           (commands/parse-input "/model  sonnet ")))))
