(ns xi.fx
  "Effect handlers for sessions, images and model listing — the impure
   counterparts to xi.commands. Effect handlers receive {:dispatch! :state}
   and a payload; they report completion by dispatching events, never by
   touching state.

   Room session shape: the on-disk session map (xi.session) plus
   :provider-session-id mirroring :cli-session-id in memory. The mirror key
   is stripped before writes so the on-disk format stays unchanged."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as node-path]
            [xi.core.log :as log]
            [xi.image :as image]
            [xi.session :as session]
            [xi.system-prompt :as system-prompt]
            [xi.util :as util]))

(defn- room-of [state room-id]
  (get-in state [:rooms room-id]))

(defn- first-user-text [room]
  (some #(when (= :user (:kind %)) (:text %)) (:history room)))

(defn- shorten-home [path]
  (let [home (aget js/process.env "HOME")]
    (if (and path home (str/starts-with? path home))
      (str "~" (subs path (count home)))
      path)))

(defn- ->disk-session [sess]
  (-> sess
      (assoc :cli-session-id (or (:provider-session-id sess)
                                 (:cli-session-id sess)))
      (dissoc :provider-session-id)))

(defn- source-suffix [s]
  (case (:source s) :claude " [claude]" :pi " [pi]" ""))

;; ── Git (for :diff/load) ─────────────────────────────────────────────────────────

(def ^:dynamic *diff-engine*
  "The diff renderer for the current :diff/load run. :git → native unified
   diff (interactive viewer); :difft → difftastic structural output (ANSI).
   Bound for the whole effect body; read only by the git-diff helpers below."
  :git)

(def ^:dynamic *diff-width*
  "Column width difftastic wraps at, requested by the client whose viewport
   the diff is for (the web client measures it from its browser width). nil →
   let difftastic pick (no tty headless → its 80-col default)."
  nil)

(defn- difft-env
  "process.env extended so `git diff` routes through difftastic with color.
   Syntax highlighting is off so the only colors are the diff signal itself:
   red for removed, green for added — not a syntax rainbow. DFT_WIDTH carries
   the requesting client's column width so the output fills its viewport."
  []
  (js/Object.assign #js {} js/process.env
                     #js {"GIT_EXTERNAL_DIFF" "difft"
                          "DFT_COLOR" "always"
                          "DFT_SYNTAX_HIGHLIGHT" "off"}
                     (if *diff-width*
                       #js {"DFT_WIDTH" (str *diff-width*)}
                       #js {})))

(defn- git-out
  "Run git synchronously in cwd. Returns {:ok stdout} or {:err message}."
  [cwd args]
  (try
    (let [proc (js/Bun.spawnSync (into-array (cons "git" args)) #js {:cwd cwd})]
      (if (zero? (.-exitCode proc))
        {:ok (.toString (.-stdout proc) "utf-8")}
        {:err (str/trim (.toString (.-stderr proc) "utf-8"))}))
    (catch :default e {:err (str e)})))

(defn- git-diff-out
  "Like git-out, but for diff-producing invocations: when *diff-engine* is
   :difft the diff is rendered by difftastic (ANSI structural output) via
   GIT_EXTERNAL_DIFF instead of git's native unified diff."
  [cwd args]
  (if (= *diff-engine* :difft)
    (try
      (let [proc (js/Bun.spawnSync (into-array (cons "git" args))
                                   #js {:cwd cwd :env (difft-env)})]
        (if (zero? (.-exitCode proc))
          {:ok (.toString (.-stdout proc) "utf-8")}
          {:err (str/trim (.toString (.-stderr proc) "utf-8"))}))
      (catch :default e {:err (str e)}))
    (git-out cwd args)))

(defn- no-index-diff
  "Synthesize a diff for a single untracked file via diff --no-index. stdout
   is read regardless of exit code — --no-index exits 1 on diffs. Honors
   *diff-engine* so untracked files render with the chosen renderer too."
  [cwd f]
  (let [opts (cond-> #js {:cwd cwd}
               (= *diff-engine* :difft) (doto (aset "env" (difft-env))))
        p (js/Bun.spawnSync
           #js ["git" "diff" "--no-index" "--" "/dev/null" f]
           opts)]
    (.toString (.-stdout p) "utf-8")))

(defn- untracked-diff
  "Synthesize a unified diff for all untracked files in cwd."
  [cwd]
  (when-let [files (some->> (:ok (git-out cwd ["ls-files" "--others" "--exclude-standard"]))
                            str/trim str/split-lines (remove empty?) seq)]
    (str/join "\n" (map (partial no-index-diff cwd) files))))

(defn- session-base-commit
  "The commit that was HEAD when the session started (works for resumed
   sessions too — derived from the session's :created timestamp)."
  [cwd created]
  (when created
    (some-> (:ok (git-out cwd ["rev-list" "-1" (str "--before=" created) "HEAD"]))
            str/trim not-empty)))

(def ^:private edit-tool-names
  "Stripped, lower-cased tool names that mutate files on disk. Includes the
   structural-edit (clj-surgeon) tools so MCP-driven edits still register."
  #{"edit" "write" "multiedit" "notebookedit"
    "clj_replace" "clj_extract" "clj_fix_declares"
    "clj_mv" "clj_fix_parens" "clj_rename_ns"})

(defn- edit-tool-call?
  "True when a history entry is a file-mutating tool call. Tolerant of an
   un-stripped mcp__ prefix and of casing so edits register regardless of how
   the provider recorded the tool name."
  [{:keys [kind tool]}]
  (and (= :tool-call kind)
       (boolean (edit-tool-names (some-> tool util/strip-mcp-prefix str/lower-case)))))

(defn- session-edited-files
  "Paths (relative to cwd) of files touched via edit/write tool calls in the
   room's history. Used to scope the session diff to files the agent changed,
   rather than every dirty file in the working tree."
  [room cwd]
  (->> (:history room)
       (filter edit-tool-call?)
       (keep (fn [{:keys [arguments]}]
               (or (:path arguments) (:file_path arguments) (:file arguments))))
       (map #(.relative node-path cwd (.resolve node-path cwd %)))
       distinct
       vec))

(defn- session-diff-text
  "Unified diff of the session-edited files vs the session base commit.
   Tracked files diff against base; untracked (newly written) files are
   synthesized via --no-index so freshly created files still show."
  [cwd base files]
  (let [{tracked false untracked true}
        (group-by #(some? (:err (git-out cwd ["ls-files" "--error-unmatch" "--" %])))
                  files)
        tracked-diff   (when (seq tracked)
                         (:ok (git-diff-out cwd (concat (if base ["diff" base] ["diff"])
                                                        ["--"] tracked))))
        untracked-diff (when (seq untracked)
                         (str/join "\n" (map (partial no-index-diff cwd) untracked)))]
    (->> [tracked-diff untracked-diff]
         (remove str/blank?)
         (str/join "\n"))))

(defn- session-commits-text
  "Unified diff of every commit made during the session (base..HEAD)."
  [cwd base]
  (when base
    (some-> (:ok (git-diff-out cwd ["diff" base "HEAD"])) str/trim not-empty)))

(defn- git-ref?
  "True when ref resolves to a commit in cwd."
  [cwd ref]
  (boolean (:ok (git-out cwd ["rev-parse" "--verify" "--quiet" (str ref "^{commit}")]))))

(defn- upstream-default-ref
  "Auto-detect the upstream branch to diff against: origin's default branch.
   Prefers origin/HEAD when set (e.g. set by clone), else the first of
   origin/main / origin/master that exists. nil when none is found."
  [cwd]
  (or (some-> (:ok (git-out cwd ["symbolic-ref" "--short" "refs/remotes/origin/HEAD"]))
              str/trim not-empty)
      (some (fn [ref] (when (git-ref? cwd ref) ref))
            ["origin/main" "origin/master"])))

(defn- diff-against-ref
  "Unified diff of the working tree against the merge-base with ref — the
   changes this branch introduced since it diverged from ref (committed +
   uncommitted), PR-style. Falls back to a plain ref diff when there is no
   common ancestor. Returns {:ok ...} | {:err ...}."
  [cwd ref]
  (let [base (some-> (:ok (git-out cwd ["merge-base" ref "HEAD"])) str/trim not-empty)]
    (git-diff-out cwd ["diff" (or base ref)])))

(defn- list-room-sessions [room scope]
  (let [pa? (get-in room [:agent :personal-agent?])]
    (cond
      pa?           (session/list-personal-agent-sessions)
      (= :all scope) (session/list-all-sessions)
      :else          (session/list-sessions (:cwd room)))))

(defn- session-item [room-id scope i s]
  {:label (or (:name s) "(unnamed)")
   :description (str (when (= scope :all)
                       (some-> (:cwd s) shorten-home (str " ")))
                     (:timestamp s)
                     (when (:user-messages s)
                       (str " (" (:user-messages s) " msgs)"))
                     (source-suffix s))
   :event {:type :command/run :room-id room-id :name "resume"
           :args (if (= scope :all) (str "all:" (inc i)) (str (inc i)))}})


(def ^:private claude-model-ids
  ["claude-opus-4-8" "claude-fable-5" "claude-opus-4-6"
   "claude-sonnet-4-6" "claude-haiku-4-5-20251001"])

(defn- fetch-all-model-ids
  "Fetch Ollama model names, combine with Claude IDs, call cb.
   Falls back to Claude-only on error."
  [cb]
  (-> (js/fetch "http://localhost:11434/api/tags")
      (.then (fn [res] (.json res)))
      (.then (fn [^js data]
               (let [models (js->clj (.-models data) :keywordize-keys true)]
                 (cb (into claude-model-ids (mapv :name models))))))
      (.catch (fn [_err] (cb claude-model-ids)))))

(defn web-model-list-reply-fx
  "Build the full model list and send it to the requesting client."
  [send-fn]
  (fetch-all-model-ids
   (fn [models] (send-fn {:type :models/web-list-result :models models}))))

(defn create-fx
  "Build effect handlers. opts:
     :system-prompt-fn  (fn [cwd] → {:system str :system-parts [{:source :text}]})
                        — called on /cd to rebuild the system prompt."
  [ring & [{:keys [system-prompt-fn]}]]
  {:session/new
   (fn [{:keys [dispatch! state]} {:keys [room-id save-current? after-prompt]}]
     (let [room (room-of state room-id)
           current (:session room)
           pa? (get-in room [:agent :personal-agent?])]
       (when (and save-current? (:provider-session-id current))
         (try (session/save-session! (->disk-session current))
              (catch :default e
                (js/console.error "[fx] session save failed:" e))))
       (dispatch! {:type :session/created
                   :room-id room-id
                   :session (session/create-session
                             (or (:cwd room) (.cwd js/process))
                             (when pa? {:personal-agent? true}))
                   :after-prompt after-prompt})))

   :session/sync
   (fn [{:keys [dispatch! state]} {:keys [room-id]}]
     (let [room (room-of state room-id)
           sess (:session room)]
       (when (:provider-session-id sess)
         (let [title (when-let [t (first-user-text room)]
                       (subs t 0 (min 60 (count t))))
               model (get-in room [:agent :model])
               named (cond-> sess
                       (and (nil? (:name sess)) title) (assoc :name title)
                       model (assoc :model model))
               touched (session/touch-session! (->disk-session named))]
           (dispatch! {:type :session/updated :room-id room-id
                       :session (-> touched
                                    (assoc :provider-session-id (:cli-session-id touched)))})))))

   :session/list
   (fn [{:keys [dispatch! state]} {:keys [room-id]}]
     (let [room (room-of state room-id)
           cwd-sessions (list-room-sessions room :cwd)
           all-sessions (list-room-sessions room :all)
           enrich (fn [items summaries]
                    (mapv (fn [item s]
                            (assoc item :search-text (session/build-search-text s)))
                          items summaries))
           cwd-items (enrich (vec (map-indexed (partial session-item room-id :cwd) cwd-sessions))
                             cwd-sessions)
           all-items (enrich (vec (map-indexed (partial session-item room-id :all) all-sessions))
                             all-sessions)]
       (if (and (empty? cwd-items) (empty? all-items))
         (dispatch! {:type :ui/status :room-id room-id :text "(no previous sessions)"})
         (dispatch! {:type :ui/menu-open :room-id room-id
                     :menu {:id :resume
                            :prompt "resume> "
                            :items cwd-items
                            :alt-items all-items
                            :tab-labels ["Current Folder" "All"]
                            :search-field :search-text}}))))

   :session/load
   (fn [{:keys [dispatch! state]} {:keys [room-id scope index]}]
     (let [room (room-of state room-id)
           sessions (list-room-sessions room scope)]
       (if (and (<= 1 index) (<= index (count sessions)))
         (let [summary (nth sessions (dec index))]
           (dispatch! {:type :session/resumed
                       :room-id room-id
                       :session (session/load-session summary)
                       :summary summary
                       :messages (session/read-session-messages summary)}))
         (dispatch! {:type :ui/status :room-id room-id :text "Session not found."}))))

   :diff/load
   (fn [{:keys [dispatch! state]} {:keys [room-id args engine cols]}]
     (binding [*diff-engine* (or engine :git)
               *diff-width*  (when (= engine :difft) cols)]
      (let [room (room-of state room-id)
           cwd (or (:cwd room) (.cwd js/process))
           open! (fn [title text]
                   (if (str/blank? text)
                     (dispatch! {:type :ui/status :room-id room-id :text "No changes."})
                     (dispatch! {:type :ui/diff-open :room-id room-id
                                 :title title :text text :engine *diff-engine*})))
           run! (fn [title git-args]
                  (let [{:keys [ok err]} (git-diff-out cwd git-args)]
                    (if err
                      (dispatch! {:type :ui/status :room-id room-id
                                  :text (str "git diff failed: " err)})
                      (open! title ok))))]
       (case args
         "git"
         (open! "All Git Changes"
                (->> [(:ok (git-diff-out cwd ["diff"]))
                      (:ok (git-diff-out cwd ["diff" "--staged"]))
                      (untracked-diff cwd)]
                     (remove str/blank?)
                     (str/join "\n")
                     str/trim))

         "git-upstream"
         (if-let [ref (upstream-default-ref cwd)]
           (let [{:keys [ok err]} (diff-against-ref cwd ref)]
             (if err
               (dispatch! {:type :ui/status :room-id room-id
                           :text (str "git diff failed: " err)})
               (open! (str "Upstream (" ref ")") ok)))
           (dispatch! {:type :ui/status :room-id room-id
                       :text "Could not detect an upstream branch (origin/main or origin/master)."}))

         "staged"   (run! "Staged Changes" ["diff" "--staged"])
         "unstaged" (run! "Unstaged Changes" ["diff"])

         "session-commits"
         (let [base (session-base-commit cwd (get-in room [:session :created]))]
           (if (nil? base)
             (dispatch! {:type :ui/status :room-id room-id
                         :text "Could not determine session base commit."})
             (open! "Session Commits" (session-commits-text cwd base))))

         ("session-edits" nil)
         (let [base  (session-base-commit cwd (get-in room [:session :created]))
               files (session-edited-files room cwd)]
           (if (empty? files)
             (dispatch! {:type :ui/status :room-id room-id
                         :text "No files edited this session."})
             (open! "Session Edits" (session-diff-text cwd base files))))

         ;; A single token that resolves to a branch/commit → diff against it
         ;; (PR-style, vs the merge-base). Anything else is passed straight to
         ;; git diff (e.g. "HEAD~3", "--stat", "A..B").
         (if (and args (not (re-find #"\s" args)) (git-ref? cwd args))
           (let [{:keys [ok err]} (diff-against-ref cwd args)]
             (if err
               (dispatch! {:type :ui/status :room-id room-id
                           :text (str "git diff failed: " err)})
               (open! (str "Diff: " args) ok)))
           (run! (str "Diff: " args) (into ["diff"] (str/split args #"\s+"))))))))

   :events/load
   (fn [{:keys [dispatch! state]} {:keys [room-id]}]
     (let [room (room-of state room-id)
           entries (when ring
                     (->> (log/entries ring)
                          (filter #(or (nil? (:room-id %)) (= room-id (:room-id %))))))
           text (if (seq entries)
                  (str/join "\n" (map log/format-entry-line entries))
                  "(no events)")]
       (dispatch! {:type :ui/buffer-open :room-id room-id
                   :buffer-id :events
                   :buffer {:title "Events" :text text}})))

   :image/process
   (fn [{:keys [dispatch!]} {:keys [room-id text images]}]
     (dispatch! {:type :prompt/submit :room-id room-id :text text
                 :images (image/process-images images)}))

   :models/fetch
   (fn [{:keys [dispatch!]} {:keys [room-id]}]
     (fetch-all-model-ids
      (fn [ids]
        (let [items (mapv (fn [id]
                            {:label id
                             :event {:type :command/run :room-id room-id
                                     :name "model" :args id}})
                          ids)]
          (dispatch! {:type :ui/menu-open :room-id room-id
                      :menu {:id :model :prompt "model> " :items items}})))))

   :cwd/change
   (fn [{:keys [dispatch! state]} {:keys [room-id path]}]
     (let [room     (room-of state room-id)
           cur-cwd  (or (:cwd room) (.cwd js/process))
           resolved (.resolve node-path cur-cwd path)]
       (if-not (.existsSync fs resolved)
         (dispatch! {:type :ui/status :room-id room-id
                     :text (str "Directory not found: " resolved)})
         (if-not (.isDirectory (.statSync fs resolved))
           (dispatch! {:type :ui/status :room-id room-id
                       :text (str "Not a directory: " resolved)})
           (let [pa?    (get-in room [:agent :personal-agent?])
                 result (when (and system-prompt-fn (not pa?))
                          (system-prompt-fn resolved))
                 agents (when (and system-prompt-fn (not pa?))
                          (system-prompt/find-agents-md resolved))]
             (dispatch! (cond-> {:type :cwd/changed :room-id room-id :cwd resolved}
                          (:system result) (assoc :system (:system result))
                          (:system-parts result) (assoc :system-parts (:system-parts result))
                          agents (assoc :agents-files agents))))))))}
)
