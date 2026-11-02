(ns xi.api.time-test
  (:require [cljs.test :refer [deftest is]]
            [xi.api.time :as time]))

(deftest today-is-the-local-date
  (let [d (js/Date.)]
    (is (re-matches #"\d{4}-\d{2}-\d{2}" (time/today)))
    (is (= [(.getFullYear d) (inc (.getMonth d)) (.getDate d)]
           (mapv js/parseInt (.split (time/today) "-"))))))
