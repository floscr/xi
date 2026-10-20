(ns xi.ext.process-manager.registry
  "The process-manager's room-scoped registry, shared by its node half
   (xi.ext.process-manager) and its browser half (xi.ext.process-manager.web),
   so the register/deregister broadcasts build the same
   `[:ext :process-manager :processes]` on every client. Browser-safe: no
   node requires."
  (:require [xi.core.state :as state]))

(def ext-id :process-manager)

(defn get-processes
  "Tracked processes for a room: `[{:pid :command :started :logfile}]`."
  [st room-id]
  (or (:processes (state/room-ext st room-id ext-id)) []))

(defn find-process [st room-id pid]
  (some #(when (= pid (:pid %)) %) (get-processes st room-id)))

(defn log-buffer-id
  "The id of the room buffer following process `pid`'s log (one per pid)."
  [pid]
  (str "proc:" pid))

(defn register-process
  [st {:keys [room-id process]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :ext ext-id :processes]
                       (fnil conj []) process)}))

(defn deregister-process
  [st {:keys [room-id pid]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :ext ext-id :processes]
                       (fn [ps] (vec (remove #(= pid (:pid %)) ps))))}))
