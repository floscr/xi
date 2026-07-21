(ns xi.ext.worktree.core
  "`/worktree` — spin the room off into a fresh git worktree.

   `/worktree <prompt>` creates a branch + linked worktree from the room's
   repo, switches the room's cwd into it, and (when a prompt is given) kicks
   the agent off there with a heads-up that this is an isolated checkout that
   needs its own build and non-default ports. Subcommands:

     /worktree merge   — merge the worktree branch into the main tree, cd back,
                         then confirm before removing the worktree + branch
     /worktree list    — list the repo's worktrees
     /worktree remove  — drop the current worktree (confirmed), cd back

   Git I/O is impure, so commands stay pure and defer to effects; the effects
   shell out via xi.ext.worktree.git and dispatch pure events back."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as node-path]
            [xi.core.state :as state]
            [xi.ext.worktree.git :as git]))

;; ── Naming (pure) ─────────────────────────────────────────────────────────────

(defn slugify
  "Lowercase, collapse non-alphanumerics to single dashes, trim dashes."
  [s]
  (-> (or s "")
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"(^-+)|(-+$)" "")))

(defn- short-slug
  "Slug of the first `n` words of `s`."
  [s n]
  (->> (str/split (slugify s) #"-")
       (remove str/blank?)
       (take n)
       (str/join "-")))

(defn- timestamp []
  (-> (js/Date.) .toISOString (subs 0 19) (str/replace #"[:T]" "-")))

(defn gen-branch-name
  "Branch name from a prompt slug, falling back to a timestamp."
  [prompt]
  (let [slug (short-slug prompt 6)]
    (if (str/blank? slug) (str "wt-" (timestamp)) slug)))

(defn- uniq-branch
  "First non-colliding branch name in `root`, appending -2, -3, … as needed."
  [root branch]
  (if-not (git/branch-exists? root branch)
    branch
    (loop [n 2]
      (let [b (str branch "-" n)]
        (if (git/branch-exists? root b) (recur (inc n)) b)))))

(defn worktree-path
  "Sibling directory for a worktree: <repo-parent>/<repo-name>-<branch-slug>."
  [repo-root branch]
  (node-path/join (node-path/dirname repo-root)
                  (str (node-path/basename repo-root) "-" (slugify branch))))

;; ── AGENTS.md worktree guidance (pure-ish read) ───────────────────────────────

(defn- read-file [p]
  (when (.existsSync fs p) (str (.readFileSync fs p "utf8"))))

(defn extract-section
  "Return the markdown section whose heading contains `needle`
   (case-insensitive), from that heading up to the next heading of the same
   or higher level. nil when no such heading exists."
  [md needle]
  (let [needle  (str/lower-case needle)
        head-re #"^(#{1,6})\s+.*$"
        level   (fn [l] (some-> (re-matches head-re l) second count))]
    (loop [lines (str/split-lines md), acc nil, want nil]
      (if (empty? lines)
        (when acc (str/trim (str/join "\n" acc)))
        (let [l (first lines)
              lv (level l)]
          (cond
            (and (nil? acc) lv (str/includes? (str/lower-case l) needle))
            (recur (rest lines) [l] lv)

            (and acc lv (<= lv want))
            (str/trim (str/join "\n" acc))

            acc
            (recur (rest lines) (conj acc l) want)

            :else
            (recur (rest lines) acc want)))))))

(defn- agents-worktree-guidance
  "Any worktree-specific section from the repo's AGENTS.md, or nil."
  [root]
  (some-> (read-file (node-path/join root "AGENTS.md"))
          (extract-section "worktree")))

(defn build-worktree-prompt
  "Prepend an isolated-checkout heads-up to the user's prompt."
  [{:keys [path branch base main-root guidance]} user-prompt]
  (str "You are now working inside a fresh git worktree — a separate checkout "
       "from the main repository.\n\n"
       "- Worktree path: " path "\n"
       "- Branch: " branch " (based on " base ")\n"
       "- Main repo: " main-root "\n\n"
       "Because this is an isolated checkout:\n"
       "- Do a fresh build here — the main repo's build/watch does not cover this worktree.\n"
       "- If you start long-running services (servers, watches, dev HTTP), use ports "
       "that differ from the main tree's defaults so they don't collide.\n"
       (if guidance
         (str "\nProject worktree guidance (from AGENTS.md):\n\n" guidance "\n")
         "- Check AGENTS.md for the project's build, serve, and port conventions.\n")
       "\n---\n\n"
       user-prompt))

;; ── Pure event handlers ───────────────────────────────────────────────────────

(defn- worktree-switch
  "Move the room's cwd to `cwd`. With :clear?, drop the tracked worktree
   metadata (used once the worktree is gone)."
  [st {:keys [room-id cwd clear?]}]
  (when (state/get-room st room-id)
    {:state (cond-> (assoc-in st [:rooms room-id :cwd] cwd)
              clear? (update-in [:rooms room-id :ext] dissoc :worktree))}))

(defn- resumed-worktree-cwd
  "Chained onto :session/resumed to refine the cwd for a removed worktree.
   The core :session/resumed handler already cds the room into the session's
   own cwd when that directory still exists (covering cross-project resumes
   and live sibling worktrees). This handler only covers the leftover case:
   the session was recorded in a sibling worktree that has since been removed
   but is still listed by git as prunable — land in the repo's main working
   tree instead of failing on the gone path. Emits the :cwd/change effect —
   the same validated path the /cd command uses."
  [st {:keys [room-id summary]}]
  (when-let [room (state/get-room st room-id)]
    (let [room-cwd (:cwd room)
          sess-cwd (:cwd summary)]
      (when (and sess-cwd room-cwd (not= sess-cwd room-cwd)
                 (not (.existsSync fs sess-cwd))
                 (some #(= sess-cwd (:path %)) (git/worktrees room-cwd)))
        (let [target (git/main-worktree-root room-cwd)]
          (when (and target (not= target room-cwd))
            {:effects [[:cwd/change {:room-id room-id :path target}]]}))))))

(defn- worktree-created
  "The worktree exists on disk — point the room at it, remember its metadata,
   and (when a prompt was given) launch the agent there with the heads-up."
  [st {:keys [room-id path branch base main-root prompt]}]
  (when (state/get-room st room-id)
    (let [guidance (agents-worktree-guidance main-root)]
      {:state (-> st
                  (assoc-in [:rooms room-id :cwd] path)
                  (assoc-in [:rooms room-id :ext :worktree]
                            {:path path :branch branch :base base :main-root main-root}))
       :effects (cond-> [[:app/dispatch {:type :ui/status :room-id room-id
                                         :text (str "Worktree ready: " branch " → " path)}]]
                  (seq prompt)
                  (conj [:app/dispatch
                         {:type :prompt/submit :room-id room-id
                          :text (build-worktree-prompt
                                 {:path path :branch branch :base base
                                  :main-root main-root :guidance guidance}
                                 prompt)}]))})))

;; ── Command (pure) ────────────────────────────────────────────────────────────

(defn- cmd-worktree
  "Route subcommands; anything else is treated as the create prompt."
  [_st {:keys [room-id args]}]
  (let [args (some-> args str/trim not-empty)
        head (some-> args (str/split #"\s+") first)]
    (case head
      "merge"         {:effects [[:worktree/merge {:room-id room-id}]]}
      "list"          {:effects [[:worktree/list {:room-id room-id}]]}
      ("remove" "rm") {:effects [[:worktree/remove {:room-id room-id}]]}
      {:effects [[:worktree/create {:room-id room-id :prompt args}]]})))

;; ── Effects (impure) ──────────────────────────────────────────────────────────

(defn- status! [dispatch! room-id text]
  (dispatch! {:type :ui/status :room-id room-id :text text}))

(defn- ask-confirm
  "Raise a y/n dialog, resolving to a boolean. Without an ask! (headless /
   client mirror) resolve false so nothing destructive happens silently."
  [ask! dispatch! get-state room-id message]
  (if ask!
    (ask! {:dispatch! dispatch! :state (get-state)}
          {:room-id room-id :dialog {:type :confirm :message message}})
    (js/Promise.resolve false)))

(defn- create-fx
  [_ask! {:keys [dispatch! state]} {:keys [room-id prompt]}]
  (let [cwd  (get-in state [:rooms room-id :cwd])
        root (git/repo-root cwd)]
    (if-not root
      (status! dispatch! room-id (str "Not a git repository: " cwd))
      (let [base   (or (git/current-branch cwd) "HEAD")
            branch (uniq-branch root (gen-branch-name prompt))
            path   (worktree-path root branch)
            res    (git/add-worktree root path branch base)]
        (if-not (git/ok? res)
          (status! dispatch! room-id (str "git worktree add failed: " (:err res)))
          (dispatch! {:type :worktree/created :room-id room-id
                      :path path :branch branch :base base :main-root root
                      :prompt prompt}))))))

(defn- remove-worktree!
  "Remove the worktree at `path` and clear it from the room. Deletes the
   branch when it's fully merged; otherwise keeps it and says so."
  [dispatch! room-id main-root path branch]
  (if (nil? path)
    (status! dispatch! room-id "Could not locate the worktree path to remove.")
    (let [rm (git/remove-worktree main-root path true)]
      (if-not (git/ok? rm)
        (status! dispatch! room-id (str "Failed to remove worktree: " (:err rm)))
        (let [del (git/delete-branch main-root branch false)]
          (dispatch! {:type :worktree/switch :room-id room-id :cwd main-root :clear? true})
          (status! dispatch! room-id
                   (if (git/ok? del)
                     (str "Removed worktree and branch " branch ".")
                     (str "Removed worktree " path ". Branch " branch
                          " kept (not fully merged)."))))))))

(defn- worktree-context
  "Resolve {:cwd :branch :main-root :here-root :path} for a merge/remove,
   preferring tracked metadata and falling back to live git."
  [state room-id]
  (let [room (get-in state [:rooms room-id])
        cwd  (:cwd room)
        wt   (get-in room [:ext :worktree])]
    {:cwd       cwd
     :branch    (or (:branch wt) (git/current-branch cwd))
     :main-root (or (:main-root wt) (git/main-worktree-root cwd))
     :here-root (git/repo-root cwd)
     :path      (or (:path wt)
                    (some (fn [w] (when (and (:branch wt)
                                             (= (:branch wt) (:branch w)))
                                    (:path w)))
                          (git/worktrees cwd)))}))

(defn- merge-fx
  [ask! {:keys [dispatch! get-state state]} {:keys [room-id]}]
  (let [{:keys [cwd branch main-root here-root path]} (worktree-context state room-id)]
    (cond
      (or (nil? branch) (nil? main-root))
      (status! dispatch! room-id "Could not determine the worktree branch or main repo.")

      (= here-root main-root)
      (status! dispatch! room-id "Not inside a worktree (you're in the main working tree).")

      (not (git/clean? cwd))
      (status! dispatch! room-id
               (str "Worktree has uncommitted changes — commit or stash them "
                    "first (they won't be part of the merge)."))

      (nil? (git/current-branch main-root))
      (status! dispatch! room-id
               (str "Main tree is in a detached HEAD — check out a branch in "
                    main-root " before merging."))

      :else
      (let [main-branch (git/current-branch main-root)
            m           (git/merge-branch main-root cwd branch main-branch)]
        (if-not (git/ok? m)
          ;; Rebase couldn't apply cleanly; it was aborted, so the worktree is
          ;; untouched and the room stays put for the user to resolve.
          (status! dispatch! room-id
                   (str "Rebasing " branch " onto " main-branch " failed — the "
                        "rebase was aborted. Resolve the conflicts in the "
                        "worktree (" cwd ") and try again.\n" (:err m)))
          (do
            ;; Linear history: main fast-forwarded to the rebased branch.
            (dispatch! {:type :worktree/switch :room-id room-id :cwd main-root})
            (status! dispatch! room-id
                     (str "Rebased " branch " onto " main-branch
                          " and fast-forwarded the main tree (no merge commit)."))
            (-> (ask-confirm ask! dispatch! get-state room-id
                             (str "Remove the worktree and delete branch '" branch "'?"))
                (.then (fn [yes?]
                         (if yes?
                           (remove-worktree! dispatch! room-id main-root path branch)
                           (status! dispatch! room-id
                                    (str "Kept worktree at " path "."))))))))))))

(defn- remove-fx
  [ask! {:keys [dispatch! get-state state]} {:keys [room-id]}]
  (let [{:keys [branch main-root here-root path]} (worktree-context state room-id)]
    (cond
      (or (nil? branch) (nil? main-root))
      (status! dispatch! room-id "Could not determine the worktree.")

      (= here-root main-root)
      (status! dispatch! room-id "Not inside a worktree (you're in the main working tree).")

      :else
      (-> (ask-confirm ask! dispatch! get-state room-id
                       (str "Remove worktree " path " and delete branch '" branch "'?"))
          (.then (fn [yes?]
                   (if yes?
                     (do (dispatch! {:type :worktree/switch :room-id room-id :cwd main-root})
                         (remove-worktree! dispatch! room-id main-root path branch))
                     (status! dispatch! room-id "Cancelled."))))))))

(defn- list-fx
  [{:keys [dispatch! state]} {:keys [room-id]}]
  (let [cwd (get-in state [:rooms room-id :cwd])
        wts (git/worktrees cwd)
        txt (if (seq wts)
              (str/join "\n"
                        (map (fn [w]
                               (str (when (:branch w) (str "[" (:branch w) "] "))
                                    (:path w)))
                             wts))
              "No worktrees.")]
    (status! dispatch! room-id txt)))

;; ── Extension ─────────────────────────────────────────────────────────────────

(defn create
  "Build the worktree extension. `ask!` (from ext.core/create-dialogs) powers
   the removal confirm; nil (client mirror / headless) makes it a no-op that
   never removes without a yes."
  [{:keys [ask!]}]
  {:id       :worktree
   :commands [{:name "worktree"
               :description "Create a git worktree and work in it (merge|list|remove)"
               :handler cmd-worktree
               :subcommands [{:name "merge"  :description "Merge the worktree branch into the main tree, then remove it"}
                             {:name "list"   :description "List the repo's worktrees"}
                             {:name "remove" :description "Remove the current worktree and cd back"}]}]
   :handlers {:worktree/created worktree-created
              :worktree/switch  worktree-switch
              :session/resumed  resumed-worktree-cwd}
   :fx       {:worktree/create (partial create-fx ask!)
              :worktree/merge  (partial merge-fx ask!)
              :worktree/remove (partial remove-fx ask!)
              :worktree/list   list-fx}})
