(ns xi.device
  "Ask a browser in a chat about its device: the clock and time zone, a
   location fix, or a picture from its camera. The server half of a round trip:

     xi.api.device ─request!─▶ :device/ask ──:ws/send-to──▶ the web client
                                (xi.ext.device)              :device/query
                                                             (xi.ext.device.web)
     promise ◀──settle!── :device/reply ◀────────────────── its answer

   Requests wait here, keyed by id, until the asked client replies, leaves or
   the timeout passes. A reply only settles the request when it comes from the
   client that was asked (the server stamps :client-id, a client can't forge
   it). Not a sandbox namespace: user extensions reach it through
   xi.api.device, which gates every call by the rules first."
  (:require [xi.core.state :as state]))

(def default-timeout-ms
  "How long a request waits for the browser: long enough for its location
   permission prompt to be answered."
  60000)

;; request id → {:client-id :resolve :reject :timer}
(defonce ^:private pending (atom {}))

(defonce ^:private seq-no (atom 0))

(defn- web-clients-of
  "`user`'s web clients, `[cid client]` pairs in id order; in `room-id` when
   given, else anywhere."
  [st room-id user]
  (for [[cid c] (sort-by key (get-in st [:connection :clients]))
        :when (and (or (nil? room-id) (= room-id (:room-id c)))
                   (= "web" (:platform c))
                   (= user (:user c)))]
    [cid c]))

(defn- first-where [pred clients]
  (some (fn [[cid c]] (when (pred c) cid)) clients))

(defn pick-client
  "The web client in `room-id` to ask: one of `user`'s (the turn's user) that
   is visible, else any of theirs. nil when no browser of theirs is attached.

   A `:camera` read prefers a browser of theirs that shares its camera (the
   Camera page sets `:camera?` on its client): in the room first, then in any
   room — the phone propped up at the beamer may well sit in another chat.
   Without one it falls back to the visible browser like the other reads."
  ([st room-id user] (pick-client st room-id user nil))
  ([st room-id user what]
   (let [mine (web-clients-of st room-id user)]
     (or (when (= :camera what)
           (or (first-where :camera? mine)
               (first-where :camera? (web-clients-of st nil user))))
         (first-where :visible? mine)
         (ffirst mine)))))

(defn- finish! [id]
  (when-let [p (get @pending id)]
    (swap! pending dissoc id)
    (js/clearTimeout (:timer p))
    p))

(defn request!
  "Ask `client-id` for `what` (:clock, :location or :camera) with `opts` →
   Promise of the browser's answer map. `dispatch!` is the app's own;
   rejects on the browser's error, its departure or after `timeout-ms`."
  [dispatch! {:keys [client-id what opts timeout-ms]}]
  (js/Promise.
   (fn [resolve reject]
     (let [id (str "dev-" (swap! seq-no inc) "-" (js/Date.now))
           ms (or timeout-ms default-timeout-ms)
           timer (js/setTimeout
                  (fn []
                    (when (finish! id)
                      (reject (ex-info (str "the browser didn't answer within "
                                            (js/Math.round (/ ms 1000)) "s")
                                       {:what what}))))
                  ms)]
       (swap! pending assoc id {:client-id client-id :resolve resolve :reject reject
                                :timer timer})
       (dispatch! {:type :device/ask :target client-id :id id :what what
                   :opts (assoc (or opts {}) :timeout-ms ms)})))))

(defn settle!
  "A :device/reply from `client-id`: resolve its request with `result`, or
   reject it with `error`. Replies to unknown ids or from another client are
   ignored."
  [{:keys [id client-id result error]}]
  (when (= client-id (get-in @pending [id :client-id]))
    (let [{:keys [resolve reject]} (finish! id)]
      (if error
        (reject (ex-info (str "the browser couldn't: " error) {}))
        (resolve result)))))

(defn client-gone!
  "Reject every request waiting on `client-id`, which disconnected."
  [client-id]
  (doseq [[id p] @pending :when (= client-id (:client-id p))]
    (finish! id)
    ((:reject p) (ex-info "the browser disconnected before answering" {}))))

(defn turn-target
  "The client a tool call in `room-id` asks, per pick-client and the room's
   turn user."
  ([st room-id] (turn-target st room-id nil))
  ([st room-id what]
   (pick-client st room-id (state/turn-user st room-id) what)))
