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

(defn claude-model?
  "Returns true if model string looks like a Claude/Anthropic model."
  [model]
  (when model
    (or (str/starts-with? model "claude-")
        (str/starts-with? model "anthropic/")
        (contains? #{"sonnet" "opus" "haiku"} model))))

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
