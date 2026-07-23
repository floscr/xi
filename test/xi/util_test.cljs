(ns xi.util-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.util :as util]))

;; ── truncate ──

(deftest truncate-short-string
  (testing "string shorter than max is unchanged"
    (is (= "hello" (util/truncate "hello" 10)))))

(deftest truncate-exact-length
  (testing "string at exact max is unchanged"
    (is (= "hello" (util/truncate "hello" 5)))))

(deftest truncate-long-string
  (testing "string longer than max is truncated with ellipsis"
    (is (= "hel..." (util/truncate "hello world" 3)))))

(deftest truncate-nil
  (testing "nil is passed through"
    (is (nil? (util/truncate nil 5)))))

(deftest truncate-empty
  (testing "empty string is unchanged"
    (is (= "" (util/truncate "" 5)))))

;; ── truncate-text-lines ──

(deftest truncate-text-lines-under-cap
  (testing "text at or under the line cap is unchanged"
    (is (= "a\nb\nc" (util/truncate-text-lines "a\nb\nc" 3)))
    (is (= "a\nb" (util/truncate-text-lines "a\nb" 5)))))

(deftest truncate-text-lines-over-cap
  (testing "overflow keeps n-1 lines plus a marker line, never exceeding n"
    (let [out (util/truncate-text-lines "a\nb\nc\nd\ne" 3)]
      (is (= "a\nb\n… (3 more lines)" out))
      (is (= 3 (count (str/split-lines out)))))))

(deftest truncate-text-lines-idempotent
  (testing "re-truncating with the same cap is a no-op (client re-truncation safe)"
    (let [once  (util/truncate-text-lines "a\nb\nc\nd\ne\nf" 4)
          twice (util/truncate-text-lines once 4)]
      (is (= once twice)))))

(deftest truncate-text-lines-non-string
  (testing "non-strings pass through untouched"
    (is (nil? (util/truncate-text-lines nil 3)))
    (is (= 42 (util/truncate-text-lines 42 3)))))

;; ── claude-model? ──

(deftest claude-model-claude-prefix
  (testing "claude- prefix is recognized"
    (is (util/claude-model? "claude-sonnet-4-20250514"))
    (is (util/claude-model? "claude-opus-4-6"))
    (is (util/claude-model? "claude-3-haiku-20240307"))))

(deftest claude-model-anthropic-prefix
  (testing "anthropic/ prefix is recognized"
    (is (util/claude-model? "anthropic/claude-sonnet-4-20250514"))))

(deftest claude-model-bare-aliases
  (testing "bare aliases are recognized"
    (is (util/claude-model? "sonnet"))
    (is (util/claude-model? "opus"))
    (is (util/claude-model? "haiku"))))

(deftest claude-model-non-claude
  (testing "non-claude models return falsy"
    (is (not (util/claude-model? "gpt-4")))
    (is (not (util/claude-model? "llama3")))
    (is (not (util/claude-model? "deepseek-r1")))))

(deftest claude-model-nil
  (testing "nil returns falsy"
    (is (not (util/claude-model? nil)))))

;; ── extract-text-content ──

(deftest extract-text-string
  (testing "plain string is returned as-is"
    (is (= "hello" (util/extract-text-content "hello")))))

(deftest extract-text-blocks
  (testing "text blocks are extracted and joined"
    (is (= "line1\nline2"
           (util/extract-text-content
            [{:type "text" :text "line1"}
             {:type "image" :url "x.png"}
             {:type "text" :text "line2"}])))))

(deftest extract-text-empty-seq
  (testing "empty sequence returns empty string"
    (is (= "" (util/extract-text-content [])))))

(deftest extract-text-other
  (testing "other types are stringified"
    (is (= "42" (util/extract-text-content 42)))))

;; ── strip-mcp-prefix ──

(deftest strip-mcp-prefix-with-prefix
  (testing "mcp__xi-tools__ prefix is stripped"
    (is (= "bash" (util/strip-mcp-prefix "mcp__xi-tools__bash")))
    (is (= "read" (util/strip-mcp-prefix "mcp__xi-tools__read")))))

(deftest strip-mcp-prefix-without-prefix
  (testing "names without prefix are unchanged"
    (is (= "Bash" (util/strip-mcp-prefix "Bash")))
    (is (= "read" (util/strip-mcp-prefix "read")))))

(deftest strip-mcp-prefix-nil
  (testing "nil is passed through"
    (is (nil? (util/strip-mcp-prefix nil)))))

(deftest strip-mcp-prefix-other-server
  (testing "other MCP server prefixes are also stripped"
    (is (= "list_files" (util/strip-mcp-prefix "mcp__other-server__list_files")))))

;; ── session-title ──

(deftest session-title-plain
  (testing "plain first message is used verbatim"
    (is (= "Fix the login bug" (util/session-title "Fix the login bug"))))
  (testing "leading/trailing whitespace is trimmed"
    (is (= "Fix the login bug" (util/session-title "  Fix the login bug  "))))
  (testing "long text is truncated to 60 chars"
    (is (= 60 (count (util/session-title (apply str (repeat 100 "x")))))))
  (testing "blank/nil yield nil"
    (is (nil? (util/session-title nil)))
    (is (nil? (util/session-title "   ")))))

(deftest session-title-native-compaction
  (testing "native Claude compaction preamble yields nil"
    (is (nil? (util/session-title
               "The conversation history has been summarized below...")))))

(deftest session-title-xi-compaction
  (testing "unwraps <conversation-summary> + Session Summary heading"
    (is (= "Figma Styles Exp"
           (util/session-title
            (str "<conversation-summary>\n"
                 "## Session Summary —Figma Styles Exp\n\n"
                 "1. Read src/foo.cljs\n"
                 "</conversation-summary>\n\n"
                 "Acknowledge this summary briefly.")))))
  (testing "handles a space after the em dash separator"
    (is (= "Admin V2 Dashboard"
           (util/session-title
            (str "<conversation-summary>\n"
                 "# Session Summary — Admin V2 Dashboard\n"
                 "</conversation-summary>")))))
  (testing "falls back to first meaningful line when no Session Summary label"
    (is (= "Refactor the router"
           (util/session-title
            (str "<conversation-summary>\n"
                 "Refactor the router\n"
                 "more detail\n"
                 "</conversation-summary>")))))
  (testing "already-cleaned title passes through unchanged (idempotent)"
    (is (= "Figma Styles Exp" (util/session-title "Figma Styles Exp")))))
