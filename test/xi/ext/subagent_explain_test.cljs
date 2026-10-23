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
  (let [res   (h/explain-call (room-state) {:room-id "r1" :call-id "tc-9"})
        spawn (first (effects-of res))]
    (is (= [:app/dispatch] (map first (:effects res))) "one dispatch: the spawn")
    (is (= :subagent/spawn (:type spawn)))
    (is (= "explain-tc-9" (:sub-id spawn)) "id derived from the call id")
    (is (= "Explain clj" (:label spawn)) "label names the tool without the MCP prefix")
    (testing "the prompt carries the call, its ask and the conversation"
      (let [p (:prompt spawn)]
        (is (str/includes? p "- tool: clj"))
        (is (str/includes? p "code: (sh \"rm\" \"-rf\" \"build\")") "strings verbatim")
        (is (str/includes? p "- working directory: /repo"))
        (is (str/includes? p "Run clj — approve?"))
        (is (str/includes? p "[user]\nclean the build dir"))
        (is (str/includes? p "[assistant]\nI'll remove it."))
        (is (str/includes? p "[tool call] ls {:path \"build\"}"))
        (is (not (str/includes? p "hmm")) "thinking is dropped")
        (is (str/includes? p "`parent_history` reads the whole parent conversation"))
        (is (str/includes? p "Recommendation"))))))

(deftest explain-call-without-dialog
  (let [res   (h/explain-call (room-state :dialogs []) {:room-id "r1" :call-id "tc-9"})
        p     (:prompt (first (effects-of res)))]
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
  (is (h/inline-sub? "explain-tc-9"))
  (is (h/inline-sub? (h/ask-sub-id)))
  (is (not (h/inline-sub? "sa-1")))
  (is (not (h/inline-sub? nil)))
  (is (= {:id "explain-tc-9" :status :done}
         (h/find-explain [{:id "sa-1"} {:id "explain-tc-9" :status :done}] "tc-9")))
  (is (nil? (h/find-explain [{:id "sa-1"}] "tc-9"))))

(deftest ask-prompt-carries-question-snippet-and-follow-up
  (let [p (h/ask-prompt "Why drop this?" "-(foo)" nil)]
    (is (str/includes? p "```diff\n-(foo)\n```"))
    (is (str/includes? p "## Question\nWhy drop this?"))
    (is (not (str/includes? p "Earlier in this thread"))))
  (let [p (h/ask-prompt "And this?" "-(foo)" [{:question "Why?" :answer "Dead code."}
                                               {:question "Safe?" :answer "Yes."}])]
    (is (str/includes? p "## Earlier in this thread\n**Q:** Why?\n\n**A:** Dead code.\n\n**Q:** Safe?"))
    (is (str/includes? p "## Question\nAnd this?"))))

(deftest diff-modify-sub-agents-may-edit
  (let [start (fn [sub-id]
                (-> (h/spawn (room-state) {:room-id "r1" :sub-id sub-id :task "t"})
                    :effects first second))]
    (is (h/inline-sub? (h/modify-sub-id)) "renders in its diff thread")
    (is (:unattended? (start "explain-tc-9")) "Explain only reads: its asks are denied")
    (let [ask (start (h/ask-sub-id))]
      (is (not (:unattended? ask)) "Ask raises its asks")
      (is (:confirm-edits? ask) "and asks for every edit"))
    (is (not-any? (start (h/modify-sub-id)) [:unattended? :confirm-edits?])
        "Modify edits like any sub-agent")))

(deftest ask-prompts-allow-confirmed-edits
  (is (str/includes? (h/ask-prompt "Rename it?" "+(foo)" nil) h/ask-edit-policy)))

(deftest modifications-lists-the-edits-that-went-through
  (let [edit (fn [id path & {:as more}]
               (merge {:kind :tool-call :id id :tool "mcp__xi-tools__edit"
                       :arguments {:path path} :status :done
                       :result [{:type "text" :text (str path "\n- a\n+ b\n[file-hash: abc123]")}]}
                      more))
        kids [{:id "ask-1"
               :history [{:kind :text :text "looking"}
                         (edit "t1" "src/a.clj")
                         {:kind :tool-call :id "t2" :tool "read" :status :done
                          :arguments {:path "src/a.clj"} :result "x"}
                         (edit "t3" "src/b.clj" :is-error true :status :error)]}
              nil
              {:id "ask-2"
               :history [(edit "t4" "src/c.clj" :tool "Write")
                         (edit "t5" "src/d.clj" :status :running :result nil)]}]]
    (is (= [{:sub-id "ask-1" :id "t1" :path "src/a.clj" :text "src/a.clj\n- a\n+ b"}
            {:sub-id "ask-2" :id "t4" :path "src/c.clj" :text "src/c.clj\n- a\n+ b"}]
           (h/modifications kids))
        "oldest first, reads, failed and running calls left out, file hash dropped")))

(deftest diff-thread-prompts-by-kind
  (let [p (h/diff-thread-prompt :modify "rename foo" "+(foo)" nil)]
    (is (str/includes? p "## Change\nrename foo"))
    (is (str/includes? p "Make this change in the working-tree files")))
  (let [p (h/diff-thread-prompt :explain "" "+(foo)" nil)]
    (is (str/includes? p "wants the selected lines explained"))
    (is (not (str/includes? p "## Focus")) "no focus given"))
  (is (str/includes? (h/diff-thread-prompt :explain "perf" "+(foo)" nil) "## Focus\nperf"))
  (is (str/includes? (h/diff-thread-prompt :explain "Why?" "+(foo)" [{:question "q" :answer "a"}])
                     "## Question\nWhy?")
      "follow-ups are questions"))

(deftest ask-suggestions-follow-the-selected-line-types
  (is (= "Why was this removed?" (first (h/ask-suggestions [:delete :context :delete]))))
  (is (= "Why was this added?" (first (h/ask-suggestions [:add]))))
  (is (= "What does this change do?" (first (h/ask-suggestions [:add :delete]))))
  (is (= "What does this do?" (first (h/ask-suggestions [:context])))))

(deftest web-half-forwards-the-click
  (let [handler (get-in web/extension [:handlers :subagent/explain-call])
        ev      {:type :subagent/explain-call :room-id "r1" :call-id "tc-9"}]
    (is (= {:effects [[:ws/send ev]]} (handler (room-state) ev)))
    (is (nil? (handler (room-state) (assoc ev :remote? true)))
        "the server echo is not applied locally")))
