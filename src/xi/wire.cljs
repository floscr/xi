(ns xi.wire
  "Wire protocol — the same event maps the local app dispatches, serialized
   as EDN strings over WebSocket. Serialization is the whole protocol: the
   server broadcasts the room events it processes; clients mirror them
   through the same pure reducers (see xi.server.ws / xi.client.ws-transport).

   EDN (not JSON like master) keeps keywords and nesting intact between
   cljs peers — the rebuilt web client (phase 7) is cljs too."
  (:require [cljs.reader :as reader]))

(defn encode
  "Event map → wire string. :remote? is transport-local, never sent.
   :event/id and :event/ts travel but are re-stamped by the receiving
   dispatcher."
  [event]
  (pr-str (dissoc event :remote?)))

(defn decode
  "Wire string → event map, or nil when unreadable or not an event."
  [data]
  (try
    (let [ev (reader/read-string (str data))]
      (when (and (map? ev) (keyword? (:type ev)))
        ev))
    (catch :default _ nil)))
