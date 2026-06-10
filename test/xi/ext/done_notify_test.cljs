(ns xi.ext.done-notify-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.core.state :as state]
            [xi.ext.done-notify :as done-notify]))

(def ^:private toggle (get-in done-notify/extension [:handlers :ext.done-notify/toggle]))
(def ^:private on-turn-end (get-in done-notify/extension [:handlers :agent/turn-end]))

(defn- state-with-room [{:keys [enabled? queued]}]
  (-> (state/initial-state {:mode :standalone})
      (assoc-in [:rooms "r1"]
                (cond-> (state/make-room "r1" {:ext {:done-notify {:enabled? enabled?}}
                                               :session {:name "Fix the bug"}})
                  queued (assoc-in [:agent :queued] queued)))
      (assoc :active-room "r1")))

(deftest toggle-flips-room-state
  (let [st (state-with-room {:enabled? false})
        on  (:state (toggle st {:room-id "r1"}))]
    (testing "toggling on sets the room-scoped flag + status line"
      (is (true? (get-in on [:rooms "r1" :ext :done-notify :enabled?])))
      (is (= {:kind :status :text "Desktop notifications: ON"}
             (last (get-in on [:rooms "r1" :history])))))
    (testing "toggling again turns it back off"
      (let [off (:state (toggle on {:room-id "r1"}))]
        (is (false? (get-in off [:rooms "r1" :ext :done-notify :enabled?])))
        (is (= "Desktop notifications: OFF"
               (:text (last (get-in off [:rooms "r1" :history])))))))))

(deftest turn-end-notifies-only-when-armed
  (testing "enabled + clean turn → desktop notification with session title"
    (let [st (state-with-room {:enabled? true})]
      (is (= [[:notify/desktop {:title "Fix the bug"}]]
             (:effects (on-turn-end st {:room-id "r1"}))))))
  (testing "disabled → no notification"
    (let [st (state-with-room {:enabled? false})]
      (is (nil? (on-turn-end st {:room-id "r1"})))))
  (testing "aborted turn → no notification"
    (let [st (state-with-room {:enabled? true})]
      (is (nil? (on-turn-end st {:room-id "r1" :aborted? true})))))
  (testing "a queued prompt is about to drain → no notification"
    (let [st (state-with-room {:enabled? true :queued [{:text "next"}]})]
      (is (nil? (on-turn-end st {:room-id "r1"}))))))
