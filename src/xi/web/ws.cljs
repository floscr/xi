(ns xi.web.ws
  "WebSocket transport for the web client.
   Connects to xi's WS server, handles the join handshake,
   and bridges events into the app-state atom.
   Supports offline: caches sessions/messages, queues pending sends."
  (:require [clojure.string :as str]
            [xi.web.state :as state]
            [xi.web.cache :as cache]
            [xi.web.router :as router]
            [xi.session.format :as fmt]))

(defonce ^:private ws-conn (atom nil))
(defonce ^:private reconnect-timer (atom nil))
(defonce ^:private reconnect-delay (atom 1000))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- gen-id []
  (str (.toString (js/Date.now) 36) "-"
       (.toString (js/Math.floor (* (js/Math.random) 1000000)) 36)))

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
;; Forward declarations
;; ---------------------------------------------------------------------------

(declare flush-pending!)
(declare join-and-resume!)
(declare join-room!)

;; ---------------------------------------------------------------------------
;; Cache helpers
;; ---------------------------------------------------------------------------

(defn- cache-current-messages!
  "Persist current messages to cache for the active session."
  []
  (when-let [sid (:session-id @state/app-state)]
    (cache/save-messages! sid (:messages @state/app-state))))

(defn- session-id-for-room
  "Try to find the session-id associated with a room-id from room list."
  [room-id]
  (some (fn [r] (when (= room-id (:id r)) (:session-id r)))
        (:rooms @state/app-state)))

;; ---------------------------------------------------------------------------
;; Event handling
;; ---------------------------------------------------------------------------

(defn- append-msg! [msg]
  (swap! state/app-state update :messages conj msg)
  (cache-current-messages!))

(defonce ^:private replaying-history? (atom false))

