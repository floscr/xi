(ns xi.ext.core-test
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [xi.ext.core :as ext]))

;; ── Helpers ───────────────────────────────────────────────────────────────────

(defn- reset-registry!
  "Clear the extension registry and hook state between tests."
  []
  ;; Re-init the private atoms via the public API
  (ext/set-state! {})
  ;; Unfortunately the registry is module-private, so we re-register per test.
  ;; Each test registers only the hooks it needs on a fresh atom.
  ;; We work around this by testing through the public API.
  )

;; ── Hook State ────────────────────────────────────────────────────────────────

(deftest set-state-replaces-state
  (testing "set-state! replaces the hook state entirely"
    (ext/set-state! {:model "a" :cwd "/tmp"})
    (is (= {:model "a" :cwd "/tmp"} (ext/get-state)))
    (ext/set-state! {:model "b"})
    (is (= {:model "b"} (ext/get-state)))))

(deftest update-state-merges
  (testing "update-state! merges into existing state"
    (ext/set-state! {:model "a" :cwd "/tmp"})
    (ext/update-state! {:effort "high"})
    (is (= {:model "a" :cwd "/tmp" :effort "high"} (ext/get-state)))))

(deftest update-state-overwrites-keys
  (testing "update-state! overwrites conflicting keys"
    (ext/set-state! {:model "a"})
    (ext/update-state! {:model "b"})
    (is (= {:model "b"} (ext/get-state)))))

;; ── dispatch-hook with state injection ────────────────────────────────────────

(deftest dispatch-hook-injects-state
  (testing "hooks receive the persistent hook state"
    (let [received (atom nil)]
      (ext/register-extension!
       {:name "test-state-inject"
        :hooks {:agent-end (fn [state] (reset! received state))}})
      (ext/set-state! {:session {:name "my session"} :cwd "/project"})
      (ext/dispatch-hook :agent-end)
      (is (= "my session" (get-in @received [:session :name])))
      (is (= "/project" (:cwd @received))))))

(deftest dispatch-hook-merges-event-ctx
  (testing "event-specific context is merged on top of hook state"
    (let [received (atom nil)]
      (ext/register-extension!
       {:name "test-event-merge"
        :hooks {:agent-end (fn [state] (reset! received state))}})
      (ext/set-state! {:session {:name "base"} :cwd "/project"})
      (ext/dispatch-hook :agent-end {:extra "data"})
      (is (= "base" (get-in @received [:session :name])))
      (is (= "data" (:extra @received))))))

(deftest dispatch-hook-event-ctx-overrides
  (testing "event context overrides hook state keys"
    (let [received (atom nil)]
      (ext/register-extension!
       {:name "test-override"
        :hooks {:agent-end (fn [state] (reset! received state))}})
      (ext/set-state! {:cwd "/original"})
      (ext/dispatch-hook :agent-end {:cwd "/override"})
      (is (= "/override" (:cwd @received))))))

;; ── dispatch-hook-transform with state injection ──────────────────────────────

(deftest transform-hook-receives-state
  (testing "transform hooks receive value + state"
    (let [received-state (atom nil)]
      (ext/register-extension!
       {:name "test-transform-state"
        :hooks {:tool-call (fn [value state]
                             (reset! received-state state)
                             value)}})
      (ext/set-state! {:cwd "/project" :model "sonnet"})
      (ext/dispatch-hook-transform :tool-call {:name "bash"})
      (is (= "/project" (:cwd @received-state)))
      (is (= "sonnet" (:model @received-state))))))

(deftest transform-hook-nil-blocks
  (testing "returning nil from transform hook blocks the chain"
    (ext/register-extension!
     {:name "test-transform-block"
      :hooks {:tool-call (fn [_value _state] nil)}})
    (ext/set-state! {})
    (is (nil? (ext/dispatch-hook-transform :tool-call {:name "bash"})))))

;; ── collect-prompt-badges with state ──────────────────────────────────────────

(deftest prompt-badges-receive-state
  (testing "prompt badge hooks receive the state"
    (let [received-state (atom nil)]
      (ext/register-extension!
       {:name "test-badge-state"
        :hooks {:prompt-badge (fn [state]
                                (reset! received-state state)
                                "🔔")}})
      (ext/set-state! {:model "opus"})
      (let [result (ext/collect-prompt-badges)]
        (is (string? result))
        (is (= "opus" (:model @received-state)))))))

(deftest prompt-badges-concat
  (testing "badges from multiple extensions are concatenated"
    (ext/register-extension!
     {:name "test-badge-a"
      :hooks {:prompt-badge (fn [_state] "A")}})
    (ext/register-extension!
     {:name "test-badge-b"
      :hooks {:prompt-badge (fn [_state] "B")}})
    (is (clojure.string/includes? (ext/collect-prompt-badges) "A"))
    (is (clojure.string/includes? (ext/collect-prompt-badges) "B"))))

(deftest prompt-badges-skip-nil
  (testing "nil badges are skipped"
    (ext/register-extension!
     {:name "test-badge-nil"
      :hooks {:prompt-badge (fn [_state] nil)}})
    (ext/register-extension!
     {:name "test-badge-ok"
      :hooks {:prompt-badge (fn [_state] "X")}})
    (is (clojure.string/includes? (ext/collect-prompt-badges) "X"))))
