(ns xi.e2e.run-queued-e2e
  "The queue popover's Run-in-a-sub-agent button, end to end: while a turn is
   held on a permission ask, a queued prompt sent through
   :subagent/run-queued leaves the queue and runs as a sub-agent whose system
   prompt carries the parent conversation and its transcript path."
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.e2e.harness :as h :refer [of-type]]
            [xi.e2e.profiles :as profiles]))

(def ^:private script
  {:rules [{:when  {:prompt "summarize the chat"} :reply [{:text "summary"}]}
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

(deftest run-a-queued-prompt-in-a-sub-agent
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
                             ;; A first turn leaves a transcript on disk.
                             (let [from (count @(:events client))]
                               ((:send! client) {:type :prompt/submit :text "hello"})
                               (-> (h/await-event client (of-type :agent/turn-end room-id) {:from from})
                                   (.then (fn [_] room-id))))))
                          (.then
                           (fn [room-id]
                             ;; The edit's ask holds the turn, so the next prompt queues.
                             (let [from (count @(:events client))]
                               ((:send! client) {:type :prompt/submit :text "retitle the readme"})
                               (-> (h/await-event client (of-type :ui/dialog-open room-id) {:from from})
                                   (.then (fn [_]
                                            ((:send! client) {:type :prompt/submit :text "summarize the chat"})
                                            room-id))))))
                          (.then
                           (fn [room-id]
                             (testing "the queued prompt leaves the queue and runs as a sub-agent"
                               (let [from (count @(:events client))]
                                 ((:send! client) {:type :subagent/run-queued :room-id room-id :index 0})
                                 (-> (js/Promise.all
                                      #js [(h/await-event client (of-type :prompt/queue-remove room-id) {:from from})
                                           (h/await-event client (of-type :subagent/spawn room-id) {:from from})
                                           (h/await-event client (of-type :subagent/turn-end room-id) {:from from})])
                                     (.then (fn [[_ spawn end]]
                                              (is (= "summarize the chat" (:prompt spawn)))
                                              (is (not (:aborted? end)))
                                              (let [turn (some #(when (= "summarize the chat" (:prompt %)) %)
                                                               (h/main-turns env))
                                                    sys  (str (:system turn))]
                                                (is (some? turn) "the sub-agent turn ran")
                                                (is (str/includes? sys "## The parent conversation"))
                                                (is (str/includes? sys "[user]\nhello\n\n[assistant]\nhi\n\n[user]\nretitle the readme")
                                                    "the parent conversation, in order")
                                                (let [transcript (second (re-find #"JSONL file (\S+\.jsonl)" sys))]
                                                  (is (some? (h/slurp transcript)) (str transcript " exists"))))
                                              (h/connect! server)))
                                     (.then (fn [c2]
                                              ((:send! c2) {:type :room/join :target room-id})
                                              (h/await-event c2 (of-type :room/joined room-id))))
                                     (.then (fn [joined]
                                              (is (empty? (get-in joined [:room :agent :queued]))
                                                  "nothing left queued behind the turn")
                                              room-id)))))))
                          (.then
                           (fn [room-id]
                             (testing "/sa <task> spawns a sub-agent while the turn still runs"
                               (let [from (count @(:events client))]
                                 ((:send! client) {:type :input/submit :room-id room-id
                                                   :text "/sa summarize the chat again"})
                                 (-> (h/await-event client (of-type :subagent/spawn room-id) {:from from})
                                     (.then (fn [spawn]
                                              (is (= "summarize the chat again" (:prompt spawn)))))))))))))
                   (.finally (:stop! server))))))))))
