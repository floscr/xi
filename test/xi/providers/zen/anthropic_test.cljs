(ns xi.providers.zen.anthropic-test
  "Tests for the prompt-caching breakpoint helpers. These drive the cost
   reduction: without cache_control on the static prefix (system + tools +
   prior turns) the Anthropic surface re-bills the whole prefix on every
   tool-loop iteration."
  (:require [cljs.test :refer [deftest is testing async]]
            [xi.providers.zen.anthropic :as anthropic]))

(def ^:private cc {:type "ephemeral"})

(def ^:private execute-tool-call #'anthropic/execute-tool-call)

;; ── tool policy ──

(deftest a-policy-deny-replaces-the-tool-result
  (testing "{:intercepted …} (a rule's deny/nudge) is returned and the tool does not run"
    (async done
      (let [ran?     (atom false)
            registry {"write" (fn [_ _] (reset! ran? true)
                                {:content [{:type "text" :text "written"}] :is-error false})}
            policy   (fn [_] (js/Promise.resolve
                              {:intercepted true
                               :result {:content [{:type "text" :text "no shell"}]
                                        :is-error true}}))]
        (-> (js/Promise.resolve
             (execute-tool-call {:id "t1" :name "write" :arguments {:path "x.sh"}}
                                registry {} policy))
            (.then (fn [res]
                     (is (false? @ran?))
                     (is (= {:type "tool_result" :tool_use_id "t1"
                             :content "no shell" :is_error true}
                            res))
                     (done))))))))

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
