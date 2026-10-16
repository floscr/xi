(ns xi.ext.blocks.handlers
  "Pure reducers over a room's blocks ([:rooms rid :ext :blocks :blocks]),
   registered by the server half (xi.ext.blocks) and replayed by every web
   client on the mirrored events (xi.ext.blocks.web). Browser-safe.

   Only the server's own code adds, updates or removes a block: those events
   carry no :client-id. A client sends `:ext.blocks/act` alone, and a block
   action only ever changes its own block (xi.blocks/act).

   Each change also stamps the block's text on its tool call's history entry
   as :block-text, which the TUI shows under the call (xi.client.view)."
  (:require [xi.blocks :as b]
            [xi.core.state :as state]))

(def ext-id b/ext-id)

(defn blocks [st room-id]
  (get-in st [:rooms room-id :ext ext-id :blocks]))

(defn find-block [st room-id block-id]
  (get (blocks st room-id) block-id))

(defn- from-client? [ev] (some? (:client-id ev)))

(defn- stamp-entry
  "Put `record`'s text on the history entry of the tool call that made it."
  [st room-id {:keys [call-id] :as record}]
  (if-not call-id
    st
    (update-in st [:rooms room-id :history]
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

(def handlers
  {:ext.blocks/add    add-block
   :ext.blocks/update update-block
   :ext.blocks/remove remove-block
   :ext.blocks/read   read-block
   :ext.blocks/act    act})
