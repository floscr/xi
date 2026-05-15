(ns xi.util-test
  (:require [cljs.test :refer [deftest is testing]]
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
