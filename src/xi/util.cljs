(ns xi.util
  "Shared pure utility functions.
   Functions here must be pure — no side effects, no I/O, no atoms."
  (:require [clojure.string :as str]))

(defn truncate
  "Truncate string s to max-len characters, appending ... if truncated."
  [s max-len]
  (if (and s (> (count s) max-len))
    (str (subs s 0 max-len) "...")
    s))

(defn truncate-text-lines
  "Clip `text` to at most `n` lines. On overflow the last kept line becomes a
   '… (K more lines)' marker, so the result is never longer than `n` lines —
   re-truncating it with the same cap is a no-op. Non-strings pass through."
  [text n]
  (if (string? text)
    (let [lines (str/split-lines text)
          total (count lines)]
      (if (<= total n)
        text
        (str (str/join "\n" (take (dec n) lines))
             "\n… (" (- total (dec n)) " more lines)")))
    text))

(def max-tool-result-chars
  "Upper bound on the text characters a single tool result may carry back to an
   LLM. Providers trim oversized output to this (via cap-tool-result-content) so
   we never spend a huge slice of the context window — and the model's token
   budget — on one tool result. Kept below the Claude SDK's own tool-output
   token guard so Xi decides how output is trimmed rather than the SDK."
  40000)

(defn head-tail-truncate
  "Trim `s` to at most `budget` chars by keeping the head and tail and dropping
   the middle. Works whether the overflow is spread across many lines or crammed
   into one giant line (e.g. a one-line JSON log dump)."
  [s budget]
  (let [n (count s)]
    (cond
      (<= n budget) s
      (< budget 200) (str "… [Xi truncated " n " chars] …")
      :else (let [keep (- budget 80)
                  head-len (quot (* keep 2) 3)
                  tail-len (- keep head-len)]
              (str (subs s 0 head-len)
                   "\n\n… [Xi truncated " (- n keep) " of " n " chars] …\n\n"
                   (subs s (- n tail-len)))))))

(defn cap-tool-result-content
  "Cap the total text of a tool result's `content` to `max-tool-result-chars`
   so a provider never forwards an oversized result to an LLM. `content` may be
   a plain string (Ollama flattens results) or a vec of `{:type ...}` blocks
   (the Claude bridge); image / non-text blocks pass through untouched. Returns
   the same shape, trimmed."
  [content]
  (cond
    (string? content) (head-tail-truncate content max-tool-result-chars)

    (sequential? content)
    (let [content (vec content)
          total (transduce (comp (filter #(= "text" (:type %)))
                                 (map #(count (:text % ""))))
                           + 0 content)]
      (if (<= total max-tool-result-chars)
        content
        (:blocks
         (reduce
          (fn [{:keys [remaining] :as acc} b]
            (if (= "text" (:type b))
              (let [t (:text b "")
                    t' (if (pos? remaining)
                         (head-tail-truncate t remaining)
                         "… [Xi truncated: output budget exhausted] …")]
                (-> acc
                    (update :blocks conj (assoc b :text t'))
                    (update :remaining - (count t'))))
              (update acc :blocks conj b)))
          {:blocks [] :remaining max-tool-result-chars}
          content))))

    :else content))

(defn claude-model?
  "Returns true if model string looks like a Claude/Anthropic model."
  [model]
  (when model
    (or (str/starts-with? model "claude-")
        (str/starts-with? model "anthropic/")
        (contains? #{"sonnet" "opus" "haiku"} model))))

(defn zen-model?
  "Returns true if model string names an OpenCode Zen model — the explicit
   `opencode/` prefix routes to Zen regardless of the bare id. (Bare Zen ids
   like `claude-opus-4-8` overlap with Claude and are intentionally NOT matched
   here; prefix with `opencode/` to force the Zen gateway.)"
  [model]
  (boolean
   (when (string? model)
     (str/starts-with? model "opencode/"))))

(defn provider-for-model
  "Route a model id to a provider keyword: Zen (`opencode/…`) wins, then Claude,
   else the OpenAI-compatible Ollama path."
  [model]
  (cond
    (zen-model? model)    :zen
    (claude-model? model) :claude
    :else                 :ollama))

(defn extract-text-content
  "Extract plain text from content that may be a string or a vec of blocks."
  [content]
  (cond
    (string? content) content
    (sequential? content)
    (->> content
         (keep #(when (= "text" (:type %)) (:text %)))
         (str/join "\n"))
    :else (str content)))

(defn strip-mcp-prefix
  "Strip MCP tool prefix: mcp__xi-tools__bash -> bash"
  [n]
  (if (and n (str/starts-with? n "mcp__"))
    (if-let [idx (str/last-index-of n "__")]
      (subs n (+ idx 2))
      n)
    n))

(def ^:private session-title-max 60)

(defn session-title
  "Derive a session-list title from a session's first user message text.

   Compacted sessions seed their first message with the summary, so the raw
   text would otherwise surface as the title. This unwraps that:
   - An Xi <conversation-summary>…</conversation-summary> block is reduced to
     the summary's first meaningful line, dropping the markdown heading and
     any leading \"Session Summary —\" label so it reads like a normal title.
   - A native Claude compaction preamble (\"The conversation history …\")
     yields nil (no usable title).
   Otherwise the text is truncated to 60 chars. Returns nil when no usable
   title can be derived. Idempotent on already-clean titles."
  [text]
  (when-let [text (some-> text str/trim not-empty)]
    (letfn [(clip [s] (subs s 0 (min session-title-max (count s))))]
      (cond
        (str/starts-with? text "The conversation history") nil

        (str/starts-with? text "<conversation-summary>")
        (let [inner (-> text
                        (str/replace #"^<conversation-summary>\s*" "")
                        (str/replace #"\s*</conversation-summary>[\s\S]*$" ""))
              line (->> (str/split-lines inner)
                        (map str/trim)
                        (remove str/blank?)
                        first)
              cleaned (some-> line (str/replace #"^#+\s*" "") str/trim)
              cleaned (when cleaned
                        (if (str/starts-with? (str/lower-case cleaned)
                                              "session summary")
                          (-> (subs cleaned (count "session summary"))
                              (str/replace #"^[\s—–:-]+" "")
                              str/trim)
                          cleaned))]
          (some-> cleaned not-empty clip))

        :else (clip text)))))
