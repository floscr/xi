(ns xi.ext.device
  "Server half of the device round trip (xi.device): sends a request to one
   browser and hands its reply back. The browser half is xi.ext.device.web.

   Neither event is ever echoed to a room: a location is for the asking
   extension only, not for every device attached to the chat."
  (:require [xi.device :as device]))

(defn- ask
  "Forward a request to the target browser. Only the server asks: an event a
   client sent carries :client-id, and is dropped."
  [_st {:keys [client-id target id what opts]}]
  (when (and target (nil? client-id))
    {:effects [[:ws/send-to {:client-id target
                             :event     {:type :device/query :id id :what what :opts opts}}]]}))

(defn- reply [_st ev]
  {:effects [[:device/settle (select-keys ev [:id :client-id :result :error])]]})

(defn- disconnect [_st {:keys [client-id]}]
  {:effects [[:device/client-gone {:client-id client-id}]]})

(def extension
  {:id              :device
   :handlers        {:device/ask        ask
                     :device/reply      reply
                     :client/disconnect disconnect}
   :fx              {:device/settle      (fn [_ payload] (device/settle! payload))
                     :device/client-gone (fn [_ {:keys [client-id]}]
                                           (device/client-gone! client-id))}
   :roomless-events #{:device/reply}
   :no-broadcast    #{:device/ask :device/reply :device/query}})
