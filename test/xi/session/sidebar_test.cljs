(ns xi.session.sidebar-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.session.sidebar :as sb]))

(def profiles {"root" {} "alice" {:name "Alice" :avatar "https://example.com/a.png"}})

(defn- lobby [profiles rooms]
  {:lobby {:profiles profiles :rooms rooms}})

(deftest room-people-resolves-profiles
  (is (= [{:id "alice" :name "Alice" :avatar "https://example.com/a.png"} {:id "root"}]
         (sb/room-people (lobby profiles []) ["alice" "root"]))))

(deftest room-people-stays-quiet-on-a-single-user-server
  (testing "only one user known: no avatars on every live chat"
    (is (= [] (sb/room-people (lobby {"root" {}} []) ["root"])))
    (is (= [] (sb/room-people {} ["root"])))))

(deftest live-rooms-carry-who-is-in-them
  (let [state (lobby profiles [{:id "r1" :session-id "s1" :users ["alice" "root"]}
                               {:id "r2" :session-id "s1" :users ["alice"]}
                               {:id "r3" :session-id "s2" :users []}])]
    (testing "a session backed by a live room"
      (is (= ["alice" "root"]
             (mapv :id (:people (sb/session-status state {:session-id "s1"}))))))
    (testing "a session with no live room has nobody"
      (is (= [] (:people (sb/session-status state {:session-id "gone"})))))
    (testing "an orphan room merges the users of its rooms"
      (is (= ["alice" "root"]
             (mapv :id (:people (first (sb/orphan-rooms state [])))))))))
