(ns xi.web.buffer-views-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.buffer-views :as buffer-views]))

(deftest registered-kinds
  (let [view (fn [_state _dispatch! _room id _buffer] [:div (str id)])]
    (buffer-views/register! :test-ext {:test-kind {:view view :icon :star}})
    (testing "a registered kind resolves to its view and icon"
      (is (= view (buffer-views/view :test-kind)))
      (is (= :star (buffer-views/icon :test-kind))))
    (testing "an unknown kind has neither"
      (is (nil? (buffer-views/view :no-such-kind)))
      (is (nil? (buffer-views/icon :no-such-kind))))))
