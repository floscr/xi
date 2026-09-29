(ns xi.ext.resume
  "Session resume tools ported from claude-code-tools' `aichat resume`
   (https://pchalasani.github.io/claude-code-tools/tools/aichat/resume/):

     /trim [args]  — preview trimming bloated tool results (and optionally
                     long assistant messages) out of the current session's
                     Claude CLI transcript. /trim yes applies in place: same
                     session id, timestamped backup, placeholders cite the
                     backup file + line so trimmed detail stays recoverable.
                     Xi resumes the transcript per turn, so a trim takes
                     effect on the very next turn — no quit/resume needed.
     /rollover [focus] — fresh session whose first message carries a
                     <session-lineage> block (ancestor transcript paths,
                     traversed via :truncated-from links). With a focus
                     argument, also runs a summary turn (like /compact) and
                     injects the summary alongside the pointers.
     /lineage      — print the ancestor chain for the current session.

   State: room-scoped [:rooms rid :ext :resume {:pending {:opts … :at ms}}]
   holds the pending trim preview (expires after 10 minutes)."
  (:require [clojure.string :as str]
            [xi.commands :as commands]
            [xi.core.state :as state]
            [xi.session :as session]
            [xi.session.sync :as sync]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(def ^:private ext-id :resume)
(def ^:private default-threshold 500)
(def ^:private preview-ttl-ms (* 10 60 1000))
(def ^:private max-lineage-depth 20)
(def ^:private SUMMARY_MODEL "claude-sonnet-4-6")

(def ^:private SUMMARY_PROMPT
  "Summarize this conversation for continuity. Produce a concise summary preserving:
1. All file paths read, written, or edited
2. Key decisions and their rationale
3. Current task state (done vs pending)
4. Errors encountered and resolutions
5. Important context needed to continue

Be thorough but concise. Output only the summary, no preamble.")

(def ^:private trim-help
  (str "Usage: /trim [options] — preview; /trim yes — apply; /trim cancel\n"
       "Options (any order):\n"
       "  a number     char threshold (default " default-threshold ")\n"
       "  -N           only trim long assistant messages, keeping the last N\n"
       "  +N           trim the first N long assistant messages\n"
       "  tool,names   only trim these tools' results (default: all)\n"
       "Examples: /trim 800   /trim bash,read   /trim -20 800 bash"))

(defn- append-status [st room-id text]
  (update-in st [:rooms room-id :history] conj (commands/status-entry text)))

;; ── Trim engine (pure over transcript lines) ─────────────────────────────────

(defn parse-trim-args
  "aichat-style shape-based args: `-N`/`+N` assistant messages, a bare number
   is the char threshold, words (comma-separated) are tool-name filters."
  [arg-str]
  (reduce (fn [opts tok]
            (cond
              (re-matches #"[-+]\d+" tok)
              (assoc opts :assistant (js/parseInt tok 10))

              (re-matches #"\d+" tok)
              (assoc opts :threshold (js/parseInt tok 10))

              :else
              (update opts :tools (fnil into #{})
                      (map str/lower-case (remove str/blank? (str/split tok #","))))))
          {:threshold default-threshold}
          (remove str/blank? (str/split (or arg-str "") #"\s+"))))

(defn- jget [o k] (when o (aget o k)))

(defn- content-text-length
  "Total text chars of a tool_result :content (string or array of blocks)."
  [content]
  (cond
    (string? content) (.-length content)
    (js/Array.isArray content)
    (reduce (fn [n item]
              (+ n (if (= "text" (jget item "type"))
                     (.-length (or (jget item "text") ""))
                     0)))
            0 (array-seq content))
    :else 0))

(defn- tool-name-index
  "tool_use_id → tool name, from assistant messages' tool_use blocks."
  [parsed]
  (let [m (js/Map.)]
    (doseq [obj parsed
            :when (and obj (= "assistant" (jget obj "type")))
            :let [content (jget (jget obj "message") "content")]
            :when (js/Array.isArray content)]
      (doseq [item (array-seq content)
              :when (= "tool_use" (jget item "type"))]
        (.set m (jget item "id") (jget item "name"))))
    m))

(defn- long-text-blocks
  "Text blocks of an assistant message content array above threshold."
  [content threshold]
  (when (js/Array.isArray content)
    (seq (filter #(and (= "text" (jget % "type"))
                       (> (.-length (or (jget % "text") "")) threshold))
                 (array-seq content)))))

(defn- placeholder [what len backup-path line-idx]
  (str "[trimmed by xi /trim] " what ", " len " chars removed — original at "
       backup-path " line " (inc line-idx)))

(defn trim-lines
  "Compute (preview) or perform (apply) a trim over transcript lines.
   lines — vector of raw JSONL strings; opts — parse-trim-args result;
   backup-path — non-nil ⇒ apply: placeholders cite it, :lines is returned.
   Returns {:stats {:tools {name {:count :chars}} :assistant {:count :chars}}
            :modified #{line-idx} :lines vec|nil}."
  [lines {:keys [threshold tools assistant]} backup-path]
  (let [threshold (or threshold default-threshold)
        parsed (mapv (fn [l] (when-not (str/blank? l)
                               (try (js/JSON.parse l) (catch :default _ nil))))
                     lines)
        tool-names (tool-name-index parsed)
        apply? (some? backup-path)
        n (count lines)
        line-content (fn [i]
                       (let [obj (parsed i)]
                         (jget (jget obj "message") "content")))
        ;; Which long assistant messages to trim: +N ⇒ the first N of them,
        ;; -N ⇒ all but the last N. Without :assistant, none.
        assist-set (when assistant
                     (let [longs (vec (for [i (range n)
                                            :let [obj (parsed i)]
                                            :when (and obj (= "assistant" (jget obj "type"))
                                                       (long-text-blocks (line-content i) threshold))]
                                        i))]
                       (if (pos? assistant)
                         (set (take assistant longs))
                         (set (drop-last (- assistant) longs)))))
        result
        (reduce
         (fn [acc i]
           (let [obj (parsed i)
                 typ (when obj (jget obj "type"))
                 content (line-content i)]
             (cond
               ;; Bloated tool results inside user messages
               (and (= "user" typ) (js/Array.isArray content))
               (reduce
                (fn [acc item]
                  (let [len (when (= "tool_result" (jget item "type"))
                              (content-text-length (jget item "content")))
                        tname (when len
                                (str/lower-case
                                 (or (.get tool-names (jget item "tool_use_id")) "unknown")))]
                    (if (and len (> len threshold)
                             (or (nil? tools) (contains? tools tname)))
                      (do (when apply?
                            (aset item "content"
                                  (placeholder (str "tool result (" tname ")")
                                               len backup-path i)))
                          (-> acc
                              (update-in [:stats :tools tname :count] (fnil inc 0))
                              (update-in [:stats :tools tname :chars] (fnil + 0) len)
                              (update :modified conj i)))
                      acc)))
                acc (array-seq content))

               ;; Selected long assistant messages
               (and assist-set (assist-set i))
               (let [blocks (long-text-blocks content threshold)
                     total (reduce + 0 (map #(.-length (jget % "text")) blocks))]
                 (when apply?
                   (doseq [b blocks]
                     (aset b "text"
                           (placeholder "assistant message"
                                        (.-length (jget b "text")) backup-path i))))
                 (-> acc
                     (update-in [:stats :assistant :count] (fnil inc 0))
                     (update-in [:stats :assistant :chars] (fnil + 0) total)
                     (update :modified conj i)))

               :else acc)))
         {:stats {} :modified #{}}
         (range n))]
    (assoc result :lines
           (when apply?
             (mapv (fn [i] (if (contains? (:modified result) i)
                             (js/JSON.stringify (parsed i))
                             (lines i)))
                   (range n))))))

(defn stats-total [stats]
  (+ (reduce + 0 (map :chars (vals (:tools stats))))
     (get-in stats [:assistant :chars] 0)))

(defn- stats-lines [stats]
  (concat
   (for [[tname {:keys [count chars]}] (sort-by (comp - :chars val) (:tools stats))]
     (str "  " tname ": " count " results, " chars " chars"))
   (when-let [{:keys [count chars]} (:assistant stats)]
     [(str "  assistant: " count " messages, " chars " chars")])))

(defn- preview-text [stats threshold]
  (let [total (stats-total stats)]
    (if (zero? total)
      (str "Nothing to trim (threshold " threshold " chars).")
      (str "Trim preview (threshold " threshold " chars):\n"
           (str/join "\n" (stats-lines stats))
           "\nTotal: " total " chars (~" (js/Math.round (/ total 4)) " tokens)\n"
           "/trim yes applies in place (backup kept, takes effect next turn); /trim cancel abandons."))))

;; ── Transcript + lineage resolution ──────────────────────────────────────────

(defn- transcript-path [cwd cli-sid]
  (when (and cwd cli-sid)
    (node-path/join (os/homedir) ".claude" "projects"
                    (sync/encode-cwd-claude cwd) (str cli-sid ".jsonl"))))

(defn- lineage-chain
  "Newest-first chain starting at the room's live session, following
   :truncated-from links through on-disk session metadata."
  [room]
  (let [sess (:session room)
        cwd (or (:cwd sess) (:cwd room))]
    (loop [acc [{:id (:id sess)
                 :name (:name sess)
                 :timestamp (:created sess)
                 :transcript (transcript-path cwd (:provider-session-id sess))}]
           parent-id (:truncated-from sess)
           seen #{(:id sess)}]
      (if (or (nil? parent-id) (seen parent-id)
              (>= (count acc) max-lineage-depth))
        acc
        (if-let [s (session/find-session-by-id parent-id)]
          (recur (conj acc {:id (:session-id s)
                            :name (:name s)
                            :timestamp (:timestamp s)
                            :transcript (transcript-path (:cwd s) (:cli-session-id s))})
                 (:truncated-from s)
                 (conj seen parent-id))
          acc)))))

(defn- lineage-entries
  "Numbered oldest-first listing of a chain."
  [chain]
  (str/join "\n"
            (map-indexed
             (fn [i {:keys [timestamp name transcript]}]
               (str (inc i) ". "
                    (when (seq (str timestamp))
                      (str (subs timestamp 0 (min 10 (count timestamp))) " "))
                    (when name (str "\"" name "\" "))
                    "— " (or transcript "(no transcript on disk)")))
             (reverse chain))))

(defn- lineage-block [chain]
  (str "<session-lineage>\n"
       "This session continues earlier sessions (oldest first). Their full "
       "transcripts are JSONL files on disk — read/grep them to recover any "
       "prior detail on demand.\n"
       (lineage-entries chain)
       "\n</session-lineage>"))

(def ^:private rollover-instruction
  (str "This is a fresh continuation of the lineage above. Acknowledge briefly "
       "and wait for the next instruction; consult ancestor transcripts only "
       "when you need earlier detail."))

;; ── Handlers (pure) ──────────────────────────────────────────────────────────

(defn- cmd-trim [st {:keys [room-id args]}]
  (when-let [room (state/get-room st room-id)]
    (let [arg (str/trim (or args ""))
          pending (:pending (state/room-ext st room-id ext-id))]
      (cond
        (get-in room [:agent :busy?])
        {:state (append-status st room-id "Cannot trim while the agent is busy.")}

        (= arg "help")
        {:state (append-status st room-id trim-help)}

        (= arg "cancel")
        {:state (-> st
                    (assoc-in [:rooms room-id :ext ext-id :pending] nil)
                    (append-status room-id "Trim preview cancelled."))}

        (= arg "yes")
        (if pending
          {:effects [[:ext.resume/trim-apply {:room-id room-id
                                              :opts (:opts pending)
                                              :at (:at pending)}]]}
          {:state (append-status st room-id "No pending trim preview — run /trim first.")})

        :else
        {:effects [[:ext.resume/trim-scan {:room-id room-id
                                           :opts (parse-trim-args arg)}]]}))))

(defn- trim-previewed [st {:keys [room-id opts stats at]}]
  (when (state/get-room st room-id)
    (let [text (preview-text stats (:threshold opts))]
      {:state (-> st
                  (assoc-in [:rooms room-id :ext ext-id :pending]
                            (when (pos? (stats-total stats)) {:opts opts :at at}))
                  (append-status room-id text))})))

(defn- trim-applied [st {:keys [room-id stats backup]}]
  (when (state/get-room st room-id)
    (let [total (stats-total stats)]
      {:state (-> st
                  (assoc-in [:rooms room-id :ext ext-id :pending] nil)
                  (append-status room-id
                                 (str "Trimmed " total " chars (~"
                                      (js/Math.round (/ total 4)) " tokens) from the "
                                      "transcript in place. Takes effect next turn.\n"
                                      "Backup: " backup)))})))

(defn- cmd-rollover [st {:keys [room-id args]}]
  (when-let [room (state/get-room st room-id)]
    (let [sid (get-in room [:session :provider-session-id])
          focus (not-empty (str/trim (or args "")))]
      (cond
        (get-in room [:agent :busy?])
        {:state (append-status st room-id "Cannot roll over while the agent is busy.")}

        (nil? sid)
        {:state (append-status st room-id "No active session to roll over.")}

        :else
        {:state (cond-> (append-status st room-id
                                       (if focus
                                         "Rolling over (extracting work summary)..."
                                         "Rolling over..."))
                  focus (assoc-in [:rooms room-id :agent :busy?] true))
         :effects [[:ext.resume/rollover {:room-id room-id
                                          :session-id sid
                                          :focus focus}]]}))))

(defn- rollover-ready [st {:keys [room-id after-prompt]}]
  (when-let [room (state/get-room st room-id)]
    {:state (assoc-in st [:rooms room-id :agent :busy?] false)
     :effects [[:session/new
                {:room-id room-id
                 :save-current? true
                 :keep-history? true
                 :truncated-from (get-in room [:session :id])
                 :after-prompt after-prompt}]]}))

(defn- cmd-lineage [st {:keys [room-id]}]
  (when (state/get-room st room-id)
    {:effects [[:ext.resume/lineage {:room-id room-id}]]}))

(defn- lineage-result [st {:keys [room-id text]}]
  (when (state/get-room st room-id)
    {:state (append-status st room-id text)}))

(defn- on-failed
  "Shared failure path — also clears :busy? set by a focus rollover."
  [st {:keys [room-id error]}]
  (when (state/get-room st room-id)
    {:state (-> st
                (assoc-in [:rooms room-id :agent :busy?] false)
                (append-status room-id error))}))

(defn- on-abort
  "Chained onto :agent/abort so Escape also stops an in-flight rollover
   summary turn (no-op when none is running — the fx inflight map decides)."
  [st {:keys [room-id]}]
  (when (get-in st [:rooms room-id :agent :busy?])
    {:effects [[:ext.resume/abort {:room-id room-id}]]}))

;; ── Effects (contained impure edge) ──────────────────────────────────────────

(defn- room-transcript
  "Resolve the room's live transcript. Returns {:path :sid} or {:error}."
  [state room-id]
  (let [room (get-in state [:rooms room-id])
        sess (:session room)
        sid (:provider-session-id sess)
        path (transcript-path (or (:cwd sess) (:cwd room)) sid)]
    (cond
      (nil? sid) {:error "No active session (nothing recorded yet)."}
      (not (fs/existsSync path)) {:error (str "Transcript not found: " path)}
      :else {:path path :sid sid})))

(defn- create-fx [providers]
  (let [inflight (js/Map.)
        fail! (fn [dispatch! room-id error]
                (dispatch! {:type :ext.resume/failed :room-id room-id :error error}))]
    {:ext.resume/trim-scan
     (fn [{:keys [dispatch! state]} {:keys [room-id opts]}]
       (let [{:keys [path error]} (room-transcript state room-id)]
         (if error
           (fail! dispatch! room-id error)
           (try
             (let [lines (str/split-lines (fs/readFileSync path "utf8"))
                   {:keys [stats]} (trim-lines lines opts nil)]
               (dispatch! {:type :ext.resume/trim-previewed :room-id room-id
                           :opts opts :stats stats :at (js/Date.now)}))
             (catch :default e
               (fail! dispatch! room-id (str "Trim preview failed: " (.-message e))))))))

     :ext.resume/trim-apply
     (fn [{:keys [dispatch! state]} {:keys [room-id opts at]}]
       (let [{:keys [path sid error]} (room-transcript state room-id)]
         (cond
           error (fail! dispatch! room-id error)

           (get-in state [:rooms room-id :agent :busy?])
           (fail! dispatch! room-id "Cannot trim while the agent is busy.")

           (> (- (js/Date.now) (or at 0)) preview-ttl-ms)
           (fail! dispatch! room-id "Trim preview expired — run /trim again.")

           :else
           (try
             (let [ts (-> (.toISOString (js/Date.)) (str/replace #"[:.]" "-"))
                   backup (str/replace path #"\.jsonl$"
                                       (str ".pre-trim-" ts ".jsonl.bak"))
                   lines (str/split-lines (fs/readFileSync path "utf8"))
                   {:keys [stats lines] :as result} (trim-lines lines opts backup)]
               (if (zero? (stats-total stats))
                 (fail! dispatch! room-id "Nothing to trim any more.")
                 (do
                   (fs/copyFileSync path backup)
                   (fs/writeFileSync path (str (str/join "\n" lines) "\n"))
                   (dispatch! {:type :ext.resume/trim-applied :room-id room-id
                               :stats stats :backup backup
                               :modified (count (:modified result))}))))
             (catch :default e
               (fail! dispatch! room-id (str "Trim failed: " (.-message e))))))))

     :ext.resume/rollover
     (fn [{:keys [dispatch! state]} {:keys [room-id session-id focus]}]
       (let [room (get-in state [:rooms room-id])
             lineage (lineage-block (lineage-chain room))
             ready! (fn [summary]
                      (dispatch! {:type :ext.resume/rollover-ready :room-id room-id
                                  :after-prompt
                                  (str lineage "\n\n"
                                       (when summary
                                         (str "<conversation-summary>\n" summary
                                              "\n</conversation-summary>\n\n"))
                                       rollover-instruction)}))]
         (if-not focus
           (ready! nil)
           (if-let [provider (get providers :anthropic)]
             (let [chunks (atom [])
                   {:keys [promise abort!]}
                   ((:start-turn! provider)
                    {:model SUMMARY_MODEL
                     :prompt (str SUMMARY_PROMPT "\n\nFocus especially on: " focus)
                     :cwd (:cwd room)
                     :resume-session-id session-id
                     :on-text (fn [text] (swap! chunks conj text))})]
               (.set inflight room-id abort!)
               (-> promise
                   (.then
                    (fn [result]
                      (.delete inflight room-id)
                      (let [summary (or (not-empty (:result-text result))
                                        (not-empty (str/join @chunks)))]
                        (cond
                          (:aborted result)
                          (fail! dispatch! room-id "Rollover aborted.")

                          (or (:is-error result) (nil? summary))
                          (fail! dispatch! room-id
                                 (str "Rollover summary failed: "
                                      (or summary "empty summary")))

                          :else (ready! summary)))))
                   (.catch
                    (fn [err]
                      (.delete inflight room-id)
                      (fail! dispatch! room-id
                             (str "Rollover failed: " (.-message err)))))))
             (fail! dispatch! room-id
                    "No claude provider for the summary — use /rollover without a focus.")))))

     :ext.resume/abort
     (fn [_ {:keys [room-id]}]
       (when-let [abort! (.get inflight room-id)]
         (abort!)))

     :ext.resume/lineage
     (fn [{:keys [dispatch! state]} {:keys [room-id]}]
       (let [room (get-in state [:rooms room-id])
             chain (lineage-chain room)]
         (dispatch! {:type :ext.resume/lineage-result :room-id room-id
                     :text (if (< (count chain) 2)
                             (str "No lineage — this session has no ancestors.\n"
                                  "Transcript: "
                                  (or (:transcript (first chain)) "(none yet)"))
                             (str "Session lineage (oldest first):\n"
                                  (lineage-entries chain)))})))}))

(def ^:private system-prompt-note
  (str "# Session continuity\n"
       "Transcripts of this and ancestor sessions are JSONL files on disk. A "
       "<session-lineage> block or a \"[trimmed by xi /trim]\" placeholder cites "
       "the file (and line) where the full original text lives — recover it "
       "with read/grep, but only when the user asks about earlier work or you "
       "need a trimmed detail."))

(defn create
  "Factory (xi.config server vector). ctx :providers powers the /rollover
   focus-summary turn; absent (client mirror) the commands still present and
   quick rollover/trim/lineage work — only focus summaries report an error."
  [{:keys [providers]}]
  {:id ext-id
   :init {:room {:pending nil}}
   :commands [{:name "trim"
               :description "Trim bloated tool results from this session's transcript"
               :handler cmd-trim}
              {:name "rollover"
               :description "Fresh session with lineage pointers to this one"
               :handler cmd-rollover}
              {:name "lineage"
               :description "Show this session's ancestor chain"
               :handler cmd-lineage}]
   :handlers {:ext.resume/trim-previewed trim-previewed
              :ext.resume/trim-applied trim-applied
              :ext.resume/rollover-ready rollover-ready
              :ext.resume/lineage-result lineage-result
              :ext.resume/failed on-failed
              :agent/abort on-abort}
   :fx (create-fx providers)
   :system-prompt system-prompt-note})
