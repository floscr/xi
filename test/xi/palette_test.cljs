(ns xi.palette-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.palette :as palette]))

(defn- keys-of [actions] (mapv :key actions))

(deftest actions-test
  (testing "no room: only the room-independent actions"
    (is (= [:new-chat :keys :reload] (keys-of (palette/actions false)))))
  (testing "a room shows everything"
    (is (= [:new-chat :change-model :chat-settings :skills :git-status :copy-debug :keys :reload]
           (keys-of (palette/actions true)))))
  (testing "a pending (not-yet-created) web chat can still change its model"
    (is (= [:new-chat :change-model :chat-settings :keys :reload]
           (keys-of (palette/actions false true))))))

(deftest order-commands-test
  (testing "featured first in their order, the rest as registered"
    (is (= ["help" "new" "diff" "gtd" "swap"]
           (mapv :name (palette/order-commands
                        [{:name "gtd"} {:name "diff"} {:name "swap"}
                         {:name "new"} {:name "help"}]))))))
