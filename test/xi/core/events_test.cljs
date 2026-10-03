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
