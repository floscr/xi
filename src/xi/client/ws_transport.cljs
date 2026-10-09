(ns xi.client.ws-transport
  "WS client transport — mirrors remote rooms into the local :rooms map.

   In :client mode the app is a pure renderer + input layer:

   - Local events (editor, abort) are NOT applied locally — every known
     event type forwards to the server as [:ws/send event]. The server
     owns all room state.
   - Server broadcasts arrive tagged :remote? and are applied with the
     same pure reducers the server ran, with effects stripped — the mirror
     replays the server's state transitions exactly, without re-running
     provider/session/image work.
   - Exception 1: a whitelist of client-side effects (clipboard) still
     runs from mirrored events, so e.g. /debug copies on the client.
   - Exception 2: /quit and /reload act on the client process, so they're
     intercepted before forwarding.
   - Exception 3: menu events (local-ui-events) apply locally without a
     round-trip — menus are per-client UI built client-side, and the WS
     echo delay was long enough to eat keystrokes typed right after '/'.
   - :room/joined installs the server's room snapshot; afterwards the
     incremental event stream keeps the mirror in sync."
  (:require [xi.commands :as commands]
            [xi.dialog :as dialog]
            [xi.wire :as wire]))

;; ── Handler wrapping (pure) ──────────────────────────────────────────────────

(def ^:private default-client-side-fx
  "Effects from mirrored events that still run on the client; extensions extend
   it via make-handlers :client-fx."
  #{:clipboard/copy})

(def ^:private local-commands
  "Slash commands that act on the client process, not the room."
  {"quit"   [:app/quit {}]
   "reload" [:app/reload {}]})

(defn- local-command-effect
  "A client-local command's process-level effect; /reload carries the active
   session id so the restarted client resumes it."
  [st name]
  (if (= name "reload")
    [:app/reload {:session-id (get-in st [:rooms (:active-room st) :session :id])}]
    (get local-commands name)))

(defn- mirror
  "Apply a remote event with the base reducer, dropping all effects except
   the client-side whitelist."
  [client-side-fx handler]
  (fn [st ev]
    (when-let [res (handler st ev)]
      {:state   (:state res)
       :effects (filterv (fn [[fx-type _]] (client-side-fx fx-type))
                         (:effects res))})))

(defn- forward [_st ev]
  {:effects [[:ws/send ev]]})

(def ^:private local-ui-events
  "Events applied locally instead of forwarded: menus (built client-side; a
   round-trip lost keystrokes typed right after '/'), `:theme/set` (this
   terminal's) and `:ui/buffer-switch` (which buffer this client views is its
   own, xi.buffers). Server-originated frames still mirror in as :remote?."
  #{:ui/menu-open :ui/menu-push :ui/menu-pop :ui/menu-populate :ui/menu-close
    :theme/set :ui/buffer-switch})

(defn- wrap-local-apply
  "remote? → mirror; else run the base handler locally — no forwarding."
  [client-side-fx handler]
  (let [m (mirror client-side-fx handler)]
    (fn [st ev]
      (if (:remote? ev) (m st ev) (handler st ev)))))

(defn- wrap
  "remote? → mirror; a local-room target → run the base handler locally with
   full effects; else forward."
  [client-side-fx handler {:keys [local-room?]}]
  (let [m (mirror client-side-fx handler)]
    (fn [st ev]
      (cond
        (:remote? ev)                      (m st ev)
        (and local-room? (local-room? ev)) (handler st ev)
        :else                              (forward st ev)))))

(defn- wrap-input-submit
  "wrap, intercepting client-local commands first and routing local-room
   submissions to :local-submit."
  [client-side-fx handler {:keys [local-room? local-submit]}]
  (let [m (mirror client-side-fx handler)]
    (fn [st ev]
      (if (:remote? ev)
        (m st ev)
        (let [parsed (commands/parse-input (:text ev))]
          (cond
            (and (= :command (:type parsed))
                 (get local-commands (:name parsed)))
            {:effects [(local-command-effect st (:name parsed))]}

            (and local-room? (local-room? ev))
            (local-submit st ev)

            :else (forward st ev)))))))

