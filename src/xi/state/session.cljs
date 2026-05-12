(ns xi.state.session
  "Accessor functions for session data within hook state.")

(defn session-title
  "Session name/title, or nil if not yet set."
  [state]
  (get-in state [:session :name]))

(defn session-id
  "Xi session id."
  [state]
  (get-in state [:session :id]))

(defn cli-session-id
  "Claude CLI session id used for resume."
  [state]
  (get-in state [:session :cli-session-id]))

(defn cwd
  "Working directory."
  [state]
  (:cwd state))

(defn model
  "Active model name."
  [state]
  (:model state))
