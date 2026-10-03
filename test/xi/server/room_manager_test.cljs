(ns xi.server.room-manager-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.core.events :as events]
            [xi.core.state :as state]
            [xi.server.room-manager :as rm]))

(def handlers (merge events/core-handlers rm/handlers))

(defn- handle [st ev] (events/handle-event handlers st ev))

(defn- apply-events [st & evs]
  (reduce #(:state (handle %1 %2)) st evs))

(defn- server-state-with-room
  "Server state with one room \"r1\" and client \"c1\" connected (lobby)."
  []
  (apply-events (state/initial-state {:mode :server})
                {:type :client/connect :client-id "c1" :client {:kind :remote}}
                {:type :room/create :room-id "r1" :room {:created 100 :cwd "/x"}}))

;; ── :room/join resolution ────────────────────────────────────────────────────

(deftest join-new-provisions-a-room
  (let [{:keys [effects]} (handle (server-state-with-room)
                                  {:type :room/join :client-id "c1" :target "new"
                                   :cwd "/y" :event/ts 1234 :event/id 7})]
    (is (= 1 (count effects)))
    (let [[fx-type payload] (first effects)]
      (is (= :room/setup fx-type))
      (is (= "c1" (:client-id payload)))
      (is (= "/y" (:cwd payload)))
      (is (= "r-ya-7" (:room-id payload))))))  ;; (.toString 1234 36) => "ya"

(deftest chat-start-opens-a-seeded-chat
  (let [st (server-state-with-room)]
    (is (= [[:chat/start {:text "do it" :cwd "/x" :client-id "c1"}]]
           (:effects (handle st {:type :chat/start :room-id "r1" :client-id "c1" :text "do it"})))
        "cwd defaults to the dispatching room's")
    (is (= [[:chat/start {:text "do it" :cwd "/y"}]]
           (:effects (handle st {:type :chat/start :cwd "/y" :text "do it"}))))
    (is (empty? (:effects (handle st {:type :chat/start :room-id "r1" :text ""})))
        "no message, no chat")))

(deftest join-latest-attaches-to-most-recent
  (let [st (apply-events (server-state-with-room)
                         {:type :room/create :room-id "r2" :room {:created 200}})
        {:keys [effects]} (handle st {:type :room/join :client-id "c1"})]
    (is (= [[:app/dispatch {:type :room/attach :client-id "c1" :room-id "r2"}]]
           effects)))
  (testing "latest with no rooms → provision"
    (let [{:keys [effects]} (handle (state/initial-state {:mode :server})
                                    {:type :room/join :client-id "c1"
                                     :target "latest" :event/ts 1 :event/id 1})]
      (is (= :room/setup (ffirst effects))))))

(deftest join-explicit-room-id
  (testing "existing id attaches"
    (let [{:keys [effects]} (handle (server-state-with-room)
                                    {:type :room/join :client-id "c1" :target "r1"})]
      (is (= [[:app/dispatch {:type :room/attach :client-id "c1" :room-id "r1"}]]
             effects))))
  (testing "unknown id provisions a new room"
    (let [{:keys [effects]} (handle (server-state-with-room)
                                    {:type :room/join :client-id "c1" :target "nope"
                                     :event/ts 1 :event/id 1})]
      (is (= :room/setup (ffirst effects))))))

(deftest join-session-id-reuses-existing-room
  (testing "map target {:session-id sid} attaches to live room hosting that session"
    (let [st (assoc-in (server-state-with-room)
                       [:rooms "r1" :session] {:id "sess-abc" :cwd "/x"})
          {:keys [effects]} (handle st {:type :room/join :client-id "c1"
                                        :target {:session-id "sess-abc"}
                                        :event/ts 999 :event/id 1})]
      (is (= [[:app/dispatch {:type :room/attach :client-id "c1" :room-id "r1"}]]
             effects))))
  (testing "map target with unknown session-id provisions a new room"
    (let [{:keys [effects]} (handle (server-state-with-room)
                                    {:type :room/join :client-id "c1"
                                     :target {:session-id "no-such-session"}
                                     :event/ts 999 :event/id 1})]
      (is (= :room/setup (ffirst effects)))))
  (testing "resume by the Claude CLI id attaches to the live room (never forks)"
    ;; A stale /chat/<cli-id> URL, cached route, or transient Claude-CLI card
    ;; carries the provider id, not the Xi uuid. It must still resolve to the
    ;; live room so an open TUI keeps streaming instead of being orphaned.
    (let [st (assoc-in (server-state-with-room)
                       [:rooms "r1" :session]
                       {:id "sess-abc" :provider-session-id "cli-xyz"
                        :cli-session-id "cli-xyz" :cwd "/x"})
          {:keys [effects]} (handle st {:type :room/join :client-id "c1"
                                        :session-id "cli-xyz"
                                        :target {:session-id "cli-xyz"}
                                        :event/ts 999 :event/id 1})]
      (is (= [[:app/dispatch {:type :room/attach :client-id "c1" :room-id "r1"}]]
             effects))))
  (testing "stale string room-id + live room for session-id attaches to the live room"
    ;; The client's lobby cached a room-id that no longer exists, but a live
    ;; room still hosts the session (agent mid-turn). We must attach to the
    ;; live room, never resume a lagging disk copy into a second room.
    (let [st (assoc-in (server-state-with-room)
                       [:rooms "r1" :session] {:id "sess-abc" :cwd "/x"})
          {:keys [effects]} (handle st {:type :room/join :client-id "c1"
                                        :target "stale-room-id"
                                        :session-id "sess-abc"
                                        :event/ts 999 :event/id 1})]
      (is (= [[:app/dispatch {:type :room/attach :client-id "c1" :room-id "r1"}]]
             effects)))))

;; ── :room/attach ─────────────────────────────────────────────────────────────

(deftest attach-marks-membership-and-sends-snapshot
  (let [{:keys [state effects]} (handle (server-state-with-room)
                                        {:type :room/attach :client-id "c1" :room-id "r1"})]
    (is (= "r1" (get-in state [:connection :clients "c1" :room-id])))
    (is (= ["c1"] (rm/clients-in-room state "r1")))
    (let [[fx-type {:keys [client-id event]}] (first effects)]
      (is (= :ws/send-to fx-type))
      (is (= "c1" client-id))
      (is (= :room/joined (:type event)))
      (is (= "r1" (:room-id event)))
      (is (= "/x" (get-in event [:room :cwd])))))
  (testing "attach to unknown room is a no-op"
    (let [st (server-state-with-room)]
      (is (= {:state st :effects []}
             (handle st {:type :room/attach :client-id "c1" :room-id "nope"}))))))

;; ── :room/leave + auto-destroy ───────────────────────────────────────────────

(defn- joined-state []
  (apply-events (server-state-with-room)
                {:type :room/attach :client-id "c1" :room-id "r1"}))

(deftest leave-last-client-idle-destroys-room
  (let [{:keys [state effects]} (handle (joined-state) {:type :room/leave :client-id "c1"})]
    (is (nil? (get-in state [:connection :clients "c1" :room-id])))
    (is (some #(= % [:app/dispatch {:type :room/close :room-id "r1"}]) effects))
    (is (some #(= :room/left (get-in % [1 :event :type])) effects))))

(deftest leave-busy-room-survives
  (let [st (apply-events (joined-state) {:type :agent/busy :room-id "r1" :busy? true})
        {:keys [effects]} (handle st {:type :room/leave :client-id "c1"})]
    (is (not-any? #(= :app/dispatch (first %)) effects))))

(deftest leave-with-running-subagent-survives
  ;; A background sub-agent (e.g. the /canvas-review builder) runs while the
  ;; room's own agent is idle. The room must NOT reap when its last client
  ;; leaves, or the sub-agent turn is aborted mid-build.
  (let [st (assoc-in (joined-state) [:rooms "r1" :ext :subagents :agents]
                     [{:id "sa-1" :status :running}])
        {:keys [effects]} (handle st {:type :room/leave :client-id "c1"})]
    (is (not-any? #(= [:app/dispatch {:type :room/close :room-id "r1"}] %) effects)))
  (testing "a finished sub-agent no longer keeps the room alive"
    (let [st (assoc-in (joined-state) [:rooms "r1" :ext :subagents :agents]
                       [{:id "sa-1" :status :done}])
          {:keys [effects]} (handle st {:type :room/leave :client-id "c1"})]
      (is (some #(= % [:app/dispatch {:type :room/close :room-id "r1"}]) effects)))))

(deftest leave-with-other-clients-keeps-room
  (let [st (apply-events (joined-state)
                         {:type :client/connect :client-id "c2" :client {:kind :remote}}
                         {:type :room/attach :client-id "c2" :room-id "r1"})
        {:keys [effects]} (handle st {:type :room/leave :client-id "c1"})]
    (is (not-any? #(= :app/dispatch (first %)) effects))))

(deftest disconnect-cleanup
  (testing "last idle client → close"
    (is (= [[:app/dispatch {:type :room/close :room-id "r1"}]]
           (:effects (rm/client-disconnect-cleanup (joined-state)
                                                   {:client-id "c1"})))))
  (testing "busy room → keep running (no abort, no close)"
    (let [st (apply-events (joined-state) {:type :agent/busy :room-id "r1" :busy? true})]
      (is (nil? (rm/client-disconnect-cleanup st {:client-id "c1"})))))
  (testing "client without a room → no-op"
    (is (nil? (rm/client-disconnect-cleanup (server-state-with-room) {:client-id "c1"})))))

(deftest turn-end-cleanup
  (testing "no clients attached → close"
    (is (= [[:app/dispatch {:type :room/close :room-id "r1"}]]
           (:effects (rm/turn-end-room-cleanup (server-state-with-room)
                                               {:room-id "r1"})))))
  (testing "client attached → keep"
    (is (nil? (rm/turn-end-room-cleanup (joined-state) {:room-id "r1"})))))

;; ── Summaries ────────────────────────────────────────────────────────────────

(deftest summaries-newest-first-with-counts
  (let [st (apply-events (joined-state)
                         {:type :room/create :room-id "r2" :room {:created 200}}
                         {:type :agent/busy :room-id "r2" :busy? true})
        [a b] (rm/room-summaries st)]
    (is (= "r2" (:id a)))
    (is (true? (:busy? a)))
    (is (= 0 (:clients a)))
    (is (= "r1" (:id b)))
    (is (= 1 (:clients b)))))

;; ── :dismissed/toggle ────────────────────────────────────────────────────────

(defn- state-with-sessioned-room
  "Server state with client c1 in the lobby and a room r1 carrying session id
   \"s1\" (nobody attached)."
  []
  (apply-events (state/initial-state {:mode :server})
                {:type :client/connect :client-id "c1" :client {:kind :remote}}
                {:type :room/create :room-id "r1"
                 :room {:created 100 :cwd "/x" :session {:id "s1"}}}))

(deftest dismissed-toggle-persists-and-reaps-idle-clientless-room
  (testing "idle + clientless room for the session → reply effect + room close"
    (let [{:keys [effects]} (handle (state-with-sessioned-room)
                                    {:type :dismissed/toggle :client-id "c1"
                                     :session-id "s1"})]
      (is (some #(= % [:dismissed/toggle-reply {:session-id "s1"}]) effects)
          "persists via the reply effect")
      (is (some #(= % [:app/dispatch {:type :room/close :room-id "r1"}]) effects)
          "reaps the lingering idle room"))))

(deftest dismissed-toggle-spares-attached-room
  (testing "a room someone is viewing is hidden but NOT closed"
    (let [st (apply-events (state-with-sessioned-room)
                           {:type :room/attach :client-id "c1" :room-id "r1"})
          {:keys [effects]} (handle st {:type :dismissed/toggle :client-id "c1"
                                        :session-id "s1"})]
      (is (= [[:dismissed/toggle-reply {:session-id "s1"}]] effects)))))

(deftest dismissed-toggle-spares-busy-room
  (testing "a room mid-turn is hidden but NOT closed"
    (let [st (apply-events (state-with-sessioned-room)
                           {:type :agent/busy :room-id "r1" :busy? true})
          {:keys [effects]} (handle st {:type :dismissed/toggle :client-id "c1"
                                        :session-id "s1"})]
      (is (= [[:dismissed/toggle-reply {:session-id "s1"}]] effects)))))

(deftest dismissed-toggle-no-live-room
  (testing "no matching live room → just the persist effect"
    (let [{:keys [effects]} (handle (state-with-sessioned-room)
                                    {:type :dismissed/toggle :client-id "c1"
                                     :session-id "other"})]
      (is (= [[:dismissed/toggle-reply {:session-id "other"}]] effects)))))

(deftest session-delete-reaps-idle-clientless-room
  (testing "idle + clientless room for the session → reply effect + room close"
    (let [{:keys [effects]} (handle (state-with-sessioned-room)
                                    {:type :session/delete :client-id "c1"
                                     :session-id "s1"})]
      (is (some #(= % [:session/delete-reply {:session-id "s1"}]) effects)
          "unlinks via the reply effect")
      (is (some #(= % [:app/dispatch {:type :room/close :room-id "r1"}]) effects)
          "closes the lingering idle room"))))

(deftest session-delete-swaps-attached-room
  (testing "a room someone is viewing is reset to a fresh session, NOT closed
            — otherwise it lingers in the lobby and re-persists the deleted
            session (delete would appear to do nothing)"
    (let [st (apply-events (state-with-sessioned-room)
                           {:type :room/attach :client-id "c1" :room-id "r1"})
          {:keys [effects]} (handle st {:type :session/delete :client-id "c1"
                                        :session-id "s1"})]
      (is (some #(= % [:session/delete-reply {:session-id "s1"}]) effects)
          "still unlinks the file")
      (is (some #(= % [:session/new {:room-id "r1" :save-current? false}]) effects)
          "swaps the attached room to a fresh session")
      (is (not-any? #(= % [:app/dispatch {:type :room/close :room-id "r1"}]) effects)
          "does not strand the attached client by closing the room"))))

(deftest session-delete-spares-busy-room
  (testing "a room mid-turn is left running — a lobby delete must not kill a live turn"
    (let [st (apply-events (state-with-sessioned-room)
                           {:type :agent/busy :room-id "r1" :busy? true})
          {:keys [state effects]} (handle st {:type :session/delete :client-id "c1"
                                              :session-id "s1"})]
      (is (= [[:session/delete-reply {:session-id "s1"}]] effects)
          "only unlinks — never closes or swaps a live turn's room")
      (is (true? (get-in state [:rooms "r1" :session :deleted?]))
          "flags the kept-alive room's session deleted so it stops re-surfacing")
      (is (empty? (rm/room-summaries state))
          "deleted session's live room is dropped from the lobby card list"))))

(deftest session-delete-no-live-room
  (testing "no matching live room → just the unlink effect"
    (let [{:keys [effects]} (handle (state-with-sessioned-room)
                                    {:type :session/delete :client-id "c1"
                                     :session-id "other"})]
      (is (= [[:session/delete-reply {:session-id "other"}]] effects)))))
