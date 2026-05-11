(ns xi.web.ws
  "WebSocket transport for the web client.
   Connects to xi's WS server, handles the join handshake,
   and bridges events into the app-state atom."
  (:require [clojure.string :as str]
            [xi.web.state :as state]))

(defonce ^:private ws-conn (atom nil))
(defonce ^:private reconnect-timer (atom nil))
(defonce ^:private reconnect-delay (atom 1000))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- get-arg
  "Get a tool argument by key, trying both string and keyword forms."
  [arguments k]
  (or (get arguments (name k))
      (get arguments k)))

(defn- format-tool-title
  "Create a short display title for a tool call."
  [tool-name arguments]
  (let [detail (case tool-name
                 ("Bash" "bash")  (get-arg arguments :command)
                 ("Read" "read")  (or (get-arg arguments :file_path) (get-arg arguments :path))
                 ("Write" "write") (or (get-arg arguments :file_path) (get-arg arguments :path))
                 ("Edit" "edit")  (or (get-arg arguments :file_path) (get-arg arguments :path))
                 ("Grep" "grep")  (get-arg arguments :pattern)
                 ("Glob" "find")  (get-arg arguments :pattern)
                 ("ls")           (get-arg arguments :path)
                 nil)]
    (if detail
      (let [short (first (str/split-lines detail))]
        (str tool-name " " (if (> (count short) 80) (str (subs short 0 80) "…") short)))
      tool-name)))

;; ---------------------------------------------------------------------------
;; Event handling
;; ---------------------------------------------------------------------------

(defn- append-msg! [msg]
  (swap! state/app-state update :messages conj msg))

(defn- handle-event [event]
  (case (:type event)
    :waiting-for-join
    (do (swap! state/app-state assoc :sessions (or (:sessions event) []))
        ;; Auto-join latest session
        (when-let [ws @ws-conn]
          (.send ws (js/JSON.stringify (clj->js {:type :join :session "latest"})))))

    :session-joined
    (swap! state/app-state assoc
           :session-id (:session-id event)
           :view :chat)

    :ready
    (swap! state/app-state assoc
           :model (:model event)
           :cwd (:cwd event))

    :user-message
    (append-msg! {:type :user :text (:text event)})

    :busy-changed
    (swap! state/app-state assoc :busy? (:busy event))

    :turn-start
    nil ;; busy-changed handles UI

    :text-delta
    (swap! state/app-state
           (fn [s]
             (let [msgs (:messages s)
                   last-msg (peek msgs)]
               (if (and last-msg (= :assistant (:type last-msg)))
                 ;; Append to current assistant message
                 (assoc s :messages (conj (pop msgs)
                                          (update last-msg :text str (:text event))))
                 ;; Start new assistant message
                 (update s :messages conj {:type :assistant :text (:text event)})))))

    :thinking
    (swap! state/app-state
           (fn [s]
             (let [msgs (:messages s)
                   last-msg (peek msgs)]
               (if (and last-msg (= :thinking (:type last-msg)))
                 (assoc s :messages (conj (pop msgs)
                                          (update last-msg :text str (:text event))))
                 (update s :messages conj {:type :thinking :text (:text event)})))))

    :tool-start
    (let [title (format-tool-title (:name event) (:arguments event))]
      (append-msg! {:type :tool
                    :tool-name (:name event)
                    :title title
                    :arguments (:arguments event)
                    :result nil
                    :is-error false
                    :finished false}))

    :tool-args
    (swap! state/app-state
           (fn [s]
             (let [msgs (:messages s)
                   last-msg (peek msgs)]
               (if (and last-msg (= :tool (:type last-msg)) (not (:finished last-msg)))
                 (let [title (format-tool-title (:name event) (:arguments event))]
                   (assoc s :messages (conj (pop msgs)
                                            (assoc last-msg
                                                   :title title
                                                   :arguments (:arguments event)))))
                 s))))

    :tool-result
    (swap! state/app-state
           (fn [s]
             (let [msgs (:messages s)
                   last-msg (peek msgs)]
               (if (and last-msg (= :tool (:type last-msg)) (not (:finished last-msg)))
                 (let [content (:content event)
                       text (cond
                              (string? content) content
                              (sequential? content)
                              (->> content
                                   (keep (fn [b]
                                           (cond
                                             (string? b) b
                                             (= "text" (:type b)) (:text b)
                                             :else nil)))
                                   (str/join "\n"))
                              :else nil)]
                   (assoc s :messages (conj (pop msgs)
                                            (assoc last-msg
                                                   :result text
                                                   :is-error (:is-error event)
                                                   :finished true))))
                 s))))

    :error
    (append-msg! {:type :error
                  :text (or (some-> event :error :message)
                            (pr-str (:error event)))})

    :turn-end
    nil ;; busy-changed handles UI

    :aborted
    (append-msg! {:type :status :text "Interrupted."})

    :session-cleared
    (swap! state/app-state assoc :messages [])

    :session-resumed
    (let [msgs (:messages event)]
      (swap! state/app-state assoc
             :messages (vec (keep (fn [block]
                                    (case (:type block)
                                      :text {:type (if (= "user" (:role block)) :user :assistant)
                                             :text (:text block)}
                                      :tool-use {:type :tool
                                                 :text (str "$ " (:name block))}
                                      nil))
                                  msgs))))

    :command-result
    (when-let [text (:text event)]
      (append-msg! {:type :status :text text}))

    :command-error
    (append-msg! {:type :error :text (:text event)})

    :history
    (do
      (swap! state/app-state assoc :messages [])
      (doseq [evt (:events event)]
        (handle-event (update evt :type keyword))))

    :quit
    (swap! state/app-state assoc :view :home :session-id nil)

    ;; default — ignore
    nil))

