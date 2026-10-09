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

(deftest reveal-expands-the-child-and-unfolds-the-panel
  (let [st0 (-> (base-state)
                (assoc-in [:rooms "r1" :ext :subagents :collapsed?] true))
        st1 (:state ((:subagent/spawn h/handlers) st0
                     {:room-id "r1" :sub-id "sa-1" :task "t" :prompt "t"}))
        st2 (:state (h/reveal st1 {:room-id "r1" :sub-id "sa-1"}))]
    (is (false? (get-in st2 [:rooms "r1" :ext :subagents :collapsed?])))
    (is (true? (:expanded? (first (room-agents st2)))))
    (is (= st2 (:state (h/reveal st2 {:room-id "r1" :sub-id "sa-1"})))
        "idempotent: a second reveal keeps it expanded (no toggle)")
    (is (nil? (h/reveal st1 {:room-id "r1" :sub-id "nope"})))))

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
    (is (= #{:session/subagent-stop :session/subagent-dismiss} (:roomless-events sa-ext/extension)))))

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

;; ── ROOT CAUSE + FIX: a background sub-agent that needs a tool confirmation
;; (every external MCP tool call via mcp-tool-gate, every guarded bash op via
;; permission-gate) used to open an interactive dialog in the PARENT room and
;; block on it. If nobody answered (e.g. the user switched chats), the provider
;; turn never settled, so :subagent/turn-end never fired and the child was stuck
;; :running forever — the reported symptom. The fix: a background sub-agent's
;; :confirm! auto-denies (safe default) instead of opening a dialog, so the
;; turn always settles and reaches a terminal status without human input. ────

(deftest subagent-tool-confirmation-auto-denies-without-hanging
  (async done
    (let [dialogs (ext/create-dialogs)
          ;; A permission-gate / mcp-tool-gate style gate: confirm, allow on
          ;; yes, block (nil) on no. This is exactly how every external MCP
          ;; tool call and every guarded bash command is gated.
          gate-result (atom :unset)
          gate (fn [tc {:keys [confirm!]}]
                 (-> (confirm! "approve?")
                     (.then (fn [ok] (reset! gate-result ok) (if ok tc nil)))))
          ;; Fake provider: its turn only settles AFTER the tool-gate settles
          ;; — the SDK's .next stays pending while a tool call is in flight.
          fake-prov {:id :fake
                     :start-turn!
                     (fn [opts]
                       {:promise (-> ((:tool-policy opts) {:name "mcp__srv__do" :arguments {}})
                                     (.then (fn [_] {:usage {} :cost 0})))
                        :abort!  (fn [])})}
          handlers (merge events/core-handlers h/handlers (:handlers dialogs))
          init (-> (state/initial-state {:mode :server})
                   ;; A client IS connected — so if the sub-agent used the
                   ;; interactive ask!, it WOULD open a (hanging) dialog. The fix
                   ;; means it must NOT, even with a client attached.
                   (assoc-in [:connection :clients] {"c1" {}})
                   (assoc-in [:rooms "r1"]
                             {:id "r1" :cwd "/x"
                              :agent {:model "x" :provider :fake}
                              :ext {:subagents {:agents [] :collapsed? false}}}))
          {:keys [dispatch! state]}
          (app/create-app
           {:initial-state init
            :handlers handlers
            :effects (merge (subagent/create-fx {:fake fake-prov}
                                                {:tool-policy gate :ask! (:ask! dialogs)})
                            (:fx dialogs))})]
      (dispatch! {:type :subagent/spawn :room-id "r1" :sub-id "sa-1"
                  :task "t" :label "L" :prompt "t"})
      (-> (js/Promise. (fn [res] (js/setTimeout res 30)))
          (.then (fn [_]
                   (is (empty? (get-in @state [:rooms "r1" :ui :dialogs]))
                       "a background sub-agent must NOT open an interactive dialog")
                   (is (= false @gate-result)
                       "the gate's :confirm! resolves to the safe default (deny)")
                   (is (= :done (child-status @state "sa-1"))
                       "turn-end fires with no human input — child reaches a terminal status")
                   (done)))))))
