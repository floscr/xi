(ns xi.ext.browser-open-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.core.state :as state]
            [xi.ext.browser-open :as browser-open]))

(defn- run [st]
  (let [handler (-> browser-open/extension :commands first :handler)]
    (handler st {:room-id "r"})))

(defn- with-session [st]
  (assoc-in st [:rooms "r"] (assoc (state/make-room "r") :session {:id "sid-1"})))

(deftest opens-the-port-in-state
  (testing "the server/standalone port from state, not a hardcoded default"
    (let [{:keys [effects]} (run (with-session (state/initial-state {:mode :server :port 7476})))]
      (is (= [[:browser/open {:url "http://localhost:7476/chat/sid-1"}]] effects))))
  (testing "falls back to 7474 when state carries no port"
    (let [{:keys [effects]} (run (with-session (state/initial-state)))]
      (is (= [[:browser/open {:url "http://localhost:7474/chat/sid-1"}]] effects)))))

(deftest no-session-no-effect
  (is (empty? (:effects (run (assoc-in (state/initial-state {:port 7476})
                                       [:rooms "r"] (state/make-room "r")))))))
