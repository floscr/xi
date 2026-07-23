(ns xi.ext.gtd.web
  "Browser half of the GTD extension — composed by xi.web.core (never loaded
   by the node builds). Contributes the /gtd route + page, the client-side
   :gtd/* handlers, the pending-task tap and the GTD nav entries via the
   ext/compose web seams (:routes/:pages/:nav-items/:taps)."
  (:require [clojure.string :as str]
            [ui.icon :as icon]
            [xi.web.views :as views]))

;; ── Handlers (client-local, never mirrored) ──────────────────────────────────

(defn- web-list-result
  "Store the GTD task list returned by the server."
  [st {:keys [tasks]}]
  {:state (assoc st :web/gtd-tasks tasks :web/gtd-loading? false)})

(defn- web-start-task
  "Click a GTD task: create a new room with the task's cwd, stash the
   task as pending-gtd so it fires :gtd/start-task after :room/joined."
  [st {:keys [task-id title cwd]}]
  {:state   (-> st
                (assoc :web/route {:page :chat})
                (assoc :web/pending-gtd {:task-id task-id :title title :cwd cwd})
                (assoc :web/timeline-window nil))
   :effects [[:ws/send {:type :room/join :target "new" :cwd cwd}]]})

(defn- on-navigate
  "Chained after the base router navigate: sync the file/task drill-down
   from the route and fetch the task list when entering GTD without
   cached data."
  [st {:keys [page file task-id]}]
  (when (= page :gtd)
    {:state   (assoc st :web/gtd-file file :web/gtd-task-id task-id)
     :effects (when (empty? (:web/gtd-tasks st))
                [[:app/dispatch {:type :gtd/web-list}]])}))

(def ^:private handlers
  {:route/navigate on-navigate
   :gtd/web-list          (fn [st ev]
                            {:state (assoc st :web/gtd-loading? true)
                             :effects [[:ws/send (dissoc ev :event/id :event/ts)]]})
   :gtd/web-list-result   web-list-result
   :gtd/web-start-task    web-start-task
   :gtd/select-file       (fn [_st {:keys [file]}]
                            {:effects [[:app/dispatch {:type :route/navigate :page :gtd :file file}]]})
   :gtd/back-to-files     (fn [_st _]
                            {:effects [[:app/dispatch {:type :route/navigate :page :gtd}]]})
   :gtd/select-task       (fn [_st {:keys [file task-id]}]
                            {:effects [[:app/dispatch {:type :route/navigate :page :gtd :file file :task-id task-id}]]})
   :gtd/back-to-tasks     (fn [_st {:keys [file]}]
                            {:effects [[:app/dispatch {:type :route/navigate :page :gtd :file file}]]})
   :gtd/context-menu      (fn [st {:keys [task x y]}]
                            {:state (assoc st :web/gtd-context-menu {:task task :x x :y y})})
   :gtd/context-menu-close (fn [st _] {:state (dissoc st :web/gtd-context-menu)})
   :gtd/web-task-action   (fn [st {:keys [task-id action] :as ev}]
                            (let [tasks (:web/gtd-tasks st)
                                  tasks' (case action
                                           ;; Both done and archive remove from visible list
                                           ("done" "archive")
                                           (vec (remove #(= (:id %) task-id) tasks))
                                           tasks)]
                              {:state (-> st
                                          (dissoc :web/gtd-context-menu)
                                          (assoc :web/gtd-tasks tasks'))
                               :effects [[:ws/send (dissoc ev :event/id :event/ts)]]}))
   :gtd/web-task-action-error (fn [st _]
                                ;; Server failed — re-fetch authoritative list
                                {:state st
                                 :effects [[:ws/send {:type :gtd/web-list}]]})
   :gtd/start-task        (fn [_st {:keys [remote?] :as ev}]
                            (when-not remote?
                              {:effects [[:ws/send (dissoc ev :event/id :event/ts)]]}))
   :gtd/clear-pending     (fn [st _] {:state (dissoc st :web/pending-gtd)})})

;; ── Tap ──────────────────────────────────────────────────────────────────────

(defn- pending-gtd-tap
  "After room join, if there's a pending GTD task, dispatch :gtd/start-task
   to the server which handles activation + prompt submission."
  [dispatch!]
  (fn [event state]
    (when (and (= :room/joined (:type event))
               (:web/pending-gtd state))
      (let [{:keys [task-id title]} (:web/pending-gtd state)
            room-id (:active-room state)]
        (dispatch! {:type :gtd/start-task :room-id room-id
                    :task-id task-id :title title})
        (dispatch! {:type :gtd/clear-pending})))))

;; ── Views ────────────────────────────────────────────────────────────────────

(defn- gtd-context-menu [dispatch! {:keys [task x y]}]
  [:div {:class ["gtd-context-backdrop"]
         :on {:click (fn [_] (dispatch! {:type :gtd/context-menu-close}))}}
   [:div {:class ["gtd-context-menu"]
          :style {:top (str y "px") :left (str x "px")}}
    [:button {:class ["gtd-context-item"]
              :on {:click (fn [e]
                            (.stopPropagation e)
                            (dispatch! {:type :gtd/web-task-action
                                        :task-id (:id task) :action "done"}))}}
     (icon/icon {:icon-name :circle-check :size :sm})
     [:span "Mark Done"]]
    [:button {:class ["gtd-context-item" "gtd-context-item--danger"]
              :on {:click (fn [e]
                            (.stopPropagation e)
                            (dispatch! {:type :gtd/web-task-action
                                        :task-id (:id task) :action "archive"}))}}
     (icon/icon {:icon-name :trash :size :sm})
     [:span "Archive"]]]])

(def ^:private long-press-state (atom nil))

(defn- touch-start [dispatch! task e]
  (let [touch (aget (.-touches e) 0)
        x     (.-clientX touch)
        y     (.-clientY touch)
        timer (js/setTimeout
               (fn []
                 (reset! long-press-state :fired)
                 (dispatch! {:type :gtd/context-menu
                             :task task :x x :y y}))
               500)]
    (reset! long-press-state {:timer timer})))

(defn- touch-end [_e]
  (when-let [st @long-press-state]
    (when (map? st) (js/clearTimeout (:timer st))))
  ;; If long-press fired, keep :fired so the subsequent click is suppressed.
  ;; Clear it on next tick after click has been processed.
  (if (= :fired @long-press-state)
    (js/setTimeout #(reset! long-press-state nil) 0)
    (reset! long-press-state nil)))

(defn- touch-move [_e]
  (when-let [st @long-press-state]
    (when (map? st) (js/clearTimeout (:timer st))))
  (reset! long-press-state nil))

(defn- gtd-task-card [dispatch! {:keys [id title todo-state file cwd tags] :as task}]
  [:div {:class ["project-card" "gtd-task-card"]
         :replicant/key (str "gtd-" id)
         :on {:click (fn [_]
                       (when-not (= :fired @long-press-state)
                         (dispatch! {:type :gtd/select-task
                                     :file file :task-id id})))
              :contextmenu (fn [e]
                             (.preventDefault e)
                             (dispatch! {:type :gtd/context-menu
                                         :task task
                                         :x (.-clientX e)
                                         :y (.-clientY e)}))
              :touchstart (fn [e] (touch-start dispatch! task e))
              :touchend touch-end
              :touchmove touch-move}}
   [:div {:class ["project-card-icon"]}
    (case todo-state
      "ACTIVE"  [:div {:class ["active-dot"]}]
      "WAITING" (icon/icon {:icon-name :pause :size :sm})
      (icon/icon {:icon-name :circle-check :size :sm}))]
   [:div {:class ["project-card-info"]}
    [:span {:class ["project-card-name"]} title]
    [:span {:class ["project-card-path"]}
     (str (or todo-state "TODO")
          (when cwd (str " \u00B7 " (views/shorten-path cwd)))
          (when (and (string? tags) (seq tags)) (str " \u00B7 " tags)))]]])

(defn- render-org-body
  "Render pre-built HTML body from the server."
  [html-str]
  (when (and (string? html-str) (seq html-str))
    [:div {:class ["org-body"] :innerHTML html-str}]))

(defn- gtd-task-detail [dispatch! state task]
  (let [{:keys [title todo-state html-body file cwd tags]} task]
    [:div {:class ["container"] :replicant/key "gtd-detail"}
     [:div {:class ["topbar"]}
      [:button {:class ["icon-btn"]
                :on {:click (fn [_]
                              (dispatch! {:type :nav/back :fallback {:page :gtd :file file}}))}}
       (icon/icon {:icon-name :arrow-left :size :md})]
      [:div {:class ["topbar-title"]} "Task"]
      (views/overflow-menu dispatch! state)]
     [:div {:class ["gtd-detail-scroll"]}
      [:div {:class ["gtd-detail"]}
       [:h2 {:class ["gtd-detail-title"]} title]
       [:div {:class ["gtd-detail-meta"]}
        (when todo-state
          [:span {:class ["gtd-detail-badge"
                          (case todo-state
                            "ACTIVE" "gtd-detail-badge--active"
                            "WAITING" "gtd-detail-badge--waiting"
                            "gtd-detail-badge--default")]}
           todo-state])
        (when file [:span {:class ["gtd-detail-file"]} file])
        (when (and (string? tags) (seq tags))
          (for [tag (.split tags " ")]
            [:span {:class ["gtd-detail-tag"]} tag]))]
       (when html-body
         (render-org-body html-body))
       (when cwd
         [:div {:class ["gtd-detail-actions"]}
          [:button {:class ["btn" "btn-primary"]
                    :on {:click (fn [_]
                                  (dispatch! {:type :gtd/web-start-task
                                              :task-id (:id task)
                                              :title title
                                              :cwd cwd}))}}
           (icon/icon {:icon-name :play :size :sm})
           [:span "Launch Agent"]]])]]]))

(def ^:private recent-limit
  "How many tasks the default /gtd list shows before the View-all button."
  5)

(defn- by-recent
  "Tasks sorted by creation date, newest first. Missing dates sort last."
  [tasks]
  (sort-by (fn [t] (or (:created-date t) "")) #(compare %2 %1) tasks))

(defn- gtd-group
  "One file's section on the main view: a clickable header (showing how many
   more tasks are hidden) and up to recent-limit tasks, newest first. The
   header opens the file's full detail view."
  [dispatch! file-name file-tasks]
  (let [more (- (count file-tasks) recent-limit)
        open (fn [_] (dispatch! {:type :gtd/select-file :file file-name}))]
    [:div {:class ["section"] :replicant/key (str "gtd-group-" file-name)}
     [:button {:class ["section-title" "gtd-group-header"] :on {:click open}}
      [:span {:class ["gtd-group-name"]}
       [:span (or file-name "Uncategorized")]
       (when (pos? more) [:span {:class ["gtd-group-more"]} (str "(" more " more)")])]
      (icon/icon {:icon-name :chevron-right :size :sm})]
     [:div {:class ["project-list"]}
      (for [t (take recent-limit file-tasks)]
        (gtd-task-card dispatch! t))]]))

(defn- gtd-view [state dispatch!]
  (let [tasks         (:web/gtd-tasks state)
        loading?      (:web/gtd-loading? state)
        selected-file (:web/gtd-file state)
        task-id       (:web/gtd-task-id state)
        ctx-menu      (:web/gtd-context-menu state)
        ;; Look up selected task by id
        selected-task (when task-id
                        (some #(when (= (:id %) task-id) %) tasks))
        recent        (by-recent tasks)
        grouped       (when tasks
                        (->> recent
                             (group-by :file)
                             ;; groups ordered by their newest task, newest first
                             (sort-by (fn [[_ ts]] (or (:created-date (first ts)) ""))
                                      #(compare %2 %1))))]
    (cond
      ;; Task detail view
      selected-task
      (gtd-task-detail dispatch! state selected-task)

      ;; Task list / file list
      :else
      [:div {:class ["container"] :replicant/key "gtd"}
       [:div {:class ["topbar"]}
        (views/nav-group dispatch! (fn [_]
                                     (dispatch! {:type :nav/back
                                                 :fallback (if selected-file
                                                             {:page :gtd}
                                                             {:page :home})})))
        [:div {:class ["topbar-title"]}
         (if selected-file selected-file "Tasks")]
        [:button {:class ["icon-btn"]
                  :on {:click (fn [_] (dispatch! {:type :gtd/web-list}))}}
         (icon/icon {:icon-name :refresh :size :md})]
        (views/overflow-menu dispatch! state)]
       [:div {:class ["home"]}
        (cond
          ;; Only block on the spinner when we have nothing cached to show.
          (and loading? (empty? tasks))
          [:div {:class ["empty-state"]} (views/spinner) [:p "Loading tasks..."]]

          (empty? tasks)
          [:div {:class ["empty-state"]} [:p "No open tasks."]]

          ;; File detail view: every task in the selected file, newest first.
          selected-file
          (let [file-tasks (get (into {} grouped) selected-file)]
            (if (seq file-tasks)
              [:div {:class ["project-list"]}
               (for [t file-tasks]
                 (gtd-task-card dispatch! t))]
              [:div {:class ["empty-state"]} [:p (str "No tasks in " selected-file)]]))

          ;; Main view: tasks grouped by file, max recent-limit per group.
          :else
          (for [[file-name file-tasks] grouped]
            (gtd-group dispatch! file-name file-tasks)))]
       (when ctx-menu
         (gtd-context-menu dispatch! ctx-menu))])))

;; ── Route ────────────────────────────────────────────────────────────────────

(defn- parse-route
  "/gtd[/:file][/:task-id] — the last segment that looks like a UUID is a
   task-id; everything before it is the (URL-encoded) file name."
  [segments]
  (let [last-seg  (last segments)
        task-id?  (and last-seg (re-find #"^[0-9a-f]{8}-" last-seg))
        task-id   (when task-id? last-seg)
        file-segs (if task-id? (butlast segments) segments)
        file      (when (seq file-segs)
                    (js/decodeURIComponent (str/join "/" file-segs)))]
    (cond-> {:page :gtd}
      file    (assoc :file file)
      task-id (assoc :task-id task-id))))

(defn- route-path [{:keys [file task-id]}]
  (cond-> "/gtd"
    file    (str "/" (js/encodeURIComponent file))
    task-id (str "/" task-id)))

;; ── Extension ────────────────────────────────────────────────────────────────

(def extension
  {:id :gtd
   :handlers handlers
   :routes {"gtd" {:parse parse-route
                   :path {:gtd route-path}
                   :roomless-pages #{:gtd}}}
   :pages {:gtd gtd-view}
   :taps [pending-gtd-tap]
   :nav-items [{:menu :home-topbar :label "GTD Tasks" :icon :list
                :event {:type :route/navigate :page :gtd}}
               {:menu :sidebar :label "GTD Tasks" :icon :list
                :event {:type :route/navigate :page :gtd}}
               {:menu :palette :label "GTD Tasks" :icon :list
                :event {:type :route/navigate :page :gtd}}]})
