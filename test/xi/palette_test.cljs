(ns xi.palette-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.palette :as palette]))

(defn- keys-of [actions] (mapv :key actions))

(deftest actions-test
  (testing "no room: only the room-independent actions"
    (is (= [:new-chat :reload] (keys-of (palette/actions false)))))
  (testing "a room shows everything"
    (is (= [:new-chat :change-model :skills :git-status :copy-debug :reload]
           (keys-of (palette/actions true)))))
  (testing "a pending (not-yet-created) web chat can still change its model"
    (is (= [:new-chat :change-model :reload]
           (keys-of (palette/actions false true))))))
