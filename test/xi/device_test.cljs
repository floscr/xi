(ns xi.device-test
  "xi.device + xi.ext.device: which browser is asked, and that only its reply
   settles the request."
  (:require [cljs.test :refer [deftest is async testing]]
            [xi.device :as device]
            [xi.ext.device :as ext]
            [xi.rules :as rules]
            [xi.rules.defaults :as defaults]))

(def ^:private st
  {:connection {:user "root"
                :clients {"c1" {:room-id "r1" :platform "web" :user "alice" :visible? false}
                          "c2" {:room-id "r1" :platform "web" :user "alice" :visible? true}
                          "c3" {:room-id "r1" :platform "tui" :user "alice" :visible? true}
                          "c4" {:room-id "r1" :platform "web" :user "bob"   :visible? true}
                          "c5" {:room-id "r2" :platform "web" :user "alice" :visible? true}}}
   :rooms {"r1" {:history [{:kind :user :user "alice" :text "where am I?"}]}}})

(deftest pick-client-prefers-the-turn-users-visible-browser
  (is (= "c2" (device/pick-client st "r1" "alice")))
  (is (= "c4" (device/pick-client st "r1" "bob")))
  (testing "a hidden browser of theirs when none is visible"
    (is (= "c1" (device/pick-client (assoc-in st [:connection :clients "c2" :visible?] false)
                                    "r1" "alice"))))
  (testing "no web client of theirs in the room"
    (is (nil? (device/pick-client st "r1" "carol")))
    (is (nil? (device/pick-client st "r3" "alice"))))
  (is (= "c2" (device/turn-target st "r1")) "the turn user is whoever sent the last prompt"))

(defn- capture []
  (let [sent (atom [])]
    [sent (fn [ev] (swap! sent conj ev))]))

(deftest a-reply-from-the-asked-client-settles-the-request
  (async done
    (let [[sent dispatch!] (capture)
          p (device/request! dispatch! {:client-id "c2" :what :clock :timeout-ms 5000})
          {:keys [id] :as ask} (first @sent)]
      (is (= {:type :device/ask :target "c2" :what :clock :opts {:timeout-ms 5000}}
             (dissoc ask :id)))
      (device/settle! {:id id :client-id "c4" :result {:time-zone "Evil/Zone"}})
      (device/settle! {:id id :client-id "c2" :result {:time-zone "Europe/Vienna"}})
      (-> p
          (.then #(is (= {:time-zone "Europe/Vienna"} %) "the other client's reply was ignored"))
          (.finally done)))))

(deftest errors-timeouts-and-departures-reject
  (async done
    (let [[sent dispatch!] (capture)
          denied  (device/request! dispatch! {:client-id "c2" :what :location})
          slow    (device/request! dispatch! {:client-id "c2" :what :clock :timeout-ms 10})
          gone    (device/request! dispatch! {:client-id "c1" :what :location})
          message #(-> % (.then (constantly ::resolved)) (.catch ex-message))]
      (device/settle! {:id (:id (first @sent)) :client-id "c2" :error "location permission was denied"})
      (device/client-gone! "c1")
      (-> (js/Promise.all #js [(message denied) (message slow) (message gone)])
          (.then (fn [[d s g]]
                   (is (re-find #"permission was denied" d))
                   (is (re-find #"didn't answer" s))
                   (is (re-find #"disconnected" g))))
          (.finally done)))))

(deftest only-the-server-asks-a-browser
  (let [ask (get-in ext/extension [:handlers :device/ask])]
    (is (= [[:ws/send-to {:client-id "c2"
                          :event {:type :device/query :id "d1" :what :location :opts {}}}]]
           (:effects (ask {} {:type :device/ask :target "c2" :id "d1" :what :location :opts {}}))))
    (is (nil? (ask {} {:type :device/ask :target "c2" :id "d1" :what :location
                       :client-id "c4"}))
        "an event a client sent carries :client-id and is dropped"))
  (is (every? (:no-broadcast ext/extension) [:device/ask :device/reply])
      "a location is never echoed to the room"))

(deftest device-requests-ask-by-default-and-grants-stay-narrow
  (let [req {:tool :device :device "location" :extension "device-info"}]
    (is (= :ask (get-in (rules/first-match defaults/default-rules req) [:action :type])))
    (is (rules/matches? {:match {:tool :device :device "location"}} req))
    (is (not (rules/matches? {:match {:tool :device :device "clock"}} req)))
    (is (not (rules/matches? {:match {:device "clock"}} {:tool :net :host "x"}))
        "other requests read nothing")))
