(ns xi.e2e.rewind-e2e
  "Deleting a message while its turn still streams (web Delete / Retry =
   :tree/navigate): the turn stops, the Claude transcript is cut in place to
   the kept prompts, the next turn resumes the same session with only those,
   and after a restart the chat shows the kept conversation."
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.e2e.harness :as h :refer [of-type]]
            [xi.e2e.profiles :as profiles]))

(def ^:private script
  {:rules [{:when {:prompt "hello"} :reply [{:text "hi there"}]}
           {:when {:prompt "wrong chat"} :reply [{:text "streaming"} {:sleep 30000} {:text "never"}]}
           {:when {:prompt "follow up"} :reply [{:text "sure"}]}]
   :side [{:text "E2E Title"}]})

(defn- prompts-seen
  "The user prompts in what the model saw of the resumed transcript."
  [turn]
  (->> (:transcript turn)
       (filter #(and (= "user" (:role %)) (:text %)))
       (map :text)))

(defn- chat!
  "hello → reply, then delete \"wrong chat\" while it streams and resubmit at
   once (as web Retry does). Resolves when the follow-up's turn ended."
  [env client]
  (let [send! (:send! client)
        !room (atom nil)
        await-from (fn [pred & [opts]]
                     (h/await-event client pred (merge {:from (count @(:events client))} opts)))]
    (send! {:type :room/join :target "new" :cwd (:project env)})
    (-> (await-from (of-type :room/joined))
        (.then (fn [joined]
                 (reset! !room (:room-id joined))
                 (let [p (await-from (of-type :agent/turn-end @!room))]
                   (send! {:type :prompt/submit :text "hello"})
                   p)))
        (.then (fn [_]
                 (let [p (await-from #(and ((of-type :agent/text-delta @!room) %)
                                           (= "streaming" (:text %))))]
                   (send! {:type :prompt/submit :text "wrong chat"})
                   p)))
        (.then (fn [_]
                 (let [p (await-from (of-type :agent/turn-end @!room) {:timeout-ms 10000})]
                   ;; [hello, hi there, wrong chat, streaming…] → keep 2
                   (send! {:type :tree/navigate :index 2})
                   (send! {:type :prompt/submit :text "follow up"})
                   p))))))

(deftest deleting-a-streaming-message-rewinds-the-transcript
  (async done
    (h/with-env! profiles/minimal script done
      (fn [env]
        (-> (h/start-server! env)
            (.then
             (fn [server]
               (-> (h/connect! server)
                   (.then #(chat! env %))
                   (.then
                    (fn [end]
                      (let [[first-turn deleted follow-up] (h/main-turns env)]
                        (testing "the deleted message's turn stopped"
                          (is (not (:aborted? end)) "the turn-end seen is the follow-up's")
                          (is (= "wrong chat" (:prompt deleted)))
                          (is (= "aborted" (:stop-reason deleted))))
                        (testing "the follow-up resumes the same session, cut to the kept prompts"
                          (is (= "follow up" (:prompt follow-up)))
                          (is (= (:session-id first-turn) (:resume-session-id follow-up)))
                          (is (= ["hello"] (prompts-seen follow-up))
                              (pr-str (:transcript follow-up)))))))
                   (.finally (fn [] (h/close-all!) ((:stop! server)))))))
            (.then (fn [_] (h/run-xi! env ["sessions" "--all" "--json"])))
            (.then
             (fn [{:keys [json stderr]}]
               (is (= 1 (count json)) stderr)
               (-> (h/start-server! env)
                   (.then
                    (fn [server]
                      (-> (h/connect! server)
                          (.then (fn [client]
                                   ((:send! client) {:type :room/join
                                                     :target {:session-id (:session-id (first json))}})
                                   (h/await-event client #(or ((of-type :session/resumed) %)
                                                              (and ((of-type :room/joined) %)
                                                                   (seq (get-in % [:room :history])))))))
                          (.then (fn [ev]
                                   (let [texts (keep :text (concat (:messages ev)
                                                                   (get-in ev [:room :history])))]
                                     (testing "after a restart the chat shows the kept conversation"
                                       (is (some #{"hello"} texts) (pr-str texts))
                                       (is (some #{"follow up"} texts) (pr-str texts))
                                       (is (not-any? #(str/includes? % "wrong chat") texts)
                                           (pr-str texts))))))
                          (.finally (fn [] (h/close-all!) ((:stop! server))))))))))))))
)