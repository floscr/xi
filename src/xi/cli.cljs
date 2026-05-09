(ns xi.cli
  "Xi entry point.

   Subcommands:
     xi              → standalone TUI + runtime (no WS server)
     xi server       → start WS server + create session + attach TUI
     xi join         → connect TUI to latest session on running server
     xi create       → connect TUI to a new session on running server
     xi sessions     → list sessions on running server (print & exit)

   Flags:
     --port N         → override port (default 7474)
     --headless       → server-only, no TUI (server subcommand only)"
  (:require [xi.runtime :as runtime]
            [xi.client.tui :as tui-client]
            [xi.client.ws-transport :as ws-transport]
            [xi.server.ws :as ws]
            [xi.server.session-manager :as sm]
            [xi.tui.buffers :as buffers]
            [xi.tui.terminal :as term]))

(def ^:private DEFAULT_PORT 7474)

(defn- parse-args
  "Parse CLI args into a command map."
  []
  (let [args (vec (drop 2 (js->clj js/process.argv)))]
    (loop [i 0
           cmd nil
           opts {}]
      (if (>= i (count args))
        (merge {:command (or cmd :standalone)} opts)

        (let [arg (nth args i)]
          (cond
            ;; Subcommands
            (and (nil? cmd) (= "server" arg))
            (recur (inc i) :server opts)

            (and (nil? cmd) (= "join" arg))
            (recur (inc i) :join opts)

            (and (nil? cmd) (= "create" arg))
            (recur (inc i) :create opts)

            (and (nil? cmd) (= "sessions" arg))
            (recur (inc i) :sessions opts)

            ;; Flags
            (= "--headless" arg)
            (recur (inc i) cmd (assoc opts :headless true))

            (= "--port" arg)
            (let [port (when (< (inc i) (count args))
                         (js/parseInt (nth args (inc i)) 10))]
              (if (and port (not (js/isNaN port)))
                (recur (+ i 2) cmd (assoc opts :port port))
                (do (js/console.error "Usage: --port <number>")
                    (js/process.exit 1))))

            :else
            (do (js/console.error (str "Unknown argument: " arg))
                (js/process.exit 1))))))))

(defn- start-standalone!
  "Start a standalone TUI + runtime with no WS server."
  [_opts]
  (let [rt (runtime/create! {})
        transport {:dispatch! (fn [cmd] (runtime/dispatch! rt cmd))
                   :busy? (fn [] (runtime/busy? rt))}
        client (tui-client/create! {:transport transport})]
    (runtime/connect! rt client)))

(defn- start-server!
  "Start server with session manager. Optionally attach a local TUI."
  [{:keys [headless port]}]
  (let [;; Set up interception early so server/session logs reach the Logs buffer
        buffer-mgr (when-not headless
                     (let [mgr (buffers/create-manager ["Logs"])]
                       (term/intercept-stdout!
                        (fn [_stream text]
                          (buffers/append! mgr "Logs" text)))
                       mgr))
        manager (sm/create-manager {})
        server (ws/start! manager {:port port})
        actual-port (:port server)]

    (if headless
      (do (js/console.error (str "[xi] Headless server running on ws://localhost:" actual-port))
          (js/console.error "[xi] Clients can connect with: xi join"))

      ;; Interactive — create a session and attach a local TUI
      (let [session-id (sm/create-session! manager)
            session (sm/get-session manager session-id)
            rt (:runtime session)
            transport {:dispatch! (fn [cmd] (runtime/dispatch! rt cmd))
                       :busy? (fn [] (runtime/busy? rt))}
            client (tui-client/create! {:transport transport
                                        :buffer-mgr buffer-mgr})]
        (sm/add-client! manager session-id :local-tui)
        (runtime/connect! rt client)))))

(defn- start-join!
  "Connect TUI to an existing server's latest session."
  [{:keys [port]}]
  (let [port (or port DEFAULT_PORT)
        event-handler (atom nil)

        transport (ws-transport/create!
                   {:port port
                    :session "latest"
                    :on-event (fn [event] (when-let [f @event-handler] (f event)))
                    :on-open (fn [] nil)
                    :on-close (fn []
                                (js/console.error
                                 (str "\nDisconnected from ws://localhost:" port))
                                (js/process.exit 1))})

        client (tui-client/create! {:transport transport})]

    (reset! event-handler (:on-event client))
    ((:on-connect client) {:model nil})))

(defn- start-create!
  "Connect TUI to an existing server with a new session."
  [{:keys [port]}]
  (let [port (or port DEFAULT_PORT)
        event-handler (atom nil)

        transport (ws-transport/create!
                   {:port port
                    :session "new"
                    :on-event (fn [event] (when-let [f @event-handler] (f event)))
                    :on-open (fn [] nil)
                    :on-close (fn []
                                (js/console.error
                                 (str "\nDisconnected from ws://localhost:" port))
                                (js/process.exit 1))})

        client (tui-client/create! {:transport transport})]

    (reset! event-handler (:on-event client))
    ((:on-connect client) {:model nil})))

(defn- list-sessions!
  "Fetch and print sessions from a running server, then exit."
  [{:keys [port]}]
  (let [port (or port DEFAULT_PORT)
        url (str "ws://localhost:" port)
        ws (js/WebSocket. url)]

    (.addEventListener ws "open" (fn [_] nil))

    (.addEventListener ws "error"
                       (fn [_]
                         (js/console.error (str "Could not connect to server on port " port))
                         (js/process.exit 1)))

    (.addEventListener ws "message"
                       (fn [^js e]
                         (try
                           (let [raw (js->clj (js/JSON.parse (.-data e)) :keywordize-keys true)
                                 msg (update raw :type keyword)]
                             (when (= :waiting-for-join (:type msg))
                               (let [sessions (:sessions msg)]
                                 (if (empty? sessions)
                                   (println "No active sessions.")
                                   (do (println "Active sessions:")
                                       (doseq [s sessions]
                                         (println (str "  " (:id s)
                                                       " — " (:clients s) " client(s)"
                                                       " (created " (js/Date. (:created s)) ")")))))
                                 (.close ws)
                                 (js/process.exit 0))))
                           (catch :default err
                             (js/console.error (str "Error: " (.-message err)))
                             (.close ws)
                             (js/process.exit 1)))))))

(defn main []
  (let [{:keys [command] :as opts} (parse-args)]
    (case command
      :standalone (start-standalone! opts)
      :server     (start-server! opts)
      :join       (start-join! opts)
      :create     (start-create! opts)
      :sessions   (list-sessions! opts))))
