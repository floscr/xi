(ns xi.web.tour-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.tour :as tour]))

(def ^:private frames
  [[0 "out" "auth/hello"]
   [5 "in" "ok"]
   [6 "out" "projects/web-list"]
   [9 "in" "projects"]
   [900 "out" "room/join"]
   [920 "in" "joined"]])

(def ^:private gates #{"auth/hello" "room/join"})

(deftest advance-plays-frames-and-waits-at-gates
  (testing "a gate the client already sent is consumed"
    (is (= {:op :emit :t 5 :data "ok" :i 2 :sent {"auth/hello" 0}}
           (tour/advance frames gates {"auth/hello" 1} 0))))
  (testing "a recorded send that is no gate is skipped"
    (is (= {:op :emit :t 9 :data "projects" :i 4 :sent {}}
           (tour/advance frames gates {} 2))))
  (testing "an unmet gate waits"
    (is (= {:op :wait :t 900 :type "room/join" :i 4 :sent {}}
           (tour/advance frames gates {} 4))))
  (testing "the end"
    (is (= :done (:op (tour/advance frames gates {} 6))))))

(deftest delay-ms-cuts-idle-time
  (is (= 20 (tour/delay-ms 900 920)))
  (is (= 1500 (tour/delay-ms 0 60000)))
  (is (= 0 (tour/delay-ms 10 5))))

(deftest shift-times-moves-epochs-and-iso-dates
  (is (= "[\"~:ts\",1700000060000,\"~:at\",\"2026-01-01T00:01:00.000Z\",\"r-1234\"]"
         (tour/shift-times "[\"~:ts\",1700000000000,\"~:at\",\"2026-01-01T00:00:00.000Z\",\"r-1234\"]"
                           60000))))

(deftest fill-echoes-puts-the-clients-values-back
  (is (= "[\"~:join-token\",\"abc\"]"
         (tour/fill-echoes "[\"~:join-token\",\"@@join-token@@\"]" {:join-token "abc"})))
  (is (= "x" (tour/fill-echoes "x" nil))))

(deftest type-stops-end-on-the-full-text-in-small-steps
  (let [text  "Cap the backoff in src/upload.ts"
        stops (tour/type-stops text)]
    (is (= (count text) (peek stops)))
    (is (every? #(<= 1 % 3) (map - stops (cons 0 stops))))))

(deftest event-type-is-the-keyword-without-colon
  (is (= "room/join" (tour/event-type {:type :room/join}))))
