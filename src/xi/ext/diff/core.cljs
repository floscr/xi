(ns xi.ext.diff.core
  "Diff viewer extension — the `/diff` command, its buffer install, and the
   git-shelling effect behind it.

   /diff parses a source (git | staged | unstaged | <branch> | session-*) and
   an optional `difft[:cols]` renderer prefix, then defers the I/O to the
   :diff/load effect. That effect shells out via xi.ext.diff.git (honoring the
   selected renderer) and dispatches :ui/diff-open, which installs the result
   as the room's :diff buffer. Only :git diffs are unified and get :diff? true
   (the interactive viewer); :difft output is ANSI structural text."
  (:require [clojure.string :as str]
            [xi.core.state :as state]
            [xi.ext.diff.difft :as difft]
            [xi.ext.diff.git :as git]
            [xi.ext.diff.handlers :as handlers]
            [xi.fx :as fx]))

;; ── Command (pure) ────────────────────────────────────────────────────────────

(defn- cmd-diff
  "Open the diff viewer. Subcommands ride in args: git | staged | unstaged;
   nil → session diff; anything else is passed to git diff directly. A leading
   `difft` token selects difftastic as the renderer (structural side-by-side)
   instead of git's unified diff; the remaining tokens are the source method.
   An optional `:N` suffix on the token (e.g. `difft:120`) sets difftastic's
   wrap width — the web client measures it from its viewport so the output
   fills the browser width."
  [_st {:keys [room-id args client-id]}]
  (let [[engine cols method]
        (if-let [[_ cols rest] (re-matches #"difft(?::(\d+))?(?:\s+(.*))?" (or args ""))]
          [:difft (some-> cols js/parseInt) (some-> rest str/trim not-empty)]
          [:git nil args])]
    {:effects [[:diff/load (cond-> {:room-id room-id :args method :engine engine}
                             client-id (assoc :client-id client-id)
                             cols      (assoc :cols cols))]]}))

(defn- cmd-commits
  "List this session's commits and let the user pick one to view its diff.
   The picking happens in the :commits/pick effect (it needs git I/O + the
   dialog `ask!`); this only fans out the effect."
  [_st {:keys [room-id client-id]}]
  {:effects [[:commits/pick (cond-> {:room-id room-id}
                              client-id (assoc :client-id client-id))]]})

(defn- diff-open-commit
  "Open one commit's diff (originator-only) via the same :diff/load path as
   /diff commit:<sha>. Dispatched by the /commits selection menu once the user
   picks a commit."
  [_st {:keys [room-id sha client-id]}]
  {:effects [[:diff/load (cond-> {:room-id room-id :args (str "commit:" sha) :engine :git}
                           client-id (assoc :client-id client-id))]]})

;; ── Effect (impure) ───────────────────────────────────────────────────────────

(defn- diff-load-fx
  "Resolve a /diff source to diff text and open it. Binds the renderer +
   width for the whole run so the git helpers route through difftastic when
   requested."
  [{:keys [dispatch! state]} {:keys [room-id args engine cols client-id]}]
  (binding [difft/*diff-engine* (or engine :git)
            difft/*diff-width*  (when (= engine :difft) cols)]
    (let [room (state/get-room state room-id)
          cwd (or (:cwd room) (.cwd js/process))
          open! (fn [title text & [extra]]
                  (if (str/blank? text)
                    (dispatch! {:type :ui/status :room-id room-id :text "No changes."})
                    ;; :client-id rides along so the server delivers the diff
                    ;; only to the client that ran /diff (originator-only).
                    ;; `extra` carries commit metadata for single-commit diffs.
                    (dispatch! (merge {:type :ui/diff-open :room-id room-id :client-id client-id
                                       :title title :text text :engine difft/*diff-engine*}
                                      extra))))
          run! (fn [title git-args]
                 (let [{:keys [ok err]} (git/git-diff-out cwd git-args)]
                   (if err
                     (dispatch! {:type :ui/status :room-id room-id
                                 :text (str "git diff failed: " err)})
                     (open! title ok))))]
      (case args
        "git"
        (open! "All Git Changes" (git/all-git-changes-text cwd))

        "git-upstream"
        (if-let [ref (git/upstream-default-ref cwd)]
          (let [{:keys [ok err]} (git/diff-against-ref cwd ref)]
            (if err
              (dispatch! {:type :ui/status :room-id room-id
                          :text (str "git diff failed: " err)})
              (open! (str "Upstream (" ref ")") ok)))
          (dispatch! {:type :ui/status :room-id room-id
                      :text "Could not detect an upstream branch (origin/main or origin/master)."}))

        "staged"   (run! "Staged Changes" ["diff" "--staged"])
        "unstaged" (run! "Unstaged Changes" ["diff"])

        "session-commits"
        (let [base (git/session-base-commit cwd (get-in room [:session :created]))]
          (if (nil? base)
            (dispatch! {:type :ui/status :room-id room-id
                        :text "Could not determine session base commit."})
            (open! "Session Commits" (git/session-commits-text cwd base))))

        ("session-edits" nil)
        (let [base  (git/session-base-commit cwd (get-in room [:session :created]))
              files (fx/session-edited-files room cwd)]
          (if (empty? files)
            (dispatch! {:type :ui/status :room-id room-id
                        :text "No files edited this session."})
            (open! "Session Edits" (git/session-diff-text cwd base files))))

        ;; Like session-edits, but diffed against HEAD (the last commit)
        ;; instead of the session base — so in a long session with in-between
        ;; commits it shows only the still-uncommitted work, scoped to the
        ;; files the agent edited this session.
        "session-git"
        (let [files (fx/session-edited-files room cwd)]
          (if (empty? files)
            (dispatch! {:type :ui/status :room-id room-id
                        :text "No files edited this session."})
            (open! "Session Changes (since last commit)"
                   (git/session-diff-text cwd "HEAD" files))))

        ;; commit:<sha> → the diff of that single commit (git show). Used by the
        ;; web "session commits" bar to open one commit's changes.
        (cond
          (and args (str/starts-with? args "commit:"))
          (let [sha  (subs args (count "commit:"))
                info (git/commit-info cwd sha)]
            (open! (str "Commit " (or (:short info) (subs sha 0 (min 8 (count sha)))))
                   (git/commit-show-text cwd sha)
                   (when info {:commit info})))

          ;; A single token that resolves to a branch/commit → diff against it
          ;; (PR-style, vs the merge-base).
          (and args (not (re-find #"\s" args)) (git/git-ref? cwd args))
          (let [{:keys [ok err]} (git/diff-against-ref cwd args)]
            (if err
              (dispatch! {:type :ui/status :room-id room-id
                          :text (str "git diff failed: " err)})
              (open! (str "Diff: " args) ok)))

          ;; Anything else is passed straight to git diff (e.g. "HEAD~3",
          ;; "--stat", "A..B").
          :else
          (run! (str "Diff: " args) (into ["diff"] (str/split args #"\s+"))))))))

(defn- commits-pick-fx
  "Open the Ctrl+P-style fuzzy menu listing the commits made this session
   (newest first); selecting one opens its diff via :diff/open-commit (the same
   :diff/load path as /diff commit:<sha>). Each item's :event forwards to the
   server, where :diff/open-commit is handled."
  [{:keys [dispatch! state]} {:keys [room-id client-id]}]
  (let [room    (state/get-room state room-id)
        cwd     (or (:cwd room) (.cwd js/process))
        ;; Collect commits from the session history (git_commit tool + shell
        ;; `git commit`s), then dedupe and drop dead (amended/rebased) ones
        ;; against the live repo — more precise than a base..HEAD range.
        commits (git/session-commits-from-refs cwd (fx/session-commit-refs room))
        status! (fn [text] (dispatch! {:type :ui/status :room-id room-id :text text}))]
    (if (empty? commits)
      (status! "No commits made this session.")
      (let [items (mapv (fn [{:keys [sha short subject rel-time]}]
                          {:label subject
                           :description (str short " · " rel-time)
                           :event (cond-> {:type :diff/open-commit :room-id room-id :sha sha}
                                    client-id (assoc :client-id client-id))})
                        commits)]
        (dispatch! {:type :ui/menu-open :room-id room-id
                    :menu {:id :commits :prompt "commit> " :items items}})))))

;; ── Extension ─────────────────────────────────────────────────────────────────

(def extension
  {:id       :diff
   :commands [{:name "diff"
               :description "Show diff viewer (git|git-upstream|staged|unstaged|<branch>); prefix with difft for difftastic"
               :handler cmd-diff
               :subcommands [{:name "git"             :description "All git changes (staged + unstaged + untracked)"}
                             {:name "git-upstream"    :description "Diff against the upstream default branch (origin/main|master)"}
                             {:name "staged"          :description "Staged changes"}
                             {:name "unstaged"        :description "Unstaged changes"}
                             {:name "session-edits"   :description "Diff of files edited this session"}
                             {:name "session-git"     :description "Session edits still uncommitted (vs the last commit)"}
                             {:name "session-commits" :description "Diff of commits made this session"}
                             {:name "difft"           :description "Render with difftastic (append a source, e.g. difft staged)"}]}
              {:name "commits"
               :description "Pick a commit made this session and view its diff"
               :handler cmd-commits}]
   :handlers {:ui/diff-open    handlers/diff-open
              :diff/open-commit diff-open-commit}
   ;; The diff viewer is a client-local view: the server delivers :ui/diff-open
   ;; only to the client that ran /diff, so it never flips other connected
   ;; clients (TUI or web) into the diff tab. :diff/open-commit is an internal
   ;; server-side relay (menu choice → :diff/load) and never needs the wire.
   :originator-only #{:ui/diff-open}
   :no-broadcast    #{:diff/open-commit}
   :fx       {:diff/load    diff-load-fx
              :commits/pick commits-pick-fx}})
