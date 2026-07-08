(ns xi.client.ws-transport
  "WS client transport — mirrors remote rooms into the local :rooms map.

   In :client mode the app is a pure renderer + input layer:

   - Local events (editor, menus, abort) are NOT applied locally — every
     known event type forwards to the server as [:ws/send event]. The
     server owns all room state.
   - Server broadcasts arrive tagged :remote? and are applied with the
     same pure reducers the server ran, with effects stripped — the mirror
     replays the server's state transitions exactly, without re-running
     provider/session/image work.
   - Exception 1: a whitelist of client-side effects (clipboard) still
     runs from mirrored events, so e.g. /debug copies on the client.
   - Exception 2: /quit and /reload act on the client process, so they're
     intercepted before forwarding.
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

(defn- wrap
  "remote? → mirror; local → forward to the server."
  [client-side-fx handler]
  (let [m (mirror client-side-fx handler)]
    (fn [st ev]
      (if (:remote? ev) (m st ev) (forward st ev)))))

(defn- wrap-input-submit
  "Like wrap, but intercept client-local commands before forwarding."
  [client-side-fx handler]
  (let [m (mirror client-side-fx handler)]
    (fn [st ev]
      (if (:remote? ev)
        (m st ev)
        (let [parsed (commands/parse-input (:text ev))]
          (if-let [fx (and (= :command (:type parsed))
                           (get local-commands (:name parsed)))]
            {:effects [fx]}
            (forward st ev)))))))

(defn- wrap-command-run
  "Like wrap-input-submit for direct :command/run (palette items)."
  [client-side-fx handler]
  (let [m (mirror client-side-fx handler)]
    (fn [st ev]
      (if (:remote? ev)
        (m st ev)
        (if-let [fx (get local-commands (:name ev))]
          {:effects [fx]}
          (forward st ev))))))

;; ── Client-only handlers ─────────────────────────────────────────────────────

(defn- room-joined
  "Install the server's room snapshot and make it active."
  [st {:keys [room-id room]}]
  {:state (-> st
              (assoc-in [:rooms room-id] room)
              (assoc :active-room room-id))})

(defn- room-left [st {:keys [room-id]}]
  {:state (cond-> (update st :rooms dissoc room-id)
            (= room-id (:active-room st)) (assoc :active-room nil))})

(defn- lobby-state [st ev]
  {:state (assoc st :lobby (select-keys ev [:rooms :sessions :personal-agent?]))})

(defn make-handlers
  "Client-mode handler map from the server-equivalent pure handlers:
   every base type forwards locally / mirrors remotely, plus the
   connection-level handlers only a client has.

   Second arity threads extension seams:
     :client-fx       extra mirrored-effect types allowed to run locally
                      (joined to the clipboard default whitelist)
     :local-handlers  process-local extension handlers (e.g. dictation)
                      installed UNWRAPPED — they act on the client process
                      and never forward/mirror. Dialog answers still
                      forward to the server, which owns the resolver."
  ([base-handlers] (make-handlers base-handlers nil))
  ([base-handlers {:keys [client-fx local-handlers]}]
   (let [client-side-fx (into default-client-side-fx client-fx)]
     (-> (into {} (map (fn [[t handler]] [t (wrap client-side-fx handler)])) base-handlers)
         (assoc :input/submit (wrap-input-submit client-side-fx (get base-handlers :input/submit))
                :command/run  (wrap-command-run client-side-fx (get base-handlers :command/run))
                ;; Dialog answers must reach the server (it holds the
                ;; pending resolver); removal mirrors back via room state.
                ;; Only forward the user's own answer — the server echoes
                ;; the event back tagged :remote?, and re-forwarding that
                ;; echo would loop endlessly (server re-echoes each time).
                :ui/dialog-response
                (fn [_st ev]
                  (when-not (:remote? ev) {:effects [[:ws/send ev]]}))
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
                 ;; nil target → no auto-join (the web router drives joins via
                 ;; forwarded :room/join, which updates lastJoin for reconnect)
                 :lastJoin (when target
                             (wire/encode {:type :room/join :target target :cwd cwd}))}]
    (letfn [(open? []
              (let [ws (.-ws ctx)] (and ws (= 1 (.-readyState ws)))))
            (ready? [] (and (open?) (.-authed ctx)))
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
                     (when-let [dispatch! (.-dispatch ctx)]
                       (dispatch! (assoc ev :remote? true))))))
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
