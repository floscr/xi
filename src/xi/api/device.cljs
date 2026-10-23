(ns xi.api.device
  "The user's device, read from the browser they are chatting from:

     (clock ctx)    → {:epoch-ms :local \"2026-10-13T14:03:12+02:00\" :weekday
                       :time-zone \"Europe/Vienna\" :utc-offset-minutes :locale}
     (location ctx) → {:latitude :longitude :accuracy-m :altitude-m
                       :altitude-accuracy-m :heading-deg :speed-mps :timestamp}
     (camera ctx)   → {:frames [{:data <base64 jpeg> :width :height :taken-at}]
                       :media-type \"image/jpeg\" :facing :device}

   All take an optional opts map: :room-id (default the ctx's, as tool calls
   carry one) and :timeout-ms (default xi.device/default-timeout-ms);
   `location` also :high-accuracy? and :max-age-ms (a cached fix that old is
   fine); `camera` :facing (\"environment\", the rear camera, or \"user\"),
   :count and :interval-ms (a burst of frames), :max-width and :quality
   (JPEG, 0–1). Keys whose value the device doesn't know are left out.

   Each call is a rules request `{:tool :device :device \"clock\"|\"location\"
   |\"camera\"}`, asked by default like :net. The browser asked is the turn
   user's, attached to the room (xi.device/pick-client); a camera read
   prefers the browser that shares its camera (the web client's Camera page).
   Without one the call rejects. For a location or a picture the browser asks
   its own permission too, and only gives one on a secure page (https, or
   localhost)."
  (:require [xi.api.core :as core]
            [xi.device :as device]))

(defn- host []
  (or (core/get-app-host)
      (throw (ex-info "xi.api.device: there are no browsers in this process" {}))))

(defn- query
  [ctx what {:keys [timeout-ms] :as opts}]
  (let [room-id (or (:room-id opts) (:room-id ctx))]
    (-> (js/Promise.resolve nil)
        (.then (fn [_]
                 (core/caller ctx)
                 (when-not room-id
                   (throw (ex-info "xi.api.device: no room — pass {:room-id …} (an :fx gets it from its payload)" {})))
                 (core/gate! (assoc ctx :room-id room-id) {:tool :device :device (name what)})))
        (.then (fn [_]
                 (let [{:keys [dispatch! get-state]} (host)
                       target (device/turn-target (get-state) room-id what)]
                   (if-not target
                     (throw (ex-info "xi.api.device: no browser is open on this chat" {:room-id room-id}))
                     (device/request! dispatch! {:client-id  target
                                                 :what       what
                                                 :opts       (dissoc opts :room-id :timeout-ms)
                                                 :timeout-ms timeout-ms}))))))))

(defn clock
  "The browser's clock, time zone and locale → Promise of a map."
  ([ctx] (clock ctx nil))
  ([ctx opts] (query ctx :clock (select-keys opts [:room-id :timeout-ms]))))

(defn location
  "A location fix from the browser → Promise of a map."
  ([ctx] (location ctx nil))
  ([ctx opts]
   (query ctx :location (select-keys opts [:room-id :timeout-ms :high-accuracy? :max-age-ms]))))

(defn camera
  "One picture, or a burst of `:count` pictures `:interval-ms` apart, from the
   browser's camera → Promise of {:frames [...] :media-type :facing :device}.
   The default timeout grows with the burst."
  ([ctx] (camera ctx nil))
  ([ctx {:keys [count interval-ms timeout-ms] :as opts}]
   (let [burst-ms (* (max 0 (dec (or count 1))) (or interval-ms 1000))]
     (query ctx :camera
            (-> (select-keys opts [:room-id :facing :count :interval-ms :max-width :quality])
                (assoc :timeout-ms (or timeout-ms (+ device/default-timeout-ms burst-ms))))))))
