(ns xi.e2e.server-e2e
  "`xi server --headless` end to end over its WebSocket and HTTP API: pairing,
   a streamed turn with its title side turn, abort, the prompt queue,
   reconnecting, the saved chat after a restart, and a restart continuing a
   turn it cut off (queue included) with nobody watching."
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.e2e.harness :as h :refer [of-type]]
            [xi.e2e.profiles :as profiles]))

(def ^:private script
  {:rules [{:when {:prompt "hello"} :reply [{:text "hello "} {:text "over ws"}]}
           {:when {:prompt "slow"} :reply [{:text "a"} {:sleep 1500} {:text "b"}]}
           {:when {:prompt "stuck"} :reply [{:text "waiting"} {:sleep 30000} {:text "never"}]}]
   :side [{:text "E2E Title"}]})

(defn- with-server!
  "Run (f env server) against a fresh env + server; stops the server after."
  [files done f]
  (h/with-env! files script done
    (fn [env]
      (-> (h/start-server! env)
          (.then (fn [server]
                   (-> (js/Promise.resolve)
                       (.then #(f env server))
                       (.finally (:stop! server)))))))))

(defn- join!
  "Join a new room in the env's project; resolves to its room id."
  [env client]
  (let [from (count @(:events client))]
    ((:send! client) {:type :room/join :target "new" :cwd (:project env)})
    (-> (h/await-event client (of-type :room/joined) {:from from})
        ;; not (.then :room-id): .then ignores a non-function
        (.then #(:room-id %)))))

(defn- text-of
  "Concatenated text deltas of `room-id` in the client's stream from `from`."
  [client room-id from]
  (->> (drop from @(:events client))
       (filter (of-type :agent/text-delta room-id))
       (map :text)
       (apply str)))

(defn- session-files
  "Contents of every saved session's metadata file in the env's HOME."
  [env]
  (let [dir (h/home-path env ".config" "xi" "sessions")]
    (->> (h/files-under dir)
         (filter #(str/ends-with? % ".json"))
         (map #(h/slurp (h/home-path env ".config" "xi" "sessions" %))))))

(deftest pairing-and-the-http-api
  (async done
    (with-server! profiles/minimal done
      (fn [env server]
        (-> (js/Promise.all
             #js [(h/connect! server)
                  (h/connect! server {:key "an-unknown-device-key-123"})
                  (h/connect! server {:key "short"})])
            (.then
             (fn [clients]
               (let [[ok pending denied] (map (comp :type first
                                                   #(filter (comp #{:auth/ok :auth/pending :auth/denied} :type) %)
                                                   deref :events)
                                              clients)]
                 (testing "the local key is trusted, others pair or are refused"
                   (is (= :auth/ok ok))
                   (is (= :auth/pending pending))
                   (is (= :auth/denied denied))))
               (doseq [c clients] ((:close! c)))
               (js/Promise.all
                #js [(h/http! server "/api/rooms" {:method "POST" :body "{}"})
                     (h/http! server "/api/rooms"
                              {:method "POST"
                               :headers {"x-xi-client-key" h/client-key}
                               :body (js/JSON.stringify #js {:prompt "hello"})})])))
            (.then
             (fn [[unauthed created]]
               (is (= 401 (:status unauthed)))
               (is (= 200 (:status created)) (pr-str created))
               (is (str/ends-with? (get-in created [:body :url])
                                   (str "/chat/" (get-in created [:body :session-id]))))
               (h/wait-until #(some (fn [t] (= "hello" (:prompt t))) (h/main-turns env))
                             10000 "the background turn")))
            (.then
             (fn [_]
               (is (= (:project env) (:cwd (first (h/main-turns env))))
                   "the background room runs in the server's cwd"))))))))

(deftest a-chat-over-the-websocket
  (async done
    (with-server! profiles/minimal done
      (fn [env server]
        (-> (h/connect! server)
            (.then
             (fn [client]
               (-> (join! env client)
                   (.then
                    (fn [room-id]
                      (testing "a turn streams deltas and ends"
                        (let [from (count @(:events client))]
                          ((:send! client) {:type :prompt/submit :text "hello"})
                          (-> (h/await-event client (of-type :agent/turn-end room-id) {:from from})
                              (.then (fn [_]
                                       (is (= "hello over ws" (text-of client room-id from)))
                                       room-id)))))))
                   (.then
                    (fn [room-id]
                      (testing "the first prompt names the chat through the side turn"
                        (-> (h/wait-until #(some :side? (h/llm-log env)) 10000 "the title turn")
                            (.then (fn [_]
                                     (let [side (first (filter :side? (h/llm-log env)))]
                                       (is (= "anthropic" (:provider side)))
                                       (is (= [] (:tools side)) "side turns carry no tools"))
                                     room-id))))))
                   (.then
                    (fn [room-id]
                      (testing "a prompt sent while busy queues and runs next"
                        (let [from (count @(:events client))]
                          ((:send! client) {:type :prompt/submit :text "slow"})
                          ((:send! client) {:type :prompt/submit :text "hello again"})
                          (-> (h/wait-until #(= 2 (count (filter (of-type :agent/turn-end room-id)
                                                                 (drop from @(:events client)))))
                                            15000 "both turns to end")
                              (.then (fn [_]
                                       (is (= ["hello" "slow" "hello again"]
                                              (map :prompt (h/main-turns env))))
                                       (is (str/starts-with? (text-of client room-id from) "ab")
                                           "the queued prompt waited for the first to finish")
                                       room-id)))))))
                   (.then
                    (fn [room-id]
                      (testing "abort stops a turn mid-sleep"
                        (let [from (count @(:events client))]
                          ((:send! client) {:type :prompt/submit :text "stuck"})
                          (-> (h/await-event client #(and ((of-type :agent/text-delta room-id) %)
                                                          (= "waiting" (:text %)))
                                             {:from from})
                              (.then (fn [_]
                                       ((:send! client) {:type :agent/abort})
                                       (h/await-event client (of-type :agent/turn-end room-id)
                                                      {:from from :timeout-ms 5000})))
                              (.then (fn [end]
                                       (is (:aborted? end) (pr-str end))
                                       (is (not (str/includes? (text-of client room-id from) "never")))
                                       (h/wait-until #(= "aborted" (:stop-reason (last (h/main-turns env))))
                                                     5000 "the aborted turn's log entry")))
                              (.then (fn [_] room-id)))))))
                   (.then
                    (fn [room-id]
                      (testing "a second device joins the live room with its history"
                        (-> (h/connect! server)
                            (.then (fn [c2]
                                     ((:send! c2) {:type :room/join :target room-id})
                                     (h/await-event c2 (of-type :room/joined room-id))))
                            (.then (fn [joined]
                                     (let [texts (keep :text (get-in joined [:room :history]))]
                                       (is (some #{"hello"} texts) (pr-str (update joined :room select-keys [:history :session :id])))
                                       (is (some #{"hello over ws"} texts) (pr-str texts)))
                                     (get-in joined [:room :session :id])))))))
                   (.then
                    (fn [session-id]
                      (testing "after everyone left, rejoining the session restores it"
                        ;; the idle, clientless room is reaped; a reconnect
                        ;; joins by session id (as the web client does)
                        (h/close-all!)
                        (-> (h/connect! server)
                            (.then (fn [c3]
                                     ((:send! c3) {:type :room/join :target {:session-id session-id}})
                                     ;; resumed from disk, or the room was still live
                                     (h/await-event c3 #(or ((of-type :session/resumed) %)
                                                            (and ((of-type :room/joined) %)
                                                                 (seq (get-in % [:room :history])))))))
                            (.then (fn [ev]
                                     (let [texts (keep :text (concat (:messages ev)
                                                                     (get-in ev [:room :history])))]
                                       (is (some #{"hello"} texts) (pr-str ev))
                                       (is (some #{"hello over ws"} texts) (pr-str texts))))))))))))
            (.then (fn [_] (h/close-all!) ((:stop! server))))
            (.then
             (fn [_]
               (testing "the chat is saved under its title"
                 (-> (h/run-xi! env ["sessions" "--all" "--json"])
                     (.then (fn [{:keys [json stderr]}]
                              (is (= ["E2E Title"] (map :name json)) stderr))))))))))))

(deftest a-restart-continues-the-cut-off-turn-and-its-queue
  ;; A hard kill mid-turn with a prompt queued behind it. The next server
  ;; continues the chat on its own — no client joins it — and the queued
  ;; prompt follows.
  (async done
    (h/with-env! profiles/minimal script done
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
                             ;; a finished turn first: the session then has a
                             ;; transcript and a provider id to resume
                             (let [from (count @(:events client))]
                               ((:send! client) {:type :prompt/submit :text "hello"})
                               (-> (h/await-event client (of-type :agent/turn-end room-id) {:from from})
                                   (.then (fn [_]
                                            ((:send! client) {:type :prompt/submit :text "stuck"})
                                            ((:send! client) {:type :prompt/submit :text "hello again"})
                                            (h/await-event client #(and ((of-type :agent/text-delta room-id) %)
                                                                        (= "waiting" (:text %)))
                                                           {:from from})))
                                   (.then (fn [_]
                                            (h/wait-until #(some (fn [s] (str/includes? (str s) "hello again"))
                                                                 (session-files env))
                                                          5000 "the queued prompt on disk")))))))
                          (.then (fn [_]
                                   (h/close-all!)
                                   (.kill (:proc server) "SIGKILL")
                                   ((:stop! server))))))))))
            (.then (fn [_] (h/start-server! env)))
            (.then
             (fn [server2]
               (-> (h/wait-until #(= ["hello" "continue" "hello again"]
                                     (map :prompt (h/main-turns env)))
                                 15000 (str "the restarted server to continue the chat and send the queue; turns: "
                                            (pr-str (map :prompt (h/main-turns env)))))
                   (.then (fn [_]
                            (let [[_ continued queued] (h/main-turns env)]
                              (is (some? (:resume-session-id continued))
                                  "continues the saved session rather than starting over")
                              (is (= "stuck" (:text (last (:transcript continued))))
                                  "the cut-off prompt is in the transcript the model sees")
                              (is (= (:session-id continued) (:resume-session-id queued))
                                  "the queued prompt runs in the same session"))
                            ;; the turn-end sync (after the fake's log line) drops the markers
                            (-> (h/wait-until #(not (some (fn [s] (str/includes? (str s) "interrupted-at"))
                                                          (session-files env)))
                                              5000 "the resume markers to clear")
                                (.catch (fn [e]
                                          (throw (js/Error. (str (.-message e) "; files: "
                                                                 (pr-str (session-files env))))))))))
                   (.finally (:stop! server2))))))))))