(defn- wrap-command-run
  "Like wrap-input-submit for direct :command/run (palette items)."
  [client-side-fx handler {:keys [local-room? local-submit]}]
  (let [m (mirror client-side-fx handler)]
    (fn [st ev]
      (cond
        (:remote? ev)                      (m st ev)
        (get local-commands (:name ev))    {:effects [(local-command-effect st (:name ev))]}
        (and local-room? (local-room? ev)) (local-submit st ev)
        :else                              (forward st ev)))))

;; ── Client-only handlers ─────────────────────────────────────────────────────

(defn room-joined
  "Install the server's room snapshot and make it active. Menu state is
   stripped (menus are client-local) and the active buffer reset to the chat
   (the snapshot's is the server's own slot). Public so the web client can wrap
   it."
  [st {:keys [room-id room]}]
  {:state (-> st
              (assoc-in [:rooms room-id] (-> room
                                             (update :ui dissoc :menu :menu-stack)
                                             (assoc-in [:ui :active-buffer] :chat)))
              (assoc :active-room room-id))})

(defn room-left
  "Drop the room mirror. Public so the web client can wrap it (it leaves the
   dead URL of a room the server closed under it)."
  [st {:keys [room-id]}]
  {:state (cond-> (update st :rooms dissoc room-id)
            (= room-id (:active-room st)) (assoc :active-room nil))})

