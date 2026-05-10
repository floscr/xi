(ns xi.session
  "Session management for Xi.
   Xi sessions are lightweight metadata files in ~/.config/xi/sessions/{cwd-encoded}/.
   The actual conversation data lives in claude CLI sessions (~/.claude/projects/).
   Xi also reads Pi sessions from ~/.pi/agent/sessions/ for resume."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as node-path]
            ["node:crypto" :as crypto]))

;; ── Paths ─────────────────────────────────────────────────────────────────────

(def ^:private HOME (aget js/process.env "HOME"))

(def ^:private XI_SESSIONS_DIR
  (.join node-path HOME ".config" "xi" "sessions"))

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

(defn- xi-session-dir [cwd]
  (.join node-path XI_SESSIONS_DIR (encode-cwd-xi cwd)))

(defn- pi-session-dir [cwd]
  (.join node-path PI_SESSIONS_DIR (encode-cwd-pi cwd)))

(defn- claude-project-dir [cwd]
  (.join node-path CLAUDE_PROJECTS_DIR (encode-cwd-xi cwd)))

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
  "Create a new Xi session. Returns session state map."
  [cwd]
  (let [dir (xi-session-dir cwd)
        session-id (gen-uuid-v7)
        timestamp (iso-now)
        meta {:id session-id
              :cli-session-id nil
              :cwd cwd
              :created timestamp
              :name nil
              :model nil}]
    (when-not (fs/existsSync dir)
      (fs/mkdirSync dir #js {:recursive true}))
    (assoc meta :_dir dir)))

(defn save-session!
  "Persist session metadata to disk."
  [session]
  (let [dir (or (:_dir session) (xi-session-dir (:cwd session)))
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
   Returns vec of {:role :text} maps."
  [filepath]
  (try
    (let [content (fs/readFileSync filepath "utf8")
          lines (str/split content #"\n")
          parsed (into [] (comp (filter seq)
                                (map #(js->clj (js/JSON.parse %) :keywordize-keys true)))
                       lines)]
      (->> parsed
           (filter #(contains? #{"user" "assistant"} (:type %)))
           (keep (fn [line]
                   (let [content (get-in line [:message :content])
                         role (:type line)]
                     (cond
                       ;; Plain text user message
                       (and (= "user" role) (string? content)
                            (not (str/starts-with? content "The conversation history")))
                       {:role "user" :text content}

                       ;; Assistant with text blocks
                       (and (= "assistant" role) (sequential? content))
                       (let [texts (->> content
                                        (filter #(= "text" (:type %)))
                                        (map :text))]
                         (when (seq texts)
                           {:role "assistant" :text (str/join "\n" texts)}))

                       :else nil))))
           vec))
    (catch :default _e [])))

(defn read-session-messages
  "Read conversation messages from a session for display.
   Returns vec of {:role :text} maps for user/assistant text messages."
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
            parsed (into [] (comp (filter seq)
                                  (map #(js->clj (js/JSON.parse %) :keywordize-keys true)))
                         lines)]
        (->> parsed
             (filter #(= "message" (:type %)))
             (keep (fn [line]
                     (let [msg (:message line)
                           role (:role msg)]
                       (cond
                         (= "user" role)
                         (let [text (->> (:content msg)
                                         (filter #(= "text" (:type %)))
                                         (map :text)
                                         (str/join "\n"))]
                           (when (seq text) {:role "user" :text text}))

                         (= "assistant" role)
                         (let [text (->> (:content msg)
                                         (filter #(= "text" (:type %)))
                                         (map :text)
                                         (str/join "\n"))]
                           (when (seq text) {:role "assistant" :text text}))

                         :else nil))))
             vec))
      (catch :default _e []))

    []))
