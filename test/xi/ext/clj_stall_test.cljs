(ns xi.ext.clj-stall-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.ext.clj :as clj-ext]))

(def ^:private evals
  {1 {:room-key "r1" :sub-id nil  :code "(loop [] (recur))" :started 0}
   2 {:room-key "r1" :sub-id nil  :code "(+ 1 2)" :started 0
      :result #js {:text "=> 3" :isError false}}
   3 {:room-key "r1" :sub-id "s1" :code "(sh \"ls\")" :started 0
      :result #js {:text "boom" :isError true}}
   4 {:room-key "r2" :sub-id nil  :code "(sleep 1)" :started 0}})

(deftest running-detached-is-per-room-for-every-agent
  (is (= [1] (map first (clj-ext/running-detached evals "r1"))))
  (is (= [4] (map first (clj-ext/running-detached evals "r2"))))
  (is (empty? (clj-ext/running-detached evals "r3"))))

(deftest finished-detached-belongs-to-the-agent-that-ran-it
  (is (= [2] (map first (clj-ext/finished-detached evals "r1" nil))))
  (is (= [3] (map first (clj-ext/finished-detached evals "r1" "s1"))))
  (is (empty? (clj-ext/finished-detached evals "r2" nil))))

(deftest texts
  (testing "busy names the eval, its age and both ways out"
    (let [t (clj-ext/busy-text 95000 [[1 (get evals 1)]])]
      (is (str/includes? t "`(loop [] (recur))` (running 95s)"))
      (is (str/includes? t "{\"wait\": true}"))
      (is (str/includes? t "{\"abort\": true}"))))
  (testing "a long first line is cut, later lines dropped"
    (let [t (clj-ext/busy-text 0 [[1 {:code (str (apply str (repeat 100 "x")) "\n(more)")
                                      :started 0}]])]
      (is (str/includes? t (str (apply str (repeat 80 "x")) "…`")))
      (is (not (str/includes? t "(more)")))))
  (testing "an earlier result says whether it failed"
    (is (= "The earlier eval `(+ 1 2)` finished:\n=> 3"
           (clj-ext/earlier-text (get evals 2))))
    (is (str/includes? (clj-ext/earlier-text (get evals 3)) "finished with an error:\nboom")))
  (is (str/includes? (clj-ext/stalled-text 60) "still running after 60s")))
