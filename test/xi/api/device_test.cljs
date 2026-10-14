(ns xi.api.device-test
  "xi.api.device — the rules decide first, then the turn user's browser in
   the room is asked."
  (:require [cljs.test :refer [deftest is async use-fixtures]]
            [xi.api.core :as core]
            [xi.api.device :as device]
            [xi.device :as transport]))

(def ^:private gated (atom []))
(def ^:private asked (atom []))

(def ^:private st
  {:connection {:user "root"
                :clients {"c1" {:room-id "r1" :platform "web" :user "root" :visible? true}}}
   :rooms {"r1" {:history []} "r2" {:history []}}})

(use-fixtures :each
  {:before (fn []
             (reset! gated [])
             (reset! asked [])
             (core/set-app-host! {:dispatch! (fn [ev] (swap! asked conj ev))
                                  :get-state (fn [] st)}))
   :after #(core/set-app-host! nil)})

(defn- ctx [id]
  {:extension id :xi.api/token (core/issue-token! id) :room-id "r1"})

(defn- rejection [p]
  (-> p (.then (constantly ::resolved)) (.catch ex-message)))

(defn- allow-all [_ctx m]
  (swap! gated conj m)
  (js/Promise.resolve m))

(def ^:private real-gate core/gate!)

(defn- with-gate
  "Run the async test `f` (→ Promise) with `gate` deciding, restored once it
   settles: with-redefs would be undone before the gate's .then runs."
  [gate f]
  (set! core/gate! gate)
  (-> (f) (.finally #(set! core/gate! real-gate))))

(deftest location-is-gated-then-asks-the-rooms-browser
  (async done
    (-> (with-gate allow-all
          (fn []
            (let [p (device/location (ctx :where) {:high-accuracy? true :evil 1})]
              (-> (js/Promise.resolve nil)
                  ;; let the gate's .then run, then answer as the browser
                  (.then (fn [_] (js/Promise. (fn [r] (js/setTimeout r 0)))))
                  (.then (fn [_]
                           (let [{:keys [id] :as ev} (first @asked)]
                             (is (= [{:tool :device :device "location"}] @gated))
                             (is (= {:type :device/ask :target "c1" :what :location
                                     :opts {:high-accuracy? true
                                            :timeout-ms transport/default-timeout-ms}}
                                    (dissoc ev :id)))
                             (transport/settle! {:id id :client-id "c1" :result {:latitude 48.2}}))
                           p))
                  (.then #(is (= {:latitude 48.2} %)))))))
        (.finally done))))

(deftest refusals
  (async done
    (-> (with-gate allow-all
          (fn []
            (-> (js/Promise.all
                 #js [(rejection (device/clock {:extension :where :room-id "r1"}))
                      (rejection (device/clock (dissoc (ctx :where) :room-id)))
                      (rejection (device/clock (ctx :where) {:room-id "r2"}))])
                (.then (fn [[forged no-room no-browser]]
                         (is (re-find #"not an extension ctx" forged))
                         (is (re-find #"no room" no-room))
                         (is (re-find #"no browser is open" no-browser))
                         (is (empty? @asked) "no browser was asked"))))))
        (.finally done))))

(deftest a-denied-request-asks-no-browser
  (async done
    (-> (with-gate (fn [_ _] (js/Promise.reject (ex-info "xi.api: device location was denied" {})))
          (fn []
            (-> (rejection (device/location (ctx :where)))
                (.then (fn [msg]
                         (is (re-find #"denied" msg))
                         (is (empty? @asked)))))))
        (.finally done))))
