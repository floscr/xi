(ns xi.dialog-test
  (:require [cljs.test :refer [deftest is testing]]
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

(deftest permission-tool-index-settled-call-still-hosts-its-ask
  ;; The turn was interrupted under the ask: its call is :aborted, nothing is
  ;; running, but the ask still belongs on that block, not in a standalone
  ;; bubble.
  (is (= 1 (dialog/permission-tool-index
            {:call {:name "grep" :arguments {:pattern "log-in"}}}
            (assoc-in parallel-history [1 :status] :aborted) 0)))
  (testing "a running match wins over an older settled one (a retried call)"
    (is (= 2 (dialog/permission-tool-index
              {:call {:name "grep" :arguments {:pattern "log-in"}}}
              (-> parallel-history
                  (assoc-in [1 :status] :error)
                  (conj {:kind :tool-call :id "g2" :status :running
                         :tool "grep" :arguments {:pattern "log-in"}}))
              0))))
  (testing "without :call only running calls are considered"
    (is (nil? (dialog/permission-tool-index
               {} (mapv #(assoc % :status :done) parallel-history) 0)))))

(deftest resolved-label-from-options
  (let [d {:options [:yes :no :always]}]
    (is (= "Allowed" (dialog/resolved-label d true)))
    (is (= "Denied" (dialog/resolved-label d false)))
    (is (= "Always allowed" (dialog/resolved-label d :always)))
    (is (= "Allowed" (dialog/resolved-label d :unknown-truthy))
        "unknown truthy value falls back to Allowed")))

(deftest answer-option-parses-allow-and-deny-args
  (is (= :yes (dialog/answer-option :allow nil)))
  (is (= :yes (dialog/answer-option :allow "  ")))
  (is (= :always (dialog/answer-option :allow "a")))
  (is (= :always (dialog/answer-option :allow "Always")))
  (is (= :allow-repo (dialog/answer-option :allow "r")))
  (is (= :allow-repo (dialog/answer-option :allow "repo")))
  (is (nil? (dialog/answer-option :allow "bogus")))
  (is (= :no (dialog/answer-option :deny "whatever"))))

(defn- room-with [& dialogs] {:ui {:dialogs (vec dialogs)}})

(deftest scope-confirm-to-call-tags-every-ask-with-the-gated-call
  (let [asked (atom [])
        ctx   {:room-id "r"
               :confirm! (fn [message opts] (swap! asked conj [message opts]) :answer)}
        call  {:name "clj" :arguments {:code "(sh \"claude\")"}}
        scoped (dialog/scope-confirm-to-call ctx call)]
    (is (= :answer ((:confirm! scoped) "allow?" {:options [:yes :no]})))
    ((:confirm! scoped) "bare")
    (is (= [["allow?" {:options [:yes :no]
                       :call {:name "clj" :arguments {:code "(sh \"claude\")"}}}]
            ["bare" {:call {:name "clj" :arguments {:code "(sh \"claude\")"}}}]]
           @asked)
        "opts are kept; the 1-arity gets just the :call")
    (is (= "r" (:room-id scoped)) "the rest of the ctx is untouched")))

(deftest capture-deny-reason-records-the-reason-and-keeps-the-answer
  (let [box      (volatile! nil)
        asked    (atom nil)
        confirm! (fn [message opts]
                   (reset! asked [message (dissoc opts :on-reason)])
                   ((:on-reason opts) "not that file")
                   false)
        wrapped  (dialog/capture-deny-reason confirm! box)]
    (is (false? (wrapped "allow?" {:options [:yes :no]})) "the answer passes through")
    (is (= ["allow?" {:options [:yes :no]}] @asked) "other opts are kept")
    (is (= "not that file" @box)))
  (is (nil? (dialog/capture-deny-reason nil (volatile! nil))) "headless stays nil"))

(deftest with-deny-reason-appends-only-a-real-reason
  (is (= "Blocked." (dialog/with-deny-reason "Blocked." nil)))
  (is (= "Blocked." (dialog/with-deny-reason "Blocked." "  ")))
  (is (= "Blocked.\nTo tell you how to proceed, the user said:\nuse rg"
         (dialog/with-deny-reason "Blocked." " use rg "))))

(deftest scope-confirm-to-call-without-confirm-is-a-no-op
  (let [ctx {:room-id "r"}]
    (is (= ctx (dialog/scope-confirm-to-call ctx {:name "clj" :arguments {}})))))

(deftest answer-targets-the-first-pending-confirm
  (let [room (room-with {:id "d1" :type :confirm :options [:yes :no :always]}
                        {:id "d2" :type :confirm})]
    (is (= {:dialog-id "d1" :value true} (dialog/answer room :yes)))
    (is (= {:dialog-id "d1" :value false} (dialog/answer room :no)))
    (is (= {:dialog-id "d1" :value :always} (dialog/answer room :always)))))

(deftest answer-errors-when-nothing-to-answer-or-option-not-offered
  (is (:error (dialog/answer (room-with) :yes)))
  (is (:error (dialog/answer (room-with {:id "s" :type :select}) :yes))
      "a non-confirm dialog in front is not a permission request")
  (is (:error (dialog/answer (room-with {:id "d" :type :confirm}) :allow-repo))
      "a plain yes/no ask doesn't offer repo writes"))
