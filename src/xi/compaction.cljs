(ns xi.compaction
  "Context compaction — summarize conversation via the Claude SDK."
  (:require ["@anthropic-ai/claude-agent-sdk" :as sdk]
            ["node:child_process" :as child-process]
            ["node:fs" :as fs]
            [clojure.string :as str]))

(defonce ^:private claude-executable
  (try
    (let [which-path (-> (child-process/execSync "which claude" #js {:encoding "utf8"}) .trim)
          real-path (fs/realpathSync which-path)]
      (when (.endsWith real-path ".js") real-path))
    (catch :default _ nil)))

;; ── Token Estimation ──────────────────────────────────────────────────────────

(def ^:private CONTEXT_LIMIT 200000)
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

;; ── Summarization ─────────────────────────────────────────────────────────────

(def ^:private COMPACT_SYSTEM_PROMPT
  "You are summarizing a conversation for agent continuity. Produce a concise summary that preserves:
1. All file operations (which files were read, written, edited) — list these explicitly
2. Key decisions made and their rationale
3. Current state of the task (what's done, what's pending)
4. Any errors encountered and how they were resolved
5. Important context the agent will need to continue

Format as a structured summary. Be thorough but concise.")

(def ^:private SUMMARIZE_CHAR_LIMIT
  "Max chars to send to the summarizer."
  600000)

(defn- truncate-for-summarization
  "Truncate text keeping tail (most recent) and a prefix for orientation."
  [text]
  (if (<= (count text) SUMMARIZE_CHAR_LIMIT)
    text
    (let [prefix-size (quot SUMMARIZE_CHAR_LIMIT 5)
          suffix-size (- SUMMARIZE_CHAR_LIMIT prefix-size 200)
          prefix (subs text 0 prefix-size)
          suffix (subs text (- (count text) suffix-size))]
      (str prefix
           "\n\n[... " (- (count text) SUMMARIZE_CHAR_LIMIT) " characters omitted ...]\n\n"
           suffix))))

(defn summarize
  "Summarize conversation text via SDK. Returns promise of summary string."
  [text]
  (let [input (truncate-for-summarization text)
        opts (cond-> {:systemPrompt COMPACT_SYSTEM_PROMPT
                      :model "claude-sonnet-4-20250514"
                      :permissionMode "bypassPermissions"}
               claude-executable
               (assoc :pathToClaudeCodeExecutable claude-executable))
        ^js q (sdk/query #js {:prompt input
                              :options (clj->js opts)})
        chunks (atom [])]
    (-> (js/Promise.
         (fn [resolve reject]
           (let [consume
                 (fn consume []
                   (-> (.next q)
                       (.then
                        (fn [^js iter]
                          (if (.-done iter)
                            (let [result (str/join @chunks)]
                              (if (seq result)
                                (resolve result)
                                (reject (js/Error. "Compaction produced empty summary"))))
                            (do
                              (let [^js msg (.-value iter)
                                    t (.-type msg)]
                                (case t
                                  "result"
                                  (let [r (.-result msg)]
                                    (when (seq r)
                                      (swap! chunks conj r)))

                                  "assistant"
                                  (let [^js m (.-message msg)
                                        content (when m (js->clj (.-content m) :keywordize-keys true))]
                                    (doseq [block content]
                                      (when (= "text" (:type block))
                                        (swap! chunks conj (:text block)))))

                                  nil))
                              (consume)))))
                       (.catch reject)))]
             (consume))))
        (.finally (fn [] (try (.close q) (catch :default _)))))))
