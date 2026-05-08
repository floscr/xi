(ns xi.server.session-manager
  "Manages multiple runtime sessions on the server.
   Each session is an independent runtime with its own event bus, state, and agent loop.
   Sessions are created lazily and destroyed when the last client disconnects."
  (:require [xi.runtime :as runtime]))

(defn create-manager
  "Create a session manager. Returns a map with atoms for tracking sessions/clients.
   sessions: atom of {session-id {:runtime rt :clients #{ws...} :created <timestamp>}}
   opts:
     :cwd   - working directory for new runtimes
     :model - model override"
  [opts]
  {:sessions (atom {})
   :opts opts})

(defn- gen-session-id []
  (str "s-" (.toString (js/Date.now) 36) "-"
       (.toString (js/Math.floor (* (js/Math.random) 1000000)) 36)))

(defn create-session!
  "Create a new session with a fresh runtime. Returns session-id."
  [manager]
  (let [sid (gen-session-id)
        rt (runtime/create! (:opts manager))
        session {:runtime rt
                 :clients (atom #{})
                 :created (js/Date.now)}]
    (swap! (:sessions manager) assoc sid session)
    (js/console.error (str "[sessions] Created session " sid))
    sid))

(defn get-session
  "Get a session by id. Returns nil if not found."
  [manager session-id]
  (get @(:sessions manager) session-id))

(defn latest-session-id
  "Return the id of the most recently created session, or nil."
  [manager]
  (let [sessions @(:sessions manager)]
    (when (seq sessions)
      (key (apply max-key (fn [[_ s]] (:created s)) sessions)))))

(defn join-session!
  "Join a client to a session. Returns the session-id joined.
   mode: :latest (join most recent or create new), :new (always create new), or a session-id string."
  [manager mode]
  (case mode
    :new
    (create-session! manager)

    :latest
    (or (latest-session-id manager)
        (create-session! manager))

    ;; Explicit session-id
    (if (get-session manager mode)
      mode
      (do (js/console.error (str "[sessions] Session " mode " not found, creating new"))
          (create-session! manager)))))

(defn add-client!
  "Add a client (ws connection) to a session. Returns the runtime client map."
  [manager session-id ws-client]
  (when-let [session (get-session manager session-id)]
    (swap! (:clients session) conj ws-client)
    (js/console.error (str "[sessions] Client joined " session-id
                           " (now " (count @(:clients session)) " clients)"))
    session))

(defn remove-client!
  "Remove a client from a session. If no clients remain, destroy the session."
  [manager session-id ws-client]
  (when-let [session (get-session manager session-id)]
    (swap! (:clients session) disj ws-client)
    (let [remaining (count @(:clients session))]
      (js/console.error (str "[sessions] Client left " session-id
                             " (" remaining " clients remaining)"))
      (when (zero? remaining)
        (js/console.error (str "[sessions] Destroying session " session-id))
        (swap! (:sessions manager) dissoc session-id)))))

(defn list-sessions
  "List all active sessions. Returns vec of {:id :clients :created}."
  [manager]
  (->> @(:sessions manager)
       (mapv (fn [[sid session]]
               {:id sid
                :clients (count @(:clients session))
                :created (:created session)}))
       (sort-by :created)
       reverse
       vec))
