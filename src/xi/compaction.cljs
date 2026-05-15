(ns xi.compaction
  "Context compaction — summarize older messages when approaching context limit.
   Uses claude CLI subprocess for summarization (compact-bridge approach)."
  (:require [clojure.string :as str]))

;; ── Token Estimation ──────────────────────────────────────────────────────────

(def ^:private CONTEXT_LIMIT 200000)  ;; approximate for claude-opus/sonnet
(def ^:private COMPACTION_THRESHOLD 0.75)

(defn- estimate-tokens
  "Rough token estimate: ~4 chars per token."
  [messages]
  (reduce
   (fn [total msg]
     (let [content (:content msg)
           text (cond
                  (string? content) content
                  (sequential? content) (str/join " " (map #(or (:text %) (:thinking %) "") content))
                  :else "")]
       (+ total (quot (count text) 4))))
   0
   messages))

(defn needs-compaction?
  "Check if messages need compaction."
  [messages]
  (> (estimate-tokens messages) (* CONTEXT_LIMIT COMPACTION_THRESHOLD)))

;; ── Summarization via Claude CLI ──────────────────────────────────────────────

(def ^:private COMPACT_SYSTEM_PROMPT
  "You are summarizing a conversation for agent continuity. Produce a concise summary that preserves:
1. All file operations (which files were read, written, edited) — list these explicitly
2. Key decisions made and their rationale
3. Current state of the task (what's done, what's pending)
4. Any errors encountered and how they were resolved
5. Important context the agent will need to continue

Format as a structured summary. Be thorough but concise.")

(def ^:private SUMMARIZE_CHAR_LIMIT
  "Max chars to send to the summarizer (~150k tokens at 4 chars/token).
   Keeps well within Claude's context window even with system prompt overhead."
  600000)

(defn- truncate-for-summarization
  "Truncate text to fit summarizer context. Keeps the tail (most recent) and
   a prefix from the start for orientation."
  [text]
  (if (<= (count text) SUMMARIZE_CHAR_LIMIT)
    text
    (let [prefix-size (quot SUMMARIZE_CHAR_LIMIT 5)  ;; 20% from start
          suffix-size (- SUMMARIZE_CHAR_LIMIT prefix-size 200) ;; rest from end
          prefix (subs text 0 prefix-size)
          suffix (subs text (- (count text) suffix-size))]
      (str prefix
           "\n\n[... " (- (count text) SUMMARIZE_CHAR_LIMIT) " characters omitted ...]\n\n"
           suffix))))

(defn- summarize-via-claude
  "Use claude CLI to summarize messages. Returns promise of summary string.
   Uses node child_process for reliable stdin piping."
  [messages-text]
  (let [input (truncate-for-summarization messages-text)
        child-process (js/require "node:child_process")]
    (js/Promise.
     (fn [resolve reject]
       (let [proc (.spawn child-process
                          "claude"
                          #js ["-p" "--system-prompt" COMPACT_SYSTEM_PROMPT]
                          #js {:cwd (.cwd js/process)
                               :stdio #js ["pipe" "pipe" "pipe"]})
             stdout-chunks #js []
             stderr-chunks #js []]
         (.on (.-stdout proc) "data" (fn [chunk] (.push stdout-chunks chunk)))
         (.on (.-stderr proc) "data" (fn [chunk] (.push stderr-chunks chunk)))
         (.on proc "close"
              (fn [code]
                (let [stdout (.toString (.concat js/Buffer stdout-chunks))
                      stderr (.toString (.concat js/Buffer stderr-chunks))]
                  (if (= 0 code)
                    (resolve stdout)
                    (reject (js/Error.
                             (str "Claude summarization failed (exit " code "): "
                                  (subs (str/trim stderr) 0 (min 200 (count stderr))))))))))
         (.on proc "error"
              (fn [err] (reject err)))
         (.write (.-stdin proc) input)
         (.end (.-stdin proc))))))


(defn- format-messages-for-summary
  "Format messages into readable text for the summarizer."
  [messages]
  (str/join "\n\n"
            (map (fn [msg]
                   (let [role (or (:role msg) "unknown")
                         content (:content msg)
                         text (cond
                                (string? content) content
                                (sequential? content)
                                (str/join "\n" (keep (fn [block]
                                                      (case (:type block)
                                                        "text" (:text block)
                                                        "toolCall" (str "[Tool: " (:name block) " " (pr-str (:arguments block)) "]")
                                                        "thinking" nil
                                                        (pr-str block)))
                                                    content))
                                :else (pr-str content))]
                     (str "## " (str/upper-case role) "\n" text)))
                 messages)))

 (defn summarize
   "Summarize conversation text via Claude CLI. Returns promise of summary string."
   [text]
   (summarize-via-claude text))

;; ── Compaction ────────────────────────────────────────────────────────────────

 (defn compact-messages
   "Compact older messages into a summary. Keeps the most recent messages intact.
   Returns promise of new message vec with compacted prefix."
   [messages]
   (let [total (count messages)
         ;; Keep last ~25% of messages intact
         keep-count (max 4 (quot total 4))
         old-messages (subvec (vec messages) 0 (- total keep-count))
         recent-messages (subvec (vec messages) (- total keep-count))
         summary-input (format-messages-for-summary old-messages)]
     (-> (summarize-via-claude summary-input)
         (.then (fn [summary]
                  (let [compacted-msg {:role "user"
                                       :content [{:type "text"
                                                   :text (str "<summary>\n" summary "\n</summary>\n\n"
                                                              "The conversation history before this point was compacted into the above summary.")}]}]
                    (into [compacted-msg] recent-messages)))))))
)