(ns xi.wire
  "Wire protocol — the same event maps the local app dispatches, serialized
   over WebSocket. Serialization is the whole protocol: the server broadcasts
   the room events it processes; clients mirror them through the same pure
   reducers (see xi.server.ws / xi.client.ws-transport).

   Transit (JSON flavour) is the codec — losslessly preserving keywords,
   namespaced keywords, and nesting between cljs peers (the web client is
   cljs too), while decoding far faster than the EDN reader, which matters
   on the client under a streaming turn's flood of broadcast events."
  (:require [cognitect.transit :as transit]))

;; Reader/writer instances are reusable across calls (transit resets its
;; per-message cache each read/write) — allocate once.
(def ^:private writer (transit/writer :json))
(def ^:private reader (transit/reader :json))

(defn encode
  "Event map → wire string. :remote? is transport-local, never sent.
   :event/id and :event/ts travel but are re-stamped by the receiving
   dispatcher."
  [event]
  (transit/write writer (dissoc event :remote?)))

(defn decode
  "Wire string → event map, or nil when unreadable or not an event."
  [data]
  (try
    (let [ev (transit/read reader (str data))]
      (when (and (map? ev) (keyword? (:type ev)))
        ev))
    (catch :default _ nil)))
