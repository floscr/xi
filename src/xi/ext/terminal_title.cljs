(ns xi.ext.terminal-title
  "Set terminal title from session name via ANSI escape."
  (:require [xi.state.session :as state.session]
            [xi.tui.terminal :as term]))

(defn- set-title [title]
  (term/write! (str "\033]0;" title "\007")))

(defn- on-turn-end [state]
  (when-let [name (state.session/session-title state)]
    (set-title (str "Xi: " name))))

(defn- on-session-start [state]
  (let [cwd (state.session/cwd state)
        dir-name (when cwd (last (.split cwd "/")))]
    (when dir-name
      (set-title (str "Xi: " dir-name)))))

(def extension
  {:name "terminal-title"
   :hooks {:session-start on-session-start
           :turn-end on-turn-end}})
