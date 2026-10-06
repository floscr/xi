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
            [xi.wire :as wire]))

;; ── Handler wrapping (pure) ──────────────────────────────────────────────────

(def ^:private default-client-side-fx
  "Effects from mirrored events that should still run on the client.
   Extensions extend this (e.g. :terminal/set-title for done-notify) via
   make-handlers :client-fx."
  #{:clipboard/copy})

(def ^:private local-commands
  "Slash commands that act on the client process, not the room."
  {"quit"   [:app/quit {}]
   "reload" [:app/reload {}]})

(defn- local-command-effect
  "Resolve a client-local command name to its process-level effect. For
   /reload, inject the active room's session-id so the restarted client
   resumes that exact session (via a {:session-id sid} join target) instead
   of joining \"latest\" — otherwise a server restart drops the session."
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
  "Menu events apply locally instead of forwarding. Their content is built
   client-side (the Ctrl+P palette, the '/' commands menu), so a server
   round-trip only adds latency — enough that characters typed right after
   '/' landed in the still-focused editor and were lost when the menu
   finally echoed back. Server-originated menu frames (e.g. /resume pushing
   its session list, model-fetch populate) still arrive tagged :remote? and
   mirror in like any other event.

   `:theme/set` joins them: the mode belongs to this terminal, so an extension
   in the client process sets it directly instead of asking the server."
  #{:ui/menu-open :ui/menu-push :ui/menu-pop :ui/menu-populate :ui/menu-close
    :theme/set})

(defn- wrap-local-apply
  "remote? → mirror; else run the base handler locally — no forwarding."
  [client-side-fx handler]
  (let [m (mirror client-side-fx handler)]
    (fn [st ev]
      (if (:remote? ev) (m st ev) (handler st ev)))))

(defn- wrap
  "remote? → mirror; local-room target → run the base handler locally with
   full effects (client-local virtual rooms, e.g. the TUI's deferred
   :pending room — its effects are all client-side); else forward."
  [client-side-fx handler {:keys [local-room?]}]
  (let [m (mirror client-side-fx handler)]
    (fn [st ev]
      (cond
        (:remote? ev)                      (m st ev)
        (and local-room? (local-room? ev)) (handler st ev)
        :else                              (forward st ev)))))

(defn- wrap-input-submit
  "Like wrap, but intercept client-local commands before forwarding, and
   route local-room submissions to :local-submit (the deferred-room stash)."
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
   stripped: menus are per-client UI handled locally (local-ui-events), so a
   menu frame a server-side flow once pushed (and the client since closed
   locally) must not resurrect from the snapshot. Public so the web client
   can wrap it (it splices a cache-elided history back in first)."
  [st {:keys [room-id room]}]
  {:state (-> st
              (assoc-in [:rooms room-id] (update room :ui dissoc :menu :menu-stack))
              (assoc :active-room room-id))})

(defn- room-left [st {:keys [room-id]}]
  {:state (cond-> (update st :rooms dissoc room-id)
            (= room-id (:active-room st)) (assoc :active-room nil))})

(defn dialog-response
  "The user's own dialog answer is forwarded to the server (the resolver
   lives there); the server's :remote? echo removes the answered dialog from
   the local room mirror."
  [st {:keys [room-id dialog-id remote?] :as ev}]
  (if remote?
    (when (some #(= dialog-id (:id %)) (get-in st [:rooms room-id :ui :dialogs]))
      {:state (update-in st [:rooms room-id :ui :dialogs]
                         (fn [ds] (vec (remove #(= dialog-id (:id %)) ds))))})
    {:effects [[:ws/send ev]]}))

(defn lobby-state
  "Replace the lobby slice. The Claude usage reading is the one key kept across
   updates when the payload omits it (server restarted, fetch failing): the
   sidebar keeps showing the last reading until it is outdated."
  [st ev]
  (let [lobby (select-keys ev [:rooms :sessions :read :agent-id :started-at :claude-usage :model])
        prev  (get-in st [:lobby :claude-usage])]
    {:state (assoc st :lobby (cond-> lobby
                               (and prev (not (:claude-usage lobby)))
                               (assoc :claude-usage prev)))}))

(defn auth-ok
  "The server admitted us and tells us which user this connection acts as
   (clients.edn assignment, our :auth/hello claim, or root — xi.server.ws).
   Record it so the renderers can tell our own prompts from other users'.
   The TUI and web clients compose this into their :auth/ok handlers."
  [st {:keys [user]}]
  (when user
    {:state (assoc-in st [:connection :user] user)}))

(defn make-handlers
  "Client-mode handler map from the server-equivalent pure handlers:
   every base type forwards locally / mirrors remotely, plus the
   connection-level handlers only a client has.

   Second arity threads extension seams:
     :client-fx       extra mirrored-effect types allowed to run locally
                      (joined to the clipboard default whitelist)
     :local-handlers  process-local extension handlers (xi.config/client)
                      installed UNWRAPPED — they act on the client process
                      and never forward/mirror. Dialog answers still
                      forward to the server, which owns the resolver.
     :local-room?     (fn [ev] → bool) — non-remote events matching this
                      predicate target a client-local virtual room and run
                      the base reducer locally instead of forwarding
                      (the TUI's deferred :pending room).
     :local-submit    (fn [st ev] → result) — :input/submit / :command/run
                      handler for local-room events (after the
                      local-commands intercept): stashes the submission and
                      creates the real server room."
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
  "Connect to a Xi server. The socket lives here; the app is wired in via
   :set-dispatch! after creation (the app needs :effects first).

   opts:
     :url        ws:// URL
     :hello      {:client-key … :client-name … :platform …} — sent as
                 :auth/hello on every (re)connect; joins/sends are held back
                 until the server answers :auth/ok. On :auth/pending the
                 connection parks until another client (or `bb serve:approve
                 <code>`) approves this key; :auth/denied stops reconnecting.
                 All auth events are also dispatched into the app for UI.
     :target     \"latest\" | \"new\" | room-id | {:session-id sid} — joined on
                 open, and replayed on every reconnect
     :cwd        working directory sent with the join request
     :reconnect? auto-reconnect with backoff (1s → 30s) on drop, queuing
                 sends until the socket reopens (web client; the TUI opts out
                 and exits via :on-close instead)
     :on-status  (fn [connected?]) — socket opened / dropped (offline badge)
     :on-close   (fn []) — only without :reconnect?: connection ended and we
                 are giving up; not called after an intentional close!

   Returns {:effects {:ws/send …} :set-dispatch! :close!}."
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
