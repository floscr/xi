(ns xi.agent-test
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.agent :as agent]
            [xi.core.app :as app]
            [xi.core.events :as events]
            [xi.core.state :as state]))

(def all-handlers (merge events/core-handlers agent/handlers))

(defn- apply-events [st & evs]
  (reduce #(:state (events/handle-event all-handlers %1 %2)) st evs))

(defn- with-room []
  (apply-events (state/initial-state)
                {:type :room/create :room-id "r"
                 :room {:model "claude-sonnet-4-5"}}))

(defn- history [st] (:history (state/get-room st "r")))

;; ── Pure handler tests ───────────────────────────────────────────────────────

(deftest prompt-submit-starts-turn
  (let [{:keys [state effects]}
        (events/handle-event all-handlers (with-room)
                             {:type :prompt/submit :room-id "r" :text "hi"})]
    (is (= [{:kind :user :text "hi" :images nil}] (:history (state/get-room state "r"))))
    (is (true? (get-in state [:rooms "r" :agent :busy?])))
    (let [[[fx-type payload]] effects]
      (is (= :provider/start-turn fx-type))
      (is (= "hi" (:prompt payload)))
      (is (= "claude-sonnet-4-5" (:model payload)))
      (is (= "r" (:room-id payload))))))

(deftest prompt-submit-queues-when-busy
  (let [st (apply-events (with-room)
                         {:type :prompt/submit :room-id "r" :text "first"}
                         {:type :prompt/submit :room-id "r" :text "second"})]
    (is (= 1 (count (history st))) "second prompt not appended to history")
    (is (= [{:text "second" :images nil}] (get-in st [:rooms "r" :agent :queued])))))

(deftest deltas-fold-into-entries
  (let [st (apply-events (with-room)
                         {:type :agent/text-delta :room-id "r" :text "Hel"}
                         {:type :agent/text-delta :room-id "r" :text "lo"}
                         {:type :agent/thinking-delta :room-id "r" :text "hmm"}
                         {:type :agent/text-delta :room-id "r" :text "again"})]
    (is (= [{:kind :text :text "Hello"}
            {:kind :thinking :text "hmm"}
            {:kind :text :text "again"}]
           (history st)))))

(deftest tool-call-lifecycle
  (let [st (apply-events (with-room)
                         {:type :agent/tool-start :room-id "r" :id "t1" :tool "bash"
                          :arguments {}}
                         {:type :agent/tool-args :room-id "r" :id "t1"
                          :arguments {:command "ls"}}
                         {:type :agent/tool-result :room-id "r" :id "t1"
                          :content "files" :is-error false})]
    (is (= [{:kind :tool-call :id "t1" :tool "bash"
             :arguments {:command "ls"} :status :done
             :result "files" :is-error false}]
           (history st)))))

(deftest turn-end-finalizes-and-drains-queue
  (let [st (apply-events (with-room)
                         {:type :prompt/submit :room-id "r" :text "go"}
                         {:type :agent/text-delta :room-id "r" :text "ok"}
                         {:type :prompt/submit :room-id "r" :text "queued!"})
        {:keys [state effects]}
        (events/handle-event all-handlers st
                             {:type :agent/turn-end :room-id "r"
                              :usage {:input_tokens 5} :cost 0.01
                              :provider-session-id "sid-1"})]
    (is (false? (get-in state [:rooms "r" :agent :busy?])))
    (is (true? (:done? (last (:history (state/get-room state "r"))))))
    (is (= {:input_tokens 5} (get-in state [:rooms "r" :agent :last-usage])))
    (is (= "sid-1" (get-in state [:rooms "r" :session :provider-session-id])))
    (is (= [] (get-in state [:rooms "r" :agent :queued])))
    (is (= [[:app/dispatch {:type :prompt/submit :room-id "r"
                            :text "queued!" :images []}]]
           effects))))

(deftest turn-end-joins-multiple-queued-prompts
  (let [st (apply-events (with-room)
                         {:type :prompt/submit :room-id "r" :text "go"}
                         {:type :prompt/submit :room-id "r" :text "first"}
                         {:type :prompt/submit :room-id "r" :text "second"})
        {:keys [state effects]}
        (events/handle-event all-handlers st {:type :agent/turn-end :room-id "r"})]
    (is (= [] (get-in state [:rooms "r" :agent :queued])))
    (is (= [[:app/dispatch {:type :prompt/submit :room-id "r"
                            :text "first\n\nsecond" :images []}]]
           effects)
        "all queued prompts are combined into one submission")))

(deftest queue-remove-drops-one-queued-prompt
  (let [st (apply-events (with-room)
                         {:type :prompt/submit :room-id "r" :text "go"}
                         {:type :prompt/submit :room-id "r" :text "a"}
                         {:type :prompt/submit :room-id "r" :text "b"}
                         {:type :prompt/submit :room-id "r" :text "c"})
        {:keys [state]}
        (events/handle-event all-handlers st
                             {:type :prompt/queue-remove :room-id "r" :index 1})]
    (is (= [{:text "a" :images nil} {:text "c" :images nil}]
           (get-in state [:rooms "r" :agent :queued])))))

(deftest resume-session-id-flows-into-next-turn
  (let [st (apply-events (with-room)
                         {:type :prompt/submit :room-id "r" :text "a"}
                         {:type :agent/turn-end :room-id "r" :provider-session-id "sid-9"})
        {:keys [effects]}
        (events/handle-event all-handlers st {:type :prompt/submit :room-id "r" :text "b"})]
    (is (= "sid-9" (:resume-session-id (second (first effects)))))))
(deftest history->context-renders-exchanges
  (is (nil? (agent/history->context [])))
  (is (nil? (agent/history->context [{:kind :tool-call :id "t1" :tool "bash"}]))
      "non-text entries carry nothing")
  (let [ctx (agent/history->context
             [{:kind :user :text "hi"}
              {:kind :thinking :text "hmm"}
              {:kind :text :text "hello!"}
              {:kind :tool-call :id "t1" :tool "bash" :result "out"}
              {:kind :user :text "next"}])]
    (is (str/includes? ctx "<conversation_history>"))
    (is (str/includes? ctx "user: hi"))
    (is (str/includes? ctx "assistant: hello!"))
    (is (str/includes? ctx "user: next"))
    (is (not (str/includes? ctx "hmm")) "thinking is not carried")
    (is (not (str/includes? ctx "out")) "tool results are not carried")))

(deftest inject-history-flag-injects-context-into-system
  (let [st (-> (with-room)
               (assoc-in [:rooms "r" :agent :system] "base prompt")
               (update-in [:rooms "r" :history] into
                          [{:kind :user :text "old question"}
                           {:kind :text :text "old answer" :done? true}])
               (update-in [:rooms "r" :session] assoc
                          :provider-session-id nil
                          :inject-history? true))
        {:keys [effects]}
        (events/handle-event all-handlers st {:type :prompt/submit :room-id "r" :text "new"})
        payload (second (first effects))]
    (is (nil? (:resume-session-id payload)))
    (is (str/starts-with? (:system payload) "base prompt")
        "base system prompt is preserved")
    (is (str/includes? (:system payload) "user: old question"))
    (is (str/includes? (:system payload) "assistant: old answer"))
    (is (not (str/includes? (:system payload) "user: new"))
        "the new prompt itself is not duplicated into the context")))

(deftest inject-history-flag-ignored-once-session-resumes
  (let [st (-> (with-room)
               (update-in [:rooms "r" :history] into
                          [{:kind :user :text "old question"}
                           {:kind :text :text "old answer" :done? true}])
               (update-in [:rooms "r" :session] assoc
                          :provider-session-id "sid-5"
                          :inject-history? true))
        {:keys [effects]}
        (events/handle-event all-handlers st {:type :prompt/submit :room-id "r" :text "new"})
        payload (second (first effects))]
    (is (= "sid-5" (:resume-session-id payload)))
    (is (nil? (:system payload)) "no history injection when resuming")))

(deftest retry-fresh-drops-dead-session-and-replays-prompt
  (let [st (-> (with-room)
               (assoc-in [:rooms "r" :agent :system] "base prompt")
               (update-in [:rooms "r" :history] into
                          [{:kind :user :text "old question"}
                           {:kind :text :text "old answer" :done? true}
                           {:kind :user :text "new prompt" :images nil}])
               (assoc-in [:rooms "r" :session :provider-session-id] "dead-sid"))
        {:keys [state effects]}
        (events/handle-event all-handlers st {:type :agent/retry-fresh :room-id "r"})
        [[fx-type payload]] effects]
    (is (nil? (get-in state [:rooms "r" :session :provider-session-id]))
        "the dead session id is cleared")
    (is (= :provider/start-turn fx-type))
    (is (= "new prompt" (:prompt payload)) "the pending prompt is replayed")
    (is (nil? (:resume-session-id payload)) "no resume on the fresh turn")
    (is (str/starts-with? (:system payload) "base prompt"))
    (is (str/includes? (:system payload) "user: old question"))
    (is (str/includes? (:system payload) "assistant: old answer"))
    (is (not (str/includes? (:system payload) "user: new prompt"))
        "the replayed prompt is not duplicated into the injected context")))

(deftest abort-only-when-busy
  (let [busy (apply-events (with-room) {:type :prompt/submit :room-id "r" :text "x"})]
    (is (= [[:provider/abort {:room-id "r"}]]
           (:effects (events/handle-event all-handlers busy {:type :agent/abort :room-id "r"}))))
    (is (= [] (:effects (events/handle-event all-handlers (with-room)
                                             {:type :agent/abort :room-id "r"})))))

(deftest provider-routing
  (let [providers {:claude {:id :claude} :ollama {:id :ollama}}]
    (is (= :claude (:id (agent/resolve-provider providers {:model "claude-sonnet-4-5"}))))
    (is (= :claude (:id (agent/resolve-provider providers {:model "opus"}))))
    (is (= :ollama (:id (agent/resolve-provider providers {:model "qwen3:32b"}))))
    (is (= :ollama (:id (agent/resolve-provider providers {:provider :ollama
                                                           :model "claude-sonnet-4-5"}))))))

;; ── Full turn through the app with a fake provider ───────────────────────────

(defn- fake-provider
  "Provider that streams a canned turn: text, tool call, more text."
  [!aborted]
  {:id :fake
   :start-turn!
   (fn [{:keys [on-text on-tool-start on-tool-result]}]
     {:promise (js/Promise.
                (fn [resolve _]
                  (on-text "thinking… ")
                  (on-tool-start {:id "t1" :name "read" :arguments {:path "x"}})
                  (on-tool-result {:id "t1" :content "data" :is-error false})
                  (on-text "done")
                  (resolve {:usage {:output_tokens 7} :session-id "fake-sid"})))
      :abort!  (fn [] (reset! !aborted true))})})

(deftest full-turn-with-fake-provider
  (async done
    (let [!aborted (atom false)
          {:keys [dispatch! state]}
          (app/create-app {:initial-state   (state/initial-state)
                           :handlers        all-handlers
                           :effects         (agent/create-fx {:fake (fake-provider !aborted)})
                           :schedule-render (fn [f] (f))})]
      (dispatch! {:type :room/create :room-id "r" :room {:provider :fake :model "m"}})
      (dispatch! {:type :prompt/submit :room-id "r" :text "go"})
      ;; Provider promise resolution is async — wait a macrotask.
      (js/setTimeout
       (fn []
         (let [room (state/get-room @state "r")]
           (is (= [:user :text :tool-call :text]
                  (mapv :kind (:history room))))
           (is (= "thinking… " (:text (second (:history room)))))
           (is (false? (get-in room [:agent :busy?])))
           (is (= "fake-sid" (get-in room [:session :provider-session-id])))
           (is (false? @!aborted))
           (done)))
       10)))))