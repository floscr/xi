(ns xi.provider.zen.anthropic-test
  "Tests for the prompt-caching breakpoint helpers. These drive the cost
   reduction: without cache_control on the static prefix (system + tools +
   prior turns) the Anthropic surface re-bills the whole prefix on every
   tool-loop iteration."
  (:require [cljs.test :refer [deftest is testing]]
            [xi.provider.zen.anthropic :as anthropic]))

(def ^:private cc {:type "ephemeral"})

;; ── system->blocks ──

(deftest system-blocks-wraps-and-caches
  (testing "a system string becomes a single cached text block"
    (is (= [{:type "text" :text "hello" :cache_control cc}]
           (anthropic/system->blocks "hello")))))

(deftest system-blocks-nil-and-empty
  (testing "nil / empty system yields nil (no block, no cache marker)"
    (is (nil? (anthropic/system->blocks nil)))
    (is (nil? (anthropic/system->blocks "")))))

;; ── cache-last-tool ──

(deftest cache-last-tool-marks-only-final
  (testing "only the last tool carries cache_control (caches the whole list)"
    (let [tools [{:name "a"} {:name "b"} {:name "c"}]
          out   (anthropic/cache-last-tool tools)]
      (is (= 3 (count out)))
      (is (nil? (:cache_control (nth out 0))))
      (is (nil? (:cache_control (nth out 1))))
      (is (= cc (:cache_control (nth out 2)))))))

(deftest cache-last-tool-empty
  (testing "empty tool list is unchanged"
    (is (= [] (anthropic/cache-last-tool [])))))

;; ── cache-conversation ──

(deftest cache-conversation-string-content
  (testing "string content on the last message is promoted to a cached block"
    (let [msgs [{:role "user" :content "first"}
                {:role "user" :content "latest"}]
          out  (anthropic/cache-conversation msgs)]
      ;; earlier message untouched
      (is (= "first" (:content (nth out 0))))
      ;; last message: string → [cached text block]
      (is (= [{:type "text" :text "latest" :cache_control cc}]
             (:content (nth out 1)))))))

(deftest cache-conversation-block-content
  (testing "only the final block of the last message gets the marker"
    (let [msgs [{:role "user"
                 :content [{:type "text" :text "a"}
                           {:type "tool_result" :tool_use_id "t" :content "r"}]}]
          out  (anthropic/cache-conversation msgs)
          content (:content (nth out 0))]
      (is (nil? (:cache_control (nth content 0))))
      (is (= cc (:cache_control (nth content 1)))))))

(deftest cache-conversation-empty
  (testing "empty message list is unchanged"
    (is (= [] (anthropic/cache-conversation [])))))
