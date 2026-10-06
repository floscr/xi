(ns xi.ext.projects
  "Project path completion — /project opens a fuzzy picker of project paths
   (xi.projects: config.edn `:projects` plus remembered git repos).
   Selecting inserts the path into the editor. Tab drills into git-tracked
   files; Shift+Tab returns to the project list.

   Every room creation and `/cd` records the git repo it happens in as
   visited (`xi.projects/visit!`), so the repo sorts first and — when no
   configured project covers it — is remembered. There is no command to add a
   project: working in a repo is what adds it.

   Also serves the web client's roomless projects page: :projects/web-list
   returns the project directories, :projects/web-sessions the saved
   sessions for one project CWD."
  (:require [clojure.string :as str]
            [xi.core.state :as state]
            [xi.projects :as projects]
            [xi.session :as session]
            [xi.user-config :as user-config]
            [xi.user-state.store :as user-store]))

(defn- project-paths
  "The project directories for this machine: the config's `:projects` spec
   (defaults, with the problem on stderr, when the file is invalid) plus the
   state file's remembered repos and visit order."
  []
  (projects/list-projects! (user-config/projects-spec)))

(defn- shorten-path
  "Replace $HOME prefix with ~."
  [path]
  (let [home (aget js/process.env "HOME")]
    (if (and home (str/starts-with? path home))
      (str "~" (subs path (count home)))
      path)))

(defn- open-picker
  "Open the project picker (defers to effect for I/O)."
  [_st {:keys [room-id]}]
  {:effects [[:project/open-picker {:room-id room-id}]]})

(defn- open-picker-fx
  "List the projects and open a menu of paths."
  [{:keys [dispatch!]} {:keys [room-id]}]
  (try
    (let [dirs (project-paths)]
      (if (seq dirs)
        (let [items (mapv (fn [p]
                            {:label (shorten-path p)
                             :description p
                             :event {:type :project/insert
                                     :room-id room-id
                                     :path p}})
                          dirs)]
          (dispatch! {:type :ui/menu-push :room-id room-id
                      :menu {:id :projects :prompt "project> " :items items
                             :key-bindings [{:key "\t"
                                             :selected? true
                                             :event {:type :project/drill}}]}}))
        (dispatch! {:type :history/append :room-id room-id
                    :entry {:kind :status :text "No projects found."}})))
    (catch :default err
      (dispatch! {:type :history/append :room-id room-id
                  :entry {:kind :status
                          :text (str "project picker failed: " (.-message err))}}))))

;; ── Visit tracking ──────────────────────────────────

(defn- visit-fx [_ctx {:keys [cwd]}]
  (try (projects/visit! cwd)
       (catch :default err
         (js/console.error "[projects] visit failed:" (.-message err)))))

(defn- on-room-create
  "A room opened in a directory — remember its repo."
  [st {:keys [room-id]}]
  (when-let [cwd (:cwd (state/get-room st room-id))]
    {:effects [[:project/visit {:cwd cwd}]]}))

(defn- on-cwd-changed
  "`/cd` (or a worktree switch) into a directory — remember its repo."
  [_st {:keys [cwd]}]
  (when cwd
    {:effects [[:project/visit {:cwd cwd}]]}))

(defn- drill-handler
  "Tab on a project — drill into its git-tracked files."
  [_st {:keys [room-id selected]}]
  (when-let [project-path (:description selected)]
    {:effects [[:project/open-files {:room-id room-id :path project-path}]]}))

