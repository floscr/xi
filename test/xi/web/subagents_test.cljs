(ns xi.web.subagents-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.subagents :as s]))

(deftest group-view-roundtrip
  (is (= "sa-1" (s/group-view-id (s/group-view "sa-1"))))
  (is (nil? (s/group-view-id "sa-1")))
  (is (nil? (s/group-view-id :chat))))

(deftest state-and-counts
  (let [asks (s/asks-by-sub [{:id "d1" :sub-id "b"} {:id "d2"}])
        kids [{:id "a" :status :done} {:id "b" :status :running}
              {:id "c" :status :running} {:id "d" :status :error}]]
    (is (= :ask (s/state-of asks (second kids))))
    (is (= :done (s/state-of asks (first kids))))
    (is (= {:ask 1 :running 1 :finished 2 :error 1 :total 4} (s/counts asks kids)))
    (is (= :ask (s/batch-state (s/counts asks kids))))
    (is (= :error (s/batch-state (s/counts {} [{:status :done} {:status :error}]))))
    (is (= :done (s/batch-state (s/counts {} [{:status :done} {:status :stopped}]))))))

(deftest summary-line
  (testing "an ask wins, without the main chat's prefix"
    (is (= "clj: allow running `git`?"
           (s/summary {"a" [{:message "Sub-agent “x”: clj: allow running `git`?"}]}
                      {:id "a" :status :running}))))
  (testing "running: the newest tool"
    (is (= "Read…" (s/summary {} {:id "a" :status :running
                                   :history [{:kind :tool-call :tool "Grep"}
                                             {:kind :text :text "hm"}
                                             {:kind :tool-call :tool "Read"}]})))
    (is (= "starting…" (s/summary {} {:id "a" :status :running}))))
  (testing "done: the answer's first line as plain text"
    (is (= "Proposed 2 commits" (s/summary {} {:id "a" :status :done
                                               :result "\n## **Proposed** 2 `commits`\nmore"})))
    (is (= "no answer" (s/summary {} {:id "a" :status :done})))
    (is (= "stopped" (s/summary {} {:id "a" :status :stopped})))))

(deftest shared-label-and-titles
  (is (= "Commit plan" (s/shared-label ["Commit plan: /home/a/x" "Commit plan: /home/a/y"])))
  (is (= "Review file" (s/shared-label ["Review file a.clj" "Review file b.clj"])))
  (is (nil? (s/shared-label ["Commit plan: /a"])))
  (is (nil? (s/shared-label ["Fix the bug" "Write docs"])))
  (is (= "/home/a/x" (s/child-title "Commit plan" "Commit plan: /home/a/x")))
  (is (= "Other" (s/child-title "Commit plan" "Other")))
  (is (= "Commit plan" (s/child-title "Commit plan" "Commit plan")))
  (is (= "Do it" (s/label {:task "\n Do it\nnow"}))))

(deftest exchanges-the-history-no-longer-holds
  (let [xs [{:answer "Hello!"} {:question "say yo" :answer "Yo!"} {:question "bye" :answer "Bye"}]]
    (is (= xs (s/exchanges-before [] xs)) "reloaded: all of them")
    (is (= [] (s/exchanges-before [{:kind :text :text "Hello!"} {:kind :user :text "say yo"}
                                   {:kind :text :text "Yo!"} {:kind :user :text "bye"}
                                   {:kind :text :text "Bye"}]
                                  xs))
        "live: the history holds them all")
    (is (= (subvec xs 0 2) (s/exchanges-before [{:kind :user :text "bye"} {:kind :text :text "Bye"}] xs))
        "reloaded, then replied: the ones before that reply")
    (is (= [] (s/exchanges-before [{:kind :text :text "Hello!"}] nil)) "never replied to")))

(deftest grouped-by-turn
  (let [history [{:kind :user :text "first\nmore"}       ; 0
                 {:kind :tool-call :id "c1"}              ; 1
                 {:kind :tool-call :id "c2"}              ; 2
                 {:kind :user :text "second"}             ; 3
                 {:kind :text :text "ok"}]                ; 4
        kids    [{:id "a" :call-id "c1"} {:id "b" :call-id "c2"}
                 {:id "x" :anchor 5} {:id "y" :anchor 5}]]
    (is (= [{:index 0 :prompt "first" :batches [[(kids 0)] [(kids 1)]]}
            {:index 3 :prompt "second" :batches [[(kids 2) (kids 3)]]}]
           (s/by-turn history kids)))
    (is (= ["x" "y"] (map :id (s/batch-of history kids "y"))))
    (is (= [{:index -1 :prompt nil :batches [[{:id "z" :anchor 0}]]}]
           (s/by-turn [] [{:id "z" :anchor 0}])))))
