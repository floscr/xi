(ns xi.server.room-manager-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.core.events :as events]
            [xi.core.state :as state]
            [xi.server.room-manager :as rm]
            [xi.wire :as wire]))

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

(deftest cwd-agents-files-replies-to-the-asking-client
  (let [st (server-state-with-room)]
    (is (= [[:cwd/agents-files-reply {:client-id "c1" :cwd "/y"}]]
           (:effects (handle st {:type :cwd/agents-files :client-id "c1" :cwd "/y"}))))
    (is (empty? (:effects (handle st {:type :cwd/agents-files :client-id "c1"})))
        "no cwd, no lookup")))

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

;; ── Cache-elided :room/joined (live-room re-open) ────────────────────────────

(def ^:private history
  [{:role :user :text "hi" :ts 1}
   {:role :assistant :blocks [{:type :text :text "hello"}
                              {:type :tool-use :id "t1" :name "read"
                               :input {:path "/a"} :result {:content [{:type "text" :text "x"}]
                                                            :is-error false}}]}
   {:role :user :text "more" :ts 3 :images nil :tags #{:a :b}}])

(deftest joined-payload-elides-cached-history
  (let [room {:cwd "/x" :history history}]
    (testing "no fingerprint → full snapshot"
      (is (= {:room room} (rm/joined-payload room nil nil))))
    (testing "cache holds the whole history → no history on the wire"
      (is (= {:room {:cwd "/x"} :history-base {:hash (hash history) :count 3} :history-tail []}
             (rm/joined-payload room (hash history) 3))))
    (testing "cache holds a clean prefix → only the tail"
      (is (= {:room {:cwd "/x"}
              :history-base {:hash (hash (subvec history 0 2)) :count 2}
              :history-tail [(nth history 2)]}
             (rm/joined-payload room (hash (subvec history 0 2)) 2))))
    (testing "stale / rewritten cache → full snapshot"
      (is (= {:room room} (rm/joined-payload room (hash [(first history)]) 2)))
      (is (= {:room room} (rm/joined-payload room (hash history) 4)) "cache longer than room")
      (is (= {:room room} (rm/joined-payload room (hash []) 0))))))

(deftest history-hash-survives-the-wire
  ;; The client fingerprints the history it decoded off the wire / out of
  ;; localStorage (both transit); the server hashes its own. They must agree.
  (let [ev {:type :room/joined :room {:history history}}]
    (is (= (hash history) (hash (get-in (wire/decode (wire/encode ev)) [:room :history]))))))

(deftest attach-with-matching-fingerprint-sends-only-the-tail
  (let [st (-> (server-state-with-room)
               (assoc-in [:rooms "r1" :history] history))
        send (fn [ev] (-> (handle st (merge {:type :room/attach :client-id "c1" :room-id "r1"} ev))
                          :effects first second :event))]
    (let [ev (send {:cached-history-hash (hash (subvec history 0 2)) :cached-history-count 2})]
      (is (not (contains? (:room ev) :history)))
      (is (= "/x" (get-in ev [:room :cwd])))
      (is (= [(nth history 2)] (:history-tail ev))))
    (is (= history (get-in (send {:cached-history-hash 123 :cached-history-count 2}) [:room :history]))
        "mismatch → full history"))
  (testing "join forwards the fingerprint to the live room's attach"
    (let [st (assoc-in (server-state-with-room) [:rooms "r1" :session] {:id "s1"})]
      (is (= [[:app/dispatch {:type :room/attach :client-id "c1" :room-id "r1"
                              :cached-history-hash 42 :cached-history-count 7}]]
             (:effects (handle st {:type :room/join :client-id "c1" :session-id "s1"
                                   :target {:session-id "s1"}
                                   :cached-history-hash 42 :cached-history-count 7})))))))

;; ── :room/leave + auto-destroy ───────────────────────────────────────────────

(defn- joined-state []
  (apply-events (server-state-with-room)
                {:type :room/attach :client-id "c1" :room-id "r1"}))

(deftest leave-last-client-idle-destroys-room
  (let [{:keys [state effects]} (handle (joined-state) {:type :room/leave :client-id "c1"})]
    (is (nil? (get-in state [:connection :clients "c1" :room-id])))
    (is (some #(= % [:app/dispatch {:type :room/close :room-id "r1"}]) effects))
    (is (some #(= :room/left (get-in % [1 :event :type])) effects))))

(defn- closes-or-aborts?
  "Does a handler result's effect list close the room or abort its agent?
   (Presence refreshes are dispatched too, so look at the event type.)"
  [effects]
  (boolean (some #(and (= :app/dispatch (first %))
                       (#{:room/close :agent/abort} (get-in % [1 :type])))
                 effects)))

(deftest leave-busy-room-survives
  (let [st (apply-events (joined-state) {:type :agent/busy :room-id "r1" :busy? true})
        {:keys [effects]} (handle st {:type :room/leave :client-id "c1"})]
    (is (not (closes-or-aborts? effects)))))

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
    (is (not (closes-or-aborts? effects)))))

(deftest disconnect-cleanup
  (testing "last idle client → close"
    (is (= [[:app/dispatch {:type :room/close :room-id "r1"}]]
           (:effects (rm/client-disconnect-cleanup (joined-state)
                                                   {:client-id "c1"})))))
  (testing "busy room → keep running (no abort, no close)"
    (let [st (apply-events (joined-state) {:type :agent/busy :room-id "r1" :busy? true})]
      (is (not (closes-or-aborts? (:effects (rm/client-disconnect-cleanup st {:client-id "c1"})))))))
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

(deftest summaries-flag-rooms-whose-last-turn-failed
  (let [errored (assoc-in (joined-state) [:rooms "r1" :history]
                          [{:kind :user :text "hi"}
                           {:kind :error :error {:type "error" :message "boom"}}])
        error?  #(:error? (first (rm/room-summaries %)))]
    (is (true? (error? errored)))
    (testing "a running turn hides it"
      (is (false? (error? (apply-events errored {:type :agent/busy :room-id "r1" :busy? true})))))
    (testing "a healthy room is not flagged"
      (is (false? (error? (joined-state)))))))

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

;; ── Per-user UI state ──

(deftest user-state-set-saves-for-the-stamped-user
  (let [st (server-state-with-room)]
    (is (= [[:user-state/save {:user "alice" :key :theme :value "dark"}]]
           (:effects (handle st {:type :user-state/set :client-id "c1" :user "alice"
                                 :key :theme :value "dark"})))
        "the user is the one the server stamped, so a client writes only its own")
    (is (= [[:user-state/save {:user "root" :key :theme :value "dark"}]]
           (:effects (handle st {:type :user-state/set :client-id "c1"
                                 :key :theme :value "dark"})))
        "an unstamped event is root")
    (testing "extension state is the server's: a client can never write it"
      (is (empty? (:effects (handle st {:type :user-state/set :user "alice"
                                        :key :ext :value {:notes {:role :admin}}})))))
    (testing "unknown keys and invalid values never reach disk"
      (is (empty? (:effects (handle st {:type :user-state/set :user "alice" :key :theme :value "sepia"}))))
      (is (empty? (:effects (handle st {:type :user-state/set :user "alice" :key :nope :value "x"})))))))

;; ── Presence (who is in a room) ──

(defn- presence-effects
  "The :room/presence events among a handler result's effects, by room."
  [effects]
  (into {} (keep (fn [[fx ev]]
                   (when (and (= :app/dispatch fx) (= :room/presence (:type ev)))
                     [(:room-id ev) (:members ev)])))
        effects))

(defn- two-user-state
  "r1 with alice (web, c1) attached; bob (tui, c2) connected in the lobby."
  []
  (apply-events (state/initial-state {:mode :server})
                {:type :client/connect :client-id "c1"
                 :client {:kind :remote :user "alice" :platform "web"}}
                {:type :client/connect :client-id "c2"
                 :client {:kind :remote :user "bob" :platform "tui"}}
                {:type :room/create :room-id "r1" :room {:created 100 :cwd "/x"}}
                {:type :room/attach :client-id "c1" :room-id "r1"}))

(deftest attach-records-and-broadcasts-presence
  (let [st (two-user-state)]
    (is (= {"c1" {:user "alice" :platform "web"}} (get-in st [:rooms "r1" :members]))
        "the joiner is in the room's member list")
    (let [{:keys [state effects]} (handle st {:type :room/attach :client-id "c2" :room-id "r1"})
          members {"c1" {:user "alice" :platform "web"} "c2" {:user "bob" :platform "tui"}}]
      (is (= members (get-in state [:rooms "r1" :members])))
      (is (= members (get-in (first effects) [1 :event :room :members]))
          "the :room/joined snapshot already carries the joiner")
      (is (= {"r1" members} (presence-effects effects))
          "and everyone in the room is told")
      (is (= ["alice" "bob"] (state/room-users (state/get-room state "r1"))))
      (is (= ["alice" "bob"] (:users (first (rm/room-summaries state))))
          "the lobby summary lists the users")))
  (testing "a client without a user is root"
    (let [st (apply-events (server-state-with-room) {:type :room/attach :client-id "c1" :room-id "r1"})]
      (is (= {"c1" {:user "root"}} (get-in st [:rooms "r1" :members]))))))

(deftest switching-rooms-refreshes-both-rooms-presence
  ;; A re-attach sends no :room/leave for the room left behind, so its
  ;; presence must be refreshed from the attach.
  (let [st (apply-events (two-user-state)
                         {:type :room/create :room-id "r2" :room {:created 200}}
                         {:type :room/attach :client-id "c2" :room-id "r1"})
        {:keys [state effects]} (handle st {:type :room/attach :client-id "c2" :room-id "r2"})]
    (is (= {"r2" {"c2" {:user "bob" :platform "tui"}}
            "r1" {"c1" {:user "alice" :platform "web"}}}
           (presence-effects effects)))
    (is (= {"c2" {:user "bob" :platform "tui"}} (get-in state [:rooms "r2" :members])))))

(deftest leave-and-disconnect-refresh-presence-of-a-surviving-room
  (let [st (apply-events (two-user-state) {:type :room/attach :client-id "c2" :room-id "r1"})]
    (testing "leave: the remaining client learns who is left"
      (let [{:keys [effects]} (handle st {:type :room/leave :client-id "c2"})]
        (is (= {"r1" {"c1" {:user "alice" :platform "web"}}} (presence-effects effects)))
        (is (not-any? #(= :room/close (get-in % [1 :type])) effects))))
    (testing "disconnect: computed without the leaver"
      (is (= {"r1" {"c1" {:user "alice" :platform "web"}}}
             (presence-effects (:effects (rm/client-disconnect-cleanup st {:client-id "c2"}))))))
    (testing "the last client leaving an idle room closes it instead"
      (let [{:keys [effects]} (handle (two-user-state) {:type :room/leave :client-id "c1"})]
        (is (empty? (presence-effects effects)))
        (is (some #(= % [:app/dispatch {:type :room/close :room-id "r1"}]) effects))))))
