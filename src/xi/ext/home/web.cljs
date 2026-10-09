(ns xi.ext.home.web
  "The dashboard's built-in cards (xi.web.dashboard): the chats that need you
   or are running, recent chats and recent projects. Plain `:dashboard-cards`
   like any extension's, drawn with xi.web.views' session and project rows."
  (:require [xi.session.sidebar :as sb]
            [xi.web.views :as views]))

(def ^:private max-rows 6)

(defn- session-cards [state]
  (let [{:keys [recent hidden earlier]} (sb/sidebar-session-groups state)]
    (concat recent hidden earlier)))

(defn- active [state]
  (take max-rows (sb/active-cards (session-cards state))))

(defn- recent
  "The last visited chats (the sidebar's Recent, then Earlier; not the ones
   the user hid) without those the Active card lists."
  [state]
  (let [shown (set (keep :session-id (active state)))
        {:keys [recent earlier]} (sb/sidebar-session-groups state)]
    (->> (concat recent earlier)
         (remove #(shown (:session-id %)))
         (take max-rows))))

(defn- session-list [state dispatch! cards]
  [:div {:class ["project-list" "dash-list"]}
   (for [c (views/with-projects cards)]
     (views/session-card dispatch! state c))])

(defn- projects-list [state dispatch!]
  (let [dirty (:web/project-dirty state)]
    [:div {:class ["project-list" "dash-list"]}
     (for [p (views/recent-projects state)]
       (views/project-dir-card dispatch! p (contains? dirty p) false))]))

(def extension
  {:id :home
   :dashboard-cards
   [{:id          :home/active
     :title       "Active"
     :icon        :zap
     :description "Chats waiting on you, failed, with unread replies or running"
     :order       10
     :when        (fn [st] (seq (active st)))
     :render      (fn [st dispatch!] (session-list st dispatch! (active st)))}
    {:id          :home/recent
     :title       "Recent chats"
     :icon        :clock
     :description "The chats you visited last"
     :order       20
     :when        (fn [st] (seq (recent st)))
     :render      (fn [st dispatch!] (session-list st dispatch! (recent st)))
     :more        {:label "All sessions" :event {:type :route/navigate :page :home :dir :all}}}
    {:id          :home/projects
     :title       "Projects"
     :icon        :folder
     :description "Recently used projects; + starts a chat in one"
     :order       30
     :when        (fn [st] (seq (views/recent-projects st)))
     :render      projects-list
     :more        {:label "All projects" :event {:type :route/navigate :page :home :dir :projects}}}]
   :nav-items
   [{:menu :overflow :mode :dashboard :label "Customize dashboard" :icon :settings
     :event {:type :dashboard/toggle-customize}}]})
