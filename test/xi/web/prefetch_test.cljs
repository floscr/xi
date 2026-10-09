(ns xi.web.prefetch-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.prefetch :as prefetch]))

(deftest neighbours
  (testing "nearest first, alternating below and above"
    (is (= ["d" "b" "e" "a"] (prefetch/neighbours ["a" "b" "c" "d" "e" "f"] "c"))))
  (testing "clipped at the ends"
    (is (= ["b" "c"] (prefetch/neighbours ["a" "b" "c"] "a")))
    (is (= ["b" "a"] (prefetch/neighbours ["a" "b" "c"] "c"))))
  (testing "a session listed in two groups counts once"
    (is (= ["b" "a"] (prefetch/neighbours ["a" "b" "a" "c"] "c"))))
  (testing "an unlisted session has none"
    (is (= [] (prefetch/neighbours ["a" "b"] "x")))))

(def ^:private messages
  [{:type :text :role "user" :text "hi"}
   {:type :text :role "assistant" :text "hello"}])

(deftest peek-result
  (testing "a neighbour's history goes to the memory tier only"
    (let [st {:web/route {:page :chat :session-id "other"}}
          {:keys [state effects]} (prefetch/peek-result st {:session-id "s1" :messages messages
                                                            :msg-hash 7 :msg-count 2})
          [[fx {:keys [session-id slice]}]] effects]
      (is (nil? state))
      (is (= :cache/remember-room fx))
      (is (= "s1" session-id))
      (is (seq (:history slice)))
      (is (= {:msg-hash 7 :msg-count 2} (select-keys slice [:msg-hash :msg-count])))))
  (testing "the viewed session with nothing painted paints it at once"
    (let [st {:web/route {:page :chat :session-id "s1"}}]
      (is (seq (get-in (prefetch/peek-result st {:session-id "s1" :messages messages})
                       [:state :web/cache "s1" :history])))))
  (testing "an already painted view is left alone"
    (let [st {:web/route {:page :chat :session-id "s1"}
              :web/cache {"s1" {:history [{:kind :user :text "cached"}]}}}]
      (is (nil? (:state (prefetch/peek-result st {:session-id "s1" :messages messages}))))))
  (testing "an empty reply does nothing"
    (is (nil? (prefetch/peek-result {} {:session-id "s1" :messages []})))))
