(ns xi.provider.zen.models
  "OpenCode Zen model metadata: id normalization + wire-format routing.

   Zen's `/v1/models` endpoint returns bare model ids with no hint about which
   API surface serves them, so the mapping is transcribed from the official
   docs endpoint table (https://opencode.ai/docs/zen). Each model is served by
   one of four wire formats:

     :chat      OpenAI Chat Completions  → POST /zen/v1/chat/completions
     :messages  Anthropic Messages       → POST /zen/v1/messages   (x-api-key)
     :responses OpenAI Responses         → POST /zen/v1/responses
     :gemini    Google generateContent   → POST /zen/v1/models/<id>

   Unknown / newly-added ids fall back to :chat (the OpenAI-compatible surface),
   which is the safest default for the free/community models Zen keeps adding."
  (:require [clojure.string :as str]))

(def base-url "https://opencode.ai/zen/v1")

;; ── Wire-format table (from docs endpoint table) ─────────────────────────────

(def ^:private responses-models
  #{"gpt-6-astra"
    "gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6-luna" "gpt-5.5" "gpt-5.5-pro"
    "gpt-5.4" "gpt-5.4-pro" "gpt-5.4-mini" "gpt-5.4-nano"
    "gpt-5.3-codex" "gpt-5.3-codex-spark" "gpt-5.2" "gpt-5.2-codex"
    "gpt-5.1" "gpt-5.1-codex" "gpt-5.1-codex-max" "gpt-5.1-codex-mini"
    "gpt-5" "gpt-5-codex" "gpt-5-nano"
    "grok-4.6" "grok-4.5" "grok-build-0.1"
    "muse-spark-1.3" "muse-spark-1.3-contributor-free"
    "muse-spark-1.2" "muse-spark-1.2-contributor-free"})

(def ^:private messages-models
  #{"claude-fable-5-1" "claude-fable-5" "claude-opus-5" "claude-opus-4-8" "claude-opus-4-7"
    "claude-opus-4-6" "claude-opus-4-5" "claude-sonnet-5" "claude-sonnet-4-6"
    "claude-sonnet-4-5" "claude-haiku-4-5"
    "qwen3.7-max" "qwen3.7-plus" "qwen3.6-plus" "qwen3.5-plus"})

(def ^:private gemini-models
  #{"gemini-3.7-flash" "gemini-3.6-flash" "gemini-3.5-flash"
    "gemini-3.5-flash-lite" "gemini-3.1-pro" "gemini-3-flash"})

;; Everything else (DeepSeek, GLM, Kimi, MiniMax, big-pickle, *-free, …) is :chat.

(defn strip-prefix
  "Drop the leading `opencode/` from a model id, if present.
   Zen configs use `opencode/<id>`, but the API expects the bare `<id>`."
  [model]
  (if (and model (str/starts-with? model "opencode/"))
    (subs model (count "opencode/"))
    model))

(defn wire-format
  "Return the wire-format keyword for a Zen model id (with or without the
   `opencode/` prefix). Unknown ids default to :chat."
  [model]
  (let [id (strip-prefix model)]
    (cond
      (contains? responses-models id) :responses
      (contains? messages-models id)  :messages
      (contains? gemini-models id)    :gemini
      :else                           :chat)))

(defn zen-model?
  "True when `model` names an OpenCode Zen model. Recognizes the explicit
   `opencode/` prefix, or a known bare Zen id (so `/model big-pickle` routes to
   Zen too)."
  [model]
  (boolean
   (when (string? model)
     (or (str/starts-with? model "opencode/")
         (contains? responses-models model)
         (contains? messages-models model)
         (contains? gemini-models model)))))