(defn dialog-response
  "Forward the user's own dialog answer to the server; the :remote? echo
   removes the dialog locally and restarts the gated call's run clock on an
   allow (xi.dialog/restamp-gated-call)."
  [st {:keys [room-id dialog-id value remote?] :as ev}]
  (if remote?
    (when-let [answered (some #(when (= dialog-id (:id %)) %)
                              (get-in st [:rooms room-id :ui :dialogs]))]
      {:state (-> st
                  (update-in [:rooms room-id :ui :dialogs]
                             (fn [ds] (vec (remove #(= dialog-id (:id %)) ds))))
                  (update-in [:rooms room-id :history]
                             dialog/restamp-gated-call answered value (:event/ts ev)))})
    {:effects [[:ws/send ev]]}))

(defn lobby-state
  "Replace the lobby slice, keeping the last Claude usage reading when the payload omits it."
  [st ev]
  (let [lobby (select-keys ev [:rooms :sessions :read :profiles :user-ids :agent-id :started-at :claude-usage :model
                               :buffers :usage-at])
        prev  (get-in st [:lobby :claude-usage])]
    {:state (assoc st :lobby (cond-> lobby
                               (and prev (not (:claude-usage lobby)))
                               (assoc :claude-usage prev)))}))

(defn auth-ok
  "Record the user this connection acts as and our client id (xi.server.ws), so
   renderers can tell our prompts from others' and reducers a view switch meant
   for us (xi.buffers/switch-here?)."
  [st {:keys [user client-id]}]
  (when (or user client-id)
    {:state (cond-> st
              user      (assoc-in [:connection :user] user)
              client-id (assoc-in [:connection :client-id] client-id))}))

(defn make-handlers
  "Client-mode handler map: every base type forwards locally / mirrors
   remotely, plus the connection-level handlers. Seams: :client-fx (extra
   mirrored effects allowed locally), :local-handlers (process-local extension
   handlers installed unwrapped), :local-room? (fn [ev]) for client-local
   virtual rooms that run the base reducer locally, :local-submit (fn [st ev])
   handling their :input/submit / :command/run."
  ([base-handlers] (make-handlers base-handlers nil))
  ([base-handlers {:keys [client-fx local-handlers local-room? local-submit]}]
   (let [client-side-fx (into default-client-side-fx client-fx)
         wrap-opts {:local-room? local-room? :local-submit local-submit}]
     (-> (into {} (map (fn [[t handler]]
                         [t (if (local-ui-events t)
                              (wrap-local-apply client-side-fx handler)
                              (wrap client-side-fx handler wrap-opts))]))
               base-handlers)
         (assoc :input/submit (wrap-input-submit client-side-fx (get base-handlers :input/submit) wrap-opts)
                :command/run  (wrap-command-run client-side-fx (get base-handlers :command/run) wrap-opts)
                ;; Dialog answers must reach the server (it holds the
                ;; pending resolver). Only forward the user's own answer —
                ;; the server echoes the event back tagged :remote?, and
                ;; re-forwarding that echo would loop endlessly (server
                ;; re-echoes each time). The echo instead drops the dialog
                ;; locally, so an answer given anywhere (another client, a
                ;; server-side /allow) clears it here too.
                :ui/dialog-response dialog-response
                :room/joined  room-joined
                :room/left    room-left
                :lobby/state  lobby-state)
         (merge local-handlers)))))

;; ── Transport (contained impure edge) ────────────────────────────────────────

(defn create!
  "Connect to a Xi server; the app is wired in via :set-dispatch!. opts: :url,
   :hello (sent as :auth/hello on every connect; sends are held until :auth/ok,
   :auth/pending parks until approved, :auth/denied stops reconnecting),
   :target (\"latest\" | \"new\" | room-id | {:session-id sid}, joined on open
   and replayed on reconnect), :cwd, :reconnect? (backoff 1s → 30s, queued
   sends), :on-status (fn [connected?]), :on-close (fn [], only without
   :reconnect?). Returns {:effects :set-dispatch! :close!}."
  [{:keys [url hello target cwd reconnect? on-status on-close]}]
  (let [ctx #js {:dispatch nil :ws nil :closed false :pending #js [] :backoff 1000
                 :authed (nil? hello)
                 ;; Incoming broadcasts are coalesced: decoded events pile into
                 ;; :inbox and flush as one synchronous batch per event-loop
                 ;; turn, so a streaming burst collapses into a single render
                 ;; instead of one full render pass per frame (which starves
                 ;; local keystroke repaints — see flush-inbox!).
                 :inbox #js [] :flushScheduled false
                 ;; nil target → no auto-join (the web router drives joins via
                 ;; forwarded :room/join, which updates lastJoin for reconnect)
                 :lastJoin (when target
                             (wire/encode {:type :room/join :target target :cwd cwd}))}]
    (letfn [(open? []
              (let [ws (.-ws ctx)] (and ws (= 1 (.-readyState ws)))))
            (ready? [] (and (open?) (.-authed ctx)))
            (flush-inbox! []
              ;; Drain buffered broadcasts in arrival order. Dispatching them
              ;; back-to-back keeps them in one app render batch: the app's
              ;; render scheduler coalesces the N reducer runs into a single
              ;; on-render pass (xi.core.app renderScheduled guard).
              (set! (.-flushScheduled ctx) false)
              (let [evs (.-inbox ctx)]
                (set! (.-inbox ctx) #js [])
                (when-let [dispatch! (.-dispatch ctx)]
                  (doseq [ev (array-seq evs)]
                    (dispatch! ev)))))
            (schedule-flush! []
              (when-not (.-flushScheduled ctx)
                (set! (.-flushScheduled ctx) true)
                (js/setTimeout flush-inbox! 0)))
            (flush-pending! [ws]
              (let [p (.-pending ctx)]
                (set! (.-pending ctx) #js [])
                (doseq [msg (array-seq p)]
                  (try (.send ws msg) (catch :default _ nil)))))
            (handle-drop! []
              (when-not (.-closed ctx)
                (when on-status (on-status false))
                (if reconnect?
                  (let [delay (.-backoff ctx)]
                    (set! (.-backoff ctx) (min 30000 (* 2 delay)))
                    (js/setTimeout #(when-not (.-closed ctx) (connect!)) delay))
                  (do (set! (.-closed ctx) true)
                      (js/console.error (str "[ws] disconnected from " url))
                      (when on-close (on-close))))))
            (connect! []
              (let [ws (js/WebSocket. url)]
                (set! (.-ws ctx) ws)
                (.addEventListener
                 ws "open"
                 (fn [_]
                   (set! (.-backoff ctx) 1000)
                   (if hello
                     ;; Authenticate first; join + queued sends flush on
                     ;; the :auth/ok reply below.
                     (do (set! (.-authed ctx) false)
                         (.send ws (wire/encode (assoc hello :type :auth/hello))))
                     (do (when-let [j (.-lastJoin ctx)] (.send ws j))
                         (flush-pending! ws)))
                   (when on-status (on-status true))))
                (.addEventListener
                 ws "message"
                 (fn [^js e]
                   (when-let [ev (wire/decode (.-data e))]
                     ;; Transport-level auth replies (also dispatched below
                     ;; so the app can render pending/denied states).
                     (case (:type ev)
                       :auth/ok      (do (set! (.-authed ctx) true)
                                         (when-let [j (.-lastJoin ctx)] (.send ws j))
                                         (flush-pending! ws))
                       :auth/pending (js/console.error
                                      (str "[ws] waiting for approval — code " (:code ev)
                                           " (bb serve:approve " (:code ev) ")"))
                       :auth/denied  (do (js/console.error "[ws] connection denied by server")
                                         (set! (.-closed ctx) true))
                       nil)
                     ;; The server closed the room under us (its blank session
                     ;; was deleted, a prune): forget the pin, or the next
                     ;; reconnect / server restart re-joins and resurrects it.
                     (when (= :room/left (:type ev))
                       (set! (.-lastJoin ctx) nil))
                     ;; Pin lastJoin to the room's current session-id so
                     ;; reconnects re-attach instead of creating a new room
                     ;; (critical on mobile where WS drops are frequent). Track
                     ;; it on :room/joined AND whenever the room swaps sessions
                     ;; (/resume, /new, /clear) — otherwise lastJoin goes stale
                     ;; and a reconnect can't match the live room, spinning up a
                     ;; fresh one.
                     (when-let [sid (case (:type ev)
                                      :room/joined     (get-in ev [:room :session :id])
                                      (:session/resumed
                                       :session/created) (get-in ev [:session :id])
                                      nil)]
                       (set! (.-lastJoin ctx)
                             (wire/encode {:type :room/join
                                           :target {:session-id sid}})))
                     (.push (.-inbox ctx) (assoc ev :remote? true))
                     (schedule-flush!))))
                (.addEventListener ws "close" (fn [_] (handle-drop!)))
                (.addEventListener ws "error"
                                   (fn [_] (try (.close ws) (catch :default _ nil))))))
            (send! [event]
              (let [msg (wire/encode event)]
                ;; Remember the active room so reconnect re-joins it; forget
                ;; it on leave so we reconnect into the lobby, not the room.
                (when (= :room/join (:type event))
                  (set! (.-lastJoin ctx) msg))
                (when (= :room/leave (:type event))
                  (set! (.-lastJoin ctx) nil))
                (if (ready?)
                  (try (.send (.-ws ctx) msg)
                       (catch :default err
                         (js/console.error "[ws] send failed:" err)
                         (.push (.-pending ctx) msg)))
                  ;; Offline or awaiting auth: queue everything but joins
                  ;; (replayed via lastJoin) so we don't double-join later.
                  (when-not (= :room/join (:type event))
                    (.push (.-pending ctx) msg)))))]
      (connect!)
      {:effects       {:ws/send (fn [_ event] (send! event))}
       :set-dispatch! (fn [dispatch!] (set! (.-dispatch ctx) dispatch!))
       :close!        (fn []
                        (set! (.-closed ctx) true)
                        (try (.close (.-ws ctx)) (catch :default _ nil)))})))
