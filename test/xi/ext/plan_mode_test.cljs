(ns xi.ext.plan-mode-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.ext.plan-mode :as plan]))

(def tool-hook (get-in plan/extension [:hooks :tool-call]))
(def hook-state {:cwd "/project" :model "test"})

;; Helper to run tests with plan mode toggled on/off
;; The plan-mode state atom is module-private, but we can toggle
;; via the command handler.
(def toggle-fn (get-in (first (:commands plan/extension)) [:handler]))

(defn- with-plan-mode
  "Run f with plan mode enabled, then disable it."
  [f]
  (toggle-fn {})  ;; enable
  (try (f)
    (finally (toggle-fn {}))))  ;; disable

;; ── Plan Mode OFF (all pass-through) ——————————————————————————————

(deftest off-allows-everything
  (testing "when plan mode is off, all tools pass through"
    (let [write-call {:name "write" :arguments {:path "src/foo.cljs"}}
          bash-call {:name "bash" :arguments {:command "echo hi > foo.txt"}}]
      (is (= write-call (tool-hook write-call hook-state)))
      (is (= bash-call (tool-hook bash-call hook-state))))))

;; ── Plan Mode ON — write/edit —————————————————————————————————

(deftest on-blocks-write-to-source
  (testing "blocks write to source files"
    (with-plan-mode
      #(is (nil? (tool-hook {:name "write" :arguments {:path "src/foo.cljs"}} hook-state))))))

(deftest on-allows-write-to-todo
  (testing "allows write to tasks/todo.md"
    (with-plan-mode
      #(let [call {:name "write" :arguments {:path "tasks/todo.md"}}]
        (is (= call (tool-hook call hook-state)))))))

(deftest on-blocks-edit-to-source
  (testing "blocks edit to source files"
    (with-plan-mode
      #(is (nil? (tool-hook {:name "edit" :arguments {:path "src/foo.cljs"}} hook-state))))))

(deftest on-allows-edit-to-todo
  (testing "allows edit to tasks/todo.md"
    (with-plan-mode
      #(let [call {:name "edit" :arguments {:path "tasks/todo.md"}}]
        (is (= call (tool-hook call hook-state)))))))

;; ── Plan Mode ON — bash ───────────────────────────────────────

(deftest on-allows-read-only-bash
  (testing "allows read-only bash"
    (with-plan-mode
      #(let [call {:name "bash" :arguments {:command "cat src/foo.cljs"}}]
        (is (= call (tool-hook call hook-state)))))))

(deftest on-allows-grep-bash
  (testing "allows grep via bash"
    (with-plan-mode
      #(let [call {:name "bash" :arguments {:command "grep -r foo src/"}}]
        (is (= call (tool-hook call hook-state)))))))

(deftest on-blocks-write-bash
  (testing "blocks bash with redirect"
    (with-plan-mode
      #(is (nil? (tool-hook {:name "bash" :arguments {:command "echo hi > foo.txt"}} hook-state))))))

(deftest on-blocks-tee-bash
  (testing "blocks tee command"
    (with-plan-mode
      #(is (nil? (tool-hook {:name "bash" :arguments {:command "tee output.txt"}} hook-state))))))

(deftest on-blocks-mv-bash
  (testing "blocks mv command"
    (with-plan-mode
      #(is (nil? (tool-hook {:name "bash" :arguments {:command "mv foo.txt bar.txt"}} hook-state))))))

;; ── Toggle command returns data ────────────────────────────────────

(deftest toggle-returns-command-result
  (testing "toggle returns a command-result event, not nil"
    (let [result (toggle-fn {})]
      (is (= :command-result (:type result)))
      (is (= "plan" (:command result)))
      (is (string? (:text result)))
      ;; Toggle back off
      (toggle-fn {}))))

;; ── Read-only tools always allowed ─────────────────────────────────

(deftest on-allows-read-tool
  (testing "read tool passes through in plan mode"
    (with-plan-mode
      #(let [call {:name "read" :arguments {:path "src/foo.cljs"}}]
        (is (= call (tool-hook call hook-state)))))))

(deftest on-allows-grep-tool
  (testing "grep tool passes through in plan mode"
    (with-plan-mode
      #(let [call {:name "grep" :arguments {:pattern "foo"}}]
        (is (= call (tool-hook call hook-state)))))))
