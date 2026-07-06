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
  [_st {:keys [room-id args]}]
  (let [[engine cols method]
        (if-let [[_ cols rest] (re-matches #"difft(?::(\d+))?(?:\s+(.*))?" (or args ""))]
          [:difft (some-> cols js/parseInt) (some-> rest str/trim not-empty)]
          [:git nil args])]
    {:effects [[:diff/load (cond-> {:room-id room-id :args method :engine engine}
                             cols (assoc :cols cols))]]}))

;; ── Buffer install (pure handler) ─────────────────────────────────────────────

(defn- diff-open
  "Diff text came back from :diff/load — install it as the :diff buffer and
   switch to it. :engine records the renderer; only :git diffs are unified and
   get :diff? true (the interactive viewer). :difft output is ANSI structural
   text shown as a plain buffer."
  [st {:keys [room-id title text engine]}]
  (when (state/get-room st room-id)
    (let [engine (or engine :git)]
      {:state (-> st
                  (assoc-in [:rooms room-id :ui :buffers :diff]
                            {:title title :text text :engine engine
                             :diff? (= engine :git)})
                  (assoc-in [:rooms room-id :ui :active-buffer] :diff))})))

;; ── Effect (impure) ───────────────────────────────────────────────────────────

(defn- diff-load-fx
  "Resolve a /diff source to diff text and open it. Binds the renderer +
   width for the whole run so the git helpers route through difftastic when
   requested."
  [{:keys [dispatch! state]} {:keys [room-id args engine cols]}]
  (binding [difft/*diff-engine* (or engine :git)
            difft/*diff-width*  (when (= engine :difft) cols)]
    (let [room (state/get-room state room-id)
          cwd (or (:cwd room) (.cwd js/process))
          open! (fn [title text]
                  (if (str/blank? text)
                    (dispatch! {:type :ui/status :room-id room-id :text "No changes."})
                    (dispatch! {:type :ui/diff-open :room-id room-id
                                :title title :text text :engine difft/*diff-engine*})))
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

        ;; A single token that resolves to a branch/commit → diff against it
        ;; (PR-style, vs the merge-base). Anything else is passed straight to
        ;; git diff (e.g. "HEAD~3", "--stat", "A..B").
        (if (and args (not (re-find #"\s" args)) (git/git-ref? cwd args))
          (let [{:keys [ok err]} (git/diff-against-ref cwd args)]
            (if err
              (dispatch! {:type :ui/status :room-id room-id
                          :text (str "git diff failed: " err)})
              (open! (str "Diff: " args) ok)))
          (run! (str "Diff: " args) (into ["diff"] (str/split args #"\s+"))))))))

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
                             {:name "session-commits" :description "Diff of commits made this session"}
                             {:name "difft"           :description "Render with difftastic (append a source, e.g. difft staged)"}]}]
   :handlers {:ui/diff-open diff-open}
   :fx       {:diff/load diff-load-fx}})
