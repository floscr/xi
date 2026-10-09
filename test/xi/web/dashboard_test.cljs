(ns xi.web.dashboard-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.dashboard :as dashboard]))

(def ^:private cards
  [{:id :a/one   :title "One"   :order 30}
   {:id :a/two   :title "Two"   :order 10 :load {:type :a/load-two}}
   {:id :a/three :title "Three"}
   {:id :a/four  :title "Four"  :order 10 :when :four? :load {:type :a/load-four}}])

(deftest visible-cards-order-hide-and-when
  (testing "by :order (default 100), then registration order"
    (is (= [:a/two :a/four :a/one :a/three]
           (map :id (dashboard/visible-cards cards {:four? true} #{})))))
  (testing ":when false hides, the user's hidden set hides"
    (is (= [:a/two :a/one :a/three] (map :id (dashboard/visible-cards cards {} #{}))))
    (is (= [:a/one :a/three] (map :id (dashboard/visible-cards cards {} #{:a/two})))))
  (testing "a throwing :when hides the card instead of the page"
    (is (= [] (dashboard/visible-cards [{:id :a/x :when (fn [_] (throw (js/Error. "no")))}] {} #{})))))

(deftest load-events-skip-hidden-cards-only
  (is (= [{:type :a/load-two} {:type :a/load-four}] (dashboard/load-events cards #{})))
  (is (= [{:type :a/load-four}] (dashboard/load-events cards #{:a/two}))
      ":when is not consulted: the load is what fills a card"))

(deftest toggle-hidden
  (is (= #{:a/one} (dashboard/toggle-hidden nil :a/one)))
  (is (= #{} (dashboard/toggle-hidden #{:a/one} :a/one))))

(deftest dashboard-route
  (is (dashboard/dashboard-route? {:page :home}))
  (is (not (dashboard/dashboard-route? {:page :home :dir :projects})))
  (is (not (dashboard/dashboard-route? {:page :home :dir "/p"})))
  (is (not (dashboard/dashboard-route? {:page :chat}))))

(deftest toggle-card-syncs-the-user-state
  (let [{:keys [state effects]} ((:dashboard/toggle-card dashboard/handlers)
                                 {:web/dashboard-hidden #{:a/one}} {:id :a/two})]
    (is (= #{:a/one :a/two} (:web/dashboard-hidden state)))
    (is (= [[:ws/send {:type :user-state/set :key :dashboard-hidden :value [:a/one :a/two]}]]
           effects))))

(deftest load-tap-fires-on-the-dashboard-only
  (let [seen (atom [])
        tap  (dashboard/load-tap #(swap! seen conj %))]
    (tap {:type :route/navigate} {:web/route {:page :home}})
    (tap {:type :connection/status :connected? true} {:web/route {:page :home}})
    (tap {:type :connection/status :connected? false} {:web/route {:page :home}})
    (tap {:type :route/navigate} {:web/route {:page :home :dir :projects}})
    (tap {:type :lobby/state} {:web/route {:page :home}})
    (is (= [{:type :dashboard/load} {:type :dashboard/load}] @seen))))
