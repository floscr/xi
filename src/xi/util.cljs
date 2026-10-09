(ns xi.util
  "Shared pure utility functions.
   Functions here must be pure — no side effects, no I/O, no atoms."
  (:require [clojure.string :as str]))

(def ^:private paste-fence-min-chars
  "Pastes at least this long are wrapped in a code fence regardless of shape."
  500)

(defn paste-code-like?
  "Heuristic: does `text` look like source code? True when it carries several
   code-punctuation tokens (braces, brackets, parens, semicolons, `=>`, `::`)."
  [text]
  (>= (count (re-seq #"[{}\[\]();]|=>|::" (str text))) 5))

(defn paste-paragraph-count
  "Number of non-blank paragraphs (blocks separated by blank lines) in `text`."
  [text]
  (->> (str/split (str text) #"\n[ \t]*\n")
       (remove str/blank?)
       count))

(defn paste-has-fence?
  "Does `text` already contain a ``` fence line? Such pastes (e.g. copied from
   a rendered code block) must not be wrapped a second time."
  [text]
  (boolean (re-find #"(?m)^[ \t]*```" (str text))))

(defn paste-should-fence?
  "Decide whether a pasted blob should be wrapped in a bare ``` code fence.
   Wraps when the paste spans more than 2 paragraphs, looks like code, or is at
   least 500 characters — unless it already carries its own fence."
  [text]
  (let [text (str text)]
    (and (not (paste-has-fence? text))
         (or (>= (count text) paste-fence-min-chars)
             (> (paste-paragraph-count text) 2)
             (paste-code-like? text)))))

(defn fence-paste
  "Wrap `text` in a bare ``` code fence. Adds a leading newline unless the
   cursor is already at the start of its line, and a trailing newline unless it
   is at the end of its line, so the fences always sit on their own lines."
  [text {:keys [at-line-start? at-line-end?]}]
  (let [body (-> (str text)
                 (str/replace #"^\n+" "")
                 (str/replace #"\n+$" ""))
        lead (if at-line-start? "" "\n")
        trail (if at-line-end? "" "\n")]
    (str lead "```\n" body "\n```" trail)))

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

(defn openai-model?
  "True when `model` names an OpenAI ChatGPT-subscription (Codex) model — the
   explicit `openai/` prefix routes to the Codex Responses backend."
  [model]
  (boolean
   (when (string? model)
     (str/starts-with? model "openai/"))))

(defn provider-for-model
  "Route a model id to a provider keyword: Zen (`opencode/…`) and OpenAI Codex
   (`openai/…`) prefixes win, then Claude, else the OpenAI-compatible Ollama
   path."
  [model]
  (cond
    (zen-model? model)    :zen
    (openai-model? model) :openai
    (claude-model? model) :anthropic
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

;; ── Session edits ──
;; Pure history reads shared by xi.fx (commit flow, git lock) and the web
;; client's edited-files finder.

(def edit-tool-names
  "Stripped, lower-cased names of the tools that mutate files, clj-surgeon's included."
  #{"edit" "write" "multiedit" "notebookedit"
    "clj_replace" "clj_extract" "clj_fix_declares"
    "clj_mv" "clj_fix_parens" "clj_rename_ns"})

(defn edit-tool-call?
  "True when a history entry is a file-mutating tool call, tolerant of the
   mcp__ prefix and casing."
  [{:keys [kind tool]}]
  (and (= :tool-call kind)
       (boolean (edit-tool-names (some-> tool strip-mcp-prefix str/lower-case)))))

(defn history-edit-paths
  "Path arguments of the file-mutating tool calls in a room's history, as the
   tools received them (absolute or cwd-relative), distinct, in history order."
  [history]
  (->> history
       (filter edit-tool-call?)
       (keep (fn [{:keys [arguments]}]
               (or (:path arguments) (:file_path arguments) (:file arguments))))
       distinct
       vec))

(defn relative-under
  "`path` relative to `dir` by string prefix alone (no filesystem, so it runs
   in the browser): strips a leading `dir/` or `./`. nil for a path outside
   `dir` — absolute elsewhere or climbing out with `..`."
  [dir path]
  (let [dir (str/replace dir #"/+$" "")
        rel (cond
              (str/starts-with? path (str dir "/")) (subs path (inc (count dir)))
              (str/starts-with? path "./")          (subs path 2)
              :else                                 path)]
    (when-not (or (str/starts-with? rel "/")
                  (= rel "..")
                  (str/starts-with? rel "../"))
      rel)))

(def ^:private collapse-marker-re #"^<!--xi:collapse=(.*?)-->\n?")

(defn with-collapse-marker
  "Prefix a generated prompt (skill body, /commit, compaction/rollover summary)
   with a hidden sentinel that records its collapse label. The marker travels
   in the provider transcript, so a session resumed from disk can rebuild the
   :collapsed-label that the in-memory mirror carried live. It's an HTML comment
   so it stays invisible in any markdown rendering, and is stripped on resume."
  [text label]
  (str "<!--xi:collapse=" label "-->\n" text))

(defn collapse-label
  "The collapse label recorded by a leading collapse marker, or nil."
  [text]
  (when text (second (re-find collapse-marker-re text))))

(defn strip-collapse-marker
  "Remove a leading collapse marker, leaving the clean prompt text."
  [text]
  (if text (str/replace text collapse-marker-re "") text))

(def root-user
  "The default user id. Every connection belongs to a user; one that claims
   none (or an invalid one) is root. Users are plain string ids — names,
   roles and authentication are extension territory, keyed by this id."
  "root")

(def ^:private user-id-re #"^[a-z0-9][a-z0-9._-]{0,63}$")

(defn user-id
  "Normalize a claimed user id: trimmed and lower-cased; `root-user` when it
   is missing or not a plain slug (letters, digits, `.`, `_`, `-`; up to 64
   chars). Shared by the server (resolving a connection's user), the CLI
   (`--user` / `XI_USER`) and the web client."
  [s]
  (let [s (when (string? s) (str/lower-case (str/trim s)))]
    (if (and s (re-matches user-id-re s)) s root-user)))

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
  (when-let [text (some-> text strip-collapse-marker str/trim not-empty)]
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
