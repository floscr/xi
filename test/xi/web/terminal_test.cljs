(ns xi.web.terminal-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.terminal :as terminal]))

(deftest osc-names-a-view-action
  (testing "a payload naming an allowed action"
    (is (= :palette/open (terminal/osc-action "palette/open")))
    (is (= :buffers/switch (terminal/osc-action " buffers/switch\n"))))
  (testing "anything else is ignored: output can carry the sequence"
    (is (nil? (terminal/osc-action "chat/delete")))
    (is (nil? (terminal/osc-action "permission/allow")))
    (is (nil? (terminal/osc-action "")))
    (is (nil? (terminal/osc-action nil)))))
