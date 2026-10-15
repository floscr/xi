(ns xi.e2e.threads-e2e
  "Question threads on chat blocks, end to end: a question about an
   assistant message runs as a sub-agent that sees the block, is saved as a
   hidden session when it finishes and linked from the parent chat's saved
   state, takes a follow-up with the earlier answer, upgrades to a listed
   session and closes."
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.e2e.harness :as h :refer [of-type]]
            [xi.e2e.profiles :as profiles]))

(def ^:private script
  {:rules [{:when {:prompt "## The block"} :reply [{:text "Because you greeted it."}]}
           {:when {:prompt "hello"} :reply [{:text "hi"}]}]
   :side  [{:text "E2E Title"}]})

(defn- sessions-dir [env] (h/home-path env ".config/xi/sessions"))

(defn- session-file
  "The saved metadata of session `sid` (any project dir), or nil."
  [env sid]
  (some #(when (str/ends-with? % (str sid ".json"))
           (h/slurp (str (sessions-dir env) "/" %)))
        (h/files-under (sessions-dir env))))

(defn- ext-sidecar [env sid]
  (some #(when (str/ends-with? % (str sid ".ext.edn"))
           (h/slurp (str (sessions-dir env) "/" %)))
        (h/files-under (sessions-dir env))))

(defn- ask-turn
  "Send `ask`, then resolve to [spawn promoted] of the sub-agent it starts."
  [client room-id ask]
  (let [from (count @(:events client))]
    ((:send! client) (merge {:type :subagent/ask :room-id room-id} ask))
    (-> (h/await-event client #(and ((of-type :subagent/spawn room-id) %) (:node %)) {:from from})
        (.then (fn [spawn]
                 (-> (h/await-event client #(and ((of-type :subagent/promoted room-id) %)
                                                 (= (:sub-id spawn) (:sub-id %)))
                                    {:from from})
                     (.then (fn [promoted] [spawn promoted]))))))))

(deftest ask-about-a-block
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
                            (.then (fn [joined]
                                     (let [room-id (:room-id joined)
                                           from    (count @(:events client))]
                                       ((:send! client) {:type :prompt/submit :text "hello"})
                                       (-> (h/await-event client (of-type :agent/turn-end room-id) {:from from})
                                           (.then (fn [_] room-id))))))
                            (.then
                             (fn [room-id]
                               (-> (ask-turn client room-id {:node {:index 1 :kind :text :hint "hi"}
                                                             :question "why hi?"})
                                   (.then
                                    (fn [[spawn promoted]]
                                      (testing "the question runs as a sub-agent that sees the block"
                                        (is (str/starts-with? (:sub-id spawn) "ask-"))
                                        (is (= (:sub-id spawn) (:thread spawn)))
                                        (is (= "why hi?" (:question spawn)))
                                        (is (str/includes? (:prompt spawn) "## The block (#1)\n```\n[assistant]\nhi"))
                                        (is (str/includes? (:prompt spawn) "## Question\nwhy hi?")))
                                      (testing "it is saved as a hidden session the parent links to"
                                        (let [child (session-file env (:session-id promoted))]
                                          (is (some? child) "the child session's metadata")
                                          (is (str/includes? (str child) "subagent-origin"))
                                          (is (str/includes? (str child) (:sub-id spawn)))))
                                      {:room-id room-id :thread (:thread spawn)
                                       :sub-id (:sub-id spawn) :child-sid (:session-id promoted)})))))
                            (.then
                             (fn [{:keys [room-id thread] :as ctx}]
                               (-> (ask-turn client room-id {:thread thread :question "and then?"})
                                   (.then
                                    (fn [[spawn _]]
                                      (testing "a follow-up stays in the thread and carries the answer"
                                        (is (= thread (:thread spawn)))
                                        (is (= 1 (get-in spawn [:node :index])) "the thread's node")
                                        (is (str/includes? (:prompt spawn)
                                                           "**Q:** why hi?\n\n**A:** Because you greeted it.")))
                                      ctx)))))
                            (.then
                             (fn [{:keys [room-id sub-id child-sid] :as ctx}]
                               (testing "the parent chat saves the links, not the histories"
                                 (let [parent-sid (some #(when (and (= :room/joined (:type %))
                                                                    (= room-id (:room-id %)))
                                                           (get-in % [:room :session :id]))
                                                        @(:events client))
                                       saved      (when parent-sid (ext-sidecar env parent-sid))]
                                   (is (some? saved) (str "the sidecar of " parent-sid))
                                   (is (str/includes? (str saved) child-sid))
                                   (is (not (str/includes? (str saved) ":history")))))
                               (let [from (count @(:events client))]
                                 ((:send! client) {:type :subagent/upgrade :room-id room-id :sub-id sub-id})
                                 (-> (h/await-event client #(and ((of-type :subagent/upgraded room-id) %)
                                                                 (= sub-id (:sub-id %)))
                                                    {:from from})
                                     (.then (fn [_]
                                              (testing "upgraded: a regular session"
                                                (is (not (str/includes? (str (session-file env child-sid))
                                                                        "subagent-origin"))))
                                              ctx))))))
                            (.then
                             (fn [{:keys [room-id thread]}]
                               (let [from (count @(:events client))]
                                 ((:send! client) {:type :subagent/thread-close :room-id room-id :thread thread})
                                 (h/await-event client (of-type :subagent/thread-close room-id) {:from from}))))))))
                   (.finally (:stop! server))))))))))
