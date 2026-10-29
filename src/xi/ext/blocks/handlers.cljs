(ns xi.ext.blocks.handlers
  "Pure reducers over a room's blocks ([:rooms rid :ext :blocks :blocks]),
   registered by the server half (xi.ext.blocks) and replayed by every web
   client on the mirrored events (xi.ext.blocks.web). Browser-safe.

   Only the server's own code adds, updates or removes a block: those events
   carry no :client-id. A client sends `:ext.blocks/act` alone, and a block
   action only ever changes its own block (xi.blocks/act).

   Each change also stamps the block's text on its tool call's history entry
   as :block-text, which the TUI shows under the call (xi.client.view).

   A sub-agent's tools act in the parent room (xi.subagent), so its blocks
   live with the room's, marked :sub-id: their :call-id and :anchor are in
   that sub-agent's own history, and its view draws them, not the chat's."
  (:require [xi.blocks :as b]
            [xi.core.state :as state]
            [xi.dialog :as dialog]
            [xi.ext.subagent.handlers :as sa]))

(def ext-id b/ext-id)

(defn blocks [st room-id]
  (get-in st [:rooms room-id :ext ext-id :blocks]))

(defn find-block [st room-id block-id]
  (get (blocks st room-id) block-id))

(defn- from-client? [ev] (some? (:client-id ev)))

(defn call-history
  "The history the tool calls of `sub-id` run in: that sub-agent's own, or
   the room's without one."
  [st room-id sub-id]
  (if sub-id
    (:history (sa/find-child st room-id sub-id))
    (get-in st [:rooms room-id :history])))

(defn- update-call-history [st room-id sub-id f]
  (if sub-id
    (update-in st [:rooms room-id :ext sa/ext-id :agents]
               (fn [as] (mapv #(if (= sub-id (:id %)) (update % :history f) %) as)))
    (update-in st [:rooms room-id :history] f)))

(defn- stamp-entry
  "Put `record`'s text on the history entry of the tool call that made it."
  [st room-id {:keys [call-id sub-id] :as record}]
  (if-not call-id
    st
    (update-call-history
     st room-id sub-id
     (fn [history]
       (if (vector? history)
         (if-let [i (some (fn [[i e]] (when (and (= :tool-call (:kind e)) (= call-id (:id e))) i))
                          (map-indexed vector history))]
           (assoc-in history [i :block-text] (b/text record))
           history)
         history)))))

(defn- put-block [st room-id record]
  (-> st
      (assoc-in [:rooms room-id :ext ext-id :blocks (:id record)] record)
      (stamp-entry room-id record)))

(defn add-block
  "Install a new block `:block` (a xi.blocks record)."
  [st {:keys [room-id block] :as ev}]
  (when (and (state/get-room st room-id) (:id block) (not (from-client? ev)))
    {:state (put-block st room-id block)}))

(defn update-block
  "Replace a block with the updated record `:block`."
  [st {:keys [room-id block] :as ev}]
  (when (and (find-block st room-id (:id block)) (not (from-client? ev)))
    {:state (put-block st room-id block)}))

(defn remove-block [st {:keys [room-id block-id] :as ev}]
  (when (and (find-block st room-id block-id) (not (from-client? ev)))
    {:state (update-in st [:rooms room-id :ext ext-id :blocks] dissoc block-id)}))

(defn read-block
  "The agent read the block's actions (`ui_state`): nothing unread any more."
  [st {:keys [room-id block-id] :as ev}]
  (when (and (find-block st room-id block-id) (not (from-client? ev)))
    {:state (assoc-in st [:rooms room-id :ext ext-id :blocks block-id :unread] 0)}))

(defn act
  "A user's action on a block: `{:block-id :action :target :value}`. An
   action the block doesn't take is dropped."
  [st {:keys [room-id block-id] :as ev}]
  (when-let [record (find-block st room-id block-id)]
    (when-let [{r :record} (b/act record (select-keys ev [:action :target :value])
                                  (state/event-user st ev) (or (:event/ts ev) (:updated-at record)))]
      {:state (put-block st room-id r)})))

(defn note-block
  "An action the host recorded on a block itself (an app view's tool call or
   context update): `{:block-id :action :target :value}`."
  [st {:keys [room-id block-id] :as ev}]
  (when-let [record (find-block st room-id block-id)]
    (when-not (from-client? ev)
      {:state (put-block st room-id (b/note record (select-keys ev [:action :target :value])
                                            (or (:event/ts ev) (:updated-at record))))})))

(defn running-call-id
  "The id of the still-running tool call `call` ({:name :arguments}) in the
   room's history, or in sub-agent `sub-id`'s; nil when none matches."
  [st room-id sub-id call]
  (let [entries (vec (call-history st room-id sub-id))
        i       (when call (dialog/permission-tool-index {:call call} entries 0))]
    (when (and i (= :running (:status (nth entries i))))
      (:id (nth entries i)))))

(defn app-open
  "A tool with a UI ran (xi.ext.mcp, a user extension's tool): resolve its
   view's resource in an effect, which adds the block (xi.ext.blocks.app).
   The call is still running now, not once the resource is read, so its id
   is taken here; a sub-agent's call (:sub-id) in that sub-agent's history."
  [st {:keys [room-id sub-id call] :as ev}]
  (when (and (state/get-room st room-id) (not (from-client? ev)))
    {:effects [[:ext.blocks/app-open (-> ev (dissoc :type) (assoc :call-id (running-call-id st room-id sub-id call)))]]}))

(def handlers
  {:ext.blocks/add    add-block
   :ext.blocks/update update-block
   :ext.blocks/remove remove-block
   :ext.blocks/read   read-block
   :ext.blocks/act    act
   :ext.blocks/note   note-block})
