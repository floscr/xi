(ns xi.ext.user.web
  "The dashboard card for user extensions that failed to load or aren't
   enabled: the server's loader report (xi.ext.user/report), which arrives on
   :lobby/state as :user-ext/report. Hidden while there is nothing to fix."
  (:require [clojure.string :as str]))

(defn- problems? [state]
  (let [{:keys [rejected skipped]} (:user-ext/report state)]
    (or (seq rejected) (seq skipped))))

(defn- basename [path]
  (last (str/split (str path) #"/")))

(defn- row [k title detail]
  [:div {:class ["ext-report-row"] :replicant/key k}
   [:span {:class ["ext-report-file"]} title]
   [:span {:class ["ext-report-detail"]} detail]])

(defn- report-card [state _dispatch!]
  (let [{:keys [rejected skipped]} (:user-ext/report state)]
    [:div {:class ["ext-report"]}
     (for [{:keys [file error]} rejected]
       (row file (basename file) [:span {:class ["ext-report-error"]} error]))
     (for [file skipped]
       (row file file "Not enabled: list it under :extensions in config.edn"))]))

(def extension
  {:id :user-extensions
   :dashboard-cards
   [{:id          :user-extensions/report
     :title       "Extensions"
     :icon        :alert-triangle
     :description "User extensions that were rejected or aren't enabled"
     :order       5
     :size        :wide
     :when        problems?
     :render      report-card}]})
