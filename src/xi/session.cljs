(ns xi.session
  "Session management for Xi.
   Xi sessions are lightweight metadata files in ~/.config/xi/sessions/{cwd-encoded}/.
   The actual conversation data lives in claude CLI sessions (~/.claude/projects/).
   Xi also reads Pi sessions from ~/.pi/agent/sessions/ for resume."
  (:require [clojure.string :as str]
            [xi.session.sync :as sync]
            ["node:fs" :as fs]
            ["node:path" :as node-path]
            ["node:crypto" :as crypto]))

;; ── Paths ─────────────────────────────────────────────────────────────────────

(def ^:private HOME (aget js/process.env "HOME"))

(def ^:private XI_SESSIONS_DIR
  (.join node-path HOME ".config" "xi" "sessions"))

(def ^:private PERSONAL_AGENT_SESSIONS_DIR
  (.join node-path HOME ".config" "xi" "personal-agent" "root"))

(def ^:private PI_SESSIONS_DIR
  (.join node-path HOME ".pi" "agent" "sessions"))

(def ^:private CLAUDE_PROJECTS_DIR
  (.join node-path HOME ".claude" "projects"))

;; ── Helpers ───────────────────────────────────────────────────────────────────

(defn- gen-uuid-v7
  "Generate a UUIDv7 (time-ordered)."
  []
  (let [now (js/Date.now)
        ts-hex (.padStart (.toString now 16) 12 "0")
        rand-hex (.toString (crypto/randomBytes 10) "hex")
        ver-nibble (str "7" (subs rand-hex 0 3))
        var-byte (bit-or (bit-and (js/parseInt (subs rand-hex 3 5) 16) 0x3f) 0x80)
        var-hex (str (.padStart (.toString var-byte 16) 2 "0") (subs rand-hex 5 7))]
    (str (subs ts-hex 0 8) "-"
         (subs ts-hex 8 12) "-"
         ver-nibble "-"
         var-hex "-"
         (subs rand-hex 7 19))))

(defn- iso-now [] (.toISOString (js/Date.)))

(defn encode-cwd-xi
  "Encode CWD for Xi session directory.
   /home/floscr/Code/Projects/xi → -home-floscr-Code-Projects-xi"
  [cwd]
  (let [stripped (if (str/starts-with? cwd "/") (subs cwd 1) cwd)]
    (str "-" (str/replace stripped "/" "-"))))

(defn- encode-cwd-pi
  "Encode CWD for Pi session directory (double-dash wrapped).
   /home/floscr/Code/Projects/xi → --home-floscr-Code-Projects-xi--"
  [cwd]
  (let [stripped (if (str/starts-with? cwd "/") (subs cwd 1) cwd)]
    (str "--" (str/replace stripped "/" "-") "--")))

(def ^:private encode-cwd-claude sync/encode-cwd-claude)

(defn- xi-session-dir [cwd]
  (.join node-path XI_SESSIONS_DIR (encode-cwd-xi cwd)))

(defn- pi-session-dir [cwd]
  (.join node-path PI_SESSIONS_DIR (encode-cwd-pi cwd)))

(defn- claude-project-dir [cwd]
  (.join node-path CLAUDE_PROJECTS_DIR (encode-cwd-claude cwd)))

;; ── Xi Session Metadata ───────────────────────────────────────────────────────
;;
;; Xi stores a small JSON metadata file per session:
;; {
;;   "id": "<uuid>",
;;   "cli_session_id": "<claude-cli-session-id>",
;;   "cwd": "/path/to/project",
;;   "created": "<ISO timestamp>",
;;   "name": "Session title",
;;   "model": "claude-opus-4-6"
;; }

