(ns xi.e2e.explain-e2e
  "The Explain button on a permission-gated tool block, end to end: a web
   client's :subagent/explain-call reaches the server, which spawns the
   explain-<call-id> sub-agent with a prompt built from the call, its ask and
   the conversation; the fake LLM answers that turn; the explanation is
   mirrored to clients and survives the ask being answered."
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.e2e.harness :as h :refer [of-type]]
            [xi.e2e.profiles :as profiles]))

(def ^:private explanation
  "**What it does** — changes the README title.\n\n**Recommendation:** deny — not what was asked.")

(def ^:private script
  ;; The explain rule comes first: the explanation prompt quotes the user's
  ;; prompt ("retitle"), so the chat rule would match it too.
  {:rules [{:when  {:prompt "You are explaining a tool call"}
            :reply [{:text explanation}]}
           {:when  {:prompt "hello"} :reply [{:text "hi"}]}
           {:when  {:prompt "retitle"}
            :reply [{:tool "edit" :args {:path "README.md"
                                         :edits [{:oldText "# e2e project" :newText "# changed"}]}}
                    {:text "{{tool-result}}"}]}]
   :side  [{:text "E2E Title"}]})

(defn- join!
  [env client]
  (let [from (count @(:events client))]
    ((:send! client) {:type :room/join :target "new" :cwd (:project env)})
    (-> (h/await-event client (of-type :room/joined) {:from from})
        (.then #(:room-id %)))))

(deftest explain-a-gated-tool-call
  (async done
    (h/with-env! profiles/locked-down script done
      (fn [env]
        (-> (h/start-server! env)
            (.then
             (fn [server]
               (-> (h/connect! server)
                   (.then
                    (fn [client]
                      (-> (join! env client)
                          (.then
                           (fn [room-id]
                             ;; A first turn, so the chat has a transcript on
                             ;; disk (the fake writes it at turn end) for the
                             ;; explanation to point at.
                             (let [from (count @(:events client))]
                               ((:send! client) {:type :prompt/submit :text "hello"})
                               (-> (h/await-event client (of-type :agent/turn-end room-id) {:from from})
                                   (.then (fn [_] room-id))))))
                          (.then
                           (fn [room-id]
                             (testing "the edit raises its ask on the tool call"
                               (let [from (count @(:events client))]
                                 ((:send! client) {:type :prompt/submit :text "retitle the readme"})
                                 (-> (js/Promise.all
                                      #js [(h/await-event client (of-type :agent/tool-start room-id) {:from from})
                                           (h/await-event client (of-type :ui/dialog-open room-id) {:from from})])
                                     (.then (fn [[call open]]
                                              (let [dialog (:dialog open)]
                                                (is (= "edit" (:tool call)))
                                                (is (= :confirm (:type dialog)))
                                                (is (= "edit" (get-in dialog [:call :name])) (pr-str dialog))
                                                {:room-id room-id :call-id (:id call)
                                                 :dialog-id (:id dialog)}))))))))
                          (.then
                           (fn [{:keys [room-id call-id] :as ctx}]
                             (testing "Explain spawns one sub-agent for the call, with the context"
                               (let [from   (count @(:events client))
                                     sub-id (str "explain-" call-id)]
                                 ((:send! client) {:type :subagent/explain-call
                                                   :room-id room-id :call-id call-id})
                                 (-> (h/await-event client #(and ((of-type :subagent/spawn room-id) %)
                                                                 (= sub-id (:sub-id %)))
                                                    {:from from})
                                     (.then (fn [spawn]
                                              (let [p (:prompt spawn)]
                                                (is (= "Explain edit" (:label spawn)))
                                                (is (str/includes? p "- tool: edit"))
                                                (is (str/includes? p (str "- working directory: " (:project env))))
                                                (is (str/includes? p "README.md — approve?") "the ask's message")
                                                (is (str/includes? p "## The change it would make (README.md)"))
                                                (is (str/includes? p "+ # changed") "the edit's diff")
                                                (is (str/includes? p "[user]\nhello\n\n[assistant]\nhi\n\n[user]\nretitle the readme")
                                                    "the conversation so far, in order")
                                                (let [transcript (second (re-find #"JSONL file (\S+\.jsonl)" p))]
                                                  (is (some? transcript) "the parent transcript path is named")
                                                  (is (some? (h/slurp transcript)) (str transcript " exists"))))
                                              (h/await-event client #(and ((of-type :subagent/turn-end room-id) %)
                                                                          (= sub-id (:sub-id %)))
                                                             {:from from})))
                                     (.then (fn [end]
                                              (is (not (:aborted? end)))
                                              (let [texts (->> (drop from @(:events client))
                                                               (filter #(and ((of-type :subagent/text-delta room-id) %)
                                                                             (= sub-id (:sub-id %))))
                                                               (map :text)
                                                               (apply str))]
                                                (is (= explanation texts) "the explanation streams to the client"))
                                              (let [turn (some #(when (str/starts-with? (str (:prompt %))
                                                                                        "You are explaining")
                                                                  %)
                                                               (h/main-turns env))]
                                                (is (some? turn) "the explain turn ran against the model")
                                                (is (not (:side? turn)) "with tools, as a full turn")
                                                (is (str/includes? (str (:system turn)) "E2E-PROJECT-INSTRUCTIONS")
                                                    "the sub-agent gets the room's project instructions"))
                                              ;; a second press while it is done is a no-op
                                              ((:send! client) {:type :subagent/explain-call
                                                                :room-id room-id :call-id call-id})
                                              (assoc ctx :from from :sub-id sub-id))))))))
                          (.then
                           (fn [{:keys [room-id dialog-id from sub-id] :as ctx}]
                             (testing "answering the ask keeps the explanation"
                               ((:send! client) {:type :ui/dialog-response
                                                 :room-id room-id :dialog-id dialog-id :value false})
                               (-> (h/await-event client (of-type :agent/turn-end room-id) {:from from})
                                   (.then (fn [_]
                                            (is (= "# e2e project\n" (h/slurp (h/path env "README.md")))
                                                "denied: nothing written")
                                            (is (= 1 (count (filter #(and ((of-type :subagent/spawn room-id) %)
                                                                          (= sub-id (:sub-id %)))
                                                                    @(:events client))))
                                                "one explanation per call")
                                            (h/connect! server)))
                                   (.then (fn [c2]
                                            ((:send! c2) {:type :room/join :target room-id})
                                            (h/await-event c2 (of-type :room/joined room-id))))
                                   (.then (fn [joined]
                                            (let [child (some #(when (= sub-id (:id %)) %)
                                                              (get-in joined [:room :ext :subagents :agents]))]
                                              (is (= :done (:status child)) (pr-str child))
                                              (is (= explanation (:result child))
                                                  "a late-joining client sees the kept explanation"))
                                            ctx)))))))))
                   (.finally (:stop! server))))))))))
