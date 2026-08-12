(ns xi.ext.canvas-review.handlers
  "Pure handlers shared by the canvas-review node core and its browser half.
   These mutate the room-scoped, mirrored canvas state at
   [:rooms rid :ext :canvas-review], so both surfaces register them: the server
   applies them and broadcasts, and each web client applies the same reducer to
   its mirror — the canvas builds up live as the model works. Browser-safe (no
   node APIs)."
  (:require [xi.core.state :as state]))

(def ext-id :canvas-review)

(defn load-handler
  "Install the diff and reset the canvas. Deterministically names an unnamed
   session (e.g. \"Canvas review: PR #333\") so the sidebar/session list shows
   what the room is instead of lingering on \"New session\" — the review is
   seeded by a /command, which bypasses the usual prompt-driven auto-naming.
   Guarded on `nil?` name so re-running /canvas-review in a real, already-named
   user session never clobbers its title."
  [st {:keys [room-id source title text name]}]
  (when (state/get-room st room-id)
    (let [st (assoc-in st [:rooms room-id :ext ext-id]
                       {:source source :title title :diff text
                        :nodes {} :edges {} :plan []})
          st (if (and name (nil? (get-in st [:rooms room-id :session :name])))
               (assoc-in st [:rooms room-id :session :name] name)
               st)]
      {:state st})))

(def ^:private lane-x
  "Horizontal lane per node kind — code on the left, notes middle, prose right."
  {:code 40 :comment 620 :prose 1180})

(def ^:private lane-w {:code 540 :comment 460 :prose 520})

(defn- layout-pos
  "Auto-place a node into its kind's lane, stacked below existing same-kind
   nodes. The model doesn't supply coordinates; the human can drag afterwards."
  [nodes kind]
  (let [n (count (filter #(= kind (:kind %)) (vals nodes)))]
    {:x (get lane-x kind 40)
     :y (+ 40 (* n 240))
     :w (get lane-w kind 480)}))

(defn add-node-handler
  [st {:keys [room-id node]}]
  (when (state/get-room st room-id)
    (let [nodes (get-in st [:rooms room-id :ext ext-id :nodes])
          node  (merge (layout-pos nodes (:kind node)) node)]
      {:state (assoc-in st [:rooms room-id :ext ext-id :nodes (:id node)] node)})))

(defn add-edge-handler
  [st {:keys [room-id edge]}]
  (when (state/get-room st room-id)
    {:state (assoc-in st [:rooms room-id :ext ext-id :edges (:id edge)] edge)}))

(defn set-plan-handler
  [st {:keys [room-id plan]}]
  (when (state/get-room st room-id)
    {:state (assoc-in st [:rooms room-id :ext ext-id :plan] plan)}))

(defn move-node-handler
  "Persist a drag from the web canvas."
  [st {:keys [room-id id x y]}]
  (when (get-in st [:rooms room-id :ext ext-id :nodes id])
    {:state (update-in st [:rooms room-id :ext ext-id :nodes id] assoc :x x :y y)}))

(defn highlight-handler
  "Emphasize specific new-side line numbers within a code-block node."
  [st {:keys [room-id id lines]}]
  (when (get-in st [:rooms room-id :ext ext-id :nodes id])
    {:state (assoc-in st [:rooms room-id :ext ext-id :nodes id :highlight] (vec lines))}))

(defn hydrate-handler
  "Install a whole persisted canvas into a room (on resume from disk). Broadcast
   to clients so their mirror rebuilds. Guarded to avoid clobbering a canvas
   that's already populated (a live room the agent is still drawing on)."
  [st {:keys [room-id canvas]}]
  (when (and canvas (state/get-room st room-id)
             (empty? (get-in st [:rooms room-id :ext ext-id :nodes])))
    {:state (assoc-in st [:rooms room-id :ext ext-id] canvas)}))

(def handlers
  {:canvas-review/load      load-handler
   :canvas-review/add-node  add-node-handler
   :canvas-review/add-edge  add-edge-handler
   :canvas-review/set-plan  set-plan-handler
   :canvas-review/move-node move-node-handler
   :canvas-review/highlight highlight-handler
   :canvas-review/hydrate   hydrate-handler})
