(ns xi.session
  "Session management for Xi.
   Xi sessions are lightweight metadata files in ~/.config/xi/sessions/{cwd-encoded}/.
   The actual conversation data lives in claude CLI sessions (~/.claude/projects/).
   Xi also reads Pi sessions from ~/.pi/agent/sessions/ for resume."
  (:require [clojure.string :as str]
            [xi.session.sync :as sync]
            ["node:fs" :as fs]
            ["node:os" :as os]
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

(def ^:private FAVORITES_FILE
  (.join node-path HOME ".config" "xi" "favorites.json"))

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

(defn git-project-cwds
  "All working-tree paths that belong to the same git repository as `cwd`
   (the main tree plus every linked worktree), main tree first. Falls back to
   just `[cwd]` when `cwd` isn't a git repo. Used so /resume from the main tree
   also surfaces sessions recorded inside its worktrees (and vice versa)."
  [cwd]
  (let [proc (js/Bun.spawnSync
              #js ["git" "worktree" "list" "--porcelain"]
              #js {:stdout "pipe" :stderr "pipe" :cwd cwd})
        paths (when (zero? (.-exitCode proc))
                (->> (str/split-lines (str (.toString (.-stdout proc))))
                     (keep (fn [line]
                             (when (str/starts-with? line "worktree ")
                               (subs line (count "worktree ")))))
                     vec))]
    (if (seq paths)
      (distinct (cons cwd paths))
      [cwd])))

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

(defn tree-filepath
  "Return the path to the tree JSONL file for a session."
  [session]
  (let [dir (or (:_dir session)
                (if (:personal-agent? session)
                  PERSONAL_AGENT_SESSIONS_DIR
                  (xi-session-dir (:cwd session))))]
    (.join node-path dir (str (:id session) ".tree.jsonl"))))

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

(defn- claude-config-dir
  "The Claude CLI config dir the SDK reads — CLAUDE_CONFIG_DIR or ~/.claude."
  []
  (or (aget js/process.env "CLAUDE_CONFIG_DIR")
      (.join node-path HOME ".claude")))

(defn make-throwaway-config-dir!
  "Create a temp CLAUDE_CONFIG_DIR mirroring the real Claude config via
   symlinks but with a fresh, empty `projects/` dir. Used for throwaway turns
   (e.g. auto-titling): pointing the SDK here makes the CLI persist that turn's
   session JSONL under the temp dir instead of polluting ~/.claude/projects,
   so it never reaches the session list. Auth/settings keep working because
   every entry except `projects` is symlinked to the live config.
   Returns the temp dir path, or nil on failure."
  []
  (try
    (let [src  (claude-config-dir)
          base (fs/mkdtempSync (.join node-path (os/tmpdir) "xi-title-"))]
      (doseq [entry (fs/readdirSync src)]
        (when-not (= entry "projects")
          (fs/symlinkSync (.join node-path src entry)
                          (.join node-path base entry))))
      (fs/mkdirSync (.join node-path base "projects"))
      base)
    (catch :default _e nil)))

(defn remove-config-dir!
  "Recursively remove a throwaway dir from make-throwaway-config-dir!.
   Symlinks are unlinked; their targets (the live config) are untouched."
  [dir]
  (when (and dir (fs/existsSync dir))
    (try (fs/rmSync dir #js {:recursive true :force true})
         (catch :default _e nil))))

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

(defn- find-claude-transcript
  "Resolve the Claude CLI transcript JSONL for a session's cli-session-id.
   Primary lookup derives ~/.claude/projects/<encoded-cwd>/<cli-sid>.jsonl
   from the session's cwd. If that file is missing — e.g. the session's
   stored cwd no longer exists so the agent ran from a fallback dir and the
   SDK wrote the transcript under a different project folder — fall back to
   scanning every project dir for <cli-sid>.jsonl (the id is globally unique).
   Returns the filepath, or nil when no transcript exists."
  [cwd cli-sid]
  (let [fname (str cli-sid ".jsonl")
        primary (when cwd (.join node-path (claude-project-dir cwd) fname))]
    (if (and primary (fs/existsSync primary))
      primary
      (->> (list-dir-subdirs CLAUDE_PROJECTS_DIR)
           (map #(.join node-path CLAUDE_PROJECTS_DIR % fname))
           (filter #(fs/existsSync %))
           first))))

;; ── Favorites (source-agnostic bookmarks) ─────────────────────────────────────
;; Favorites live in one JSON file keyed by the summary's :session-id, so
;; Xi/Claude/Pi sessions can all be starred without editing their own files.

(defn load-favorites
  "Set of favorited session-ids from ~/.config/xi/favorites.json (or #{})."
  []
  (try
    (if (fs/existsSync FAVORITES_FILE)
      (->> (js/JSON.parse (fs/readFileSync FAVORITES_FILE "utf8"))
           (js->clj)
           (set))
      #{})
    (catch :default _e #{})))

(defn annotate-favorites
  "Tag each summary with :favorite? using a favorites set. The 1-arity reads
   the set from disk once; the 2-arity is pure (for tests / batch use)."
  ([summaries] (annotate-favorites summaries (load-favorites)))
  ([summaries favs]
   (mapv #(assoc % :favorite? (contains? favs (:session-id %))) summaries)))

(defn favorite?
  "True when session-id is currently favorited."
  [session-id]
  (contains? (load-favorites) session-id))

(defn toggle-favorite!
  "Add/remove session-id from favorites. Returns the new favorite? state."
  [session-id]
  (let [favs  (load-favorites)
        fav?  (contains? favs session-id)
        favs' (if fav? (disj favs session-id) (conj favs session-id))]
    (try
      (fs/mkdirSync (.dirname node-path FAVORITES_FILE) #js {:recursive true})
      (fs/writeFileSync FAVORITES_FILE (js/JSON.stringify (clj->js (vec favs'))))
      (catch :default e
        (js/console.error "[session] favorites write failed:" e)))
    (not fav?)))

(defn- sessions-for-cwd
  "Session summaries recorded under a single CWD, from all sources, with
   Claude sessions that already have Xi metadata filtered out. Unsorted,
   not favorite-annotated — callers merge/sort/annotate across CWDs."
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
        xi-ids (set (keep :cli-session-id xi-sessions))
        ;; Don't show claude sessions that have Xi metadata (avoid duplicates)
        claude-filtered (remove #(contains? xi-ids (:session-id %)) claude-sessions)]
    ;; Claude/Pi summaries don't record their own cwd; backfill the dir they
    ;; were scanned from so callers can tell which worktree a session lives in.
    (map #(update % :cwd (fn [c] (or c cwd)))
         (concat xi-sessions claude-filtered pi-sessions))))

(defn list-sessions
  "List all sessions for a CWD from all sources. Returns vec of session
   summaries, newest first. Sources: Xi metadata, Claude CLI, Pi sessions.
   Scans the whole git project — the main working tree plus every linked
   worktree — so /resume from the main repo also surfaces sessions started
   inside its worktrees."
  [cwd]
  (annotate-favorites
   (->> (git-project-cwds cwd)
        (mapcat sessions-for-cwd)
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
    (annotate-favorites
     (->> (concat xi-sessions claude-filtered pi-sessions)
          (sort-by #(or (:last-accessed %) (:timestamp %)))
          reverse
          vec))))

(defn list-personal-agent-sessions
  "List sessions from the personal-agent sessions dir only.
   Returns vec of session summaries, newest first."
  []
  (let [xi-sessions (->> (list-dir-files PERSONAL_AGENT_SESSIONS_DIR ".json")
                         (keep read-xi-session-meta))]
    (annotate-favorites
     (->> xi-sessions
          (sort-by #(or (:last-accessed %) (:timestamp %)))
          reverse
          vec))))

(defn- summary-matches-id? [session-id summary]
  (or (= session-id (:session-id summary))
      (= session-id (:cli-session-id summary))))

(defn find-session-by-id
  "Find a session summary by its ID across all sources. Matches the summary
   id or, for Xi metadata summaries, the underlying CLI session id (Claude
   sessions are deduped out of the listing once Xi metadata references them,
   so a CLI id must resolve through the Xi summary)."
  [session-id]
  (first (filter (partial summary-matches-id? session-id) (list-all-sessions))))

(defn find-personal-agent-session-by-id
  "Find a session summary by its ID in the personal-agent sessions dir."
  [session-id]
  (first (filter (partial summary-matches-id? session-id)
                 (list-personal-agent-sessions))))

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
                      (when-let [filepath (find-claude-transcript (:cwd meta) cli-sid)]
                        [sid (count-assistant-turns-in-jsonl filepath)])))))
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
      (cond-> {:id (:id data)
               :cli-session-id (:cli-session-id data)
               :cwd (:cwd data)
               :created (:created data)
               :last-accessed (:last-accessed data)
               :name (:name data)
               :model (:model data)
               :source :xi}
        ;; Keep the flag so resumed sessions save back to the PA dir
        (:personal-agent? data) (assoc :personal-agent? true)))

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
      (if-let [filepath (find-claude-transcript (:cwd summary) cli-sid)]
        (read-claude-session-messages filepath)
        [])
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

(defn build-search-text
  "Extract concatenated user+assistant text from a session for content search.
   Returns a single string, capped to 16KB."
  [summary]
  (try
    (let [msgs (read-session-messages summary)]
      (->> msgs
           (keep (fn [{:keys [type text]}] (when (= :text type) text)))
           (str/join "\n")
           (#(if (> (count %) 16384) (subs % 0 16384) %))))
    (catch :default _ "")))

(defonce ^:private search-text-cache
  ;; session-id -> {:stamp <mtime> :text <lowercased search text>}. Reading a
  ;; session's messages off disk is expensive, so memoize it; the stamp
  ;; (last-accessed / timestamp) busts the entry when the session grows.
  (atom {}))

(defn- cached-search-text
  [summary]
  (let [id    (:session-id summary)
        stamp (or (:last-accessed summary) (:timestamp summary) 0)
        hit   (get @search-text-cache id)]
    (if (and hit (= (:stamp hit) stamp))
      (:text hit)
      (let [text (str/lower-case (build-search-text summary))]
        (swap! search-text-cache assoc id {:stamp stamp :text text})
        text))))

(defn content-search
  "Session-ids whose name or conversation text contains `query`
   (case-insensitive). `cwd` nil/blank -> search across all sessions;
   otherwise scope to that project directory. Returns a vec of session-ids."
  [cwd query]
  (let [q (str/lower-case (str/trim (or query "")))]
    (if (str/blank? q)
      []
      (->> (if (seq cwd) (list-sessions cwd) (list-all-sessions))
           (keep (fn [s]
                   (when (or (str/includes? (str/lower-case (or (:name s) "")) q)
                             (str/includes? (cached-search-text s) q))
                     (:session-id s))))
           vec))))
