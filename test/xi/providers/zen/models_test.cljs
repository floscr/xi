(ns xi.providers.zen.models-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.providers.zen.models :as models]))

;; ── strip-prefix ──

(deftest strip-prefix-removes-opencode
  (testing "opencode/ prefix is stripped to the bare id"
    (is (= "big-pickle" (models/strip-prefix "opencode/big-pickle")))
    (is (= "claude-opus-4-8" (models/strip-prefix "opencode/claude-opus-4-8")))))

(deftest strip-prefix-passthrough
  (testing "bare ids and nil pass through unchanged"
    (is (= "big-pickle" (models/strip-prefix "big-pickle")))
    (is (nil? (models/strip-prefix nil)))))

;; ── wire-format ──

(deftest wire-format-chat
  (testing "free + openai-compatible models are :chat"
    (is (= :chat (models/wire-format "big-pickle")))
    (is (= :chat (models/wire-format "opencode/big-pickle")))
    (is (= :chat (models/wire-format "deepseek-v4-flash")))
    (is (= :chat (models/wire-format "glm-5.2")))
    (is (= :chat (models/wire-format "kimi-k3")))
    (is (= :chat (models/wire-format "minimax-m3")))))

(deftest wire-format-messages
  (testing "claude + qwen models use the Anthropic surface"
    (is (= :messages (models/wire-format "claude-opus-4-8")))
    (is (= :messages (models/wire-format "opencode/claude-haiku-4-5")))
    (is (= :messages (models/wire-format "qwen3.7-max")))))

(deftest wire-format-responses
  (testing "gpt/grok/muse models use the Responses surface"
    (is (= :responses (models/wire-format "gpt-6-astra")))
    (is (= :responses (models/wire-format "opencode/gpt-6-astra")))
    (is (= :responses (models/wire-format "gpt-5.5")))
    (is (= :responses (models/wire-format "opencode/gpt-5-nano")))
    (is (= :responses (models/wire-format "grok-4.6")))
    (is (= :responses (models/wire-format "muse-spark-1.3")))
    (is (= :responses (models/wire-format "muse-spark-1.2")))))

(deftest wire-format-gemini
  (testing "gemini models use the Google surface"
    (is (= :gemini (models/wire-format "gemini-3.1-pro")))
    (is (= :gemini (models/wire-format "opencode/gemini-3-flash")))))

(deftest wire-format-unknown-defaults-chat
  (testing "unknown / newly-added ids fall back to :chat"
    (is (= :chat (models/wire-format "some-new-free-model")))
    (is (= :chat (models/wire-format "opencode/brand-new-2027")))))

;; ── zen-model? ──

(deftest zen-model-recognizes-prefix-and-known-ids
  (testing "opencode/ prefix and known bare ids are Zen"
    (is (models/zen-model? "opencode/big-pickle"))
    (is (models/zen-model? "claude-opus-4-8"))
    (is (models/zen-model? "gpt-6-astra"))
    (is (models/zen-model? "gpt-5.5"))
    (is (models/zen-model? "gemini-3.1-pro")))
  (testing "unknown bare ids and nil are not"
    (is (not (models/zen-model? "llama3")))
    (is (not (models/zen-model? "big-pickle")))
    (is (not (models/zen-model? nil)))))
