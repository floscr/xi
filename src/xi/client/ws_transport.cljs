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
  {:state (assoc st :lobby (select-keys ev [:rooms]))})

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
                :ui/dialog-response forward
                :room/joined  room-joined
                :room/left    room-left
                :lobby/state  lobby-state)
         (merge local-handlers)))))

;; ── Transport (contained impure edge) ────────────────────────────────────────

(defn create!
  "Connect to a Xi server. The socket lives here; the app is wired in via
   :set-dispatch! after creation (the app needs :effects first).

   opts:
     :url      ws:// URL
     :target   \"latest\" | \"new\" | room-id — joined on open
     :cwd      working directory sent with the join request
     :on-close (fn []) — connection ended (error or server gone); not
               called after an intentional close!

   Returns {:effects {:ws/send …} :set-dispatch! :close!}."
  [{:keys [url target cwd on-close]}]
  (let [ctx #js {:dispatch nil :closed false}
        ws (js/WebSocket. url)
        handle-close!
        (fn [reason]
          (when-not (.-closed ctx)
            (set! (.-closed ctx) true)
            (js/console.error (str "[ws] " reason))
            (when on-close (on-close))))]

    (.addEventListener ws "open"
                       (fn [_]
                         (.send ws (wire/encode {:type :room/join
                                                 :target (or target "latest")
                                                 :cwd cwd}))))
    (.addEventListener ws "message"
                       (fn [^js e]
                         (when-let [ev (wire/decode (.-data e))]
                           (when-let [dispatch! (.-dispatch ctx)]
                             (dispatch! (assoc ev :remote? true))))))
    (.addEventListener ws "close"
                       (fn [_] (handle-close! (str "Disconnected from " url))))
    (.addEventListener ws "error"
                       (fn [^js e]
                         (handle-close! (str "Connection error: " url
                                             (when-let [m (.-message e)] (str " — " m))))))

    {:effects {:ws/send (fn [_ event]
                          (try
                            (.send ws (wire/encode event))
                            (catch :default err
                              (js/console.error "[ws] send failed:" err))))}
     :set-dispatch! (fn [dispatch!] (set! (.-dispatch ctx) dispatch!))
     :close!        (fn []
                      (set! (.-closed ctx) true)
                      (try (.close ws) (catch :default _ nil)))}))
