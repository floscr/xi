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

(deftest osc52-puts-text-on-the-clipboard
  (testing "base64 decodes as UTF-8, whichever selection is named"
    (is (= "hi" (terminal/osc52-text "c;aGk=")))
    (is (= "hi" (terminal/osc52-text ";aGk=")))
    (is (= "äö→" (terminal/osc52-text "c;w6TDtuKGkg=="))))
  (testing "a query or broken payload sets nothing"
    (is (nil? (terminal/osc52-text "c;?")))
    (is (nil? (terminal/osc52-text "c")))
    (is (nil? (terminal/osc52-text "c;!!!")))))
