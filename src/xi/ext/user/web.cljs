(ns xi.ext.user.web
  "The dashboard card for user extensions that failed to load: the rejected
   part of the server's loader report (xi.ext.user/report), which arrives on
   :lobby/state as :user-ext/report. Hidden while there is nothing to fix."
  (:require [clojure.string :as str]))

(defn- problems? [state]
  (seq (:rejected (:user-ext/report state))))

(defn- basename [path]
  (last (str/split (str path) #"/")))

(defn- row [k title detail]
  [:div {:class ["ext-report-row"] :replicant/key k}
   [:span {:class ["ext-report-file"]} title]
   [:span {:class ["ext-report-detail"]} detail]])

(defn- report-card [state _dispatch!]
  [:div {:class ["ext-report"]}
   (for [{:keys [file error]} (:rejected (:user-ext/report state))]
     (row file (basename file) [:span {:class ["ext-report-error"]} error]))])

(def extension
  {:id :user-extensions
   :dashboard-cards
   [{:id          :user-extensions/report
     :title       "Extensions"
     :icon        :alert-triangle
     :description "User extensions that were rejected"
     :order       5
     :size        :wide
     :when        problems?
     :render      report-card}]})
