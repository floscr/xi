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

;; ── zen-model? ──

(deftest zen-model-opencode-prefix
  (testing "opencode/ prefix routes to Zen"
    (is (util/zen-model? "opencode/big-pickle"))
    (is (util/zen-model? "opencode/claude-opus-4-8"))
    (is (util/zen-model? "opencode/gpt-5.5"))))

(deftest zen-model-non-zen
  (testing "bare ids and other providers are not Zen"
    (is (not (util/zen-model? "big-pickle")))
    (is (not (util/zen-model? "claude-opus-4-8")))
    (is (not (util/zen-model? "llama3")))
    (is (not (util/zen-model? nil)))))

;; ── openai-model? ──

(deftest openai-model-prefix
  (testing "openai/ prefix routes to the Codex provider"
    (is (util/openai-model? "openai/gpt-5.1-codex"))
    (is (util/openai-model? "openai/gpt-5-codex")))
  (testing "bare ids and other providers are not OpenAI Codex"
    (is (not (util/openai-model? "gpt-5.1-codex")))
    (is (not (util/openai-model? "opencode/gpt-6-astra")))
    (is (not (util/openai-model? nil)))))

;; ── provider-for-model ──

(deftest provider-for-model-routing
  (testing "opencode/ wins over an overlapping claude bare id"
    (is (= :zen (util/provider-for-model "opencode/claude-opus-4-8")))
    (is (= :zen (util/provider-for-model "opencode/big-pickle"))))
  (testing "openai/ prefix routes to the Codex provider"
    (is (= :openai (util/provider-for-model "openai/gpt-5.1-codex")))
    (is (= :openai (util/provider-for-model "openai/gpt-5-codex"))))
  (testing "claude models route to :claude"
    (is (= :claude (util/provider-for-model "claude-opus-4-6")))
    (is (= :claude (util/provider-for-model "sonnet"))))
  (testing "everything else routes to :ollama"
    (is (= :ollama (util/provider-for-model "llama3")))
    (is (= :ollama (util/provider-for-model "deepseek-r1")))
    (is (= :ollama (util/provider-for-model nil)))))

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

;; ── cap-tool-result-content ──

(def ^:private max-chars util/max-tool-result-chars)

(defn- total-text [content]
  (if (string? content)
    (count content)
    (->> content
         (filter #(= "text" (:type %)))
         (map #(count (:text %)))
         (reduce + 0))))

(deftest cap-small-content-passes-through
  (testing "under-budget string is unchanged"
    (is (= "hello world" (util/cap-tool-result-content "hello world"))))
  (testing "under-budget block vec is unchanged"
    (let [content [{:type "text" :text "hello world"}]]
      (is (= content (util/cap-tool-result-content content))))))

(deftest cap-huge-string-is-trimmed
  (testing "a plain string far over budget is trimmed under the cap (Ollama shape)"
    (let [big (apply str (repeat (* max-chars 3) "x"))
          out (util/cap-tool-result-content big)]
      (is (string? out))
      (is (<= (count out) max-chars))
      (is (re-find #"Xi truncated" out)))))

(deftest cap-single-huge-line-block-is-trimmed
  (testing "a one-line block dump over budget keeps head+tail under the cap"
    (let [big (apply str (repeat (* max-chars 3) "x"))
          content [{:type "text" :text big}]
          out (util/cap-tool-result-content content)]
      (is (<= (total-text out) max-chars))
      (is (re-find #"Xi truncated" (:text (first out))))
      (is (< (total-text out) (count big))))))

(deftest cap-image-blocks-pass-through
  (testing "non-text blocks are untouched even when text is trimmed"
    (let [big (apply str (repeat (* max-chars 2) "y"))
          img {:type "image" :source {:type "base64" :data "AAAA"}}
          content [{:type "text" :text big} img]
          out (util/cap-tool-result-content content)]
      (is (some #(= img %) out))
      (is (<= (total-text out) max-chars)))))

;; ── paste fencing ──

(deftest paste-should-fence-short-prose
  (testing "a short single-line prose paste is not fenced"
    (is (not (util/paste-should-fence? "just a quick note here")))))

(deftest paste-should-fence-by-length
  (testing ">= 500 chars is always fenced"
    (is (util/paste-should-fence? (apply str (repeat 500 "a"))))
    (is (not (util/paste-should-fence? (apply str (repeat 499 "a")))))))

(deftest paste-should-fence-by-paragraphs
  (testing "more than 2 paragraphs is fenced"
    (is (util/paste-should-fence? "one\n\ntwo\n\nthree"))
    (is (not (util/paste-should-fence? "one\n\ntwo")))))

(deftest paste-should-fence-code-like
  (testing "code-punctuation-heavy text is fenced even when short"
    (is (util/paste-should-fence? "fn(a){b();c();}"))
    (is (not (util/paste-should-fence? "hello world")))))

(deftest fence-paste-standalone-line
  (testing "cursor alone on its line gets no extra newlines"
    (is (= "```\nx();\n```"
           (util/fence-paste "x();" {:at-line-start? true :at-line-end? true})))))

(deftest fence-paste-mid-sentence
  (testing "cursor mid-line gets leading and trailing newlines"
    (is (= "\n```\nx();\n```\n"
           (util/fence-paste "x();" {:at-line-start? false :at-line-end? false})))))

(deftest fence-paste-trims-body-newlines
  (testing "surrounding newlines in the paste body are trimmed"
    (is (= "```\nx();\n```"
           (util/fence-paste "\n\nx();\n\n" {:at-line-start? true :at-line-end? true})))))
