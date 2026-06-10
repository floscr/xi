(ns xi.ext.plan-mode-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.core.state :as state]
            [xi.ext.plan-mode :as plan]))

(defn- state-with-plan [enabled?]
  (-> (state/initial-state {:mode :standalone})
      (assoc-in [:rooms "r1"] (state/make-room "r1" {:ext {:plan-mode {:enabled? enabled?}}}))
      (assoc :active-room "r1")))

(defn- gate [st tool-call]
  (plan/tool-gate tool-call {:get-state (constantly st) :room-id "r1"}))

(deftest disabled-passes-everything
  (let [st (state-with-plan false)]
    (testing "with plan mode off every tool passes through unchanged"
      (doseq [tc [{:name "write" :arguments {:path "src/foo.cljs"}}
                  {:name "edit"  :arguments {:path "src/foo.cljs"}}
                  {:name "bash"  :arguments {:command "rm -rf /"}}
                  {:name "read"  :arguments {:path "src/foo.cljs"}}]]
        (is (= tc (gate st tc)))))))

(deftest enabled-blocks-mutations
  (let [st (state-with-plan true)]
    (testing "write/edit allowed only to the plan file"
      (is (some? (gate st {:name "write" :arguments {:path "tasks/todo.md"}})))
      (is (some? (gate st {:name "edit"  :arguments {:path "tasks/todo.md"}})))
      (is (nil?  (gate st {:name "write" :arguments {:path "src/foo.cljs"}})))
      (is (nil?  (gate st {:name "edit"  :arguments {:path "src/foo.cljs"}}))))
    (testing "bash allowed only when read-only"
      (is (some? (gate st {:name "bash" :arguments {:command "ls -la"}})))
      (is (some? (gate st {:name "bash" :arguments {:command "git status"}})))
      (is (nil?  (gate st {:name "bash" :arguments {:command "rm -rf build"}})))
      (is (nil?  (gate st {:name "bash" :arguments {:command "echo hi > out.txt"}})))
      (is (nil?  (gate st {:name "bash" :arguments {:command "sudo reboot"}}))))
    (testing "read-only tools always pass"
      (doseq [tc [{:name "read" :arguments {:path "src/foo.cljs"}}
                  {:name "grep" :arguments {:pattern "x"}}
                  {:name "ls"   :arguments {:path "."}}]]
        (is (= tc (gate st tc)))))))
