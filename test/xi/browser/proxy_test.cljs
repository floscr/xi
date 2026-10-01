(ns xi.browser.proxy-test
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.browser.chrome :as chrome]
            [xi.browser.proxy :as proxy]
            ["node:net" :as net]))

(deftest host-allowed-covers-subdomains-only
  (let [hosts ["amazon.de" "willhaben.at"]]
    (is (proxy/host-allowed? hosts "amazon.de"))
    (is (proxy/host-allowed? hosts "www.amazon.de"))
    (is (proxy/host-allowed? hosts "M.Media.Amazon.DE"))
    (is (proxy/host-allowed? hosts "www.amazon.de."))
    (is (not (proxy/host-allowed? hosts "evilamazon.de")))
    (is (not (proxy/host-allowed? hosts "amazon.de.evil.com")))
    (is (not (proxy/host-allowed? hosts "127.0.0.1")))
    (is (not (proxy/host-allowed? hosts "")))
    (is (not (proxy/host-allowed? hosts nil)))))

(deftest parse-connect-reads-only-connect-requests
  (is (= {:host "www.amazon.de" :port 443}
         (proxy/parse-connect "CONNECT www.amazon.de:443 HTTP/1.1\r\nHost: www.amazon.de:443\r\n\r\n")))
  (is (nil? (proxy/parse-connect "GET http://amazon.de/ HTTP/1.1\r\n\r\n")))
  (is (nil? (proxy/parse-connect "CONNECT [::1]:443 HTTP/1.1\r\n\r\n"))))

(defn- send-head
  "Write `head` to the proxy on `port` → Promise<first response line>."
  [port head]
  (js/Promise.
   (fn [resolve]
     (let [^js sock (.connect net #js {:host "127.0.0.1" :port port})
           buf      (atom "")]
       (.on sock "connect" #(.write sock head))
       (.on sock "data" (fn [c] (swap! buf str c)))
       (.on sock "close" #(resolve (first (str/split-lines @buf))))
       (.on sock "error" #(resolve (str "error: " (.-message %))))))))

(deftest refuses-undeclared-hosts-loopback-and-plain-http
  (async done
    (-> (proxy/start! ["example.com"])
        (.then (fn [{:keys [port close!]}]
                 (-> (js/Promise.all
                      #js [(send-head port "CONNECT 127.0.0.1:7474 HTTP/1.1\r\n\r\n")
                           (send-head port "CONNECT evil.com:443 HTTP/1.1\r\n\r\n")
                           (send-head port "CONNECT example.com:22 HTTP/1.1\r\n\r\n")
                           (send-head port "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n\r\n")])
                     (.then (fn [lines]
                              (is (every? #(str/starts-with? % "HTTP/1.1 403") lines)
                                  (pr-str (vec lines)))))
                     (.finally close!))))
        (.catch (fn [e] (is false (str "unexpected: " (ex-message e)))))
        (.finally done))))

(deftest chrome-runs-behind-the-proxy-on-a-pipe
  (let [args (set (chrome/chrome-args "/tmp/profile" 4321))]
    (is (contains? args "--proxy-server=http://127.0.0.1:4321"))
    (is (contains? args "--proxy-bypass-list=<-loopback>"))
    (is (contains? args "--remote-debugging-pipe"))
    (is (not-any? #(str/starts-with? % "--remote-debugging-port") args))
    (is (contains? args "--user-data-dir=/tmp/profile"))))