;; ---------------------------------------------------------------------------
;; Connection
;; ---------------------------------------------------------------------------

(defn- ws-url []
  (let [params (js/URLSearchParams. (.-search js/window.location))
        host   (or (.get params "host") "localhost")
        port   (or (.get params "port") "7474")]
    (str "ws://" host ":" port)))

(defn connect! []
  (when-let [old @ws-conn]
    (.close old))

  (let [url (ws-url)
        ws  (js/WebSocket. url)]
    (reset! ws-conn ws)

    (set! (.-onopen ws)
          (fn [_]
            (js/console.log (str "[ws] connected to " url))
            (reset! reconnect-delay 1000)
            (swap! state/app-state assoc :connected? true)))

    (set! (.-onclose ws)
          (fn [_]
            (js/console.log "[ws] disconnected, reconnecting...")
            (swap! state/app-state assoc :connected? false)
            (when-let [t @reconnect-timer]
              (js/clearTimeout t))
            (reset! reconnect-timer
                    (js/setTimeout
                     (fn []
                       (swap! reconnect-delay #(min (* % 2) 30000))
                       (connect!))
                     @reconnect-delay))))

    (set! (.-onerror ws)
          (fn [_]
            (js/console.log "[ws] error")))

    (set! (.-onmessage ws)
          (fn [^js e]
            (try
              (let [raw  (js->clj (js/JSON.parse (.-data e)) :keywordize-keys true)
                    event (update raw :type keyword)]
                (handle-event event))
              (catch :default err
                (js/console.error "[ws] parse error:" err)))))))

(defn disconnect! []
  (when-let [ws @ws-conn]
    (.close ws)
    (reset! ws-conn nil))
  (when-let [t @reconnect-timer]
    (js/clearTimeout t)
    (reset! reconnect-timer nil)))

;; ---------------------------------------------------------------------------
;; Dispatch (send commands to server)
;; ---------------------------------------------------------------------------

(defn dispatch!
  "Send a command or prompt to the server.
   Accepts a string (prompt or /command) or a map ({:type :abort})."
  [command]
  (when-let [ws @ws-conn]
    (when (= (.-OPEN js/WebSocket) (.-readyState ws))
      (let [parsed (if (string? command)
                     (let [text (str/trim command)]
                       (if (str/starts-with? text "/")
                         (let [parts (str/split text #"\s+" 2)
                               cmd-name (subs (first parts) 1)
                               args (when (second parts) (str/trim (second parts)))]
                           {:type :command :name cmd-name :args args})
                         {:type :prompt :text text}))
                     command)]
        (.send ws (js/JSON.stringify (clj->js parsed)))))))

(defn join-session!
  "Join a specific session by id, or \"new\" / \"latest\"."
  [session-mode]
  (when-let [ws @ws-conn]
    (.send ws (js/JSON.stringify (clj->js {:type :join :session session-mode})))))
