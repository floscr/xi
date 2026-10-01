(ns xi.browser.proxy
  "Host-allowlist proxy for the headless Chrome behind xi.api.chrome.

   Chrome is started with this as its only way out (`--proxy-server`, loopback
   included via `--proxy-bypass-list=<-loopback>`), so page scripts, redirects,
   subresources and WebSockets reach the extension's declared hosts and nothing
   else — including xi's own server on localhost. Only HTTPS `CONNECT` tunnels
   to port 443 are served; plain-http requests and any other host get a 403."
  (:require [clojure.string :as str]
            ["node:net" :as net]))

(defn host-allowed?
  "True when `host` is one of `hosts` or a subdomain of one (\"amazon.de\"
   covers www.amazon.de). Case-insensitive; a trailing dot is ignored."
  [hosts host]
  (let [h (some-> host str/lower-case (str/replace #"\.$" ""))]
    (boolean
     (and (seq h)
          (some (fn [allowed]
                  (let [a (str/lower-case allowed)]
                    (or (= h a) (str/ends-with? h (str "." a)))))
                hosts)))))

(defn parse-connect
  "The CONNECT target of a request head (\"CONNECT host:443 HTTP/1.1\\r\\n…\")
   → {:host :port}, or nil for anything else (incl. bracketed IPv6)."
  [head]
  (when-let [[_ host port] (re-find #"^CONNECT ([^\s:\[\]]+):(\d+) HTTP/1\.[01]\r\n" head)]
    {:host host :port (js/parseInt port 10)}))

(defn- refuse! [^js sock]
  (.end sock "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n"))

(defn- tunnel!
  "Serve one client connection: read the request head, then either open the
   tunnel or refuse."
  [hosts ^js client]
  (let [buf (atom "")]
    (.on client "error" (fn [_] (.destroy client)))
    (letfn [(on-data [chunk]
              (swap! buf str (.toString chunk "latin1"))
              (let [end (str/index-of @buf "\r\n\r\n")]
                (cond
                  end
                  (do (.removeListener client "data" on-data)
                      (let [{:keys [host port]} (parse-connect @buf)
                            rest-bytes (subs @buf (+ end 4))]
                        (if (and host (= 443 port) (host-allowed? hosts host))
                          (let [^js upstream (.connect net #js {:host host :port port})]
                            (.on upstream "error" (fn [_] (.destroy client)))
                            (.on client "close" (fn [_] (.destroy upstream)))
                            (.on upstream "connect"
                                 (fn []
                                   (.write client "HTTP/1.1 200 Connection Established\r\n\r\n")
                                   (when (seq rest-bytes)
                                     (.write upstream (js/Buffer.from rest-bytes "latin1")))
                                   (.pipe client upstream)
                                   (.pipe upstream client))))
                          (refuse! client))))

                  (> (count @buf) 8192) (refuse! client))))]
      (.on client "data" on-data))))

(defn start!
  "Listen on an ephemeral loopback port, tunnelling only to `hosts`.
   → Promise<{:port n :close! (fn [])}>."
  [hosts]
  (js/Promise.
   (fn [resolve reject]
     (let [sockets (js/Set.)
           server  (.createServer net (fn [^js sock]
                                        (.add sockets sock)
                                        (.on sock "close" #(.delete sockets sock))
                                        (tunnel! hosts sock)))]
       (.on server "error" reject)
       (.listen server 0 "127.0.0.1"
                (fn []
                  (resolve {:port   (.-port (.address server))
                            :close! (fn []
                                      (.forEach sockets #(.destroy ^js %))
                                      (.close server))})))))))
