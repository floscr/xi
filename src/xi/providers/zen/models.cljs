(ns xi.providers.zen.models
  "OpenCode model metadata: gateway + id normalization + wire-format routing.

   OpenCode runs two gateways with the same wire formats behind different
   URLs and keys:

     :zen  pay-as-you-go Console models   `opencode/<id>`     (https://opencode.ai/docs/zen)
     :go   the OpenCode Go subscription    `opencode-go/<id>`  (https://opencode.ai/docs/go)

   Neither `/models` endpoint says which API surface serves a model, so the
   mapping is transcribed from the docs' endpoint tables. Each model is served
   by one of four wire formats:

     :chat      OpenAI Chat Completions  → POST <base>/chat/completions
     :messages  Anthropic Messages       → POST <base>/messages   (x-api-key)
     :responses OpenAI Responses         → POST <base>/responses
     :gemini    Google generateContent   → POST <base>/models/<id>

   Unknown / newly-added ids fall back to :chat (the OpenAI-compatible surface),
   which is the safest default for the free/community models OpenCode keeps
   adding."
  (:require [clojure.string :as str]))

(def ^:private prefixes {:zen "opencode/" :go "opencode-go/"})

(def labels {:zen "Zen" :go "OpenCode Go"})

;; ── Wire-format table (from docs endpoint tables) ────────────────────────────

(def ^:private responses-models
  #{"gpt-6-astra" "gpt-6-sol" "gpt-6.1-sol" "gpt-6-luna"
    "gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6-luna" "gpt-5.5" "gpt-5.5-pro"
    "gpt-5.4" "gpt-5.4-pro" "gpt-5.4-mini" "gpt-5.4-nano"
    "gpt-5.3-codex" "gpt-5.3-codex-spark" "gpt-5.2" "gpt-5.2-codex"
    "gpt-5.1" "gpt-5.1-codex" "gpt-5.1-codex-max" "gpt-5.1-codex-mini"
    "gpt-5" "gpt-5-codex" "gpt-5-nano"
    "grok-4.7" "grok-4.6" "grok-4.5" "grok-build-0.1"
    "muse-spark-1.3" "muse-spark-1.3-contributor" "muse-spark-1.3-contributor-free"
    "muse-spark-1.2" "muse-spark-1.2-contributor" "muse-spark-1.2-contributor-free"})

(def ^:private messages-models
  #{"claude-fable-5-1" "claude-fable-5" "claude-opus-5-5" "claude-opus-5" "claude-opus-4-8" "claude-opus-4-7"
    "claude-opus-4-6" "claude-opus-4-5" "claude-sonnet-5-5" "claude-sonnet-5" "claude-sonnet-4-6"
    "claude-sonnet-4-5" "claude-haiku-5-5" "claude-haiku-4-5"
    "qwen3.8-flash" "qwen3.7-max" "qwen3.7-plus" "qwen3.6-plus" "qwen3.5-plus"})

;; Go serves these on the Anthropic surface where Zen uses chat-completions.
(def ^:private go-messages-models
  #{"minimax-m3" "qwen3.8-max"})

(def ^:private gemini-models
  #{"gemini-4-argon" "gemini-4-argon-contributor" "gemini-3.8-flash"
    "gemini-3.7-flash" "gemini-3.6-flash" "gemini-3.5-flash"
    "gemini-3.5-flash-lite" "gemini-3.1-pro" "gemini-3-flash"})

;; Everything else (DeepSeek, GLM, Kimi, MiniMax, big-pickle, *-free, …) is :chat.

(defn gateway
  "The gateway a model id addresses: :go for `opencode-go/<id>`, else :zen."
  [model]
  (if (and (string? model) (str/starts-with? model (:go prefixes)))
    :go
    :zen))

(defn prefix
  "`opencode/` or `opencode-go/`."
  [gateway]
  (get prefixes gateway))

(defn strip-prefix
  "Drop a leading `opencode/` or `opencode-go/` from a model id, if present.
   Configs use the prefixed form, but the APIs expect the bare `<id>`."
  [model]
  (if-let [p (and model (some #(when (str/starts-with? model %) %) (vals prefixes)))]
    (subs model (count p))
    model))

(defn wire-format
  "Return the wire-format keyword for an OpenCode model id (with or without a
   gateway prefix). Unknown ids default to :chat."
  [model]
  (let [id (strip-prefix model)]
    (cond
      (and (= :go (gateway model))
           (contains? go-messages-models id))  :messages
      (contains? responses-models id)          :responses
      (contains? messages-models id)           :messages
      (contains? gemini-models id)             :gemini
      :else                                    :chat)))

(defn base-url
  "The URL the adapter for `wire` appends `/chat/completions`, `/messages` or
   `/responses` to, on `gateway`."
  [gateway wire]
  (case gateway
    :go (case wire
          :messages "https://opencode.ai/inference/go/anthropic/v1"
          :gemini   "https://opencode.ai/inference/go/google/v1beta"
          "https://opencode.ai/inference/go/openai/v1")
    "https://opencode.ai/zen/v1"))

(defn models-url
  "The gateway's model list endpoint (OpenAI `/models` shape, no auth needed)."
  [gateway]
  (case gateway
    :go "https://opencode.ai/inference/go/v1/models"
    "https://opencode.ai/zen/v1/models"))

(defn zen-model?
  "True when `model` names an OpenCode model. Recognizes the explicit
   `opencode/` and `opencode-go/` prefixes, or a known bare Zen id (so
   `/model big-pickle` routes to Zen too)."
  [model]
  (boolean
   (when (string? model)
     (or (some #(str/starts-with? model %) (vals prefixes))
         (contains? responses-models model)
         (contains? messages-models model)
         (contains? gemini-models model)))))
