(ns xi.error-info-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.error-info :as ei]))

(def rate-limit-error
  {:type "rate_limit"
   :info {:status "rejected" :resetsAt 1791032400 :rateLimitType "five_hour"
          :overageStatus "rejected" :isUsingOverage false
          :unifiedWindows {:five_hour {:utilization 1 :resetsAt 1791032400}
                           :seven_day {:utilization 0.27 :resetsAt 1791446400}}}})

(deftest describe-rate-limit
  (let [d (ei/describe rate-limit-error)]
    (is (= :rate-limit (:kind d)))
    (is (= "Session limit reached" (:title d)))
    (is (= 1791032400 (:resets-at d)))
    (is (= [{:label "5-hour window" :pct 100} {:label "Weekly" :pct 27}]
           (:windows d)))
    (is (string? (:raw d))))
  (testing "weekly window"
    (is (= "Weekly limit reached"
           (:title (ei/describe (assoc-in rate-limit-error [:info :rateLimitType] "seven_day"))))))
  (testing "missing windows / unknown window type"
    (let [d (ei/describe {:type "rate_limit" :info {:resetsAt 1}})]
      (is (= "Usage limit reached" (:title d)))
      (is (= [] (:windows d))))))

(deftest describe-messages
  (is (= :rate-limit
         (:kind (ei/describe {:type "error" :message "You've hit your session limit · resets 3pm"}))))
  (is (= :auth
         (:kind (ei/describe {:type "error" :message "Claude authentication failed — the login has expired."}))))
  (is (= "Usage credits required"
         (:title (ei/describe {:message "Claude Code returned an error result: Fable 5.1 requires usage credits. Switch to another model."}))))
  (is (= :overloaded (:kind (ei/describe {:message "API Error: 529 Overloaded"}))))
  (is (= :network (:kind (ei/describe {:message "fetch failed: ECONNRESET"}))))
  (testing "raw message is kept"
    (is (= "fetch failed" (:raw (ei/describe {:message "fetch failed"})))))
  (testing "unrecognised errors fall through"
    (is (nil? (ei/describe {:message "something odd happened"})))
    (is (nil? (ei/describe "weird")))))

(deftest minutes-until
  (is (= 54 (ei/minutes-until 1000 (- 1000000 (* 54 60 1000)))))
  (testing "rounds up"
    (is (= 1 (ei/minutes-until 1000 (- 1000000 1000)))))
  (testing "past or unknown"
    (is (nil? (ei/minutes-until 1000 2000000)))
    (is (nil? (ei/minutes-until nil 0)))))

(deftest format-minutes
  (is (= "54 min" (ei/format-minutes 54)))
  (is (= "2 h" (ei/format-minutes 120)))
  (is (= "2 h 5 min" (ei/format-minutes 125))))
