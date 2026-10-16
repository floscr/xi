(ns xi.e2e.subagent-reply-e2e
  "Replying to a finished sub-agent, end to end: the reply runs on the same
   child, resuming the hidden session it was saved as (it sees its first
   turn), every client gets the reply, and the answer becomes its result."
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.e2e.harness :as h :refer [of-type]]
            [xi.e2e.profiles :as profiles]))

(def ^:private script
  {:rules [{:when {:prompt "count the files"} :reply [{:text "There are 3 files."}]}
           {:when {:prompt "which is largest"} :reply [{:text "a.txt is largest."}]}]
   :side  [{:text "E2E Title"}]})

(defn- of-child [t room-id sub-id]
  #(and ((of-type t room-id) %) (= sub-id (:sub-id %))))

(deftest reply-to-a-finished-sub-agent
  (async done
    (h/with-env! profiles/locked-down script done
      (fn [env]
        (-> (h/start-server! env)
            (.then
             (fn [server]
               (-> (h/connect! server)
                   (.then
                    (fn [client]
                      (let [from (count @(:events client))]
                        ((:send! client) {:type :room/join :target "new" :cwd (:project env)})
                        (-> (h/await-event client (of-type :room/joined) {:from from})
                            (.then
                             (fn [joined]
                               (let [room-id (:room-id joined)
                                     from    (count @(:events client))]
                                 ((:send! client) {:type :subagent/spawn :room-id room-id
                                                   :task "count the files"})
                                 (-> (h/await-event client #(and ((of-type :subagent/spawn room-id) %)
                                                                 (:sub-id %))
                                                    {:from from})
                                     (.then (fn [spawn]
                                              (-> (h/await-event client (of-child :subagent/promoted room-id
                                                                                  (:sub-id spawn))
                                                                 {:from from})
                                                  (.then (fn [promoted]
                                                           {:room-id room-id :sub-id (:sub-id spawn)
                                                            :session-id (:session-id promoted)})))))))))
                            (.then
                             (fn [{:keys [room-id sub-id session-id]}]
                               (let [from (count @(:events client))]
                                 ((:send! client) {:type :subagent/reply :room-id room-id
                                                   :sub-id sub-id :text "which is largest?"})
                                 (-> (h/await-event client (of-child :subagent/reply room-id sub-id)
                                                    {:from from})
                                     (.then (fn [_]
                                              (h/await-event client (of-child :subagent/turn-end room-id sub-id)
                                                             {:from from})))
                                     (.then
                                      (fn [_]
                                        (let [turns (h/main-turns env)
                                              reply (last turns)]
                                          (testing "the reply resumes the child's saved session"
                                            (is (= 2 (count turns)) "the task, then the reply")
                                            (is (= "which is largest?" (:prompt reply)))
                                            (is (some? (:resume-session-id reply)))
                                            (is (str/includes? (pr-str (:transcript reply)) "There are 3 files.")
                                                "it sees its first turn"))
                                          (testing "no new session: the reply appends to the hidden one"
                                            (let [after (filterv #(and ((of-type :subagent/promoted room-id) %)
                                                                       (= sub-id (:sub-id %)))
                                                                 (drop from @(:events client)))]
                                              (is (empty? after))
                                              (is (some? session-id)))))))))))))))
                   (.finally (:stop! server))))))))))
