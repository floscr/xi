(ns xi.session
  "Session management for Xi.
   Xi sessions are lightweight metadata files in ~/.config/xi/sessions/{cwd-encoded}/.
   The actual conversation data lives in claude CLI sessions (~/.claude/projects/)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [xi.session.sync :as sync]
            [xi.util :as util]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]
            ["node:crypto" :as crypto]))

;; ── Paths ─────────────────────────────────────────────────────────────────────

(def ^:private HOME (aget js/process.env "HOME"))

(def ^:private XI_SESSIONS_DIR
  (.join node-path HOME ".config" "xi" "sessions"))

(def ^:private PERSONAL_AGENT_DIR
  (.join node-path HOME ".config" "xi" "personal-agent"))

(defn personal-agent-dir
  "Sessions dir for a named personal agent. nil/absent agent-id = the default
   agent (\"root\", the historical layout)."
  [agent-id]
  (.join node-path PERSONAL_AGENT_DIR (or agent-id "root")))

(defn agent-config
  "Read a named agent's optional agent.edn config from its sessions dir
   (~/.config/xi/personal-agent/<agent-id>/agent.edn). Recognized keys:
     :system-prompt      - system prompt text (replaces the default PA prompt)
     :system-prompt-file - path to a file holding the system prompt (relative
                           paths resolve against the agent dir)
     :model              - default model for this agent
   Returns the parsed map (with :system-prompt-file resolved into
   :system-prompt) or nil when no config exists / it fails to parse."
  [agent-id]
  (let [dir (personal-agent-dir agent-id)
        fp  (.join node-path dir "agent.edn")]
    (when (fs/existsSync fp)
      (try
        (let [cfg (edn/read-string (fs/readFileSync fp "utf8"))]
          (if-let [prompt-file (:system-prompt-file cfg)]
            (let [resolved (if (.isAbsolute node-path prompt-file)
                             prompt-file
                             (.join node-path dir prompt-file))]
              (-> cfg
                  (dissoc :system-prompt-file)
                  (assoc :system-prompt (str/trim (fs/readFileSync resolved "utf8")))))
            cfg))
        (catch :default e
          (js/console.error (str "xi: failed to read agent config " fp ": "
                                 (.-message e)))
          nil)))))

(def ^:private CLAUDE_PROJECTS_DIR
  (.join node-path HOME ".claude" "projects"))

(def ^:private FAVORITES_FILE
  (.join node-path HOME ".config" "xi" "favorites.json"))

(def ^:private READ_STATE_FILE
  (.join node-path HOME ".config" "xi" "read-state.json"))

(def ^:private PREFERRED_MODEL_FILE
  (.join node-path HOME ".config" "xi" "preferred-model.json"))

(def ^:private COUNT_CACHE_FILE
  (.join node-path HOME ".config" "xi" "response-counts-cache.json"))

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

(def ^:private encode-cwd-claude sync/encode-cwd-claude)

(defn- xi-session-dir [cwd]
  (.join node-path XI_SESSIONS_DIR (encode-cwd-xi cwd)))

(defn- claude-project-dir [cwd]
  (.join node-path CLAUDE_PROJECTS_DIR (encode-cwd-claude cwd)))

(defn git-project-cwds
  "All working-tree paths that belong to the same git repository as `cwd`
   (the main tree plus every linked worktree), main tree first. Falls back to
   just `[cwd]` when `cwd` isn't a git repo — or when the spawn itself throws
   (git not on PATH, cwd doesn't exist; e.g. the sanitized demo env). Used so
   /resume from the main tree also surfaces sessions recorded inside its
   worktrees (and vice versa)."
  [cwd]
  (let [paths (try
                (let [proc (js/Bun.spawnSync
                            #js ["git" "worktree" "list" "--porcelain"]
                            #js {:stdout "pipe" :stderr "pipe" :cwd cwd})]
                  (when (zero? (.-exitCode proc))
                    (->> (str/split-lines (str (.toString (.-stdout proc))))
                         (keep (fn [line]
                                 (when (str/starts-with? line "worktree ")
                                   (subs line (count "worktree ")))))
                         vec)))
                (catch :default _ nil))]
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
     :personal-agent? - store in personal-agent sessions dir
     :agent           - named agent id (subdir of the personal-agent dir;
                        implies :personal-agent?)"
  [cwd & [opts]]
  (let [pa? (boolean (or (:personal-agent? opts) (:agent opts)))
        dir (if pa?
              (personal-agent-dir (:agent opts))
              (xi-session-dir cwd))
        session-id (gen-uuid-v7)
        timestamp (iso-now)
        meta (cond-> {:id session-id
                      :cli-session-id nil
                      :cwd cwd
                      :created timestamp
                      :name nil
                      :model nil
                      :personal-agent? pa?}
               (:agent opts) (assoc :agent (:agent opts)))]
    (when-not (fs/existsSync dir)
      (fs/mkdirSync dir #js {:recursive true}))
    (assoc meta :_dir dir)))

(defn tree-filepath
  "Return the path to the tree JSONL file for a session."
  [session]
  (let [dir (or (:_dir session)
                (if (:personal-agent? session)
                  (personal-agent-dir (:agent session))
                  (xi-session-dir (:cwd session))))]
    (.join node-path dir (str (:id session) ".tree.jsonl"))))

(defonce ^:private all-sessions-cache
  ;; {:at <ms> :sessions [...]} — short-TTL cache of the raw (un-annotated)
  ;; list-all-sessions scan. The scan stats every session file on disk
  ;; (thousands of sync fs calls) and used to run multiple times per lobby
  ;; broadcast AND per unread-counts query, per client. Invalidated on any
  ;; session write so a just-synced session lists fresh.
  (atom nil))

(defn- invalidate-listing-cache! []
  (reset! all-sessions-cache nil))

(defn save-session!
  "Persist session metadata to disk."
  [session]
  (let [dir (or (:_dir session)
                (if (:personal-agent? session)
                  (personal-agent-dir (:agent session))
                  (xi-session-dir (:cwd session))))
        filepath (.join node-path dir (str (:id session) ".json"))
        data (dissoc session :_dir :source)]
    (when-not (fs/existsSync dir)
      (fs/mkdirSync dir #js {:recursive true}))
    (fs/writeFileSync filepath (js/JSON.stringify (clj->js data) nil 2) "utf8")
    (invalidate-listing-cache!)
    session))

(defn- session-dir
  "The on-disk directory holding a session's metadata + sidecars."
  [session]
  (or (:_dir session)
      (if (:personal-agent? session)
        (personal-agent-dir (:agent session))
        (xi-session-dir (:cwd session)))))

(defn canvas-sidecar-path
  "Path to a session's canvas-review sidecar (the node-based review canvas).
   Stored as EDN so keyword values (node :kind) and string node-id map keys
   survive the round-trip — a JSON round-trip would mangle both."
  [session]
  (.join node-path (session-dir session) (str (:id session) ".canvas.edn")))

(defn save-canvas!
  "Persist a room's canvas-review state (diff + nodes + edges + plan) so it
   survives room reaping and server restarts. nil canvas removes the sidecar."
  [session canvas]
  (let [fp  (canvas-sidecar-path session)
        dir (session-dir session)]
    (when-not (fs/existsSync dir)
      (fs/mkdirSync dir #js {:recursive true}))
    (if canvas
      (fs/writeFileSync fp (pr-str canvas) "utf8")
      (when (fs/existsSync fp) (fs/unlinkSync fp)))))

(defn load-canvas
  "Read a session's persisted canvas-review state, or nil if none. A parse
   failure is logged (not swallowed silently) so a corrupt sidecar surfaces
   instead of masquerading as \"no canvas\" and resuming to an empty page."
  [session]
  (let [fp (canvas-sidecar-path session)]
    (when (and (:id session) (fs/existsSync fp))
      (try (edn/read-string (str (fs/readFileSync fp "utf8")))
           (catch :default e
             (js/console.error (str "[session] failed to parse canvas sidecar " fp
                                    ": " (.-message e)))
             nil)))))

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

(defn mark-interrupted!
  "Persist the session with an :interrupted-at marker (a turn is in flight /
   spinner shown). Any subsequent normal save/touch drops the marker, so it
   only survives a hard process kill mid-turn — the signal used to auto-resume
   an interrupted agent when a client reconnects."
  [session]
  (save-session! (assoc session :interrupted-at (iso-now))))

(defn clear-interrupted!
  "Remove the :interrupted-at marker from a session's on-disk file (located by
   its summary :filepath), if present. Called after auto-resuming so a later
   restart with no in-flight turn doesn't resume the same session again."
  [filepath]
  (try
    (when (and filepath (fs/existsSync filepath))
      (let [data (js->clj (js/JSON.parse (fs/readFileSync filepath "utf8"))
                          :keywordize-keys true)]
        (when (:interrupted-at data)
          (fs/writeFileSync filepath
                            (js/JSON.stringify (clj->js (dissoc data :interrupted-at)) nil 2)
                            "utf8")
          (invalidate-listing-cache!))))
    (catch :default _e nil)))


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

(defn promote-subagent-session!
  "Promote a sub-agent's throwaway session into a real, resumable one: move
   its Claude CLI transcript out of the throwaway CLAUDE_CONFIG_DIR into the
   real ~/.claude/projects, then create + save Xi session metadata pointing
   at it. The :subagent-origin marker keeps the promoted session out of the
   normal session listings (see list-all-sessions) — it is only reachable
   through its origin session's sub-agents UI.
   Returns the saved session map, or nil when the transcript is missing
   (e.g. a non-Claude provider, which keeps no server-side transcript)."
  [{:keys [config-dir cwd cli-session-id label origin]}]
  (try
    (let [src (.join node-path config-dir "projects" (encode-cwd-claude cwd)
                     (str cli-session-id ".jsonl"))]
      (when (fs/existsSync src)
        (let [dest-dir (claude-project-dir cwd)
              dest (.join node-path dest-dir (str cli-session-id ".jsonl"))]
          (when-not (fs/existsSync dest-dir)
            (fs/mkdirSync dest-dir #js {:recursive true}))
          ;; copy + unlink, not rename — the throwaway dir lives in the OS
          ;; tmpdir, which may be a different filesystem (tmpfs).
          (fs/copyFileSync src dest)
          (fs/unlinkSync src)
          (-> (create-session cwd)
              (assoc :cli-session-id cli-session-id
                     :name label
                     :subagent-origin origin)
              (save-session!)))))
    (catch :default e
      (js/console.error (str "[session] promote sub-agent failed: " (.-message e)))
      nil)))

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

(defn- claude-message-text
  "Pull the first text out of a raw Claude message JS object's `.content`,
   which is either a plain string or an array of content blocks. Kept on the
   raw JS side (no js->clj) since it runs once per session in the listing."
  [^js message]
  (let [content (some-> message .-content)]
    (cond
      (string? content) content
      (array? content)
      (let [n (alength content)]
        (loop [i 0]
          (when (< i n)
            (let [block (aget content i)]
              (if (= "text" (.-type block))
                (.-text block)
                (recur (inc i)))))))
      :else nil)))

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
          ;; Whole file fits in the head window — so a missing assistant reply
          ;; below means the transcript really has none (not just unread tail).
          full? (<= (.-size (fs/statSync filepath)) 16384)
          ;; Read fields off the raw JS objects rather than js->clj-converting
          ;; every line: we only need the first user message and whether any
          ;; assistant line exists, and deep keywordized conversion of ~8k
          ;; head lines dominated /resume's open latency.
          [first-user assistant?]
          (reduce (fn [[fu asst?] line]
                    (if (seq line)
                      (let [obj (try (js/JSON.parse line) (catch :default _ nil))
                            t   (some-> obj .-type)]
                        [(if (and (nil? fu) (= "user" t)) obj fu)
                         (or asst? (= "assistant" t))])
                      [fu asst?]))
                  [nil false] lines)
          first-text (some-> first-user .-message claude-message-text)
          name (util/session-title first-text)
          timestamp (some-> first-user .-timestamp)]
      {:session-id session-id
       :source :claude
       :filepath filepath
       :timestamp timestamp
       :name name
       :user-messages nil
       ;; A complete transcript with a first user prompt but no assistant reply
       ;; is an aborted stub — the user interrupted before any response, and Xi
       ;; often spun up a NEW cli session for the retry, leaving this one as an
       ;; orphaned duplicate in listings. Flag it so callers can drop it.
       :empty? (boolean (and full? first-user (not assistant?)))})
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
       :name (util/session-title (:name data))
       :model (:model data)
       :interrupted-at (:interrupted-at data)
       :aborted-at (:aborted-at data)
       :truncated-from (:truncated-from data)
       :subagent-origin (:subagent-origin data)
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

(defonce ^:private transcript-index-cache
  ;; {:at <ms> :index {"<sid>.jsonl" "/full/path"}} — short-TTL index of every
  ;; transcript under ~/.claude/projects. The find-claude-transcript fallback
  ;; used to probe every project dir with existsSync PER session; for listings
  ;; with many Xi metas whose transcript is gone that was O(sessions × dirs)
  ;; syscalls per unread-counts query. One readdir sweep replaces them all.
  (atom nil))

(defn- claude-transcript-index
  "Basename → filepath index of all Claude CLI transcripts, cached briefly."
  []
  (let [now (js/Date.now)
        cached @transcript-index-cache]
    (if (and cached (< (- now (:at cached)) 2000))
      (:index cached)
      (let [index (into {}
                        (for [sub (list-dir-subdirs CLAUDE_PROJECTS_DIR)
                              f   (list-dir-files (.join node-path CLAUDE_PROJECTS_DIR sub) ".jsonl")]
                          [(.basename node-path f) f]))]
        (reset! transcript-index-cache {:at now :index index})
        index))))

(defn- find-claude-transcript
  "Resolve the Claude CLI transcript JSONL for a session's cli-session-id.
   Primary lookup derives ~/.claude/projects/<encoded-cwd>/<cli-sid>.jsonl
   from the session's cwd. If that file is missing — e.g. the session's
   stored cwd no longer exists so the agent ran from a fallback dir and the
   SDK wrote the transcript under a different project folder — fall back to
   the (cached) index of every project dir's transcripts (the id is globally
   unique). Returns the filepath, or nil when no transcript exists."
  [cwd cli-sid]
  (let [fname (str cli-sid ".jsonl")
        primary (when cwd (.join node-path (claude-project-dir cwd) fname))]
    (if (and primary (fs/existsSync primary))
      primary
      (get (claude-transcript-index) fname))))

;; ── Favorites (source-agnostic bookmarks) ─────────────────────────────────────
;; Favorites live in one JSON file keyed by the summary's :session-id, so
;; Xi/Claude sessions can all be starred without editing their own files.


(defn delete-session!
  "Delete a session by its summary map (must contain :filepath and :source).
   Returns true if the primary file was deleted, false if it was not found.

   For :xi sessions the :filepath is only the metadata file — the actual
   conversation lives in a Claude CLI transcript under ~/.claude/projects,
   referenced by :cli-session-id. That transcript MUST be removed too: the
   session listing dedups a Claude transcript out only while an Xi meta
   references it (see scan-all-sessions), so unlinking the meta alone leaves
   the orphaned transcript to resurface as a standalone Claude card — the
   deleted session appears to come back. The canvas sidecar is removed as
   well so no stray review canvas is left behind."
  [summary]
  (let [filepath   (:filepath summary)
        transcript (when (= :xi (:source summary))
                     (find-claude-transcript (:cwd summary) (:cli-session-id summary)))
        canvas     (when (and filepath (str/ends-with? filepath ".json"))
                     (str (subs filepath 0 (- (count filepath) 5)) ".canvas.edn"))
        rm!        (fn [f] (when (and f (fs/existsSync f)) (fs/unlinkSync f)))
        deleted?   (boolean (rm! filepath))]
    (rm! transcript)
    (rm! canvas)
    (when deleted? (invalidate-listing-cache!))
    deleted?))

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

;; ── Preferred model (last model chosen via /model) ────────────────────────────
;; Persisted so a model picked via /model becomes the default for new sessions.

(defn load-preferred-model
  "The last model chosen via /model (from ~/.config/xi/preferred-model.json),
   or nil when none has been saved / the file is missing or invalid."
  []
  (try
    (when (fs/existsSync PREFERRED_MODEL_FILE)
      (let [m (js->clj (js/JSON.parse (fs/readFileSync PREFERRED_MODEL_FILE "utf8")))]
        (get m "model")))
    (catch :default _e nil)))

(defn save-preferred-model!
  "Persist model as the default for new sessions."
  [model]
  (try
    (fs/mkdirSync (.dirname node-path PREFERRED_MODEL_FILE) #js {:recursive true})
    (fs/writeFileSync PREFERRED_MODEL_FILE
                      (js/JSON.stringify #js {:model model}))
    (catch :default e
      (js/console.error "[session] preferred-model write failed:" e))))

;; ── Dismissed (hidden from Recent) ────────────────────────────────────────────
;; Reversible "archive from the recent list": session-ids the user has hidden
;; from the sidebar Recent group this run. The session stays fully on disk and
;; resumable (it still shows in All sessions / search) — this only moves the
;; card into the sidebar's "Hidden" group. Deliberately in-memory (process
;; local, not a file): the hidden list is a per-focus-session convenience and
;; is scrapped on server restart, so a restart gives a clean Recent list again.

(defonce ^:private dismissed-set (atom #{}))

(defn load-dismissed
  "The current in-memory set of dismissed (hidden-from-Recent) session-ids."
  []
  @dismissed-set)

(defn annotate-dismissed
  "Tag each summary with :dismissed? using a dismissed set. The 1-arity reads
   the live in-memory set; the 2-arity is pure (for tests / batch use)."
  ([summaries] (annotate-dismissed summaries (load-dismissed)))
  ([summaries dismissed]
   (mapv #(assoc % :dismissed? (contains? dismissed (:session-id %))) summaries)))

(defn toggle-dismissed!
  "Add/remove session-id from the in-memory dismissed set. Returns the new
   dismissed? state."
  [session-id]
  (let [d? (contains? @dismissed-set session-id)]
    (if d?
      (swap! dismissed-set disj session-id)
      (swap! dismissed-set conj session-id))
    (not d?)))

(defn undismiss!
  "Remove session-id from the in-memory dismissed set (no-op when not
   dismissed). Used to auto-unhide a session the moment it sees new activity —
   a hidden session the user prompts again clearly belongs back in Recent."
  [session-id]
  (swap! dismissed-set disj session-id))

;; ── Read state (cross-device unread markers) ──────────────────────────────
;; Persisted {session-id → seen-response-count}. A session is unread when its
;; current assistant-turn count exceeds the seen count. Stored server-side and
;; shipped in the lobby payload so the marker syncs across every client/device.

(defn load-read-state
  "Persisted {session-id → seen-response-count}. {} when missing or unreadable."
  []
  (try
    (if (fs/existsSync READ_STATE_FILE)
      (js->clj (js/JSON.parse (fs/readFileSync READ_STATE_FILE "utf8")))
      {})
    (catch :default _e {})))

(defn mark-session-read!
  "Record session-id as seen at `n` assistant responses. Returns the updated
   read-state map."
  [session-id n]
  (let [state' (assoc (load-read-state) session-id n)]
    (try
      (fs/mkdirSync (.dirname node-path READ_STATE_FILE) #js {:recursive true})
      (fs/writeFileSync READ_STATE_FILE (js/JSON.stringify (clj->js state')))
      (catch :default e
        (js/console.error "[session] read-state write failed:" e)))
    state'))

(defonce ^:private summary-cache
  ;; filepath -> {:mtime <ms> :summary <map|nil>}. Memoizes the per-file
  ;; summary reads that /resume does across every session on disk: the stat is
  ;; cheap, but the head read + JSON parse per file dominated open latency and
  ;; was repeated on every open (no caching). Keyed by mtime so an edited
  ;; session file is re-read; bounded by the number of session files on disk.
  (atom {}))

(defn- cached-summary
  "Return the summary for `filepath`, invoking `read-fn` only when the file is
   new or its mtime changed since it was last read."
  [read-fn filepath]
  (let [mtime (try (.-mtimeMs (fs/statSync filepath)) (catch :default _ nil))]
    (if (nil? mtime)
      (read-fn filepath)
      (let [cached (get @summary-cache filepath)]
        (if (= (:mtime cached) mtime)
          (:summary cached)
          (let [summary (read-fn filepath)]
            (swap! summary-cache assoc filepath {:mtime mtime :summary summary})
            summary))))))

(defn- sessions-for-cwd
  "Session summaries recorded under a single CWD, from all sources, with
   Claude sessions that already have Xi metadata filtered out. Unsorted,
   not favorite-annotated — callers merge/sort/annotate across CWDs."
  [cwd]
  (let [;; Xi metadata sessions
        xi-sessions (->> (list-dir-files (xi-session-dir cwd) ".json")
                         (keep #(cached-summary read-xi-session-meta %)))
        ;; Claude CLI sessions (dropping aborted stubs with no assistant reply)
        claude-sessions (->> (list-dir-files (claude-project-dir cwd) ".jsonl")
                             (keep #(cached-summary read-claude-session-summary %))
                             (remove :empty?))
        xi-ids (set (keep :cli-session-id xi-sessions))
        ;; Don't show claude sessions that have Xi metadata (avoid duplicates)
        claude-filtered (remove #(contains? xi-ids (:session-id %)) claude-sessions)]
    ;; Claude summaries don't record their own cwd; backfill the dir they
    ;; were scanned from so callers can tell which worktree a session lives in.
    (map #(update % :cwd (fn [c] (or c cwd)))
         (concat xi-sessions claude-filtered))))

(defn list-sessions
  "List all sessions for a CWD from all sources. Returns vec of session
   summaries, newest first. Sources: Xi metadata, Claude CLI.
   Scans the whole git project — the main working tree plus every linked
   worktree — so /resume from the main repo also surfaces sessions started
   inside its worktrees."
  [cwd]
  (annotate-dismissed
   (annotate-favorites
    (->> (git-project-cwds cwd)
         (mapcat sessions-for-cwd)
         ;; Promoted sub-agent sessions stay out of the pickers — they are
         ;; opened through their origin session's sub-agents UI instead.
         (remove :subagent-origin)
         (sort-by #(or (:last-accessed %) (:timestamp %)))
         reverse
         vec))))

(def ^:private all-sessions-cache-ttl-ms 2000)

(defn- scan-all-sessions
  "The raw (un-annotated) all-CWDs session scan behind list-all-sessions."
  []
  (let [;; Xi: each subdir under XI_SESSIONS_DIR is an encoded CWD
        xi-sessions (->> (list-dir-subdirs XI_SESSIONS_DIR)
                         (mapcat (fn [subdir]
                                   (let [dir (.join node-path XI_SESSIONS_DIR subdir)]
                                     (->> (list-dir-files dir ".json")
                                          (keep #(cached-summary read-xi-session-meta %)))))))
        ;; Claude: each subdir under CLAUDE_PROJECTS_DIR is an encoded CWD
        claude-sessions (->> (list-dir-subdirs CLAUDE_PROJECTS_DIR)
                             (mapcat (fn [subdir]
                                       (let [dir (.join node-path CLAUDE_PROJECTS_DIR subdir)]
                                         (->> (list-dir-files dir ".jsonl")
                                              (keep #(cached-summary read-claude-session-summary %))))))
                             ;; Drop aborted stubs with no assistant reply.
                             (remove :empty?))
        ;; Dedup: Xi meta takes priority over claude sessions with same session-id
        xi-ids (set (keep :cli-session-id xi-sessions))
        claude-filtered (remove #(contains? xi-ids (:session-id %)) claude-sessions)]
    (->> (concat xi-sessions claude-filtered)
         (sort-by #(or (:last-accessed %) (:timestamp %)))
         reverse
         vec)))

(defn- all-sessions-raw
  "The briefly-cached raw scan behind list-all-sessions — INCLUDES hidden
   (:subagent-origin) sessions, so id lookups (find-session-by-id) can still
   resolve them."
  []
  (let [now (js/Date.now)
        cached @all-sessions-cache]
    (if (and cached (< (- now (:at cached)) all-sessions-cache-ttl-ms))
      (:sessions cached)
      (let [sessions (scan-all-sessions)]
        (reset! all-sessions-cache {:at now :sessions sessions})
        sessions))))

(defn list-all-sessions
  "List sessions across ALL CWDs from all sources. Returns vec of session
   summaries, newest first. Each summary includes :cwd. The underlying disk
   scan is cached briefly (see all-sessions-cache) — favorites/dismissed
   annotation stays per-call so toggles reflect instantly. Promoted sub-agent
   sessions (:subagent-origin) are filtered out — they are reachable only
   through their origin session's sub-agents UI (or a direct id/URL)."
  []
  (annotate-dismissed
   (annotate-favorites (into [] (remove :subagent-origin) (all-sessions-raw)))))

(defn- list-all-personal-agent-session-files
  "All session metadata files across every named-agent subdir of the
   personal-agent dir."
  []
  (->> (list-dir-subdirs PERSONAL_AGENT_DIR)
       (mapcat #(list-dir-files (.join node-path PERSONAL_AGENT_DIR %) ".json"))
       vec))

(defn list-personal-agent-sessions
  "List sessions from the personal-agent sessions dir only. With an agent-id,
   lists that named agent's dir; without, the default (root) agent.
   Returns vec of session summaries, newest first."
  [& [agent-id]]
  (let [xi-sessions (->> (list-dir-files (personal-agent-dir agent-id) ".json")
                         (keep #(cached-summary read-xi-session-meta %)))]
    (annotate-dismissed
     (annotate-favorites
      (->> xi-sessions
           (sort-by #(or (:last-accessed %) (:timestamp %)))
           reverse
           vec)))))

(defn- summary-matches-id? [session-id summary]
  (or (= session-id (:session-id summary))
      (= session-id (:cli-session-id summary))))

(defn find-session-by-id
  "Find a session summary by its ID across all sources. Matches the summary
   id or, for Xi metadata summaries, the underlying CLI session id (Claude
   sessions are deduped out of the listing once Xi metadata references them,
   so a CLI id must resolve through the Xi summary). Uses the raw scan, so
   hidden promoted sub-agent sessions resolve too."
  [session-id]
  (first (filter (partial summary-matches-id? session-id) (all-sessions-raw))))

(defn find-personal-agent-session-by-id
  "Find a session summary by its ID in the personal-agent sessions dir.
   With an agent-id, searches that named agent's dir."
  [session-id & [agent-id]]
  (first (filter (partial summary-matches-id? session-id)
                 (list-personal-agent-sessions agent-id))))

;; ── Response counting (for unread indicators) ────────────────────────────────

(defn- count-assistant-lines
  "Count assistant message lines in a chunk of JSONL text — just checks the
   type field."
  [text]
  (reduce (fn [n line]
            (if (and (not (str/blank? line))
                     (str/includes? line "\"type\":\"assistant\""))
              (inc n)
              n))
          0 (str/split text #"\n")))

(defn- count-assistant-turns-in-jsonl
  "Count assistant message lines in a JSONL file (full read)."
  [filepath]
  (try
    (count-assistant-lines (fs/readFileSync filepath "utf8"))
    (catch :default _ 0)))

(defn- read-file-slice
  "Read `len` bytes of a file starting at byte `offset`, decoded as utf8."
  [filepath offset len]
  (let [fd  (fs/openSync filepath "r")
        buf (js/Buffer.alloc len)]
    (try
      (let [n (fs/readSync fd buf 0 len offset)]
        (.toString buf "utf8" 0 n))
      (finally (fs/closeSync fd)))))

(defonce ^:private response-count-cache
  ;; filepath → {:mtime <ms> :size <bytes> :n <count>}, nil until loaded from
  ;; COUNT_CACHE_FILE. Unread-dot counts used to re-read EVERY transcript in
  ;; full, synchronously, on every counts query from every client — hundreds
  ;; of MB of sync reads blocking the WS event loop (the "web stalls +
  ;; dropped sessions"). Cached by (mtime,size); persisted so restarts don't
  ;; pay the full-scan warmup either.
  (atom nil))

(defonce ^:private count-cache-save-timer (atom nil))

(defn- ensure-count-cache!
  "Lazy-load the persisted response-count cache (once per process)."
  []
  (when (nil? @response-count-cache)
    (reset! response-count-cache
            (try
              (if (fs/existsSync COUNT_CACHE_FILE)
                (into {}
                      (map (fn [[fp e]]
                             [fp {:mtime (nth e 0) :size (nth e 1) :n (nth e 2)}]))
                      (js->clj (js/JSON.parse (fs/readFileSync COUNT_CACHE_FILE "utf8"))))
                {})
              (catch :default _ {})))))

(defn- schedule-count-cache-save!
  "Debounced persist of the response-count cache (compact [mtime size n]
   entries) — counts trickle in per query, so coalesce writes."
  []
  (when (nil? @count-cache-save-timer)
    (reset! count-cache-save-timer
            (js/setTimeout
             (fn []
               (reset! count-cache-save-timer nil)
               (try
                 (fs/writeFileSync
                  COUNT_CACHE_FILE
                  (js/JSON.stringify
                   (clj->js (into {}
                                  (map (fn [[fp {:keys [mtime size n]}]]
                                         [fp [mtime size n]]))
                                  @response-count-cache)))
                  "utf8")
                 (catch :default _ nil)))
             1000))))

(defn- transcript-response-count
  "Assistant-turn count for a transcript, via the mtime/size cache. When a
   cached file has only grown (transcripts are append-only JSONL, so the old
   EOF is a line boundary), count just the appended tail instead of
   re-reading the whole file."
  [filepath]
  (ensure-count-cache!)
  (let [stat (try (fs/statSync filepath) (catch :default _ nil))]
    (if-not stat
      0
      (let [mtime (.-mtimeMs stat)
            size  (.-size stat)
            entry (get @response-count-cache filepath)
            fresh? (and entry (= mtime (:mtime entry)) (= size (:size entry)))
            n (cond
                fresh?
                (:n entry)

                (and entry (> size (:size entry)))
                (+ (:n entry)
                   (count-assistant-lines
                    (read-file-slice filepath (:size entry) (- size (:size entry)))))

                :else
                (count-assistant-turns-in-jsonl filepath))]
        (when-not fresh?
          (swap! response-count-cache assoc filepath {:mtime mtime :size size :n n})
          (schedule-count-cache-save!))
        n))))

(defn- summary->transcript
  "Resolve a session summary to the JSONL transcript whose assistant turns
   should be counted. Xi metadata only points at the transcript (the real
   conversation lives in the Claude CLI file); Claude and Pi summaries already
   carry their JSONL filepath."
  [summary]
  (case (:source summary)
    :xi     (when-let [cli-sid (:cli-session-id summary)]
              (find-claude-transcript (:cwd summary) cli-sid))
    :claude (:filepath summary)
    :pi     (:filepath summary)
    nil))

(defn count-session-responses
  "Given a seq of session-ids (as they appear in a lobby listing), return
   {session-id response-count} where the count is the number of assistant
   turns in each session's transcript. Resolves ids across ALL sources — Xi
   coding sessions, raw Claude sessions, Pi, and the personal-agent dir — so
   the unread marker works for every session, not just personal-agent ones.

   The listing is read once and indexed by both the summary id and (for Xi
   metadata) the underlying CLI id, so a lobby id resolves whichever form it
   takes. With {:personal-agent? true} only the personal-agent dirs are
   scanned — a PA server's lobby never lists coding sessions, so the
   all-CWDs scan would be pure waste (and painfully slow on a Pi)."
  [session-ids & [{:keys [personal-agent?]}]]
  (let [pa-summaries (->> (list-all-personal-agent-session-files)
                          (keep #(cached-summary read-xi-session-meta %)))
        summaries   (if personal-agent?
                      pa-summaries
                      (concat (list-all-sessions) pa-summaries))
        id->summary (persistent!
                     (reduce (fn [acc s]
                               (cond-> (assoc! acc (:session-id s) s)
                                 (:cli-session-id s) (assoc! (:cli-session-id s) s)))
                             (transient {}) summaries))]
    (into {}
          (keep (fn [sid]
                  (when-let [s (get id->summary sid)]
                    (when-let [filepath (summary->transcript s)]
                      [sid (transcript-response-count filepath)]))))
          session-ids)))

;; ── Resume Support ────────────────────────────────────────────────────────────

(defn- transcript-first-timestamp
  "First message timestamp in a session's Claude transcript, used to backfill
   a missing :created. Sessions imported from the Claude CLI (and the Xi
   metadata later saved from them) have no :created, which left
   /diff session-edits unable to resolve the session base commit."
  [cwd cli-sid]
  (when-let [filepath (and cli-sid (find-claude-transcript cwd cli-sid))]
    (try
      (reduce (fn [_ line]
                (when (seq line)
                  (let [ts (:timestamp (js->clj (js/JSON.parse line)
                                                :keywordize-keys true))]
                    (when ts (reduced ts)))))
              nil (read-head-lines filepath 16384))
      (catch :default _e nil))))

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
               :created (or (:created data)
                            (transcript-first-timestamp (:cwd data)
                                                        (:cli-session-id data)))
               :last-accessed (:last-accessed data)
               :name (:name data)
               :model (:model data)
               :source :xi}
        ;; Keep the flag so resumed sessions save back to the PA dir
        (:personal-agent? data) (assoc :personal-agent? true)
        (:agent data) (assoc :agent (:agent data))
        ;; Keep the /truncate lineage link so it survives future saves
        (:truncated-from data) (assoc :truncated-from (:truncated-from data))
        ;; Keep the sub-agent links so they survive future saves — the origin
        ;; marker hides a promoted session from listings; the promoted list
        ;; reseeds the sub-agents panel on resume (xi.ext.subagent.handlers).
        (:subagent-origin data) (assoc :subagent-origin (:subagent-origin data))
        (:promoted-subagents data) (assoc :promoted-subagents (:promoted-subagents data))))

    :claude
    {:id (:session-id summary)
     :cli-session-id (:session-id summary)
     :cwd nil
     ;; No :created in the transcript metadata — use the first message's
     ;; timestamp so /diff session-edits can resolve the session base commit.
     :created (:timestamp summary)
     :name (:name summary)
     :source :claude}))

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
                                                     :else nil)
                                              ;; Keep any image blocks so a
                                              ;; resumed view_image / screenshot
                                              ;; result still shows its picture —
                                              ;; flattening to text alone dropped
                                              ;; them. result-images (web) reads
                                              ;; the API {:source {:media_type …}}
                                              ;; shape stored here.
                                              images (when (sequential? c)
                                                       (filterv #(and (map? %)
                                                                      (= "image" (:type %)))
                                                                c))]
                                          {:type :tool-result
                                           :tool-use-id (:tool_use_id block)
                                           :content (if (seq images)
                                                      (cond-> []
                                                        (seq text) (conj {:type "text" :text text})
                                                        true       (into images))
                                                      (or text ""))
                                           :is-error (boolean (:is_error block))})

                                        ;; Skip thinking, etc.
                                        nil))))

                         :else nil))))
           vec))
    (catch :default _e [])))

(defn- read-own-session-messages
  "Read a session's own conversation messages (no /truncate ancestry).
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

    []))

(defn read-session-messages
  "Read conversation messages from a session for display.

   When the session was created by /truncate it carries a :truncated-from
   lineage link; the ancestor chain's messages are prepended for display,
   each ancestor block tagged :pre-truncation? true (→ :no-llm? history
   entries — shown but never replayed to the model) with a
   {:type :truncation-divider} block between ancestor and descendant.

   Returns vec of block maps — see read-claude-session-messages for format."
  [summary]
  (loop [msgs (read-own-session-messages summary)
         parent-id (:truncated-from summary)
         seen #{(:session-id summary)}]
    (let [parent (when (and parent-id (not (seen parent-id)))
                   (or (find-session-by-id parent-id)
                       (find-personal-agent-session-by-id parent-id)))]
      (if-not parent
        msgs
        (recur (-> (mapv #(assoc % :pre-truncation? true)
                         (read-own-session-messages parent))
                   (conj {:type :truncation-divider})
                   (into msgs))
               (:truncated-from parent)
               (conj seen parent-id))))))

(def resume-result-line-cap
  "Max lines of any tool-result kept when shipping a resumed transcript over
   the wire. Both the web client (hard 100 in xi.web.views) and the TUI
   (truncate-output-block-after-n-lines, default 100) clip tool output at
   render, so sending more is pure wire waste on a long session."
  100)

(defn truncate-message-results
  "Clip every :tool-result block's text content to `resume-result-line-cap`
   lines before it crosses the wire on resume. :content is a string for plain
   results, or a vec of blocks when the result carries an image (see
   read-claude-session-messages) — in that case only the text block(s) are
   clipped and image blocks pass through. Every other block is untouched."
  [messages]
  (mapv (fn [block]
          (if (= :tool-result (:type block))
            (update block :content
                    (fn [c]
                      (if (sequential? c)
                        (mapv (fn [b]
                                (if (and (map? b) (= "text" (:type b)))
                                  (update b :text util/truncate-text-lines resume-result-line-cap)
                                  b))
                              c)
                        (util/truncate-text-lines c resume-result-line-cap))))
            block))
        messages))

(def ^:private search-text-byte-cap
  "Max bytes of a transcript read when building content-search text. The
   search corpus is capped at 16KB of extracted text anyway; fully reading
   every transcript (hundreds of MB across a big install) synchronously
   froze the event loop on the first search."
  (* 512 1024))

(defn build-search-text
  "Extract concatenated user+assistant text from a session for content search.
   Returns a single string, capped to 16KB. Reads at most
   `search-text-byte-cap` bytes of the transcript and pulls text straight off
   the raw JS lines (no js->clj of the whole conversation), so per-file work
   stays bounded no matter how large the transcript is."
  [summary]
  (try
    (if-let [filepath (summary->transcript summary)]
      (loop [lines (read-head-lines filepath search-text-byte-cap)
             acc   ""]
        (if (or (empty? lines) (>= (count acc) 16384))
          (if (> (count acc) 16384) (subs acc 0 16384) acc)
          (let [line (first lines)
                obj  (when (seq line)
                       (try (js/JSON.parse line) (catch :default _ nil)))
                t    (some-> obj .-type)
                text (when (or (= "user" t) (= "assistant" t))
                       (some-> obj .-message claude-message-text))]
            (recur (rest lines)
                   (if (seq text) (str acc "\n" text) acc)))))
      "")
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
   otherwise scope to that project directory. In personal-agent mode the
   corpus is the personal-agent sessions dir (which has no project cwds).
   Returns a vec of session-ids."
  ([cwd query] (content-search cwd query nil))
  ([cwd query {:keys [personal-agent?]}]
  (let [q (str/lower-case (str/trim (or query "")))]
    (if (str/blank? q)
      []
      (->> (cond
             personal-agent? (list-personal-agent-sessions)
             (seq cwd)       (list-sessions cwd)
             :else           (list-all-sessions))
           (keep (fn [s]
                   (when (or (str/includes? (str/lower-case (or (:name s) "")) q)
                             (str/includes? (cached-search-text s) q))
                     (:session-id s))))
           vec)))))

(defn- match-snippet
  "Short single-line excerpt around the first case-insensitive occurrence of
   `q` (already lowercased) in `text`, or nil when absent."
  [text q]
  (when (and (seq text) (seq q))
    (let [idx (str/index-of (str/lower-case text) q)]
      (when idx
        (let [start (max 0 (- idx 50))
              end   (min (count text) (+ idx (count q) 50))]
          (-> (str (when (pos? start) "…")
                   (subs text start end)
                   (when (< end (count text)) "…"))
              (str/replace #"\s+" " ")
              str/trim))))))

(defn search-sessions
  "Like `content-search`, but returns full session summaries (newest first)
   instead of bare ids. Each summary is augmented with :snippet — a short
   excerpt around the first content match, or nil when only the title matched.
   `cwd` nil/blank -> search across all projects; otherwise scope to that
   project directory."
  ([cwd query] (search-sessions cwd query nil))
  ([cwd query {:keys [personal-agent?]}]
   (let [q (str/lower-case (str/trim (or query "")))]
     (if (str/blank? q)
       []
       (->> (cond
              personal-agent? (list-personal-agent-sessions)
              (seq cwd)       (list-sessions cwd)
              :else           (list-all-sessions))
            (keep (fn [s]
                    (let [name-match?    (str/includes?
                                          (str/lower-case (or (:name s) "")) q)
                          content-match? (str/includes? (cached-search-text s) q)]
                      (when (or name-match? content-match?)
                        (assoc s :snippet
                               (when content-match?
                                 (match-snippet (build-search-text s) q)))))))
            vec)))))
