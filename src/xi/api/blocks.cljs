(ns xi.api.blocks
  "Agent blocks for user extensions (xi.blocks): post a block into a chat
   without the model, such as a long job's progress or a report card, and
   change or read it later.

     (show!   ctx {:kind \"checklist\" :title \"Backup\" :data {:items […]}}) → the block id
     (update! ctx id {:data … :title …})                               → the id
     (state   ctx id)                       → {:kind :state :actions :unread}

   `:kind` is a built-in kind or one of the extension's own `:block-kinds`,
   by its short name. Each takes `:room-id` in its opts; without it the ctx's
   room is used (tool calls carry one; an :fx gets its room from its payload,
   so pass it). The block's frame names the extension as its source, and an
   extension can only change its own blocks. Invalid data throws an ex-info
   with the reason.

   Blocks are written with the app's own dispatch (xi.api.core/get-app-host),
   since :ext.blocks/* are not the extension's own events."
  (:require [clojure.string :as str]
            [xi.api.core :as core]
            [xi.blocks :as b]))

(defn- host [what]
  (or (core/get-app-host)
      (throw (ex-info (str "xi.api.blocks/" what ": blocks aren't available in this process") {}))))

(defn- room-of [ctx opts what]
  (let [room-id (or (:room-id opts) (:room-id ctx))
        {:keys [get-state]} (host what)]
    (cond
      (nil? room-id)
      (throw (ex-info (str "xi.api.blocks/" what ": no room — pass {:room-id …} (an :fx gets it from its payload)") {}))

      (nil? (get-in (get-state) [:rooms room-id]))
      (throw (ex-info (str "xi.api.blocks/" what ": unknown room " room-id) {:room-id room-id}))

      :else room-id)))

(defn- full-kind
  "`kind` as stored: a built-in name, else <ext-id>/<name> for the caller's
   own kinds (a short name is qualified)."
  [id kind extra]
  (let [k (str kind)
        own (str (name id) "/" k)]
    (cond
      (contains? b/kinds k)     k
      (contains? extra own)     own
      (str/starts-with? k (str (name id) "/")) k
      :else k)))

(defn- gen-id [id]
  (str (name id) "-" (.toString (js/Math.floor (* (js/Math.random) 0x7fffffff)) 36)))

(defn- own-block [ctx block-id opts what]
  (let [id      (core/caller ctx)
        room-id (room-of ctx opts what)
        record  (get-in ((:get-state (host what))) [:rooms room-id :ext b/ext-id :blocks block-id])]
    (when-not (and record (= {:type :ext :id id} (select-keys (:source record) [:type :id])))
      (throw (ex-info (str "xi.api.blocks/" what ": no block " (pr-str block-id) " of extension " (name id)
                           " in room " room-id)
                      {:block-id block-id})))
    [room-id record]))

(defn show!
  "Post a block → its id. `opts`: :kind :title :data :id :room-id."
  [ctx {:keys [kind title data] :as opts}]
  (let [id      (core/caller ctx)
        room-id (room-of ctx opts "show!")
        {:keys [dispatch! get-state]} (host "show!")
        room    (get-in (get-state) [:rooms room-id])
        extra   (core/block-kinds)
        block-id (or (b/safe-id (:id opts)) (gen-id id))
        {:keys [record error]}
        (b/make-record {:id block-id :kind (full-kind id kind extra) :title title :data data
                        :source {:type :ext :id id}
                        :anchor (count (:history room)) :at (js/Date.now) :extra extra})]
    (cond
      error (throw (ex-info (str "xi.api.blocks/show!: " error) {}))

      (get-in room [:ext b/ext-id :blocks block-id])
      (throw (ex-info (str "xi.api.blocks/show!: block " block-id " exists already; use update!") {}))

      (>= (count (get-in room [:ext b/ext-id :blocks])) b/max-blocks)
      (throw (ex-info (str "xi.api.blocks/show!: the chat has " b/max-blocks " blocks, the most it keeps") {}))

      :else
      (do (dispatch! {:type :ext.blocks/add :room-id room-id :block record})
          block-id))))

(defn update!
  "Change one of the extension's blocks → its id. `opts`: :data :title :room-id."
  [ctx block-id {:keys [data title] :as opts}]
  (let [[room-id record] (own-block ctx block-id opts "update!")
        {:keys [record error]} (b/update-record record {:data data :title title :at (js/Date.now)
                                                        :extra (core/block-kinds)})]
    (when error (throw (ex-info (str "xi.api.blocks/update!: " error) {})))
    ((:dispatch! (host "update!")) {:type :ext.blocks/update :room-id room-id :block record})
    block-id))

(defn state
  "What users did on one of the extension's blocks:
   {:kind :state :actions [{:action :target :value :user :at}] :unread n}."
  ([ctx block-id] (state ctx block-id nil))
  ([ctx block-id opts]
   (let [[_ record] (own-block ctx block-id opts "state")]
     (select-keys record [:kind :state :actions :unread]))))
