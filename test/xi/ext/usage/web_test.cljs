(ns xi.ext.usage.web-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.ext.usage.web :as web]
            [xi.usage :as usage]))

(def now (js/Date.parse "2026-10-09T10:30:00Z"))

(defn- iso [ms] (.toISOString (js/Date. ms)))

(def five-hour
  {:id "five-hour" :label "5-hour" :used 14 :period-ms usage/five-hours-ms
   :resets-at (iso (+ now (* 1000 60 168)))})

(def seven-day
  {:id "seven-day" :label "7-day" :used 11 :period-ms usage/week-ms
   :resets-at (iso (+ now (* 6 usage/day-ms) usage/hour-ms))})

(deftest resets-note-test
  (testing "a short window counts down and shows the elapsed part"
    (is (= "Resets in 2h 48m · 2h 12m of 5h"
           (web/resets-note five-hour (usage/window-stats five-hour [] now)))))
  (testing "a long window names the day it is on"
    (let [n (web/resets-note seven-day (usage/window-stats seven-day [] now))]
      (is (re-find #"^Resets .* · day 1 of 7$" n))))
  (testing "no stats: just the time"
    (is (re-find #"^Resets " (web/resets-note five-hour nil))))
  (testing "no reset time: nothing"
    (is (nil? (web/resets-note {:used 3} nil)))))

(deftest pace-note-test
  (is (= "≈6%/h · on pace for 32% at reset"
         (web/pace-note (usage/window-stats five-hour [] now))))
  (is (= "≈6%/h · on pace for 32% at reset · last hour ≈10%"
         (web/pace-note (usage/window-stats five-hour [[(- now (* 2 usage/hour-ms)) 4]] now))))
  (is (= "≈11%/day · on pace for 80% at reset · last 24h ≈11%"
         (web/pace-note (usage/window-stats seven-day [] now))))
  (is (nil? (web/pace-note nil)))
  (is (nil? (web/pace-note {:unit :hour})))
  (is (nil? (web/pace-note (usage/window-stats (assoc five-hour :used 0) [] now)))
      "an idle window has no pace to report"))

(deftest handlers-test
  (let [{:keys [handlers]} web/extension]
    (testing "fetch asks the server and marks loading"
      (let [{:keys [state effects]} ((:usage/fetch handlers) {} {})]
        (is (true? (get-in state [:web/usage :loading?])))
        (is (= [[:ws/send {:type :usage/fetch}]] effects))))
    (testing "a reply replaces the slice"
      (let [{:keys [state]} ((:usage/state handlers) {:web/usage {:loading? true}}
                             {:readings [{:id "a"}] :history nil :fetched-at 5})]
        (is (= {:readings [{:id "a"}] :history {} :fetched-at 5 :loading? false}
               (:web/usage state)))))
    (testing "navigating elsewhere is not ours"
      (is (nil? ((:route/navigate handlers) {} {:page :chat})))
      (is (some? ((:route/navigate handlers) {} {:page :usage}))))))

(deftest refetch-tap-test
  (let [sent (atom [])
        tap  ((first (:taps web/extension)) #(swap! sent conj %))
        on-page {:web/route {:page :usage} :web/usage {:fetched-at 10}}]
    (tap {:type :lobby/state :usage-at 20} on-page)
    (tap {:type :lobby/state :usage-at 10} on-page)
    (tap {:type :lobby/state :usage-at 20} (assoc-in on-page [:web/route :page] :chat))
    (tap {:type :lobby/state} on-page)
    (is (= [{:type :usage/fetch}] @sent) "only a newer poll while on the page")))
