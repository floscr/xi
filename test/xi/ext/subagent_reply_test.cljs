(ns xi.ext.subagent-reply-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.core.state :as state]
            [xi.ext.subagent.handlers :as h]))

(def ^:private finished
  {:id "sa-1" :label "Review" :task "review the diff" :status :done
   :history [{:kind :tool-call :id "t1" :tool "read" :status :done}
             {:kind :text :text "Looks fine."}]
   :result "Looks fine." :started 1 :ended 2 :anchor 1 :ended-anchor 2
   :cli-session-id "cli-1" :session-id "s-child"
   :parent-context? false})

(defn- base-state [child]
  (-> (state/initial-state {:mode :server})
      (assoc-in [:rooms "r1"]
                {:id "r1" :cwd "/x" :agent {:model "m" :system "base"}
                 :session {:id "s1"}
                 :history [{:kind :user :text "hi"} {:kind :text :text "hello"}]
                 :ext {:subagents {:agents [child]}}})))

(defn- child [st]
  (first (get-in st [:rooms "r1" :ext :subagents :agents])))

(defn- reply! [st text]
  ((:subagent/reply h/handlers) st {:type :subagent/reply :room-id "r1" :sub-id "sa-1"
                                    :text text}))

(defn- end! [st]
  (:state ((:subagent/turn-end h/handlers) st {:room-id "r1" :sub-id "sa-1"})))

(deftest a-reply-continues-the-same-child
  (let [{st :state [[fx payload]] :effects} (reply! (base-state finished) "  and the tests?  ")
        c (child st)]
    (testing "the child runs again, the prompt in its history"
      (is (= :running (:status c)))
      (is (= {:kind :user :text "and the tests?"} (peek (:history c))))
      (is (= [{:answer "Looks fine." :status :done} {:question "and the tests?"}]
             (:exchanges c)))
      (is (= 2 (:ended-anchor c)) "the result post stays put while it streams"))
    (testing "the turn resumes its conversation"
      (is (= :subagent/start fx))
      (is (= "and the tests?" (:prompt payload)))
      (is (= {:cli-session-id "cli-1" :session-id "s-child"} (:resume payload)))
      (is (= {:kind :user :text "review the diff"} (first (:history payload)))
          "sessionless providers replay the task first")
      (is (not (re-find #"The parent conversation" (:system payload)))
          "the spawn's :parent-context? false still holds"))))

(deftest the-reply-s-answer-becomes-the-result
  (let [st (-> (base-state finished)
               (reply! "and the tests?") :state
               (update-in [:rooms "r1" :ext :subagents :agents 0 :history]
                          conj {:kind :text :text "No tests cover it."})
               end!)
        c  (child st)]
    (is (= :done (:status c)))
    (is (= "No tests cover it." (:result c)))
    (is (= {:question "and the tests?" :answer "No tests cover it." :status :done}
           (peek (:exchanges c))))
    (is (= (:exchanges c) (:exchanges (h/saved-child c))) "exchanges are saved with the link")))

(deftest a-reply-without-text-shows-nothing-of-the-old-answer
  (testing "no answer yet: the result is not the previous turn's"
    (is (nil? (h/final-text [{:kind :text :text "old"} {:kind :user :text "q"}])))
    (is (= "new" (h/final-text [{:kind :text :text "old"} {:kind :user :text "q"}
                                {:kind :text :text "new"}])))))

(deftest a-reply-while-running-steers-its-turn
  (let [running (assoc finished :status :running)
        res     (reply! (base-state running) " also check the tests ")]
    (testing "no new turn, the text goes into the running one"
      (is (nil? (:state res)))
      (is (= [[:subagent/steer {:room-id "r1" :sub-id "sa-1"
                                :text "also check the tests" :images nil}]]
             (:effects res))))
    (testing "once the turn took it, it joins the history"
      (let [st (:state ((:subagent/steered h/handlers) (base-state running)
                        {:room-id "r1" :sub-id "sa-1" :text "also check the tests"}))
            c  (child st)]
        (is (= {:kind :user :text "also check the tests" :steered? true}
               (peek (:history c))))
        (is (= :running (:status c)))
        (is (nil? (:exchanges c)) "a steer is no exchange of its own")))))

(deftest no-reply-when-blank-or-unknown
  (is (nil? (reply! (base-state finished) "   ")))
  (is (nil? ((:subagent/reply h/handlers) (base-state finished)
             {:room-id "r1" :sub-id "nope" :text "x"}))))
