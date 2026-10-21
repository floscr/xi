(ns xi.providers.zen.models-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.providers.zen.models :as models]))

;; ── gateway ──

(deftest gateway-by-prefix
  (testing "opencode-go/ is the Go gateway, everything else Zen"
    (is (= :go (models/gateway "opencode-go/deepseek-v4.1-flash")))
    (is (= :zen (models/gateway "opencode/deepseek-v4.1-flash")))
    (is (= :zen (models/gateway "big-pickle")))
    (is (= :zen (models/gateway nil)))))

(deftest prefix-per-gateway
  (is (= "opencode/" (models/prefix :zen)))
  (is (= "opencode-go/" (models/prefix :go))))

;; ── strip-prefix ──

(deftest strip-prefix-removes-opencode
  (testing "opencode/ and opencode-go/ prefixes are stripped to the bare id"
    (is (= "big-pickle" (models/strip-prefix "opencode/big-pickle")))
    (is (= "claude-opus-4-8" (models/strip-prefix "opencode/claude-opus-4-8")))
    (is (= "deepseek-v4.1-flash" (models/strip-prefix "opencode-go/deepseek-v4.1-flash")))))

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
    (is (= :chat (models/wire-format "opencode-go/deepseek-v4.1-flash")))
    (is (= :chat (models/wire-format "glm-5.2")))
    (is (= :chat (models/wire-format "kimi-k3")))
    (is (= :chat (models/wire-format "minimax-m3")))))

(deftest wire-format-messages
  (testing "claude + qwen models use the Anthropic surface"
    (is (= :messages (models/wire-format "claude-opus-4-8")))
    (is (= :messages (models/wire-format "opencode/claude-haiku-4-5")))
    (is (= :messages (models/wire-format "opencode-go/claude-haiku-5-5")))
    (is (= :messages (models/wire-format "qwen3.7-max")))))

(deftest wire-format-go-overrides
  (testing "Go serves MiniMax M3 and Qwen3.8 Max on the Anthropic surface, Zen on chat"
    (is (= :messages (models/wire-format "opencode-go/minimax-m3")))
    (is (= :chat (models/wire-format "opencode/minimax-m3")))
    (is (= :messages (models/wire-format "opencode-go/qwen3.8-max")))
    (is (= :chat (models/wire-format "opencode/qwen3.8-max")))))

(deftest wire-format-responses
  (testing "gpt/grok/muse models use the Responses surface"
    (is (= :responses (models/wire-format "gpt-6-astra")))
    (is (= :responses (models/wire-format "opencode/gpt-6-astra")))
    (is (= :responses (models/wire-format "gpt-5.5")))
    (is (= :responses (models/wire-format "opencode/gpt-5-nano")))
    (is (= :responses (models/wire-format "opencode-go/gpt-6-luna")))
    (is (= :responses (models/wire-format "grok-4.6")))
    (is (= :responses (models/wire-format "muse-spark-1.3")))
    (is (= :responses (models/wire-format "opencode-go/muse-spark-1.3-contributor")))
    (is (= :responses (models/wire-format "muse-spark-1.2")))))

(deftest wire-format-gemini
  (testing "gemini models use the Google surface"
    (is (= :gemini (models/wire-format "gemini-3.1-pro")))
    (is (= :gemini (models/wire-format "opencode/gemini-3-flash")))
    (is (= :gemini (models/wire-format "opencode-go/gemini-4-argon-contributor")))))

(deftest wire-format-unknown-defaults-chat
  (testing "unknown / newly-added ids fall back to :chat"
    (is (= :chat (models/wire-format "some-new-free-model")))
    (is (= :chat (models/wire-format "opencode/brand-new-2027")))
    (is (= :chat (models/wire-format "opencode-go/brand-new-2027")))))

;; ── base-url ──

(deftest base-url-per-gateway-and-surface
  (testing "Zen has one base for every surface"
    (is (= "https://opencode.ai/zen/v1" (models/base-url :zen :chat)))
    (is (= "https://opencode.ai/zen/v1" (models/base-url :zen :messages)))
    (is (= "https://opencode.ai/zen/v1" (models/base-url :zen :responses))))
  (testing "Go splits by vendor API"
    (is (= "https://opencode.ai/inference/go/openai/v1" (models/base-url :go :chat)))
    (is (= "https://opencode.ai/inference/go/openai/v1" (models/base-url :go :responses)))
    (is (= "https://opencode.ai/inference/go/anthropic/v1" (models/base-url :go :messages)))))

(deftest models-url-per-gateway
  (is (= "https://opencode.ai/zen/v1/models" (models/models-url :zen)))
  (is (= "https://opencode.ai/inference/go/v1/models" (models/models-url :go))))

;; ── zen-model? ──

(deftest zen-model-recognizes-prefix-and-known-ids
  (testing "opencode/ and opencode-go/ prefixes and known bare ids are OpenCode"
    (is (models/zen-model? "opencode/big-pickle"))
    (is (models/zen-model? "opencode-go/deepseek-v4.1-flash"))
    (is (models/zen-model? "claude-opus-4-8"))
    (is (models/zen-model? "gpt-6-astra"))
    (is (models/zen-model? "gpt-5.5"))
    (is (models/zen-model? "gemini-3.1-pro")))
  (testing "unknown bare ids and nil are not"
    (is (not (models/zen-model? "llama3")))
    (is (not (models/zen-model? "big-pickle")))
    (is (not (models/zen-model? nil)))))
