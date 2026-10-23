(ns xi.ext.subagent-mirror-test
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.core.app :as app]
            [xi.core.events :as events]
            [xi.core.state :as state]
            [xi.subagent :as subagent]
            [xi.ext.core :as ext]
            [xi.ext.subagent :as sa-ext]
            [xi.ext.subagent.handlers :as h]
            [xi.ext.subagent.web :as sa-web]
            [xi.client.subagents-buffer :as sab]
            [xi.tui.ansi :as ansi]
            [xi.client.ws-transport :as wst]))

(defn- room-agents [st]
  (get-in st [:rooms "r1" :ext :subagents :agents]))

(defn- child-status [st sub-id]
  (:status (some #(when (= sub-id (:id %)) %) (room-agents st))))

(defn- base-state []
  (-> (state/initial-state {:mode :server})
      (assoc-in [:rooms "r1"]
                {:id "r1" :cwd "/x" :agent {:model "m"}
                 :ext {:subagents {:agents [] :collapsed? false}}})))

;; ── Pure handlers (server-side) ──────────────────────────────────────────────

(deftest server-spawn-then-turn-end-marks-done
  (let [st0 (base-state)
        spawn ((:subagent/spawn h/handlers) st0
               {:room-id "r1" :sub-id "sa-1" :task "do it" :label "L" :prompt "do it"})
        st1 (:state spawn)
        _   (is (= :running (child-status st1 "sa-1")))
        te  ((:subagent/turn-end h/handlers) st1
             {:room-id "r1" :sub-id "sa-1" :aborted? false})]
    (is (= :done (child-status (:state te) "sa-1")))))

(deftest spawn-and-turn-end-anchor-to-the-history-length
  ;; The web timeline posts a child's card after the entry that spawned it
  ;; and its result where the chat was when it ended.
  (let [st0 (assoc-in (base-state) [:rooms "r1" :history]
                      [{:kind :user :text "go"} {:kind :tool-call :id "t1"}])
        st1 (:state ((:subagent/spawn h/handlers) st0
                     {:room-id "r1" :sub-id "sa-1" :task "do it" :prompt "do it"}))
        st2 (update-in st1 [:rooms "r1" :history] conj {:kind :text :text "meanwhile"})
        st3 (:state ((:subagent/turn-end h/handlers) st2
                     {:room-id "r1" :sub-id "sa-1" :aborted? false}))
        child (first (room-agents st3))]
    (is (= 2 (:anchor child)))
    (is (= 3 (:ended-anchor child)))))

(deftest dismissing-the-sub-agent-in-view-returns-to-the-chat
  (let [dismiss (get-in sa-web/extension [:handlers :subagent/dismiss])
        st      (-> (base-state)
                    (assoc-in [:rooms "r1" :ext :subagents :agents]
                              [{:id "sa-1" :status :done} {:id "sa-2" :status :done}])
                    (assoc-in [:rooms "r1" :ui :active-buffer] "sa-1"))
        view    #(get-in (:state %) [:rooms "r1" :ui :active-buffer])]
    (is (= :chat (view (dismiss st {:room-id "r1" :sub-id "sa-1"}))))
    (is (= :chat (view (dismiss st {:room-id "r1"})))
        "dismissing every finished one too")
    (is (= "sa-1" (view (dismiss st {:room-id "r1" :sub-id "sa-2"})))
        "another sub-agent's dismissal keeps the view")
    (is (= [[:ws/send {:room-id "r1" :sub-id "sa-2"}]]
           (:effects (dismiss st {:room-id "r1" :sub-id "sa-2"})))
        "a click is forwarded")
    (is (nil? (:effects (dismiss st {:room-id "r1" :sub-id "sa-1" :remote? true})))
        "the server's echo is not")))

(deftest sidebar-rows-act-on-sub-agents-by-session
  ;; roomless: the row names the session, the server finds the live room
  (let [st   (-> (base-state)
                 (assoc-in [:rooms "r1" :session :id] "s1")
                 (assoc-in [:rooms "r1" :ext :subagents :agents] [{:id "sa-1" :status :running}]))
        stop (get-in sa-ext/extension [:handlers :session/subagent-stop])
        drop (get-in sa-ext/extension [:handlers :session/subagent-dismiss])]
    (is (= [[:app/dispatch {:type :subagent/abort :room-id "r1" :sub-id "sa-1"}]]
           (:effects (stop st {:session-id "s1" :sub-id "sa-1"}))))
    (is (= [[:app/dispatch {:type :subagent/dismiss :room-id "r1" :sub-id "sa-1"}]]
           (:effects (drop st {:session-id "s1" :sub-id "sa-1"}))))
    (is (nil? (stop st {:session-id "nope" :sub-id "sa-1"})) "no live room: nothing to act on")
    (is (= #{:session/subagent-stop :session/subagent-dismiss :session/subagent-upgrade}
           (:roomless-events sa-ext/extension)))))

;; ── Client mirror path ───────────────────────────────────────────────────────

(deftest client-mirror-applies-turn-end
  (let [mirror-handlers (wst/make-handlers h/handlers)
        spawn-h (:subagent/spawn mirror-handlers)
        te-h    (:subagent/turn-end mirror-handlers)
        st0 (base-state)
        {st1 :state} (spawn-h st0 {:type :subagent/spawn :remote? true
                                   :room-id "r1" :sub-id "sa-1"
                                   :task "t" :label "L" :prompt "t"})
        _   (is (= :running (child-status st1 "sa-1")))
        {st2 :state} (te-h st1 {:type :subagent/turn-end :remote? true
                                :room-id "r1" :sub-id "sa-1" :aborted? false})]
    (is (= :done (child-status st2 "sa-1")))))

;; ── Pager render + invalidation (the TUI live-refresh suspect) ────────────────

(deftest pager-reflects-status-after-invalidate
  ;; The sub-agents pager reads live agents via get-agents and only recomputes
  ;; its body on :invalidate. Simulate a running → done transition and assert
  ;; the rendered lines flip from "running" to "done".
  (let [agents (atom [{:id "sa-1" :label "L" :task "t" :status :running :history []
                       :started 0}])
        buf (sab/make-subagents-buffer
             {:get-agents (fn [] @agents)
              :on-stop (fn [_]) :on-open (fn [_])
              :on-close (fn []) :on-command-mode (fn [])})
        render (fn [] (str/join "\n" (map ansi/strip-ansi ((:render buf) 80))))]
    (is (str/includes? (render) "running"))
    ;; Mimic turn-end: status flips, new vector identity, host invalidates.
    (reset! agents [{:id "sa-1" :label "L" :task "t" :status :done :history []
                     :started 0 :ended 1000}])
    ((:invalidate buf))
    (let [out (render)]
      (is (str/includes? out "done"))
      (is (not (str/includes? out "running"))))))

;; ── End-to-end: :subagent/start effect → turn-end dispatch (server wiring) ─────

(deftest subagent-start-effect-dispatches-turn-end
  ;; The whole point: a spawned sub-agent whose provider turn RESOLVES must
  ;; drive :subagent/turn-end and land the child at a terminal status. Uses a
  ;; fake provider that resolves immediately.
  (async done
    (let [fake-prov {:id :fake
                     :start-turn! (fn [_opts]
                                    {:promise (js/Promise.resolve {:usage {} :cost 0})
                                     :abort!  (fn [])})}
          handlers (merge events/core-handlers h/handlers)
          {:keys [dispatch! state]}
          (app/create-app
           {:initial-state (assoc-in (state/initial-state {:mode :server})
                                     [:rooms "r1"]
                                     {:id "r1" :cwd "/x"
                                      :agent {:model "x" :provider :fake}
                                      :ext {:subagents {:agents [] :collapsed? false}}})
            :handlers handlers
            :effects (subagent/create-fx {:fake fake-prov})})]
      (dispatch! {:type :subagent/spawn :room-id "r1" :sub-id "sa-1"
                  :task "t" :label "L" :prompt "t"})
      ;; Let the effect's provider promise + the follow-up turn-end dispatch
      ;; drain across a few microtask/task ticks.
      (-> (js/Promise.resolve)
          (.then (fn [_] (js/Promise.resolve)))
          (.then (fn [_] (js/Promise.resolve)))
          (.then (fn [_]
                   (js/Promise.
                    (fn [res] (js/setTimeout res 20)))))
          (.then (fn [_]
                   (is (= :done (child-status @state "sa-1"))
                       "turn-end must fire and mark the child :done")
                   (done)))))))

;; ── Permission asks: a sub-agent's gated call asks the user in the parent
;; room like the main agent's, tagged with the sub-agent; Stop denies an ask
;; still open, so the turn settles. Explanations and questions (inline
;; children, read-only by their prompt) deny every ask without a dialog. ──

(defn- gated-app
  "An app running sub-agents whose one tool call goes through a confirm gate.
   The fake turn settles once the gate does, or at once (aborted) on Stop."
  [gate-result]
  (let [dialogs  (ext/create-dialogs)
        gate     (fn [tc {:keys [confirm!]}]
                   (-> (confirm! "approve?")
                       (.then (fn [ok] (reset! gate-result ok) (if ok tc nil)))))
        fake-prov {:id :fake
                   :start-turn!
                   (fn [opts]
                     (let [stop (atom nil)]
                       {:promise (js/Promise.race
                                  [(-> ((:tool-policy opts) {:name "mcp__srv__do" :arguments {}})
                                       (.then (fn [_] {:usage {} :cost 0})))
                                   (js/Promise. (fn [res] (reset! stop res)))])
                        :abort!  (fn [] (@stop {:aborted true}))}))}
        init (-> (state/initial-state {:mode :server})
                 (assoc-in [:connection :clients] {"c1" {}})
                 (assoc-in [:rooms "r1"]
                           {:id "r1" :cwd "/x"
                            :agent {:model "x" :provider :fake}
                            :ext {:subagents {:agents [] :collapsed? false}}}))]
    (app/create-app
     {:initial-state init
      :handlers (merge events/core-handlers (:handlers sa-ext/extension) (:handlers dialogs))
      :effects (merge (subagent/create-fx {:fake fake-prov}
                                          {:tool-policy gate :ask! (:ask! dialogs)})
                      (:fx dialogs)
                      {:subagent/save! (fn [_ _] nil)})})))

(defn- tick [] (js/Promise. (fn [res] (js/setTimeout res 20))))

(defn- open-dialogs [st] (get-in st [:rooms "r1" :ui :dialogs]))

(deftest a-sub-agent-s-ask-waits-for-the-user
  (async done
    (let [result (atom :unset)
          {:keys [dispatch! state]} (gated-app result)]
      (dispatch! {:type :subagent/spawn :room-id "r1" :sub-id "sa-1"
                  :task "t" :label "Review" :prompt "t"})
      (-> (tick)
          (.then (fn [_]
                   (let [[d :as ds] (open-dialogs @state)]
                     (is (= 1 (count ds)) "the ask opens in the parent room")
                     (is (= "sa-1" (:sub-id d)))
                     (is (= "Sub-agent “Review”: approve?" (:message d)))
                     (is (= :running (child-status @state "sa-1")) "it waits")
                     (dispatch! {:type :ui/dialog-response :room-id "r1"
                                 :dialog-id (:id d) :value true}))
                   (tick)))
          (.then (fn [_]
                   (is (true? @result) "the user's allow reaches the gate")
                   (is (= :done (child-status @state "sa-1")))
                   (done)))))))

(deftest stopping-a-sub-agent-denies-its-open-ask
  (async done
    (let [result (atom :unset)
          {:keys [dispatch! state]} (gated-app result)]
      (dispatch! {:type :subagent/spawn :room-id "r1" :sub-id "sa-1"
                  :task "t" :label "L" :prompt "t"})
      (-> (tick)
          (.then (fn [_]
                   (is (= 1 (count (open-dialogs @state))))
                   (dispatch! {:type :subagent/abort :room-id "r1" :sub-id "sa-1"})
                   (tick)))
          (.then (fn [_]
                   (is (= :stopped (child-status @state "sa-1")))
                   (is (empty? (open-dialogs @state)) "no ask is left for a gone turn")
                   (is (false? @result) "the gate sees a deny")
                   (done)))))))

(deftest an-explanation-denies-without-asking
  (async done
    (let [result (atom :unset)
          {:keys [dispatch! state]} (gated-app result)]
      (dispatch! {:type :subagent/spawn :room-id "r1" :sub-id "explain-tc-1"
                  :task "t" :label "Q" :prompt "t"})
      (-> (tick)
          (.then (fn [_]
                   (is (empty? (open-dialogs @state)))
                   (is (false? @result))
                   (is (= :done (child-status @state "explain-tc-1")))
                   (done)))))))

(deftest an-ask-sub-agent-asks-the-user
  (async done
    (let [result (atom :unset)
          {:keys [dispatch! state]} (gated-app result)]
      (dispatch! {:type :subagent/spawn :room-id "r1" :sub-id "ask-1"
                  :task "t" :label "Q" :prompt "t"})
      (-> (tick)
          (.then (fn [_]
                   (is (= ["ask-1"] (map :sub-id (open-dialogs @state)))
                       "an Ask may act, once the user says so")
                   (dispatch! {:type :subagent/abort :room-id "r1" :sub-id "ask-1"})
                   (tick)))
          (.then (fn [_] (done)))))))
