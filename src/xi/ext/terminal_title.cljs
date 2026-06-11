(ns xi.ext.terminal-title
  "Set terminal title from session name / cwd via ANSI escape.

   Server extension: in standalone mode the ANSI write reaches the terminal
   directly; in client+server mode on the same machine the server process
   sets the title (best-effort — a headless server ignores it)."
  (:require [xi.core.state :as state]
            [xi.tui.terminal :as term]))

(defn- set-title-fx
  "Write the ANSI OSC title-set escape."
  [_ {:keys [title]}]
  (term/write! (str "\033]0;" title "\007")))

(defn- dir-name [cwd]
  (when cwd (last (.split cwd "/"))))

(defn- on-room-create
  "Initial title: directory name from the room's cwd."
  [st {:keys [room-id]}]
  (when-let [room (state/get-room st room-id)]
    (when-let [d (dir-name (:cwd room))]
      {:effects [[:terminal/set-title {:title (str "Xi: " d)}]]})))

(defn- on-turn-end
  "After an agent turn, update the title with the session name if known."
  [st {:keys [room-id]}]
  (when-let [room (state/get-room st room-id)]
    (when-let [name (get-in room [:session :name])]
      {:effects [[:terminal/set-title {:title (str "Xi: " name)}]]})))

(defn- on-session-created
  "New session: reset title to directory name."
  [st {:keys [room-id]}]
  (when-let [room (state/get-room st room-id)]
    (when-let [d (dir-name (:cwd room))]
      {:effects [[:terminal/set-title {:title (str "Xi: " d)}]]})))

(def extension
  {:id       :terminal-title
   :handlers {:room/create      on-room-create
              :agent/turn-end   on-turn-end
              :session/created  on-session-created}
   :fx       {:terminal/set-title set-title-fx}})
