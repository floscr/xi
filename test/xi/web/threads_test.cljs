(ns xi.web.threads-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.core.state :as state]
            [xi.web.threads :as threads]))

(defn- base-state []
  (-> (state/initial-state {:mode :client})
      (assoc-in [:rooms "r1"]
                {:id "r1"
                 :history [{:kind :user :text "go"}
                           {:kind :tool-call :id "t1" :tool "read" :arguments {:path "a"}}]
                 :ext {:subagents {:agents [{:id "sa-1" :status :done
                                             :history [{:kind :text :text "Found it."}]}]}}})))

(defn- run [st ev] ((get threads/handlers (:type ev)) st ev))

(deftest a-post-names-its-node
  (let [st (base-state)]
    (is (= {:index 1 :kind :tool-call :call-id "t1" :hint "read"}
           (threads/node-of st "r1" {:index 1})))
    (is (= {:sub-id "sa-1" :index 0 :kind :text :hint "Found it."}
           (threads/node-of st "r1" {:sub-id "sa-1" :index 0})))
    (is (= {:sub-id "sa-1"} (threads/node-of st "r1" {:sub-id "sa-1"})))
    (is (nil? (threads/node-of st "r1" {:sub-id "gone"})))
    (is (nil? (threads/node-of st "r1" {:index 7})))))

(deftest the-composer-asks-the-server
  (let [st  (-> (base-state) (assoc :web/thread-menu {:index 1}))
        st' (:state (run st {:type :thread/compose-open :room-id "r1" :index 1}))]
    (is (= {:room-id "r1" :node {:index 1 :kind :tool-call :call-id "t1" :hint "read"}}
           (:web/thread-compose st')))
    (is (nil? (:web/thread-menu st')) "opening it closes the menu")
    (testing "submit sends the question and closes the composer"
      (let [{:keys [state effects]} (run st' {:type :thread/compose-submit :text " why? "})]
        (is (nil? (:web/thread-compose state)))
        (is (= [[:ws/send {:type :subagent/ask :room-id "r1" :question "why?"
                           :node {:index 1 :kind :tool-call :call-id "t1" :hint "read"}}]]
               effects))))
    (is (nil? (run st' {:type :thread/compose-submit :text "  "})) "no question: nothing")
    (is (nil? (run (base-state) {:type :thread/compose-open :room-id "r1" :index 9}))
        "a gone block opens nothing")))

(deftest replies-and-collapse
  (is (= [[:ws/send {:type :subagent/ask :room-id "r1" :thread "ask-1" :question "and?"}]]
         (:effects (run (base-state) {:type :thread/reply :room-id "r1" :thread "ask-1" :text "and?"}))))
  (let [toggle #(:state (run % {:type :thread/toggle :thread "ask-1"}))
        st     (toggle (base-state))]
    (is (= #{"ask-1"} (:web/thread-collapsed st)))
    (is (= #{} (:web/thread-collapsed (toggle st))))))
