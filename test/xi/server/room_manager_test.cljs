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
