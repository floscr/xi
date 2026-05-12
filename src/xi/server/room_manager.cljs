(ns xi.server.room-manager
  "Manages multiple runtime rooms on the server.
   Each room is an independent runtime with its own event bus, state, and agent loop."
  (:require [xi.runtime :as runtime]))

(defn create-manager
  "Create a room manager. Returns a map with atoms for tracking rooms/clients.
   rooms: atom of {room-id {:runtime rt :clients #{ws...} :created <timestamp>}}
   opts:
     :cwd   - working directory for new runtimes
     :model - model override"
  [opts]
  {:rooms (atom {})
   :opts opts})

(defn- gen-room-id []
  (str "r-" (.toString (js/Date.now) 36) "-"
       (.toString (js/Math.floor (* (js/Math.random) 1000000)) 36)))

(defn create-room!
  "Create a new room with a fresh runtime. Returns room-id.
   room-opts are merged over the manager's default opts (e.g. :cwd from client)."
  [manager & [room-opts]]
  (let [rid (gen-room-id)
        rt (runtime/create! (merge (:opts manager) room-opts))
        room {:runtime rt
              :clients (atom #{})
              :created (js/Date.now)}]
    (swap! (:rooms manager) assoc rid room)
    (js/console.error (str "[rooms] Created room " rid))
    rid))

(defn get-room
  "Get a room by id. Returns nil if not found."
  [manager room-id]
  (get @(:rooms manager) room-id))

(defn latest-room-id
  "Return the id of the most recently created room, or nil."
  [manager]
  (let [rooms @(:rooms manager)]
    (when (seq rooms)
      (key (apply max-key (fn [[_ r]] (:created r)) rooms)))))

(defn join-room!
  "Join a client to a room. Returns the room-id joined.
   mode: :latest (join most recent or create new), :new (always create new), or a room-id string.
   room-opts: per-room overrides (e.g. :cwd) passed through when creating a new room."
  [manager mode & [room-opts]]
  (case mode
    :new
    (create-room! manager room-opts)

    :latest
    (or (latest-room-id manager)
        (create-room! manager room-opts))

    ;; Explicit room-id
    (if (get-room manager mode)
      mode
      (do (js/console.error (str "[rooms] Room " mode " not found, creating new"))
          (create-room! manager room-opts)))))

(defn add-client!
  "Add a client (ws connection) to a room."
  [manager room-id ws-client]
  (when-let [room (get-room manager room-id)]
    (swap! (:clients room) conj ws-client)
    (js/console.error (str "[rooms] Client joined " room-id
                           " (now " (count @(:clients room)) " clients)"))
    room))

(defn remove-client!
  "Remove a client from a room."
  [manager room-id ws-client]
  (when-let [room (get-room manager room-id)]
    (swap! (:clients room) disj ws-client)
    (let [remaining (count @(:clients room))]
      (js/console.error (str "[rooms] Client left " room-id
                             " (" remaining " clients remaining)")))))

(defn list-rooms
  "List all active rooms. Returns vec of {:id :clients :created}."
  [manager]
  (->> @(:rooms manager)
       (mapv (fn [[rid room]]
               {:id rid
                :clients (count @(:clients room))
                :created (:created room)}))
       (sort-by :created)
       reverse
       vec))
