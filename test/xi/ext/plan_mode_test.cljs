(ns xi.ext.plan-mode-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.core.state :as state]
            [xi.ext.plan-mode :as plan]))

(defn- with-room [enabled?]
  (-> (state/initial-state {:mode :standalone})
      (assoc-in [:rooms "r1"] (state/make-room "r1" {:ext {:plan-mode {:enabled? enabled?}}}))
      (assoc :active-room "r1")))

(defn- toggle-handler [] (-> plan/extension :commands first :handler))

(deftest toggle-flips-the-room-flag
  (let [handler (toggle-handler)]
    (testing "off → on, and a status entry is appended"
      (let [{st' :state} (handler (with-room false) {:room-id "r1"})]
        (is (true? (get-in st' [:rooms "r1" :ext :plan-mode :enabled?])))
        (is (= :status (:kind (last (get-in st' [:rooms "r1" :history])))))))
    (testing "on → off"
      (let [{st' :state} (handler (with-room true) {:room-id "r1"})]
        (is (false? (get-in st' [:rooms "r1" :ext :plan-mode :enabled?])))))))

(deftest badge-shows-only-when-on
  (let [badge (:prompt-badge plan/extension)]
    (is (nil? (badge (with-room false))) "no badge when plan mode is off")
    (is (string? (badge (with-room true))) "a badge when plan mode is on")))

(deftest extension-has-no-tool-gate
  (testing "the read-only policy now lives in the rules engine, not this ext"
    (is (nil? (:tool-gate plan/extension)))))