(defn- handle-event [event]
  (case (:type event)
    :waiting-for-join
    ;; Only show home screen if we explicitly left (room-id already nil)
    ;; When reconnecting with a cached session, auto-rejoin.
    (let [currently-in-room (:room-id @state/app-state)
          cached-session-id (:session-id @state/app-state)
          sessions (or (:sessions event) [])
          rooms (or (:rooms event) [])
          active-sids (set (or (:active-sessions event) []))]
      (swap! state/app-state assoc
             :rooms rooms
             :home-sessions sessions
             :active-sessions active-sids
             :personal-agent? (boolean (:personal-agent? event)))
      ;; Cache sessions for offline
      (cache/save-sessions! sessions)
      (let [has-pending? (seq (:pending-messages @state/app-state))
            in-chat? (or currently-in-room (= :chat (get-in @state/app-state [:route :page])))]
        (if in-chat?
          ;; We were in a chat (from cache, offline new session, or before disconnect).
          ;; Try to rejoin: find if our session still has an active room.
          (let [room-for-session (when cached-session-id
                                   (some (fn [r] (when (= cached-session-id (:session-id r)) (:id r))) rooms))]
            (cond
              room-for-session
              ;; Session still active — rejoin its room
              (join-room! room-for-session)

              cached-session-id
              ;; Session not active — resume into a new room
              (let [idx (some (fn [[i s]] (when (= cached-session-id (:session-id s)) (inc i)))
                              (map-indexed vector sessions))]
                (if idx
                  (join-and-resume! idx)
                  ;; Session not in list — stay on cached view
                  nil))

              has-pending?
              ;; Offline new session with pending messages — join new room to flush
              (join-room! "new")

              :else nil))
          ;; Not in a room — show home
          (do
            (router/navigate! {:page :home})
            (swap! state/app-state assoc
                   :messages []
                   :busy? false)))))

    :room-joined
    (let [room-id (:room-id event)
          sid (session-id-for-room room-id)]
      (swap! state/app-state assoc
             :room-id room-id
             :session-id sid)
      (router/navigate! {:page :chat :session-id sid})
      (cache/save-last-room! room-id sid)
      ;; Flush pending messages now that we're in a room
      (js/setTimeout flush-pending! 100))

    :ready
    (swap! state/app-state assoc
           :model (:model event)
           :cwd (:cwd event))

    :user-message
    (do
      ;; Server echoes user message back. Skip if we already showed it optimistically
      ;; (the last message is already a :user with same text).
      (let [last-msg (peek (:messages @state/app-state))]
        (when-not (and last-msg
                       (= :user (:type last-msg))
                       (= (:text event) (:text last-msg)))
          (append-msg! (cond-> {:type :user :text (:text event)}
                         (seq (:images event)) (assoc :images (:images event))))))
      ;; When server confirms our message, clear it from pending
      ;; (pending messages match by payload text)
      (let [pending (:pending-messages @state/app-state)]
        (when (seq pending)
          (let [matching (first (filter #(= (:text event) (get-in % [:payload :text])) pending))]
            (when matching
              (let [updated (cache/remove-pending! (:id matching))]
                (swap! state/app-state assoc :pending-messages updated)))))))

    :busy-changed
    (swap! state/app-state assoc :busy? (:busy event))

    :turn-start
    nil ;; busy-changed handles UI

    :text-delta
    (do
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
      (cache-current-messages!))

    :thinking
    (do
      (swap! state/app-state
             (fn [s]
               (let [msgs (:messages s)
                     last-msg (peek msgs)]
                 (if (and last-msg (= :thinking (:type last-msg)))
                   (assoc s :messages (conj (pop msgs)
                                            (update last-msg :text str (:text event))))
                   (update s :messages conj {:type :thinking :text (:text event)})))))
      (cache-current-messages!))

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
    (do
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
      (cache-current-messages!))

    :tool-result
    (do
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
      (cache-current-messages!))

    :error
    (append-msg! {:type :error
                  :text (or (some-> event :error :message)
                            (pr-str (:error event)))})

    :turn-end
    ;; Flush pending messages that were waiting for turn to end
    ;; Also update session-id if server provides it
    (when-let [sid (:session-id event)]
      (swap! state/app-state assoc :session-id sid)
      (router/replace! {:page :chat :session-id sid})
      (cache/save-last-room! (:room-id @state/app-state) sid)
      (cache-current-messages!))

    :aborted
    (append-msg! {:type :status :text "Interrupted."})

    :session-cleared
    (do
      ;; Clear session-id first so cache-current-messages! is a no-op.
      ;; This prevents corrupting the old session's cache with empty [].
      ;; The new session-id arrives via :turn-end.
      (swap! state/app-state assoc :messages [] :session-id nil))

    :compact-start
    (append-msg! {:type :status :text "Compacting conversation..."})

    :session-compacted
    (do
      (swap! state/app-state assoc :messages
             [{:type :status :text "Session compacted. Summary preserved as context."}])
      (cache-current-messages!))

    :session-resumed
    (let [msgs (:messages event)
          results-by-id (into {}
                              (comp (filter #(= :tool-result (keyword (:type %))))
                                    (map (fn [r] [(:tool-use-id r) r])))
                              msgs)
          session (:session event)
          session-name (or (:name session) (:cli-session-id session) (:id session))
          ;; Update session-id from the resumed session
          sid (:id session)
          ;; Group consecutive :image blocks with the preceding :text user block
          grouped (reduce
                   (fn [acc block]
                     (case (keyword (:type block))
                       :image
                       (let [last-msg (peek acc)]
                         (if (and last-msg (= :user (:type last-msg)))
                           ;; Attach image to previous user message
                           (conj (pop acc)
                                 (update last-msg :images
                                         (fnil conj [])
                                         {:data (:data block)
                                          :media-type (:media-type block)}))
                           ;; Standalone image — make a user message
                           (conj acc {:type :user :text "[image]"
                                      :images [{:data (:data block)
                                                 :media-type (:media-type block)}]})))

                       :text
                       (conj acc {:type (if (= "user" (:role block)) :user :assistant)
                                  :text (:text block)})

                       :tool-use
                       (let [result (get results-by-id (:tool-use-id block))
                             content (:content result)
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
                         (conj acc {:type :tool
                                    :tool-name (:name block)
                                    :title (format-tool-title (:name block) (:arguments block))
                                    :arguments (:arguments block)
                                    :result text
                                    :is-error (:is-error result)
                                    :finished true}))

                       :tool-result acc ;; rendered inline with tool-use
                       acc))
                   []
                   msgs)]
      (swap! state/app-state assoc
             :messages (into [{:type :status
                               :text (str "Resumed: " (or session-name "session"))}]
                             grouped)
             :session-id sid)
      (when sid
        (router/replace! {:page :chat :session-id sid})
        (cache/save-last-room! (:room-id @state/app-state) sid)
        (cache-current-messages!)))

    :command-result
    (case (:command event)
      "resume-list"
      ;; Only open overlay from live interaction, not history replay
      (when-not @replaying-history?
        (swap! state/app-state assoc :resume-sessions (:sessions event)))

      ;; Default — show text if present
      (when-let [text (:text event)]
        (append-msg! {:type :status :text text})))

    :command-error
    (append-msg! {:type :error :text (:text event)})

    :history
    (do
      (swap! state/app-state assoc :messages [])
      (reset! replaying-history? true)
      (doseq [evt (:events event)]
        (handle-event (update evt :type keyword)))
      (reset! replaying-history? false)
      (cache-current-messages!))

    :quit
    (do
      (cache/clear-last-room!)
      (swap! state/app-state assoc :room-id nil :session-id nil)
      (router/navigate! {:page :home}))

    ;; default — ignore
    nil))

;; ---------------------------------------------------------------------------
;; Connection
;; ---------------------------------------------------------------------------

(defn- ws-url []
  (let [params (js/URLSearchParams. (.-search js/window.location))
        host   (or (.get params "host") (.-hostname js/window.location))
        port   (or (.get params "port") "7474")]
    (str "ws://" host ":" port)))

(defn- send-raw!
  "Send a raw clj map as JSON over the WS connection. Returns true if sent."
  [msg]
  (when-let [ws @ws-conn]
    (when (= (.-OPEN js/WebSocket) (.-readyState ws))
      (.send ws (js/JSON.stringify (clj->js msg)))
      true)))

(defn- flush-pending!
  "Try to send all pending messages. Removes successfully acknowledged ones."
  []
  (let [pending (:pending-messages @state/app-state)]
    (when (seq pending)
      (doseq [pm pending]
        (when (send-raw! (:payload pm))
          ;; Mark as sent — remove from pending
          (let [updated (cache/remove-pending! (:id pm))]
            (swap! state/app-state assoc :pending-messages updated)))))))

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
            (swap! state/app-state assoc :connected? true)
            ;; Flush any pending messages after a short delay
            ;; (wait for join handshake to complete first)
            (js/setTimeout flush-pending! 500)))

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

(defn- parse-command
  "Parse a user input string into a command map."
  [text]
  (let [text (str/trim text)]
    (if (str/starts-with? text "/")
      (let [parts (str/split text #"\s+" 2)
            cmd-name (subs (first parts) 1)
            args (when (second parts) (str/trim (second parts)))]
        {:type :command :name cmd-name :args args})
      {:type :prompt :text text})))

(defn dispatch!
  "Send a command or prompt to the server.
   If offline, queues as pending message.
   Accepts a string (prompt or /command), a map ({:type :abort}),
   or a map with :text and :images for image attachments."
  [command]
  (let [payload (if (string? command)
                  (parse-command command)
                  command)
        sent? (send-raw! payload)]
    (when (and (not sent?)
               ;; Only queue prompts, not control commands like :abort
               (contains? #{:prompt} (:type payload)))
      ;; Offline — queue as pending
      (let [pm (fmt/make-pending-message
                (gen-id)
                (:session-id @state/app-state)
                (:room-id @state/app-state)
                payload)]
        (cache/add-pending! pm)
        (swap! state/app-state update :pending-messages conj pm)))))

(defn dispatch-with-images!
  "Send a prompt with attached images to the server.
   images: [{:data base64-string :media-type mime-type} ...]"
  [text images]
  (dispatch! {:type :prompt
              :text text
              :images images}))

(defn join-room!
  "Join a specific room by id, or \"new\" / \"latest\".
   If offline, transitions to chat view anyway (messages will be cached/pending)."
  [room-mode]
  (let [sent? (send-raw! {:type :join :room room-mode})]
    (when (and (not sent?) (= room-mode "new"))
      ;; Offline new session — show chat view with empty timeline
      (swap! state/app-state assoc
             :room-id nil
             :session-id nil
             :messages [])
      (router/navigate! {:page :chat}))))

(defn leave-room!
  "Leave the current room and return to the room list."
  []
  (cache/clear-last-room!)
  (swap! state/app-state assoc :room-id nil :session-id nil)
  (router/navigate! {:page :home})
  (send-raw! {:type :leave}))

(defn new-room!
  "Leave current room (keeping its agent running) and join a fresh room."
  []
  (cache/clear-last-room!)
  (swap! state/app-state assoc
         :room-id nil
         :session-id nil
         :messages []
         :busy? false)
  ;; Leave current room on server (disconnects event subscription),
  ;; then immediately join a new one. The server processes these in
  ;; order: leave clears conn-state, join creates a fresh room.
  (send-raw! {:type :leave})
  (send-raw! {:type :join :room "new"}))

(defn join-and-resume!
  "Join a new room and immediately resume session at index n."
  [n]
  (send-raw! {:type :join :room "new"})
  ;; Small delay so join processes first
  (js/setTimeout
   #(send-raw! {:type :command :name "resume" :args (str n)})
   100))

(defn join-session-room!
  "Join the active room for a session (by matching room-id from rooms list)."
  [room-id]
  (join-room! room-id))

;; ---------------------------------------------------------------------------
;; Offline session viewing
;; ---------------------------------------------------------------------------

(defn open-cached-session!
  "Open a cached session for offline viewing."
  [session-id]
  (let [msgs (cache/load-messages session-id)]
    (swap! state/app-state assoc
           :session-id session-id
           :messages msgs)
    (router/navigate! {:page :chat :session-id session-id})))

;; ---------------------------------------------------------------------------
;; Init — hydrate from cache
;; ---------------------------------------------------------------------------

(defn hydrate-from-cache!
  "Load cached state into app-state on startup (before WS connects).
   This makes the app usable immediately even if the server is down."
  []
  (let [sessions (cache/load-sessions)
        pending (cache/load-pending)
        last-room (cache/load-last-room)
        route (router/current-route)
        url-sid (:session-id route)]
    ;; Always hydrate home sessions so they show even offline
    (when (seq sessions)
      (swap! state/app-state assoc :home-sessions sessions))
    ;; Restore pending messages
    (when (seq pending)
      (swap! state/app-state assoc :pending-messages pending))
    ;; If URL points to a chat session, try to hydrate from cache
    (if (and (= :chat (:page route)) url-sid)
      (let [cached-msgs (cache/load-messages url-sid)]
        (when (seq cached-msgs)
          (swap! state/app-state assoc
                 :session-id url-sid
                 :messages cached-msgs)))
      ;; Otherwise fall back to last-room cache
      (when last-room
        (let [sid (:session-id last-room)
              cached-msgs (when sid (cache/load-messages sid))]
          (when (seq cached-msgs)
            (swap! state/app-state assoc
                   :session-id sid
                   :room-id (:room-id last-room)
                   :messages cached-msgs)
            (router/replace! {:page :chat :session-id sid})))))
)

;; ---------------------------------------------------------------------------
;; Router integration
;; ---------------------------------------------------------------------------

(router/set-on-navigate-home!
 (fn []
   (cache/clear-last-room!)
   (send-raw! {:type :leave})))

(router/set-on-navigate-chat!
 (fn [{:keys [session-id]}]
   (when session-id
     (let [msgs (cache/load-messages session-id)]
       (swap! state/app-state assoc
              :session-id session-id
              :messages (or msgs []))))))
)