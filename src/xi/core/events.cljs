(ns xi.core.events
  "Pure event reducer.

   An event is a plain map with a :type keyword (plus :event/id, :event/ts
   stamped by the dispatcher). A handler is a pure fn:

     (fn [state event]) → {:state state' :effects [[:fx/type payload] ...]}
                        | nil  ;; no change

   Handlers never perform side effects — effects are data, interpreted by
   xi.core.app. Unknown event types are valid: they flow to taps/log only
   (extensions and transports may listen without a state transition).

   Event naming: :room/*, :client/*, :history/*, :agent/*, :ui/*,
   :prompt/*, :render/*."
  (:require [xi.core.state :as state]))

;; ── Rooms ────────────────────────────────────────────────────────────────────

(defn- room-create [st {:keys [room-id room]}]
  (when-not (state/get-room st room-id)
    {:state (-> st
                (assoc-in [:rooms room-id] (state/make-room room-id room))
                (update :active-room #(or % room-id)))}))

(defn- room-switch [st {:keys [room-id]}]
  (when (state/get-room st room-id)
    {:state (assoc st :active-room room-id)}))

(defn- room-close [st {:keys [room-id]}]
  (when (state/get-room st room-id)
    (let [st' (update st :rooms dissoc room-id)]
      {:state (cond-> st'
                (= room-id (:active-room st'))
                (assoc :active-room (first (state/room-ids st'))))})))

;; ── Clients ──────────────────────────────────────────────────────────────────

(defn- client-connect [st {:keys [client-id client]}]
  {:state (assoc-in st [:connection :clients client-id]
                    (merge {:visible? true} client))})

(defn- client-disconnect [st {:keys [client-id]}]
  {:state (update-in st [:connection :clients] dissoc client-id)})

(defn- client-update
  "Per-client metadata update (e.g. visibility). Connection-level: keyed by
   client-id, no room state, never broadcast."
  [st {:keys [client-id visible?]}]
  (when (get-in st [:connection :clients client-id])
    {:state (cond-> st
              (some? visible?)
              (assoc-in [:connection :clients client-id :visible?] visible?))}))

;; ── History ──────────────────────────────────────────────────────────────────

(defn- history-append [st {:keys [room-id entry]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :history] conj entry)}))

;; ── Agent ────────────────────────────────────────────────────────────────────

(defn- agent-busy [st {:keys [room-id busy?]}]
  (when (state/get-room st room-id)
    {:state (assoc-in st [:rooms room-id :agent :busy?] busy?)}))

(defn- agent-set-model [st {:keys [room-id model provider]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :agent]
                       merge (cond-> {}
                               model    (assoc :model model)
                               provider (assoc :provider provider)))}))

;; ── UI (per room) ────────────────────────────────────────────────────────────

(defn- dialog-open [st {:keys [room-id dialog] :as ev}]
  (when (state/get-room st room-id)
    (let [dialog (update dialog :id #(or % (:event/id ev)))]
      {:state (update-in st [:rooms room-id :ui :dialogs] conj dialog)})))

(defn- dialog-close [st {:keys [room-id dialog-id]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :ui :dialogs]
                       (fn [ds]
                         (if dialog-id
                           (vec (remove #(= dialog-id (:id %)) ds))
                           (vec (rest ds)))))}))

(defn- buffer-set [st {:keys [room-id buffer-id buffer]}]
  (when (state/get-room st room-id)
    {:state (assoc-in st [:rooms room-id :ui :buffers buffer-id] buffer)}))

(defn- buffer-switch [st {:keys [room-id buffer-id]}]
  (when (state/get-room st room-id)
    {:state (assoc-in st [:rooms room-id :ui :active-buffer] buffer-id)}))

;; ── Registry ─────────────────────────────────────────────────────────────────

(def core-handlers
  {:room/create       room-create
   :room/switch       room-switch
   :room/close        room-close
   :client/connect    client-connect
   :client/disconnect client-disconnect
   :client/update     client-update
   :history/append    history-append
   :agent/busy        agent-busy
   :agent/set-model   agent-set-model
   :ui/dialog-open    dialog-open
   :ui/dialog-close   dialog-close
   :ui/buffer-set     buffer-set
   :ui/buffer-switch  buffer-switch})

(defn chain
  "Compose handlers left→right into one. Each handler sees the state
   accumulated so far; effects concatenate. nil results are skipped."
  [& handlers]
  (fn [st event]
    (reduce (fn [acc handler]
              (if-let [result (handler (:state acc) event)]
                {:state   (or (:state result) (:state acc))
                 :effects (into (:effects acc) (:effects result))}
                acc))
            {:state st :effects []}
            handlers)))

(defn handle-event
  "Apply one event to state via the handler registry. Pure.
   Returns {:state state' :effects [...]} — state unchanged when no handler
   matches or the handler returns nil."
  [handlers st event]
  (if-let [handler (get handlers (:type event))]
    (let [result (handler st event)]
      {:state   (or (:state result) st)
       :effects (vec (:effects result))})
    {:state st :effects []}))
