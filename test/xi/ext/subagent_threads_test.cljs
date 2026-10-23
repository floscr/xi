(ns xi.ext.subagent-threads-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.core.state :as state]
            [xi.ext.core :as ext]
            [xi.ext.subagent :as sa-ext]
            [xi.ext.subagent.handlers :as h]
            [xi.ext.subagent.threads :as t]
            [xi.ext.subagent.web :as sa-web]))

(def ^:private live-history
  [{:kind :user :text "make config a defrecord"}
   {:kind :thinking :text "hmm"}
   {:kind :tool-call :id "t1" :tool "mcp__xi-tools__read" :arguments {:path "a.clj"}
    :result [{:type "text" :text "(ns a)"}]}
   {:kind :text :text "Done: config is a record now."}])

;; The same chat rebuilt from its transcript: no thinking, indices shift.
(def ^:private rebuilt-history
  (into [] (remove #(= :thinking (:kind %))) live-history))

(defn- base-state []
  (-> (state/initial-state {:mode :server})
      (assoc-in [:rooms "r1"]
                {:id "r1" :cwd "/x" :agent {:model "m"}
                 :session {:id "s1"}
                 :history live-history
                 :ext {:subagents {:agents []}}})))

(defn- with-agents [st agents]
  (assoc-in st [:rooms "r1" :ext :subagents :agents] agents))

(defn- room-agents [st]
  (get-in st [:rooms "r1" :ext :subagents :agents]))

;; ── Nodes ────────────────────────────────────────────────────────────────────

(deftest a-node-names-its-block
  (is (= {:index 3 :kind :text :hint "Done: config is a record now."}
         (t/entry-node live-history 3)))
  (is (= {:index 2 :kind :tool-call :call-id "t1" :hint "read"}
         (t/entry-node live-history 2)) "tool calls: their id, the tool without MCP prefix")
  (is (= {:sub-id "sa-1" :index 0 :kind :user :hint "make config a defrecord"}
         (t/entry-node "sa-1" live-history 0)))
  (is (nil? (t/entry-node live-history 9)))
  (is (= "#3" (t/address {:index 3})))
  (is (= "sa-1#4" (t/address {:sub-id "sa-1" :index 4})))
  (is (= "sa-1" (t/address (t/agent-node "sa-1"))))
  (is (= {:sub-id "sa-1" :hint "Review"} (t/agent-node "sa-1" "Review"))
      "a sub-agent's label is its hint"))

(deftest a-node-is-found-again-in-a-rebuilt-history
  (testing "unchanged history: its index"
    (is (= 3 (t/locate live-history (t/entry-node live-history 3)))))
  (testing "thinking dropped: the same text, one earlier"
    (is (= 2 (t/locate rebuilt-history (t/entry-node live-history 3)))))
  (testing "a tool call by its id"
    (is (= 1 (t/locate rebuilt-history (t/entry-node live-history 2)))))
  (testing "nothing matches: the index, clamped"
    (is (= 2 (t/locate rebuilt-history {:index 7 :kind :text :hint "gone"}))))
  (is (nil? (t/locate [] {:index 0 :kind :user})))
  (is (nil? (t/locate live-history (t/agent-node "sa-1"))) "a whole sub-agent has no index"))

(deftest a-card-follows-its-spawn-call
  (let [child {:id "sa-1" :call-id "t1" :anchor 3 :ended-anchor 4}]
    (is (= 3 (t/anchor-of live-history child)))
    (is (= 2 (t/anchor-of rebuilt-history child)) "after the call, wherever it moved")
    (is (= 3 (t/ended-anchor-of rebuilt-history child))
        "the result moves with the card"))
  (let [child {:id "sa-1" :anchor 9 :ended-anchor 12}]
    (is (= 4 (t/anchor-of live-history child)) "no call id: the anchor, clamped")
    (is (= 4 (t/ended-anchor-of live-history child))))
  (is (nil? (t/ended-anchor-of live-history {:anchor 1})) "still running"))

(deftest resolve-node-reads-the-block
  (let [st (with-agents (base-state)
             [{:id "sa-1" :label "Review" :task "review a.clj" :status :done
               :result "Looks fine." :history [{:kind :user :text "review a.clj"}
                                               {:kind :text :text "Looks fine."}]}])]
    (is (= {:node {:index 2 :kind :tool-call :call-id "t1" :hint "read"}
            :text "[tool call] read {:path \"a.clj\"}\n[result]\n(ns a)"}
           (t/resolve-node st "r1" {:index 2 :kind :tool-call :call-id "t1"})))
    (is (= "[thinking]\nhmm" (:text (t/resolve-node st "r1" (t/entry-node live-history 1)))))
    (is (= "[assistant]\nLooks fine."
           (:text (t/resolve-node st "r1" {:sub-id "sa-1" :index 1 :kind :text}))))
    (let [{whole :text node :node} (t/resolve-node st "r1" (t/agent-node "sa-1"))]
      (is (= {:sub-id "sa-1" :hint "Review"} node))
      (is (str/starts-with? whole "[sub-agent sa-1] Review"))
      (is (str/includes? whole "result:\nLooks fine.")))
    (is (nil? (t/resolve-node st "r1" (t/agent-node "nope"))) "a gone sub-agent")))

;; ── Threads ──────────────────────────────────────────────────────────────────

(defn- spawn-of [res]
  (some (fn [[fx ev]] (when (and (= :app/dispatch fx) (= :subagent/spawn (:type ev))) ev))
        (:effects res)))

(deftest asking-about-a-block-spawns-a-thread
  (let [ev (spawn-of (t/ask (base-state) {:room-id "r1" :question "  why a record? "
                                          :node (t/entry-node live-history 3)}))]
    (is (str/starts-with? (:sub-id ev) "ask-"))
    (is (h/inline-sub? (:sub-id ev)) "renders under its block, not in the sub-agent posts")
    (is (= (:sub-id ev) (:thread ev)) "a new thread is named by its first turn")
    (is (= "why a record?" (:question ev)))
    (is (= 3 (get-in ev [:node :index])))
    (is (str/includes? (:prompt ev) "## The block (#3)\n```\n[assistant]\nDone: config is a record now."))
    (is (str/includes? (:prompt ev) "## Question\nwhy a record?"))
    (is (str/includes? (:prompt ev) "(the block is entry #3)")))
  (is (nil? (t/ask (base-state) {:room-id "r1" :question " " :node {:index 0 :kind :user}})))
  (is (nil? (t/ask (base-state) {:room-id "r1" :question "q" :node (t/agent-node "nope")}))))

(deftest asking-about-a-selection
  (let [node (t/entry-node live-history 3)
        ev   (spawn-of (t/ask (base-state) {:room-id "r1" :question "why?" :client-id "c1"
                                            :selection "  a record now. " :node node}))]
    (is (= "a record now." (:selection ev)))
    (is (= "c1" (:client-id ev)) "the asking client, whose browser shows the thread")
    (is (str/includes? (:prompt ev) "text they selected in one block of it, #3"))
    (is (str/includes? (:prompt ev) "## The selected text\n```\na record now.\n```"))
    (testing "the child keeps the selection"
      (is (= "a record now."
             (:selection (first (room-agents (:state ((:subagent/spawn h/handlers) (base-state) ev))))))))
    (testing "a follow-up quotes the first turn's selection"
      (let [st  (with-agents (base-state)
                  [{:id "ask-1" :thread "ask-1" :node node :question "why?" :selection "a record now."
                    :status :done :result "because" :started 1}])
            ev2 (spawn-of (t/ask st {:room-id "r1" :thread "ask-1" :question "sure?"}))]
        (is (= "a record now." (:selection ev2)))
        (is (str/includes? (:prompt ev2) "## The selected text\n```\na record now.\n```"))))
    (is (nil? (:selection (spawn-of (t/ask (base-state) {:room-id "r1" :question "why?" :node node}))))
        "no selection: a question about the block")))

(deftest a-follow-up-keeps-the-thread-and-its-answers
  (let [node (t/entry-node live-history 2)
        st   (with-agents (base-state)
               [{:id "ask-1" :thread "ask-1" :node node :question "what did this read?"
                 :status :done :result "a.clj" :started 1}])
        ev   (spawn-of (t/ask st {:room-id "r1" :thread "ask-1" :question "and why?"
                                  :node {:index 0 :kind :user}}))]
    (is (= "ask-1" (:thread ev)))
    (is (= node (:node ev)) "the thread's node, not the event's")
    (is (str/includes? (:prompt ev) "**Q:** what did this read?\n\n**A:** a.clj"))))

(deftest a-question-about-a-sub-agent-row-points-at-its-history
  (let [st (with-agents (base-state)
             [{:id "sa-1" :status :done :history [{:kind :text :text "Found it."}]}])
        ev (spawn-of (t/ask st {:room-id "r1" :question "how?"
                                :node {:sub-id "sa-1" :index 0 :kind :text}}))]
    (is (str/includes? (:prompt ev) "## The block (sa-1#0)"))
    (is (str/includes? (:prompt ev) "with `agent: \"sa-1\"`"))))

(deftest threads-group-their-turns-and-close
  (let [n1 {:index 0 :kind :user}
        n2 {:index 3 :kind :text}
        as [{:id "sa-1" :status :done}
            {:id "ask-1" :thread "ask-1" :node n1 :status :done :started 1}
            {:id "ask-2" :thread "ask-2" :node n2 :status :running :started 2}
            {:id "ask-3" :thread "ask-1" :node n1 :status :done :started 3}]
        st (with-agents (base-state) as)]
    (is (= [["ask-1" n1 ["ask-1" "ask-3"]] ["ask-2" n2 ["ask-2"]]]
           (map (juxt :id :node #(mapv :id (:turns %))) (t/threads as))))
    (let [res (t/close st {:room-id "r1" :thread "ask-2"})]
      (is (= [[:app/dispatch {:type :subagent/abort :room-id "r1" :sub-id "ask-2"}]]
             (:effects res)) "a running turn is stopped")
      (is (:closed? (second (rest (room-agents (:state res))))))
      (is (= ["ask-1"] (map :id (t/threads (room-agents (:state res)))))
          "a closed thread no longer shows"))
    (testing "the web closes at once and forwards; the echo applies only state"
      (let [close (get-in sa-web/extension [:handlers :subagent/thread-close])
            ev    {:type :subagent/thread-close :room-id "r1" :thread "ask-2"}]
        (is (= [[:ws/send ev]] (:effects (close st ev))))
        (is (nil? (:effects (close st (assoc ev :remote? true)))))))))

(deftest dismissing-all-keeps-threads
  (let [st (with-agents (base-state)
             [{:id "sa-1" :status :done} {:id "ask-1" :thread "ask-1" :status :done}
              {:id "explain-t1" :status :done} {:id "sa-2" :status :running}])]
    (is (= ["ask-1" "explain-t1" "sa-2"]
           (map :id (room-agents (:state (h/dismiss st {:room-id "r1"}))))))))

;; ── Saved with the parent chat ───────────────────────────────────────────────

(deftest the-parent-saves-links-not-histories
  (let [slice {:agents [{:id "sa-1" :status :done :history [{:kind :text :text "x"}]
                         :expanded? true :result "x" :session-id "s-child" :anchor 2}
                        {:id "sa-2" :status :running :history []}]}
        saved (h/persisted slice)]
    (is (= {:agents [{:id "sa-1" :status :done :result "x" :session-id "s-child" :anchor 2}
                     {:id "sa-2" :status :stopped}]}
           saved) "a turn that was running can't be once the chat is loaded again")
    (is (= saved (h/persisted saved)) "idempotent: hydration filters through it again")))

(deftest hydration-dedupes-saved-entries-by-id
  (let [{:keys [handlers persist-room]} (ext/compose [sa-ext/extension])
        hydrate (:ext.persist/hydrate handlers)
        ;; a legacy :promoted-subagents stub already reseeded on resume
        st      (with-agents (base-state) [{:id "sa-1" :status :done :history [] :session-id "c1"}])
        saved   {:subagents {:agents [{:id "sa-1" :status :done :session-id "c1" :result "r"}
                                      {:id "sa-2" :status :done}]}}
        st'     (:state (hydrate st {:room-id "r1" :slices saved}))]
    (is (fn? (:subagents persist-room)))
    (is (= ["sa-1" "sa-2"] (map :id (room-agents st'))))
    (is (= {:subagents {:agents [{:id "sa-1" :status :done :session-id "c1"}]}}
           (ext/persisted-room-state persist-room
                                     (get-in (with-agents st [{:id "sa-1" :status :done :session-id "c1"
                                                               :history [{:kind :text}]}])
                                             [:rooms "r1"]))))
    (is (= {} (ext/persisted-room-state persist-room {:ext {:subagents {:agents []}}}))
        "nothing to keep: nothing saved")))

(deftest another-session-takes-the-room
  (let [st (with-agents (base-state) [{:id "sa-1" :status :done} {:id "sa-2" :status :running}])]
    (is (= ["sa-2"] (map :id (room-agents (:state (h/reset-for-session st {:room-id "r1"})))))
        "running ones stay to finish their turn")
    (is (nil? (h/reset-for-session st {:room-id "r1" :keep-history? true})) "/truncate keeps them")
    (is (nil? (h/reset-for-session (base-state) {:room-id "r1"})))))

;; ── Node ext: tools and the save on finish ───────────────────────────────────

(deftest a-finished-sub-agent-is-saved
  (let [turn-end (get-in sa-ext/extension [:handlers :subagent/turn-end])
        st       (with-agents (base-state) [{:id "sa-1" :status :running :history []}])
        res      (turn-end st {:room-id "r1" :sub-id "sa-1"})]
    (is (= :done (:status (first (room-agents (:state res))))))
    (is (= [[:subagent/save! {:room-id "r1" :sub-id "sa-1"}]] (:effects res))))
  (is (= :subagent/save!
         (ffirst (:effects ((get-in sa-ext/extension [:handlers :subagent/promote])
                            (with-agents (base-state) [{:id "sa-1" :status :done}])
                            {:room-id "r1" :sub-id "sa-1" :open? true}))))
      "opening one that has no session yet saves it first"))

(deftest upgrade-needs-a-saved-session
  (let [upgrade (get-in sa-ext/extension [:handlers :subagent/upgrade])]
    (is (= [[:subagent/upgrade! {:session-id "c1"}]]
           (:effects (upgrade (with-agents (base-state) [{:id "sa-1" :session-id "c1"}])
                              {:room-id "r1" :sub-id "sa-1"}))))
    (is (nil? (upgrade (with-agents (base-state) [{:id "sa-1"}]) {:room-id "r1" :sub-id "sa-1"}))))
  (is (= [{:id "sa-1" :session-id "c1" :upgraded? true}]
         (room-agents (:state (h/upgraded (with-agents (base-state) [{:id "sa-1" :session-id "c1"}])
                                          {:room-id "r1" :sub-id "sa-1"}))))))

(defn- tool-text [res] (-> res :content first :text))

(deftest spawn-subagent-records-its-call
  (let [spawned (atom nil)
        st      (assoc-in (base-state) [:rooms "r1" :history]
                          [{:kind :user :text "go"}
                           {:kind :tool-call :id "toolu_9" :tool "mcp__xi-tools__spawn_subagent"
                            :arguments {:task "review"} :status :running}])
        run     (get-in sa-ext/extension [:tool-registry "spawn_subagent"])]
    (run {:task "review"} {:dispatch! #(reset! spawned %) :get-state (constantly st)
                           :room-id "r1" :call {:name "spawn_subagent" :arguments {:task "review"}}})
    (is (= "toolu_9" (:call-id @spawned)))))

(deftest agents-read-a-sub-agents-history
  (let [st  (with-agents (base-state)
              [{:id "sa-1" :status :done :result "ok"
                :history [{:kind :user :text "review a.clj"} {:kind :text :text "ok"}]}])
        ctx {:dispatch! identity :get-state (constantly st) :room-id "r1"}]
    (testing "parent_history with agent"
      (let [p (tool-text ((get sa-ext/child-tool-registry "parent_history") {:agent "sa-1"} ctx))]
        (is (str/starts-with? p "Sub-agent sa-1, entries sa-1#0–sa-1#1 of 2"))
        (is (str/includes? p "sa-1#1 [assistant]\nok"))))
    (testing "subagent_result with history"
      (is (str/includes? (tool-text ((get-in sa-ext/extension [:tool-registry "subagent_result"])
                                     {:id "sa-1" :history true} ctx))
                         "sa-1#0 [user]\nreview a.clj")))
    (is (= "No history for sub-agent nope."
           (tool-text ((get sa-ext/child-tool-registry "parent_history") {:agent "nope"} ctx))))))

(deftest list-subagents-shows-what-was-asked-where
  (let [st (with-agents (base-state)
             [{:id "ask-2" :thread "ask-1" :node {:index 3 :kind :text} :question "why?"
               :status :done :session-id "c2"}])
        out (tool-text ((get-in sa-ext/extension [:tool-registry "list_subagents"])
                        {} {:dispatch! identity :get-state (constantly st) :room-id "r1"}))]
    (is (str/includes? out "[done] ask-2 — the user asked about #3 (thread ask-1): \"why?\" [session c2]"))))
