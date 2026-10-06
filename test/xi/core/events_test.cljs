(ns xi.core.events-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.core.events :as events]
            [xi.core.state :as state]))

(defn- apply-events [st & evs]
  (reduce #(:state (events/handle-event events/core-handlers %1 %2)) st evs))

(deftest room-lifecycle
  (testing "create sets room + first room becomes active"
    (let [st (apply-events (state/initial-state)
                           {:type :room/create :room-id "a"}
                           {:type :room/create :room-id "b"})]
      (is (= ["a" "b"] (sort (state/room-ids st))))
      (is (= "a" (:active-room st)))
      (is (= [] (:history (state/get-room st "a"))))))

  (testing "create is idempotent — existing room untouched"
    (let [st  (apply-events (state/initial-state)
                            {:type :room/create :room-id "a"}
                            {:type :history/append :room-id "a" :entry {:n 1}})
          st' (apply-events st {:type :room/create :room-id "a"})]
      (is (= st st'))))

  (testing "switch only to existing rooms"
    (let [st (apply-events (state/initial-state)
                           {:type :room/create :room-id "a"}
                           {:type :room/create :room-id "b"}
                           {:type :room/switch :room-id "b"})]
      (is (= "b" (:active-room st)))
      (is (= "b" (:active-room (apply-events st {:type :room/switch :room-id "nope"}))))))

  (testing "close removes room and re-points active-room"
    (let [st (apply-events (state/initial-state)
                           {:type :room/create :room-id "a"}
                           {:type :room/create :room-id "b"}
                           {:type :room/close :room-id "a"})]
      (is (= ["b"] (state/room-ids st)))
      (is (= "b" (:active-room st))))))

(deftest clients
  (let [st (apply-events (state/initial-state {:mode :server})
                         {:type :client/connect :client-id "c1" :client {:kind :tui}}
                         {:type :client/connect :client-id "c2" :client {:kind :web :visible? false}})]
    (is (= {:kind :tui :visible? true} (get-in st [:connection :clients "c1"])))
    (is (= false (get-in st [:connection :clients "c2" :visible?])))
    (is (= ["c1"] (keys (get-in (apply-events st {:type :client/disconnect :client-id "c2"})
                                [:connection :clients]))))))

(deftest room-presence
  (let [st (apply-events (state/initial-state {:mode :server})
                         {:type :room/create :room-id "a"}
                         {:type :room/presence :room-id "a"
                          :members {"c1" {:user "alice" :platform "web"}
                                    "c2" {:user "root" :platform "tui"}}})]
    (is (= {"c1" {:user "alice" :platform "web"} "c2" {:user "root" :platform "tui"}}
           (get-in st [:rooms "a" :members])))
    (is (= ["alice" "root"] (state/room-users (state/get-room st "a"))))
    (testing "a fresh room has nobody in it"
      (is (= {} (:members (state/make-room "x")))))
    (testing "nil members clears the room"
      (is (= {} (get-in (apply-events st {:type :room/presence :room-id "a"}) [:rooms "a" :members]))))
    (testing "unknown rooms are no-ops"
      (is (= st (apply-events st {:type :room/presence :room-id "ghost" :members {"c" {:user "x"}}}))))))

(deftest user-records
  (let [loaded {:type :user/loaded :user "alice"
                :profile {:name "Alice" :meta {:team "ops"}}
                :ui {:theme "dark"} :ext {:notes {:n 1}}}
        st     (apply-events (state/initial-state {:mode :server}) loaded)]
    (testing "loading installs the declared profile with the stored state"
      (is (= {:id "alice" :name "Alice" :meta {:team "ops"} :ui {:theme "dark"} :ext {:notes {:n 1}}}
             (state/user-record st "alice")))
      (is (= {:n 1} (state/user-ext st "alice" :notes)))
      (is (nil? (state/user-ext st "alice" :other)))
      (is (nil? (state/user-record st "bob"))))
    (testing "an undeclared user loads with no name and no metadata"
      (let [st (apply-events (state/initial-state) {:type :user/loaded :user "carol"})]
        (is (= {:id "carol" :name nil :meta {} :ui {} :ext {}} (state/user-record st "carol")))))
    (testing "a reconnect replaces the record, so a config edit shows up"
      (let [st' (apply-events st (assoc-in loaded [:profile :name] "Alice B."))]
        (is (= "Alice B." (:name (state/user-record st' "alice"))))))
    (testing "saved UI state and extension state update the record"
      (let [st' (apply-events st
                              {:type :user/ui-set :user "alice" :key :theme :value "light"}
                              {:type :user/ext-set :user "alice" :ext :notes :value {:n 2}}
                              {:type :user/ext-set :user "alice" :ext :todo :value ["a"]})]
        (is (= "light" (get-in st' [:users "alice" :ui :theme])))
        (is (= {:notes {:n 2} :todo ["a"]} (get-in st' [:users "alice" :ext])))
        (is (= "Alice" (:name (state/user-record st' "alice"))) "the profile is untouched"))
      (testing "nil forgets an extension's entry"
        (is (= {} (get-in (apply-events st {:type :user/ext-set :user "alice" :ext :notes :value nil})
                          [:users "alice" :ext])))))
    (testing "a write for a user not loaded yet starts a record"
      (let [st' (apply-events (state/initial-state) {:type :user/ext-set :user "dan" :ext :notes :value 1})]
        (is (= {:notes 1} (get-in st' [:users "dan" :ext])))))
    (testing "events without a user or extension id change nothing"
      (is (= st (apply-events st {:type :user/ext-set :ext :notes :value 1})))
      (is (= st (apply-events st {:type :user/ext-set :user "alice" :ext "notes" :value 1}))))))

(deftest turn-user-is-the-latest-prompts-sender
  (let [st (apply-events (state/initial-state {:user "carol"})
                         {:type :room/create :room-id "a"}
                         {:type :history/append :room-id "a" :entry {:kind :user :text "1" :user "alice"}}
                         {:type :history/append :room-id "a" :entry {:kind :text :text "ok"}}
                         {:type :history/append :room-id "a" :entry {:kind :user :text "2" :user "bob"}}
                         {:type :history/append :room-id "a" :entry {:kind :text :text "ok"}})]
    (is (= "bob" (state/turn-user st "a")) "the latest prompt, not the first")
    (testing "no prompt yet, or an unattributed one → the process' own user"
      (is (= "carol" (state/turn-user (apply-events (state/initial-state {:user "carol"})
                                                    {:type :room/create :room-id "b"})
                                      "b")))
      (is (= "carol" (state/turn-user (apply-events (state/initial-state {:user "carol"})
                                                    {:type :room/create :room-id "b"}
                                                    {:type :history/append :room-id "b"
                                                     :entry {:kind :user :text "old"}})
                                      "b"))))))

(deftest own-and-event-user
  (testing "every process has a user; root unless told otherwise"
    (is (= "root" (state/own-user (state/initial-state))))
    (is (= "alice" (state/own-user (state/initial-state {:user "Alice"})))
        "ids are normalized")
    (is (= "root" (state/own-user (state/initial-state {:user "not a slug!"})))))
  (testing "an event acts for the user the server stamped on it, else ours"
    (let [st (state/initial-state {:user "alice"})]
      (is (= "bob" (state/event-user st {:type :prompt/submit :user "bob"})))
      (is (= "alice" (state/event-user st {:type :prompt/submit}))))))

(deftest history-and-agent
  (let [st (apply-events (state/initial-state)
                         {:type :room/create :room-id "a"}
                         {:type :history/append :room-id "a" :entry {:role :user :text "hi"}}
                         {:type :history/append :room-id "a" :entry {:role :assistant :text "yo"}}
                         {:type :agent/busy :room-id "a" :busy? true}
                         {:type :agent/set-model :room-id "a" :model "opus" :provider :anthropic})]
    (is (= [:user :assistant] (mapv :role (:history (state/get-room st "a")))))
    (is (true? (get-in st [:rooms "a" :agent :busy?])))
    (is (= "opus" (get-in st [:rooms "a" :agent :model])))
    (testing "events for unknown rooms are no-ops"
      (is (= st (apply-events st {:type :history/append :room-id "ghost" :entry {:x 1}}))))))

(deftest ui-dialogs-and-buffers
  (let [st (apply-events (state/initial-state)
                         {:type :room/create :room-id "a"}
                         {:type :ui/dialog-open :room-id "a" :dialog {:id :confirm :text "ok?"}}
                         {:type :ui/dialog-open :room-id "a" :dialog {:text "no id"} :event/id 99}
                         {:type :ui/buffer-set :room-id "a" :buffer-id :logs :buffer {:lines ["x"]}}
                         {:type :ui/buffer-switch :room-id "a" :buffer-id :logs})]
    (is (= [:confirm 99] (mapv :id (get-in st [:rooms "a" :ui :dialogs]))))
    (is (= :logs (get-in st [:rooms "a" :ui :active-buffer])))
    (is (= {:lines ["x"]} (get-in st [:rooms "a" :ui :buffers :logs])))
    (testing "close by id / close first"
      (let [st' (apply-events st {:type :ui/dialog-close :room-id "a" :dialog-id :confirm})]
        (is (= [99] (mapv :id (get-in st' [:rooms "a" :ui :dialogs])))))
      (let [st' (apply-events st {:type :ui/dialog-close :room-id "a"})]
        (is (= [99] (mapv :id (get-in st' [:rooms "a" :ui :dialogs]))))))))

(deftest theme-set
  (let [st (apply-events (state/initial-state) {:type :theme/set :mode :light})]
    (is (= :light (get-in st [:theme :mode])))
    (testing "a later event replaces the mode"
      (is (= :dark (get-in (apply-events st {:type :theme/set :mode :dark}) [:theme :mode]))))
    (testing "nil or an unknown mode clears it"
      (is (nil? (get-in (apply-events st {:type :theme/set :mode nil}) [:theme :mode])))
      (is (nil? (get-in (apply-events st {:type :theme/set :mode :sepia}) [:theme :mode]))))))

(deftest unknown-events-pass-through
  (let [st     (state/initial-state)
        result (events/handle-event events/core-handlers st {:type :ext/whatever})]
    (is (identical? st (:state result)))
    (is (= [] (:effects result)))))
