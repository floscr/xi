(ns xi.events-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.runtime.events :as events]))

(deftest emit-delivers-to-subscriber
  (testing "emit delivers event to type-specific subscriber"
    (let [bus (events/create-bus)
          received (atom nil)]
      ((:subscribe! bus) :foo (fn [e] (reset! received e)))
      ((:emit! bus) {:type :foo :data 42})
      (is (= {:type :foo :data 42} @received)))))

(deftest emit-does-not-cross-types
  (testing "subscriber only receives matching event types"
    (let [bus (events/create-bus)
          received (atom [])]
      ((:subscribe! bus) :foo (fn [e] (swap! received conj e)))
      ((:emit! bus) {:type :bar :data 1})
      ((:emit! bus) {:type :foo :data 2})
      (is (= [{:type :foo :data 2}] @received)))))

(deftest wildcard-receives-all
  (testing "wildcard subscriber receives all event types"
    (let [bus (events/create-bus)
          received (atom [])]
      ((:subscribe! bus) :* (fn [e] (swap! received conj (:type e))))
      ((:emit! bus) {:type :foo})
      ((:emit! bus) {:type :bar})
      (is (= [:foo :bar] @received)))))

(deftest unsubscribe-stops-delivery
  (testing "unsubscribe fn stops future deliveries"
    (let [bus (events/create-bus)
          received (atom 0)
          unsub ((:subscribe! bus) :foo (fn [_] (swap! received inc)))]
      ((:emit! bus) {:type :foo})
      (is (= 1 @received))
      (unsub)
      ((:emit! bus) {:type :foo})
      (is (= 1 @received)))))

(deftest multiple-subscribers
  (testing "multiple subscribers for same type all receive"
    (let [bus (events/create-bus)
          a (atom 0)
          b (atom 0)]
      ((:subscribe! bus) :foo (fn [_] (swap! a inc)))
      ((:subscribe! bus) :foo (fn [_] (swap! b inc)))
      ((:emit! bus) {:type :foo})
      (is (= 1 @a))
      (is (= 1 @b)))))

(deftest unsubscribe-all-clears-everything
  (testing "unsubscribe-all stops all delivery"
    (let [bus (events/create-bus)
          received (atom 0)]
      ((:subscribe! bus) :foo (fn [_] (swap! received inc)))
      ((:subscribe! bus) :* (fn [_] (swap! received inc)))
      ((:unsubscribe-all! bus))
      ((:emit! bus) {:type :foo})
      (is (= 0 @received)))))

(deftest handler-error-does-not-break-others
  (testing "error in one handler does not prevent other handlers"
    (let [bus (events/create-bus)
          received (atom 0)
          orig-error js/console.error]
      ;; Suppress expected console.error output during this test
      (set! js/console.error (fn [& _]))
      ((:subscribe! bus) :foo (fn [_] (throw (js/Error. "boom"))))
      ((:subscribe! bus) :foo (fn [_] (swap! received inc)))
      ((:emit! bus) {:type :foo})
      (set! js/console.error orig-error)
      (is (= 1 @received)))))