(defn- open-files-fx
  "Fetch git-tracked files for a project and open a file picker menu.
   Shift+Tab goes back to the project list."
  [{:keys [dispatch!]} {:keys [room-id path]}]
  (let [proc (js/Bun.spawn #js ["git" "-C" path "ls-files"]
                            #js {:stdout "pipe" :stderr "pipe"})]
    (-> (.text (.-stdout proc))
        (.then (fn [stdout]
                 (let [files (->> (str/split-lines (str/trim stdout))
                                  (remove empty?))]
                   (if (seq files)
                     (let [items (mapv (fn [f]
                                         {:label f
                                          :event {:type :project/insert
                                                  :room-id room-id
                                                  :path (str path "/" f)}})
                                       files)]
                       (dispatch! {:type :ui/menu-open :room-id room-id
                                   :menu {:id :project-files
                                          :prompt (str (shorten-path path) " > ")
                                          :items items
                                          :key-bindings [{:key (str (char 27) "[Z")
                                                          :event {:type :project/open}}]}}))
                     ;; No git files — just insert the project path
                     (dispatch! {:type :project/insert :room-id room-id :path path})))))
        (.catch (fn [_]
                  ;; Not a git repo or error — insert the project path
                  (dispatch! {:type :project/insert :room-id room-id :path path}))))))

(defn- insert-handler
  "Menu-selected a project path → insert it into the editor."
  [_st {:keys [room-id path]}]
  {:effects [[:editor/insert-text {:text path}]]})

;; ── Web projects page (roomless) ───────────────────────────────────────────

(defn- web-list
  "Roomless: return the list of project directories."
  [_st {:keys [client-id]}]
  {:effects [[:projects/web-list-reply {:client-id client-id}]]})

(defn- web-sessions
  "Roomless: return sessions for a specific CWD, starred as the sender has them."
  [st {:keys [client-id cwd] :as ev}]
  {:effects [[:projects/web-sessions-reply {:client-id client-id :cwd cwd
                                            :user (state/event-user st ev)}]]})

(defn- git-dirty?
  "Resolve to true when the git working tree at `dir` has uncommitted changes.
   Non-git dirs or errors resolve to false."
  [dir]
  (let [proc (js/Bun.spawn #js ["git" "-C" dir "status" "--porcelain"]
                           #js {:stdout "pipe" :stderr "pipe"})]
    (-> (.text (.-stdout proc))
        (.then (fn [out] (not (str/blank? out))))
        (.catch (fn [_] false)))))

(defn- server-fx
  "WS-server fx: project list + per-project sessions, replied to the
   requesting client."
  [{:keys [send!]}]
  {;; Project list, plus the subset whose git working tree is dirty (drives
   ;; the status dot).
   :projects/web-list-reply
   (fn [_ {:keys [client-id]}]
     (let [dirs (try (project-paths)
                     (catch :default err
                       (js/console.error "[projects] listing failed:" (.-message err))
                       []))]
       (-> (js/Promise.all (clj->js (mapv git-dirty? dirs)))
           (.then (fn [flags]
                    (let [dirty (into #{} (keep-indexed
                                           (fn [i d] (when (aget flags i) d))
                                           dirs))]
                      (send! client-id {:type :projects/web-list-result
                                        :dirs dirs
                                        :dirty dirty}))))
           (.catch (fn [_]
                     (send! client-id {:type :projects/web-list-result
                                       :dirs dirs}))))))

   ;; Sessions for a specific project CWD.
   :projects/web-sessions-reply
   (fn [{:keys [state]} {:keys [client-id cwd user]}]
     (let [;; Provider session ids held by live rooms must be hidden from
           ;; the saved-session list to avoid a duplicate card during the
           ;; first agent turn before Xi's own :session/sync has run.
           live-pids (into #{}
                           (keep (fn [[_ room]]
                                   (get-in room [:session :provider-session-id])))
                           (:rooms state))
           sessions (->> (session/list-sessions cwd)
                         (remove #(contains? live-pids (:session-id %))))
           sessions (session/annotate-favorites
                     sessions (user-store/favorite-ids user))
           sessions (mapv #(select-keys % [:session-id :name :cwd
                                           :last-accessed :timestamp :source :favorite?])
                          sessions)]
       (send! client-id {:type :projects/web-sessions-result
                         :cwd cwd
                         :sessions sessions})))})

(def extension
  {:id       :projects
   :commands [{:name "project"
               :description "Pick a project path"
               :handler open-picker}]
   :handlers {:project/insert insert-handler
              :project/open   open-picker
              :project/drill  drill-handler
              :projects/web-list     web-list
              :projects/web-sessions web-sessions
              :room/create    on-room-create
              :cwd/changed    on-cwd-changed}
   :fx       {:project/open-picker open-picker-fx
              :project/open-files  open-files-fx
              :project/visit       visit-fx}
   :server-fx server-fx
   :roomless-events #{:projects/web-list :projects/web-sessions}
   :keybindings [{:key "alt+p"
                  :event {:type :project/open}}]})
