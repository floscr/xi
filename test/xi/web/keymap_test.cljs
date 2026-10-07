(ns xi.web.keymap-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.keymap :as keymap]))

(deftest event-actions
  (keymap/register-action! {:id :chat/new :event {:type :room/new}})
  (testing "an :event action runs by dispatching its event"
    (let [sent (atom [])]
      ((:run (get (keymap/registered-actions) :chat/new)) {} #(swap! sent conj %) nil)
      (is (= [{:type :room/new}] @sent))))
  (testing "event-shortcut finds the key of the action dispatching that event"
    (is (some? (keymap/shortcut {} :chat/new)))
    (is (= (keymap/shortcut {} :chat/new)
           (keymap/event-shortcut {} {:type :room/new}))))
  (testing "no match → nil"
    (is (nil? (keymap/event-shortcut {} {:type :room/new :extra 1})))
    (is (nil? (keymap/event-shortcut {} nil))))
  (keymap/unregister-action! :chat/new))
