(ns xi.agent-test
  (:require [cljs.test :refer [deftest is testing async]]
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
                            :text "queued!" :images nil}]]
           effects))))

(deftest resume-session-id-flows-into-next-turn
  (let [st (apply-events (with-room)
                         {:type :prompt/submit :room-id "r" :text "a"}
                         {:type :agent/turn-end :room-id "r" :provider-session-id "sid-9"})
        {:keys [effects]}
        (events/handle-event all-handlers st {:type :prompt/submit :room-id "r" :text "b"})]
    (is (= "sid-9" (:resume-session-id (second (first effects)))))))

(deftest abort-only-when-busy
  (let [busy (apply-events (with-room) {:type :prompt/submit :room-id "r" :text "x"})]
    (is (= [[:provider/abort {:room-id "r"}]]
           (:effects (events/handle-event all-handlers busy {:type :agent/abort :room-id "r"}))))
    (is (= [] (:effects (events/handle-event all-handlers (with-room)
                                             {:type :agent/abort :room-id "r"}))))))

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
       10))))
