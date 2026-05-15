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
