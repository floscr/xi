(ns xi.server.shadow-relay
  "Relays a dev web build's shadow-cljs live-reload socket
   (/api/remote-relay) to the local shadow server. Over https the page can't
   open ws://<host>:9630 (mixed content), so resources/public/index.html
   points the devtools client at the page's own origin and xi.server.ws
   hands the upgrade here. shadow checks the server-token itself.")

(def ^:private shadow-port 9630)

(defn relay-path? [pathname]
  (= pathname "/api/remote-relay"))

(defn upstream-url [^js req]
  (let [url (js/URL. (.-url req))]
    (str "ws://127.0.0.1:" shadow-port (.-pathname url) (.-search url))))

(defn- relayed?
  "Whether the Bun socket `ws` was upgraded with {:relay upstream-url}."
  [^js ws]
  (some? (.. ws -data -relay)))

(defn- close! [^js ws]
  (try (.close ws) (catch :default _ nil)))

(defn- open!
  "Connect the upstream socket; client messages sent before it opens queue up."
  [^js ws]
  (let [data (.-data ws)
        up   (js/WebSocket. (.-relay data))
        queue #js []]
    (set! (.-queue data) queue)
    (set! (.-upstream data) up)
    (set! (.-onopen up) (fn []
                          (doseq [m (array-seq queue)] (.send up m))
                          (set! (.-length queue) 0)))
    (set! (.-onmessage up) (fn [^js e]
                             (try (.send ws (.-data e)) (catch :default _ nil))))
    ;; An upstream error is followed by close; both end the client socket.
    (set! (.-onclose up) (fn [_] (close! ws)))))

(defn- message! [^js ws data]
  (let [d  (.-data ws)
        up (.-upstream d)]
    (case (.-readyState up)
      0 (.push (.-queue d) data)
      1 (.send up data)
      nil)))

(defn- close-upstream! [^js ws]
  (some-> (.. ws -data -upstream) close!))

(defn wrap-websocket
  "Bun.serve `:websocket` handlers that send relayed sockets here and every
   other socket to `handlers`."
  [^js handlers]
  (js/Object.assign
   #js {} handlers
   #js {:open    (fn [ws] (if (relayed? ws) (open! ws) (.open handlers ws)))
        :message (fn [ws data]
                   (if (relayed? ws) (message! ws data) (.message handlers ws data)))
        :close   (fn [ws code reason]
                   (if (relayed? ws) (close-upstream! ws) (.close handlers ws code reason)))}))