(defn create-session
  "Create a new Xi session. Returns session state map.
   opts:
     :personal-agent? - store in personal-agent sessions dir"
  [cwd & [opts]]
  (let [dir (if (:personal-agent? opts)
              PERSONAL_AGENT_SESSIONS_DIR
              (xi-session-dir cwd))
        session-id (gen-uuid-v7)
        timestamp (iso-now)
        meta {:id session-id
              :cli-session-id nil
              :cwd cwd
              :created timestamp
              :name nil
              :model nil
              :personal-agent? (:personal-agent? opts)}]
    (when-not (fs/existsSync dir)
      (fs/mkdirSync dir #js {:recursive true}))
    (assoc meta :_dir dir)))

(defn save-session!
  "Persist session metadata to disk."
  [session]
  (let [dir (or (:_dir session)
                (if (:personal-agent? session)
                  PERSONAL_AGENT_SESSIONS_DIR
                  (xi-session-dir (:cwd session))))
        filepath (.join node-path dir (str (:id session) ".json"))
        data (dissoc session :_dir :source)]
    (when-not (fs/existsSync dir)
      (fs/mkdirSync dir #js {:recursive true}))
    (fs/writeFileSync filepath (js/JSON.stringify (clj->js data) nil 2) "utf8")
    session))

(defn update-session!
  "Update session fields and persist."
  [session updates]
  (let [updated (merge session updates)]
    (save-session! updated)
    updated))

(defn touch-session!
  "Update last-accessed timestamp and persist. Returns updated session."
  [session]
  (update-session! session {:last-accessed (iso-now)}))

(defn delete-session!
  "Delete a session by its summary map (must contain :filepath and :source).
   For :xi sessions, also deletes associated JSONL data file if present.
   Returns true if deleted, false if file not found."
  [summary]
  (let [filepath (:filepath summary)]
    (if (and filepath (fs/existsSync filepath))
      (do (fs/unlinkSync filepath)
          true)
      false)))

;; ── Claude CLI Session Reading ────────────────────────────────────────────────

(defn- read-head-lines
  "Read the first `max-bytes` of a file and return lines.
   Drops the last (potentially truncated) line."
  [filepath max-bytes]
  (let [fd (fs/openSync filepath "r")
        buf (js/Buffer.alloc max-bytes)
        bytes-read (fs/readSync fd buf 0 max-bytes)
        _ (fs/closeSync fd)
        text (.toString buf "utf8" 0 bytes-read)
        lines (str/split text #"\n")]
    ;; Drop last line — it may be truncated
    (if (> (count lines) 1)
      (butlast lines)
      lines)))

(defn- read-claude-session-summary
  "Read a claude CLI session file and extract summary info.
   Only reads the first 16KB for speed — enough for the first user message."
  [filepath]
  (try
    (let [session-id (-> filepath
                         (.split "/")
                         last
                         (str/replace ".jsonl" ""))
          lines (read-head-lines filepath 16384)
          first-user (reduce (fn [_ line]
                               (when (seq line)
                                 (let [obj (js->clj (js/JSON.parse line) :keywordize-keys true)]
                                   (when (= "user" (:type obj))
                                     (reduced obj)))))
                             nil lines)
          first-text (when first-user
                       (let [content (:content (:message first-user))]
                         (cond
                           (string? content) content
                           (sequential? content)
                           (->> content
                                (filter #(= "text" (:type %)))
                                (map :text)
                                first)
                           :else nil)))
          name (when first-text
                 (let [text (str/trim first-text)
                       text (if (str/starts-with? text "The conversation history")
                              nil
                              text)]
                   (when text
                     (subs text 0 (min 60 (count text))))))
          timestamp (:timestamp first-user)]
      {:session-id session-id
       :source :claude
       :filepath filepath
       :timestamp timestamp
       :name name
       :user-messages nil})
    (catch :default _e nil)))

;; ── Pi Session Reading ────────────────────────────────────────────────────────

(defn- read-pi-session-summary
  "Read a Pi session file and extract summary info.
   Only reads the first 16KB for speed."
  [filepath]
  (try
    (let [lines (read-head-lines filepath 16384)
          header (when (seq (first lines))
                   (js->clj (js/JSON.parse (first lines)) :keywordize-keys true))
          ;; Find session name from session_info or first user message
          name-or-msg (reduce (fn [_ line]
                                (when (seq line)
                                  (let [obj (js->clj (js/JSON.parse line) :keywordize-keys true)]
                                    (cond
                                      (= "session_info" (:type obj))
                                      (reduced {:name (:name obj)})
                                      (and (= "message" (:type obj))
                                           (= "user" (get-in obj [:message :role])))
                                      (reduced {:name (let [text (get-in obj [:message :content])]
                                                        (when (string? text)
                                                          (subs text 0 (min 60 (count text)))))})
                                      :else nil))))
                              nil lines)]
      {:session-id (:id header)
       :source :pi
       :filepath filepath
       :timestamp (:timestamp header)
       :name (:name name-or-msg)
       :user-messages nil})
    (catch :default _e nil)))

;; ── Xi Session Reading ────────────────────────────────────────────────────────

(defn- uuid7->iso
  "Extract ISO timestamp from a UUIDv7 id (first 48 bits = unix ms)."
  [id]
  (try
    (let [hex (subs (str/replace id "-" "") 0 12)
          ms (js/parseInt hex 16)]
      (when (pos? ms)
        (.toISOString (js/Date. ms))))
    (catch :default _e nil)))

(defn- read-xi-session-meta
  "Read an Xi session metadata JSON file."
  [filepath]
  (try
    (let [content (fs/readFileSync filepath "utf8")
          data (js->clj (js/JSON.parse content) :keywordize-keys true)
          timestamp (or (:created data) (uuid7->iso (:id data)))]
      {:session-id (:id data)
       :cli-session-id (:cli-session-id data)
       :cwd (:cwd data)
       :source :xi
       :filepath filepath
       :timestamp timestamp
       :last-accessed (:last-accessed data)
       :name (:name data)
       :model (:model data)
       :user-messages nil})
    (catch :default _e nil)))

;; ── Session Listing (merged) ──────────────────────────────────────────────────

(defn- list-dir-files [dir ext]
  (if-not (fs/existsSync dir)
    []
    (->> (fs/readdirSync dir)
         (filter #(str/ends-with? % ext))
         (mapv #(.join node-path dir %)))))

(defn- list-dir-subdirs
  "List subdirectory names in a directory."
  [dir]
  (if-not (fs/existsSync dir)
    []
    (->> (fs/readdirSync dir)
         (filter (fn [name]
                   (let [full (.join node-path dir name)]
                     (try (.isDirectory (fs/statSync full))
                          (catch :default _ false))))))))

(defn list-sessions
  "List all sessions for a CWD from all sources. Returns vec of session
   summaries, newest first. Sources: Xi metadata, Claude CLI, Pi sessions."
  [cwd]
  (let [;; Xi metadata sessions
        xi-sessions (->> (list-dir-files (xi-session-dir cwd) ".json")
                         (keep read-xi-session-meta))
        ;; Claude CLI sessions
        claude-sessions (->> (list-dir-files (claude-project-dir cwd) ".jsonl")
                             (keep read-claude-session-summary))
        ;; Pi sessions
        pi-sessions (->> (list-dir-files (pi-session-dir cwd) ".jsonl")
                         (keep read-pi-session-summary))
        ;; Merge, dedup by session-id (Xi meta takes priority), sort by timestamp
        xi-ids (set (keep :cli-session-id xi-sessions))
        ;; Don't show claude sessions that have Xi metadata (avoid duplicates)
        claude-filtered (remove #(contains? xi-ids (:session-id %)) claude-sessions)]
    (->> (concat xi-sessions claude-filtered pi-sessions)
         (sort-by #(or (:last-accessed %) (:timestamp %)))
         reverse
         vec)))

(defn list-all-sessions
  "List sessions across ALL CWDs from all sources. Returns vec of session
   summaries, newest first. Each summary includes :cwd."
  []
  (let [;; Xi: each subdir under XI_SESSIONS_DIR is an encoded CWD
        xi-sessions (->> (list-dir-subdirs XI_SESSIONS_DIR)
                         (mapcat (fn [subdir]
                                   (let [dir (.join node-path XI_SESSIONS_DIR subdir)]
                                     (->> (list-dir-files dir ".json")
                                          (keep read-xi-session-meta))))))
        ;; Claude: each subdir under CLAUDE_PROJECTS_DIR is an encoded CWD
        claude-sessions (->> (list-dir-subdirs CLAUDE_PROJECTS_DIR)
                             (mapcat (fn [subdir]
                                       (let [dir (.join node-path CLAUDE_PROJECTS_DIR subdir)]
                                         (->> (list-dir-files dir ".jsonl")
                                              (keep read-claude-session-summary))))))
        ;; Pi: each subdir under PI_SESSIONS_DIR is an encoded CWD
        pi-sessions (->> (list-dir-subdirs PI_SESSIONS_DIR)
                         (mapcat (fn [subdir]
                                   (let [dir (.join node-path PI_SESSIONS_DIR subdir)]
                                     (->> (list-dir-files dir ".jsonl")
                                          (keep read-pi-session-summary))))))
        ;; Dedup: Xi meta takes priority over claude sessions with same session-id
        xi-ids (set (keep :cli-session-id xi-sessions))
        claude-filtered (remove #(contains? xi-ids (:session-id %)) claude-sessions)]
    (->> (concat xi-sessions claude-filtered pi-sessions)
         (sort-by #(or (:last-accessed %) (:timestamp %)))
         reverse
         vec)))

(defn list-personal-agent-sessions
  "List sessions from the personal-agent sessions dir only.
   Returns vec of session summaries, newest first."
  []
  (let [xi-sessions (->> (list-dir-files PERSONAL_AGENT_SESSIONS_DIR ".json")
                         (keep read-xi-session-meta))]
    (->> xi-sessions
         (sort-by #(or (:last-accessed %) (:timestamp %)))
         reverse
         vec)))

;; ── Response counting (for unread indicators) ────────────────────────────────

(defn- count-assistant-turns-in-jsonl
  "Count assistant message lines in a JSONL file. Fast — just checks type field."
  [filepath]
  (try
    (let [content (fs/readFileSync filepath "utf8")
          lines (str/split content #"\n")]
      (reduce (fn [n line]
                (if (and (not (str/blank? line))
                         (str/includes? line "\"type\":\"assistant\""))
                  (inc n)
                  n))
              0 lines))
    (catch :default _ 0)))

(defn count-session-responses
  "Given a seq of xi session-ids, return {session-id response-count}.
   Reads session metadata to find JSONL, counts assistant turns."
  [session-ids]
  (let [all-metas (->> (list-dir-files PERSONAL_AGENT_SESSIONS_DIR ".json")
                       (keep read-xi-session-meta))
        id->meta (into {} (map (fn [m] [(:session-id m) m])) all-metas)]
    (into {}
          (keep (fn [sid]
                  (when-let [meta (get id->meta sid)]
                    (when-let [cli-sid (:cli-session-id meta)]
                      (let [cwd (or (:cwd meta) "/")
                            filepath (.join node-path (claude-project-dir cwd)
                                            (str cli-sid ".jsonl"))]
                        (when (fs/existsSync filepath)
                          [sid (count-assistant-turns-in-jsonl filepath)]))))))
          session-ids)))

;; ── Resume Support ────────────────────────────────────────────────────────────

(defn load-session
  "Load a session for resume. Returns session state map with :cli-session-id
   for passing to claude CLI via -r."
  [summary]
  (case (:source summary)
    :xi
    (let [content (fs/readFileSync (:filepath summary) "utf8")
          data (js->clj (js/JSON.parse content) :keywordize-keys true)]
      {:id (:id data)
       :cli-session-id (:cli-session-id data)
       :cwd (:cwd data)
       :created (:created data)
       :last-accessed (:last-accessed data)
       :name (:name data)
       :model (:model data)
       :source :xi})

    :claude
    {:id (:session-id summary)
     :cli-session-id (:session-id summary)
     :cwd nil
     :name (:name summary)
     :source :claude}

    :pi
    ;; Pi sessions can't be resumed via claude CLI — they use a different format.
    ;; Show them for reference but mark as read-only.
    {:id (:session-id summary)
     :cli-session-id nil
     :cwd nil
     :name (:name summary)
     :source :pi}))

(defn- read-claude-session-messages
  "Read conversation messages from a Claude CLI session file.
   Returns vec of block maps:
     {:type :text :role \"user\" :text \"...\"}
     {:type :text :role \"assistant\" :text \"...\"}
     {:type :tool-use :name \"tool\" :tool-use-id \"...\" :arguments {...}}
     {:type :tool-result :tool-use-id \"...\" :content \"...\" :is-error bool}"
  [filepath]
  (try
    (let [content (fs/readFileSync filepath "utf8")
          lines (str/split content #"\n")
          parsed (into [] (comp (filter #(not (str/blank? %)))
                                (keep (fn [line]
                                        (try
                                          (js->clj (js/JSON.parse line) :keywordize-keys true)
                                          (catch :default _e nil)))))
                       lines)]
      (->> parsed
           (filter #(contains? #{"user" "assistant"} (:type %)))
           (mapcat (fn [line]
                     (let [content (get-in line [:message :content])
                           role (:type line)]
                       (cond
                         ;; Plain text user message
                         (and (= "user" role) (string? content)
                              (not (str/starts-with? content "The conversation history")))
                         [{:type :text :role "user" :text content}]

                         ;; Sequential content — extract all block types
                         (and (sequential? content))
                         (->> content
                              (keep (fn [block]
                                      (case (:type block)
                                        "text"
                                        {:type :text :role role :text (:text block)}

                                        "image"
                                        (let [source (:source block)]
                                          (when (and source (= "base64" (:type source)))
                                            {:type :image
                                             :role role
                                             :media-type (:media_type source)
                                             :data (:data source)}))

                                        "tool_use"
                                        {:type :tool-use
                                         :name (:name block)
                                         :tool-use-id (:id block)
                                         :arguments (or (:input block) {})}

                                        "tool_result"
                                        (let [c (:content block)
                                              text (cond
                                                     (string? c) c
                                                     (sequential? c)
                                                     (->> c
                                                          (keep (fn [b]
                                                                  (cond
                                                                    (string? b) b
                                                                    (= "text" (:type b)) (:text b)
                                                                    :else nil)))
                                                          (str/join "\n"))
                                                     :else nil)]
                                          {:type :tool-result
                                           :tool-use-id (:tool_use_id block)
                                           :content (or text "")
                                           :is-error (boolean (:is_error block))})

                                        ;; Skip thinking, etc.
                                        nil))))

                         :else nil))))
           vec))
    (catch :default _e [])))

(defn read-session-messages
  "Read conversation messages from a session for display.
   Returns vec of block maps — see read-claude-session-messages for format."
  [summary]
  (case (:source summary)
    :xi
    (if-let [cli-sid (:cli-session-id summary)]
      (let [cwd (:cwd summary)
            filepath (when cwd
                       (.join node-path (claude-project-dir cwd)
                              (str cli-sid ".jsonl")))]
        (if (and filepath (fs/existsSync filepath))
          (read-claude-session-messages filepath)
          []))
      [])

    :claude
    (read-claude-session-messages (:filepath summary))

    :pi
    (try
      (let [content (fs/readFileSync (:filepath summary) "utf8")
            lines (str/split content #"\n")
            parsed (into [] (comp (filter #(not (str/blank? %)))
                                  (keep (fn [line]
                                          (try
                                            (js->clj (js/JSON.parse line) :keywordize-keys true)
                                            (catch :default _e nil)))))
                         lines)]
        (->> parsed
             (filter #(= "message" (:type %)))
             (mapcat (fn [line]
                       (let [msg (:message line)
                             role (:role msg)]
                         (cond
                           (= "user" role)
                           (let [text (->> (:content msg)
                                           (filter #(= "text" (:type %)))
                                           (map :text)
                                           (str/join "\n"))]
                             (when (seq text) [{:type :text :role "user" :text text}]))

                           (= "assistant" role)
                           (let [text (->> (:content msg)
                                           (filter #(= "text" (:type %)))
                                           (map :text)
                                           (str/join "\n"))]
                             (when (seq text) [{:type :text :role "assistant" :text text}]))

                           :else nil))))
             vec))
      (catch :default _e []))

    []))
