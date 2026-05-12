(ns xi.state.session-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.state.session :as state.session]))

;; ── session-title ─────────────────────────────────────────────────────────────

(deftest session-title-returns-name
  (testing "returns session name when present"
    (is (= "my task" (state.session/session-title
                      {:session {:name "my task"}})))))

(deftest session-title-nil-when-missing
  (testing "returns nil when session has no name"
    (is (nil? (state.session/session-title {:session {}})))
    (is (nil? (state.session/session-title {})))))

;; ── session-id ────────────────────────────────────────────────────────────────

(deftest session-id-returns-id
  (testing "returns session id"
    (is (= "abc-123" (state.session/session-id
                      {:session {:id "abc-123"}})))))

(deftest session-id-nil-when-missing
  (testing "returns nil when no session"
    (is (nil? (state.session/session-id {})))))

;; ── cli-session-id ────────────────────────────────────────────────────────────

(deftest cli-session-id-returns-value
  (testing "returns CLI session id"
    (is (= "sess_xyz" (state.session/cli-session-id
                       {:session {:cli-session-id "sess_xyz"}})))))

(deftest cli-session-id-nil-when-missing
  (testing "returns nil when not set"
    (is (nil? (state.session/cli-session-id {:session {}})))))

;; ── cwd ───────────────────────────────────────────────────────────────────────

(deftest cwd-returns-value
  (testing "returns working directory"
    (is (= "/home/user/project" (state.session/cwd
                                 {:cwd "/home/user/project"})))))

(deftest cwd-nil-when-missing
  (testing "returns nil when not set"
    (is (nil? (state.session/cwd {})))))

;; ── model ─────────────────────────────────────────────────────────────────────

(deftest model-returns-value
  (testing "returns model name"
    (is (= "claude-sonnet-4" (state.session/model
                              {:model "claude-sonnet-4"})))))

(deftest model-nil-when-missing
  (testing "returns nil when not set"
    (is (nil? (state.session/model {})))))

;; ── Composition ───────────────────────────────────────────────────────────────

(deftest full-state-map
  (testing "all accessors work on a complete state map"
    (let [state {:session {:name "refactor auth"
                           :id "019abc"
                           :cli-session-id "sess_123"}
                 :model "claude-sonnet-4"
                 :effort "high"
                 :cwd "/home/user/project"}]
      (is (= "refactor auth" (state.session/session-title state)))
      (is (= "019abc" (state.session/session-id state)))
      (is (= "sess_123" (state.session/cli-session-id state)))
      (is (= "claude-sonnet-4" (state.session/model state)))
      (is (= "/home/user/project" (state.session/cwd state))))))
