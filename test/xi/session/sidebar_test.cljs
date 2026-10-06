(ns xi.session.sidebar-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.session.sidebar :as sb]))

(def profiles {"root" {} "alice" {:name "Alice" :avatar "https://example.com/a.png"}})

(defn- lobby
  ([profiles rooms] (lobby profiles rooms "root"))
  ([profiles rooms me]
   {:connection {:user me}
    :lobby {:profiles profiles :rooms rooms}}))

(deftest room-people-resolves-profiles
  (is (= [{:id "alice" :name "Alice" :avatar "https://example.com/a.png"}]
         (sb/room-people (lobby profiles []) ["alice"]))))

(deftest room-people-leaves-out-the-viewer
  (is (= ["alice"] (mapv :id (sb/room-people (lobby profiles []) ["alice" "root"])))
      "root views: only alice")
  (is (= ["root"] (mapv :id (sb/room-people (lobby profiles [] "alice") ["alice" "root"])))
      "alice views: only root")
  (is (= [] (sb/room-people (lobby profiles []) ["root"])) "alone in the room"))

(deftest room-people-stays-quiet-on-a-single-user-server
  (testing "only one user known: no avatars on every live chat"
    (is (= [] (sb/room-people (lobby {"root" {}} []) ["root"])))
    (is (= [] (sb/room-people {} ["root"])))))

(defn- with-sessions [sessions]
  (assoc-in (lobby profiles []) [:lobby :sessions] sessions))

(def ^:private favorites-group
  {:id :favorites :label "Favorites" :where :favorite? :limit 5
   :more {:label "All favorites" :icon :star :event {:type :favorites/open}}})

(deftest extension-group-lists-the-most-recent-matches
  (let [favs   (for [i (range 8)]
                 {:session-id (str "f" i) :favorite? true :timestamp (+ 1000 i)})
        others [{:session-id "x" :timestamp 9999}]
        {:keys [cards total more label]}
        (sb/extension-group (with-sessions (concat others favs)) favorites-group)]
    (testing "capped at the limit, newest visit first, only sessions with the key"
      (is (= ["f7" "f6" "f5" "f4" "f3"] (mapv :session-id cards))))
    (testing "the total drives the trailing row, which is passed through"
      (is (= 8 total))
      (is (= "All favorites" (:label more))))
    (is (= "Favorites" label))
    (testing "cards carry the live indicators like every other group"
      (is (every? #(contains? % :active?) cards)))))

(deftest extension-group-without-limit-lists-everything
  (let [state (with-sessions (for [i (range 8)] {:session-id (str i) :starred? true :timestamp i}))]
    (is (= 8 (count (:cards (sb/extension-group state {:id :s :label "S" :where :starred?})))))))

(deftest extension-group-reaches-old-sessions
  (testing "a match older than the Recent list's 25 sessions still shows"
    (let [recent (for [i (range 30)] {:session-id (str "r" i) :timestamp (+ 5000 i)})
          old    {:session-id "old" :favorite? true :timestamp 1}
          state  (with-sessions (cons old recent))]
      (is (= ["old"] (mapv :session-id (:cards (sb/extension-group state favorites-group))))))))

(deftest extension-group-includes-flagged-live-rooms
  (testing "a live room hides its saved session from the list, so it joins the group by its own flag, first"
    (let [state (-> (with-sessions [{:session-id "a" :favorite? true :timestamp 1}])
                    (assoc-in [:lobby :rooms]
                              [{:id "r1" :session-id "live" :session-name "Live one"
                                :favorite? true :users []}
                               {:id "r2" :session-id "a" :favorite? true :users []}
                               {:id "r3" :session-id "plain" :users []}]))
          {:keys [cards total]} (sb/extension-group state favorites-group)]
      (is (= ["live" "a"] (mapv :session-id cards))
          "a room whose session is in the list is not listed twice")
      (is (= 2 total))
      (is (true? (:active? (first cards))))
      (is (= "Live one" (:name (first cards)))))))

(deftest orphan-room-cards-carry-the-extension-flags
  (let [state (assoc-in (with-sessions [])
                        [:lobby :rooms]
                        [{:id "r1" :session-id "live" :favorite? true :work? false :busy? true
                          :users []}])
        [card] (sb/orphan-rooms state [])]
    (is (true? (:favorite? card)))
    (is (false? (:work? card)))
    (is (true? (:busy? card)) "the room's own :busy? is the card's, not a flag copied over it")))

(deftest empty-extension-groups-are-left-out
  (let [state (with-sessions [{:session-id "a" :favorite? true :timestamp 2}
                              {:session-id "b" :timestamp 1}])
        groups (sb/extension-groups state [favorites-group
                                           {:id :starred :label "S" :where :starred?}])]
    (is (= [:favorites] (mapv :id groups)))))

(deftest extension-groups-stay-out-of-keyboard-navigation
  (let [state (with-sessions [{:session-id "a" :favorite? true :timestamp 2}
                              {:session-id "b" :timestamp 1}])]
    (is (= 2 (count (sb/sidebar-session-order state)))
        "a favorite is listed once: its time group already has it")))

(deftest live-rooms-carry-who-is-in-them
  (let [state (lobby profiles [{:id "r1" :session-id "s1" :users ["alice" "root"]}
                               {:id "r2" :session-id "s1" :users ["alice"]}
                               {:id "r3" :session-id "s2" :users []}])]
    (testing "a session backed by a live room: the others, not the viewer"
      (is (= ["alice"]
             (mapv :id (:people (sb/session-status state {:session-id "s1"}))))))
    (testing "a session with no live room has nobody"
      (is (= [] (:people (sb/session-status state {:session-id "gone"})))))
    (testing "an orphan room merges the users of its rooms"
      (is (= ["alice"]
             (mapv :id (:people (first (sb/orphan-rooms state [])))))))))
