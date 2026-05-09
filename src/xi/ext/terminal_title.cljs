(ns xi.ext.terminal-title
  "Set terminal title from session name via ANSI escape."
  (:require [xi.tui.terminal :as term]))

(defn- set-title [title]
  (term/write! (str "\033]0;" title "\007")))

(defn- on-turn-end [{:keys [session]}]
  (when-let [name (:name session)]
    (set-title (str "Xi: " name))))

(defn- on-session-start [{:keys [cwd]}]
  (let [dir-name (.split cwd "/")]
    (set-title (str "Xi: " (last dir-name)))))

(def extension
  {:name "terminal-title"
   :hooks {:session-start on-session-start
           :turn-end on-turn-end}})
