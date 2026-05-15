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

;; ── Summarization ─────────────────────────────────────────────────────────────

(def ^:private COMPACT_PROMPT
  "Summarize this conversation for continuity. Produce a concise summary preserving:
1. All file paths read, written, or edited
2. Key decisions and their rationale
3. Current task state (done vs pending)
4. Errors encountered and resolutions
5. Important context needed to continue

Be thorough but concise. Output only the summary, no preamble.")

(defn summarize
  "Resume the current SDK session and ask the model to summarize it.
   Returns promise of summary string."
  [session-id]
  (let [opts (cond-> {:model "claude-sonnet-4-20250514"
                      :permissionMode "bypassPermissions"
                      :resume session-id}
               claude-executable
               (assoc :pathToClaudeCodeExecutable claude-executable))
        ^js q (sdk/query #js {:prompt COMPACT_PROMPT
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
