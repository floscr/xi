(ns xi.commands-test
  (:require [clojure.string :as str]
            [cljs.test :refer [deftest is testing]]
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

(deftest parse-input-absolute-path
  (testing "absolute path is treated as prompt, not command"
    (is (= {:type :prompt :text "/home/floscr/Code/Projects/xi"}
           (commands/parse-input "/home/floscr/Code/Projects/xi"))))

  (testing "absolute path with surrounding text"
    (is (= {:type :prompt :text "/usr/local/bin/thing"}
           (commands/parse-input "/usr/local/bin/thing"))))

  (testing "single slash command is still a command"
    (is (= {:type :command :name "help" :args nil}
           (commands/parse-input "/help")))))

;; ── format-scrollback ─────────────────────────────────────────────────────────

(deftest format-scrollback-empty
  (testing "empty events produce empty string"
    (is (= "" (commands/format-scrollback [])))))

(deftest format-scrollback-user-message
  (testing "user message is formatted"
    (let [result (commands/format-scrollback [{:type :user-message :text "hello"}])]
      (is (str/includes? result "### User"))
      (is (str/includes? result "hello")))))

(deftest format-scrollback-text-deltas
  (testing "text deltas are accumulated into assistant block"
    (let [result (commands/format-scrollback
                  [{:type :text-delta :text "hel"}
                   {:type :text-delta :text "lo"}])]
      (is (str/includes? result "### Assistant"))
      (is (str/includes? result "hello")))))

(deftest format-scrollback-text-flushed-on-user-message
  (testing "accumulated text is flushed when a user message arrives"
    (let [result (commands/format-scrollback
                  [{:type :text-delta :text "response"}
                   {:type :user-message :text "follow-up"}])]
      (is (str/includes? result "### Assistant\nresponse"))
      (is (str/includes? result "### User\nfollow-up")))))

(deftest format-scrollback-tool-args
  (testing "tool args are formatted with JSON"
    (let [result (commands/format-scrollback
                  [{:type :tool-args :name "read" :arguments {:path "foo.txt"}}])]
      (is (str/includes? result "### Tool: read"))
      (is (str/includes? result "foo.txt")))))

(deftest format-scrollback-tool-result
  (testing "tool result is formatted"
    (let [result (commands/format-scrollback
                  [{:type :tool-result :content "file contents" :is-error false}])]
      (is (str/includes? result "### Tool Result"))
      (is (str/includes? result "file contents")))))

(deftest format-scrollback-tool-result-error
  (testing "tool error is labeled"
    (let [result (commands/format-scrollback
                  [{:type :tool-result :content "not found" :is-error true}])]
      (is (str/includes? result "(ERROR)")))))

(deftest format-scrollback-turn-end
  (testing "turn end includes cost when present"
    (let [result (commands/format-scrollback
                  [{:type :turn-end :cost 0.05 :usage {:input 100 :output 50}}])]
      (is (str/includes? result "Turn end"))
      (is (str/includes? result "$0.05")))))

(deftest format-scrollback-error-event
  (testing "error event is formatted"
    (let [result (commands/format-scrollback
                  [{:type :error :error {:message "boom"}}])]
      (is (str/includes? result "### Error")))))

(deftest format-scrollback-aborted
  (testing "aborted event is formatted"
    (let [result (commands/format-scrollback
                  [{:type :aborted}])]
      (is (str/includes? result "Aborted")))))

(deftest format-scrollback-unknown-events-skipped
  (testing "unknown event types are silently skipped"
    (let [result (commands/format-scrollback
                  [{:type :user-message :text "hi"}
                   {:type :some-unknown-thing :data 42}
                   {:type :text-delta :text "response"}])]
      (is (str/includes? result "### User"))
      (is (str/includes? result "### Assistant")))))
