(ns xi.dialog-test
  (:require [cljs.test :refer [deftest is]]
            [xi.dialog :as dialog]))

(deftest confirm-options-defaults-to-yes-no
  (is (= [true false]
         (map :value (dialog/confirm-options {:type :confirm :message "?"})))))

(deftest confirm-options-normalizes-keywords
  (let [opts (dialog/confirm-options {:options [:yes :no :allow-repo]})]
    (is (= [true false :repo] (map :value opts)))
    (is (= ["y" "n" "r"] (map :key opts)))
    (is (every? :label opts))))

(deftest confirm-options-resolves-the-rules-repo-alias
  (is (= (dialog/confirm-options {:options [:yes :no :allow-repo]})
         (dialog/confirm-options {:options [:yes :no :repo]}))
      "a rule's :repo option is the [r] allow-repo choice"))

(deftest confirm-options-drops-unknown-keywords-keeps-maps
  (let [custom {:value :x :key "x" :label "X"}]
    (is (= [custom]
           (dialog/confirm-options {:options [:nope custom]})))))

(deftest form-fields-humanizes-missing-labels
  (is (= [{:name "commit-message" :label "Commit message" :value ""}
          {:name "scope" :label "Scope" :value ""}]
         (dialog/form-fields {:type :form
                              :fields [{:name "commit-message"}
                                       {:name "scope"}]}))))

(deftest form-fields-keeps-explicit-labels-drops-nameless
  (is (= [{:name "a" :label "Custom" :value ""}]
         (dialog/form-fields {:fields [{:name "a" :label "Custom"}
                                       {:label "no name"}]}))))

(deftest form-fields-prefills-value
  (is (= [{:name "rule" :label "Rule" :value "{:a 1}"}]
         (dialog/form-fields {:fields [{:name "rule" :value "{:a 1}"}]}))))

(def ^:private parallel-history
  "A write waiting on its ask, with a grep started (and still running) after it."
  [{:kind :tool-call :id "w" :status :running
    :tool "mcp__xi-tools__write" :arguments {"path" "/a.clj" "content" "x"}}
   {:kind :tool-call :id "g" :status :running
    :tool "mcp__xi-tools__grep" :arguments {:pattern "log-in"}}])

(deftest permission-tool-index-picks-the-gated-call
  (is (= 0 (dialog/permission-tool-index
            {:call {:name "write" :arguments {:path "/a.clj" :content "x"}}}
            parallel-history 0))
      "matches by name (sans MCP prefix) and arguments (key type ignored), not recency"))

(deftest permission-tool-index-no-call-falls-back-to-newest-running
  (is (= 1 (dialog/permission-tool-index {} parallel-history 0))))

(deftest permission-tool-index-nil-without-a-match
  (is (nil? (dialog/permission-tool-index
             {:call {:name "write" :arguments {:path "/other.clj"}}}
             parallel-history 0))
      "standalone dialog instead of the wrong block")
  (is (nil? (dialog/permission-tool-index
             {:call {:name "write" :arguments {:path "/a.clj" :content "x"}}}
             parallel-history 1))
      "entries before the rendered window are not considered"))

(deftest permission-tool-index-ignores-finished-calls
  (is (nil? (dialog/permission-tool-index
             {:call {:name "grep" :arguments {:pattern "log-in"}}}
             (assoc-in parallel-history [1 :status] :done) 0))))

(deftest resolved-label-from-options
  (let [d {:options [:yes :no :always]}]
    (is (= "Allowed" (dialog/resolved-label d true)))
    (is (= "Denied" (dialog/resolved-label d false)))
    (is (= "Always allowed" (dialog/resolved-label d :always)))
    (is (= "Allowed" (dialog/resolved-label d :unknown-truthy))
        "unknown truthy value falls back to Allowed")))
