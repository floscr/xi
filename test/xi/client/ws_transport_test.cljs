(ns xi.client.ws-transport-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.agent :as agent]
            [xi.client.ws-transport :as wst]
            [xi.commands :as commands]
            [xi.core.events :as events]
            [xi.core.state :as state]))

(def base (merge events/core-handlers agent/handlers commands/handlers))
(def handlers (wst/make-handlers base))

(defn- handle [st ev] (events/handle-event handlers st ev))

(defn- joined-state []
  (:state (handle (state/initial-state {:mode :client})
                  {:type :room/joined :remote? true :room-id "r1"
                   :room (state/make-room "r1" {:model "test-model" :cwd "/tmp"})})))

(deftest room-joined-installs-snapshot
  (let [st (joined-state)]
    (is (= "r1" (:active-room st)))
    (is (= "/tmp" (:cwd (state/active-room st))))
    (is (= "test-model" (get-in st [:rooms "r1" :agent :model])))))

(deftest local-events-forward-without-state-change
  (let [st (joined-state)
        ev {:type :input/submit :room-id "r1" :text "hello"}
        {state' :state effects :effects} (handle st ev)]
    (is (= st state') "local input must not touch the mirror")
    (is (= [[:ws/send ev]] effects)))
  (testing "abort forwards too"
    (let [st (assoc-in (joined-state) [:rooms "r1" :agent :busy?] true)
          ev {:type :agent/abort :room-id "r1"}]
      (is (= [[:ws/send ev]] (:effects (handle st ev)))))))

(deftest local-quit-and-reload-stay-local
  (let [st (assoc-in (joined-state) [:rooms "r1" :session :id] "sess-1")]
    (is (= [[:app/quit {}]]
           (:effects (handle st {:type :input/submit :room-id "r1" :text "/quit"}))))
    (testing "reload injects the active room's session-id so it resumes that session"
      (is (= [[:app/reload {:session-id "sess-1"}]]
             (:effects (handle st {:type :input/submit :room-id "r1" :text "/reload"})))))
    (testing "palette :command/run as well"
      (is (= [[:app/quit {}]]
             (:effects (handle st {:type :command/run :room-id "r1" :name "quit"})))))
    (testing "other commands forward"
      (let [ev {:type :command/run :room-id "r1" :name "model" :args "opus"}]
        (is (= [[:ws/send ev]] (:effects (handle st ev))))))))

(deftest remote-events-mirror-state-without-effects
  (let [st (joined-state)
        {state' :state effects :effects}
        (handle st {:type :prompt/submit :remote? true :room-id "r1" :text "hi"})]
    (is (= [] effects) "provider effects must be stripped")
    (is (true? (get-in state' [:rooms "r1" :agent :busy?])))
    (is (= {:kind :user :text "hi" :images nil}
           (first (get-in state' [:rooms "r1" :history]))))
    (testing "delta + turn-end replay"
      (let [st2 (-> state'
                    (as-> s (:state (handle s {:type :agent/text-delta :remote? true
                                               :room-id "r1" :text "yo"})))
                    (as-> s (:state (handle s {:type :agent/turn-end :remote? true
                                               :room-id "r1" :provider-session-id "sid"}))))]
        (is (false? (get-in st2 [:rooms "r1" :agent :busy?])))
        (is (= {:kind :text :text "yo" :done? true}
               (peek (get-in st2 [:rooms "r1" :history]))))
        (is (= "sid" (get-in st2 [:rooms "r1" :session :provider-session-id])))))))

(deftest menu-events-apply-locally-without-round-trip
  (let [st (joined-state)
        menu {:id :commands :prompt "/" :items []}
        {state' :state effects :effects}
        (handle st {:type :ui/menu-open :room-id "r1" :menu menu})]
    (is (empty? effects) "menu-open must not forward — no ws/send round-trip")
    (is (= menu (get-in state' [:rooms "r1" :ui :menu])) "menu appears instantly")
    (testing "menu-pop / menu-close apply locally too"
      (let [popped (:state (handle state' {:type :ui/menu-pop :room-id "r1"}))]
        (is (nil? (get-in popped [:rooms "r1" :ui :menu])))))
    (testing "server-originated menu frames still mirror in"
      (let [pushed {:id :resume :prompt "resume> " :items [] :load [:session/list {}]}
            {state'' :state effects'' :effects}
            (handle state' {:type :ui/menu-push :remote? true :room-id "r1" :menu pushed})]
        (is (= [] effects'') "the frame's :load effect ran server-side — stripped here")
        (is (= :resume (get-in state'' [:rooms "r1" :ui :menu :id])))))))

(deftest room-joined-strips-stale-menu-state
  (let [room (-> (state/make-room "r2" {:cwd "/tmp"})
                 (assoc-in [:ui :menu] {:id :resume})
                 (assoc-in [:ui :menu-stack] [{:id :commands}]))
        st (:state (handle (state/initial-state {:mode :client})
                           {:type :room/joined :remote? true :room-id "r2" :room room}))]
    (is (nil? (get-in st [:rooms "r2" :ui :menu])))
    (is (nil? (get-in st [:rooms "r2" :ui :menu-stack])))))

(deftest clipboard-effect-survives-mirroring
  (let [{:keys [effects]} (handle (joined-state)
                                  {:type :command/run :remote? true
                                   :room-id "r1" :name "debug"})]
    (is (= [:clipboard/copy] (mapv first effects)))))

(deftest room-left-and-lobby
  (let [st (joined-state)
        {state' :state} (handle st {:type :room/left :remote? true :room-id "r1"})]
    (is (empty? (:rooms state')))
    (is (nil? (:active-room state'))))
  (let [{state' :state} (handle (joined-state)
                                {:type :lobby/state :remote? true
                                 :rooms [{:id "r9"}]})]
    (is (= [{:id "r9"}] (get-in state' [:lobby :rooms])))))
