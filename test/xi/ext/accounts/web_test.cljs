(ns xi.ext.accounts.web-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.ext.accounts.web :as web]
            [xi.web.palette-items :as palette-items]))

(def ^:private handle (:handlers web/extension))

(def ^:private providers
  [{:name "claude" :label "Claude" :accounts? true :login? true
    :accounts [{:id "1" :email "a@x.io" :alias "pro" :active? true}
               {:id "2" :email "b@x.io" :alias "apple" :active? false}]}
   {:name "opencode" :label "OpenCode Go" :accounts? true :login? true :accounts []
    :sign-in {:url "https://o/device" :user-code "AB-12"}}])

(deftest entering-the-page-fetches
  (is (= [[:ws/send {:type :accounts/fetch}]]
         (:effects ((:route/navigate handle) {} {:page :accounts}))))
  (is (nil? ((:route/navigate handle) {} {:page :usage}))))

(deftest a-button-sends-the-words-of-its-command
  (let [{:keys [state effects]} ((:accounts/run handle) {:web/accounts {:result {:text "old"}}}
                                                        {:words ["claude" "switch" "2"]})]
    (is (= [[:ws/send {:type :accounts/run :words ["claude" "switch" "2"]}]] effects))
    (is (:busy? (:web/accounts state)) "a switch answers at once: the buttons wait")
    (is (nil? (get-in state [:web/accounts :result])) "the last result goes"))
  (testing "a sign-in answers when it settles: nothing waits on it"
    (is (not (get-in ((:accounts/run handle) {} {:words ["claude" "login"]})
                     [:state :web/accounts :busy?])))))

(deftest results-and-state-land
  (let [st (-> {:web/accounts {:busy? true :loading? true}}
               (#(:state ((:accounts/state handle) % {:providers providers})))
               (#(:state ((:accounts/result handle) % {:result {:text "ok" :tone :success}}))))]
    (is (= providers (get-in st [:web/accounts :providers])))
    (is (= {:text "ok" :tone :success} (get-in st [:web/accounts :result])))
    (is (not (get-in st [:web/accounts :busy?])))
    (is (not (get-in st [:web/accounts :loading?])))))

(deftest the-palette-switches-to-accounts-that-arent-live
  (let [rows (filter #(= :accounts/run (get-in % [:event :type]))
                     (palette-items/items {:web/accounts {:providers providers}}))]
    (is (= [{:label "Switch Claude to b@x.io (apple)" :icon :user
             :event {:type :accounts/run :words ["claude" "switch" "2"]}}]
           rows))))
