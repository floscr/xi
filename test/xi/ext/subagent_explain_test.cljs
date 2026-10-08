(ns xi.ext.subagent-explain-test
  "The Explain button on a permission-gated tool block
   (xi.ext.subagent.handlers/explain-call): one explanation sub-agent per
   tool call, spawned as a mirrored effect with a prompt built from the
   call, its ask and the parent conversation."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.core.state :as state]
            [xi.ext.subagent.handlers :as h]
            [xi.ext.subagent.web :as web]))

(def ^:private call
  {:kind :tool-call :id "tc-9" :tool "mcp__xi-tools__clj"
   :arguments {:code "(sh \"rm\" \"-rf\" \"build\")"} :status :running})

(def ^:private dialog
  {:id "dlg-1" :type :confirm :message "Run clj — approve?"
   :call {:name "mcp__xi-tools__clj" :arguments {:code "(sh \"rm\" \"-rf\" \"build\")"}}})

(defn- room-state [& {:keys [agents dialogs history]}]
  (-> (state/initial-state {:mode :server})
      (assoc-in [:rooms "r1"]
                {:id "r1" :cwd "/repo" :agent {:model "m"}
                 :history (or history
                              [{:kind :user :text "clean the build dir"}
                               {:kind :thinking :text "hmm"}
                               {:kind :text :text "I'll remove it."}
                               {:kind :tool-call :id "tc-1" :tool "ls"
                                :arguments {:path "build"} :status :done}
                               call])
                 :ui {:dialogs (or dialogs [dialog])}
                 :ext {:subagents {:agents (or agents []) :collapsed? false}}})))

(defn- effects-of [res]
  (map second (:effects res)))

(deftest explain-call-spawns-an-explanation-sub-agent
  (let [res   (h/explain-call (room-state) {:room-id "r1" :call-id "tc-9"
                                            :transcript "/t/abc.jsonl"})
        spawn (first (effects-of res))]
    (is (= [:app/dispatch] (map first (:effects res))) "one dispatch: the spawn")
    (is (= :subagent/spawn (:type spawn)))
    (is (= "explain-tc-9" (:sub-id spawn)) "id derived from the call id")
    (is (= "Explain clj" (:label spawn)) "label names the tool without the MCP prefix")
    (testing "the prompt carries the call, its ask, the conversation and the transcript"
      (let [p (:prompt spawn)]
        (is (str/includes? p "- tool: clj"))
        (is (str/includes? p "code: (sh \"rm\" \"-rf\" \"build\")") "strings verbatim")
        (is (str/includes? p "- working directory: /repo"))
        (is (str/includes? p "Run clj — approve?"))
        (is (str/includes? p "[user]\nclean the build dir"))
        (is (str/includes? p "[assistant]\nI'll remove it."))
        (is (str/includes? p "[tool call] ls {:path \"build\"}"))
        (is (not (str/includes? p "hmm")) "thinking is dropped")
        (is (str/includes? p "/t/abc.jsonl"))
        (is (str/includes? p "Recommendation"))))))

(deftest explain-call-without-transcript-or-dialog
  (let [res   (h/explain-call (room-state :dialogs []) {:room-id "r1" :call-id "tc-9"})
        p     (:prompt (first (effects-of res)))]
    (is (str/includes? p "no transcript on disk"))
    (is (not (str/includes? p "## The permission prompt")))))

(deftest explain-call-includes-the-change-preview
  (let [diff  {:path "a.txt" :text "-old\n+new"}
        st    (room-state :history [(assoc call :diff diff)])
        p     (:prompt (first (effects-of (h/explain-call st {:room-id "r1" :call-id "tc-9"}))))]
    (is (str/includes? p "## The change it would make (a.txt)"))
    (is (str/includes? p "-old\n+new"))
    (is (str/includes? p "(no earlier messages)"))))

(deftest explain-call-is-one-per-call
  (testing "running or done: nothing happens"
    (doseq [status [:running :done]]
      (is (nil? (h/explain-call (room-state :agents [{:id "explain-tc-9" :status status}])
                                {:room-id "r1" :call-id "tc-9"})))))
  (testing "a failed or stopped attempt is dismissed and retried"
    (doseq [status [:error :stopped]]
      (let [res (h/explain-call (room-state :agents [{:id "explain-tc-9" :status status}])
                                {:room-id "r1" :call-id "tc-9"})]
        (is (= [:subagent/dismiss :subagent/spawn] (map :type (effects-of res))))
        (is (= "explain-tc-9" (:sub-id (first (effects-of res)))))))))

(deftest explain-call-ignores-unknown-calls-and-rooms
  (is (nil? (h/explain-call (room-state) {:room-id "r1" :call-id "nope"})))
  (is (nil? (h/explain-call (room-state) {:room-id "r2" :call-id "tc-9"}))))

(deftest explain-helpers
  (is (= "explain-tc-9" (h/explain-sub-id "tc-9")))
  (is (h/explain-sub? "explain-tc-9"))
  (is (not (h/explain-sub? "sa-1")))
  (is (not (h/explain-sub? nil)))
  (is (= {:id "explain-tc-9" :status :done}
         (h/find-explain [{:id "sa-1"} {:id "explain-tc-9" :status :done}] "tc-9")))
  (is (nil? (h/find-explain [{:id "sa-1"}] "tc-9"))))

(deftest web-half-forwards-the-click
  (let [handler (get-in web/extension [:handlers :subagent/explain-call])
        ev      {:type :subagent/explain-call :room-id "r1" :call-id "tc-9"}]
    (is (= {:effects [[:ws/send ev]]} (handler (room-state) ev)))
    (is (nil? (handler (room-state) (assoc ev :remote? true)))
        "the server echo is not applied locally")))
