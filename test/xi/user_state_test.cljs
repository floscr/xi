(ns xi.user-state-test
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [xi.user-state :as user-state]
            [xi.user-state.store :as store]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(deftest valid-accepts-known-keys-with-allowed-values
  (is (user-state/valid? :theme "dark"))
  (is (user-state/valid? :appearance {:viewer-mode? false :tool-blocks :open}))
  (is (user-state/valid? :sidebar-collapsed [:projects :recent]))
  (is (user-state/valid? :preferred-model "claude-opus-4-6"))
  (is (user-state/valid? :recent-commands ["model" "clear"]))
  (is (user-state/valid? :recent-skills [])))

(deftest valid-rejects-unknown-keys-and-bad-values
  (testing "unknown key"
    (is (not (user-state/valid? :bookmarks ["a"])))
    (is (not (user-state/valid? "theme" "dark"))))
  (testing "bad values"
    (is (not (user-state/valid? :theme "sepia")))
    (is (not (user-state/valid? :theme :dark)))
    (is (not (user-state/valid? :appearance [:a])))
    (is (not (user-state/valid? :appearance {"viewer-mode?" true})))
    (is (not (user-state/valid? :appearance {:tool-blocks {:nested true}})))
    (is (not (user-state/valid? :sidebar-collapsed ["projects"])))
    (is (not (user-state/valid? :preferred-model "")))
    (is (not (user-state/valid? :recent-commands [1 2]))))
  (testing "size bounds keep a client from growing the store"
    (is (not (user-state/valid? :recent-commands (vec (repeat 21 "x")))))
    (is (not (user-state/valid? :recent-skills [(apply str (repeat 101 "x"))])))
    (is (not (user-state/valid? :preferred-model (apply str (repeat 201 "x")))))))

(deftest plain-data-is-what-survives-the-state-file
  (is (user-state/plain-data? nil))
  (is (user-state/plain-data? {:n 1 :s "x" :k :v :b true :v [1 2 #{3}] :m {"a" nil} :l '(1 2)}))
  (testing "anything the file could not read back is refused"
    (is (not (user-state/plain-data? inc)))
    (is (not (user-state/plain-data? 'a-symbol)))
    (is (not (user-state/plain-data? {:f inc})))
    (is (not (user-state/plain-data? [(js-obj)])))
    (is (not (user-state/plain-data? (atom 1)))))
  (testing "bounded depth and size"
    (is (not (user-state/plain-data? (reduce (fn [v _] [v]) [] (range 20)))))
    (is (not (user-state/plain-data? (vec (range 6000)))))))

(deftest ext-state-is-server-only
  (testing ":ext validates as a map of keyword → plain data under the size limit"
    (is (user-state/valid? :ext {:notes {:n 1} :todo ["a"]}))
    (is (not (user-state/valid? :ext {"notes" {:n 1}})))
    (is (not (user-state/valid? :ext {:notes inc})))
    (is (not (user-state/valid? :ext {:notes (apply str (repeat 70000 "x"))}))))
  (testing "a client may never write it"
    (is (not (user-state/client-valid? :ext {:notes {:n 1}})))
    (is (user-state/client-valid? :theme "dark") "UI keys are still client-writable"))
  (testing "and never sees it"
    (is (= {:theme "dark"} (user-state/client-view {:theme "dark" :ext {:notes {:n 1}}})))))

(deftest normalize-keeps-only-valid-entries
  (is (= {:theme "light"}
         (user-state/normalize {:theme "light" :theme-x 1 :appearance 5})))
  (is (= {} (user-state/normalize nil)))
  (is (= {} (user-state/normalize [:theme "light"]))))

;; ── store ────────────────────────────────────────────────────────────────────

(def ^:private tmp (atom nil))

(use-fixtures :each
  {:before (fn []
             (reset! tmp (.mkdtempSync fs (.join node-path (.tmpdir os) "xi-user-state-")))
             (store/set-dir! @tmp))
   :after  (fn []
             (store/set-dir! nil)
             (.rmSync fs @tmp #js {:recursive true :force true}))})

(deftest store-round-trips-per-user
  (is (= {} (store/load-state "alice")) "no file → no state")
  (is (true? (store/set-key! "alice" :theme "dark")))
  (is (true? (store/set-key! "alice" :appearance {:viewer-mode? false})))
  (is (true? (store/set-key! "bob" :theme "light")))
  (is (= {:theme "dark" :appearance {:viewer-mode? false}} (store/load-state "alice")))
  (is (= {:theme "light"} (store/load-state "bob")) "users never see each other's state")
  (is (= {} (store/load-state "root")) "root is a user like any other"))

(deftest read-markers-hidden-chats-and-favorites-are-server-only
  (testing "they validate"
    (is (user-state/valid? :read-state {"s1" 3 "s2" 0}))
    (is (user-state/valid? :dismissed ["s1" "s2"]))
    (is (user-state/valid? :favorites ["s1" "s2"]))
    (is (not (user-state/valid? :favorites [""])))
    (is (not (user-state/valid? :favorites "s1")))
    (is (not (user-state/valid? :read-state {"s1" -1})))
    (is (not (user-state/valid? :read-state {"s1" "3"})))
    (is (not (user-state/valid? :read-state {:s1 3})))
    (is (not (user-state/valid? :dismissed [""])))
    (is (not (user-state/valid? :dismissed "s1"))))
  (testing "a client may neither write nor see them"
    (is (not (user-state/client-valid? :read-state {"s1" 3})))
    (is (not (user-state/client-valid? :dismissed ["s1"])))
    (is (not (user-state/client-valid? :favorites ["s1"])))
    (is (= {:theme "dark"}
           (user-state/client-view {:theme "dark" :read-state {"s1" 3}
                                    :dismissed ["s1"] :favorites ["s1"]})))))

(deftest store-keeps-read-markers-per-user
  (is (nil? (:read-state (store/load-state "alice"))) "nothing marked yet")
  (is (true? (store/mark-read! "alice" {} "s1" 2)))
  (is (true? (store/mark-read! "alice" {"s1" 2} "s2" 5)))
  (is (= {"s1" 2 "s2" 5} (:read-state (store/load-state "alice"))))
  (is (nil? (:read-state (store/load-state "bob"))) "bob has read nothing")
  (testing "the base map is what the markers are written on top of"
    (store/mark-read! "bob" {"old" 1} "s1" 4)
    (is (= {"old" 1 "s1" 4} (:read-state (store/load-state "bob")))))
  (testing "past the cap the newest marker survives"
    (let [full (into {} (map (fn [i] [(str "s" i) 1])) (range user-state/max-read-state))]
      (store/mark-read! "alice" full "fresh" 9)
      (let [m (:read-state (store/load-state "alice"))]
        (is (= user-state/max-read-state (count m)))
        (is (= 9 (get m "fresh")))))))

(deftest store-hides-chats-per-user
  (is (= #{} (store/dismissed "alice")))
  (is (true? (store/toggle-dismissed! "alice" "s1")) "hidden")
  (is (true? (store/toggle-dismissed! "alice" "s2")))
  (is (= #{"s1" "s2"} (store/dismissed "alice")))
  (is (= #{} (store/dismissed "bob")) "bob still sees it in Recent")
  (is (false? (store/toggle-dismissed! "alice" "s1")) "shown again")
  (is (= #{"s2"} (store/dismissed "alice")))
  (testing "un-hiding writes nothing when the chat was not hidden"
    (is (nil? (store/undismiss! "alice" "s1")))
    (is (true? (store/undismiss! "alice" "s2")))
    (is (= #{} (store/dismissed "alice"))))
  (testing "past the cap the oldest chats fall back to Recent"
    (doseq [i (range (inc user-state/max-dismissed))]
      (store/toggle-dismissed! "carol" (str "s" i)))
    (let [hidden (store/dismissed "carol")]
      (is (= user-state/max-dismissed (count hidden)))
      (is (not (contains? hidden "s0")))
      (is (contains? hidden (str "s" user-state/max-dismissed))))))

(deftest store-stars-chats-per-user
  ;; A user with no stored favorites starts from the legacy global file, so
  ;; these assert on the ids they toggle rather than on the whole set.
  (is (true? (store/toggle-favorite! "alice" "fav-s1")) "starred")
  (is (true? (store/toggle-favorite! "alice" "fav-s2")))
  (is (contains? (store/favorite-ids "alice") "fav-s1"))
  (is (not (contains? (store/favorite-ids "bob") "fav-s1")) "bob has not starred it")
  (testing "the newest star is last"
    (is (= ["fav-s1" "fav-s2"] (take-last 2 (store/favorites "alice")))))
  (is (false? (store/toggle-favorite! "alice" "fav-s1")) "unstarred")
  (is (not (contains? (store/favorite-ids "alice") "fav-s1")))
  (is (contains? (store/favorite-ids "alice") "fav-s2"))
  (testing "every user's stars are kept in the shared lobby list"
    (store/toggle-favorite! "bob" "fav-s3")
    (let [all (store/all-favorite-ids)]
      (is (contains? all "fav-s2"))
      (is (contains? all "fav-s3"))))
  (testing "past the cap the oldest stars fall away"
    (doseq [i (range (inc user-state/max-favorites))]
      (store/toggle-favorite! "carol" (str "s" i)))
    (let [favs (store/favorite-ids "carol")]
      (is (= user-state/max-favorites (count favs)))
      (is (not (contains? favs "s0")))
      (is (contains? favs (str "s" user-state/max-favorites))))))

(deftest store-keeps-extension-state-apart-from-ui-state
  (store/set-key! "alice" :theme "dark")
  (is (true? (store/set-ext! "alice" :notes {:n 1})))
  (is (true? (store/set-ext! "alice" :todo ["a" "b"])))
  (is (= {:n 1} (store/ext-state "alice" :notes)))
  (is (= {:theme "dark" :ext {:notes {:n 1} :todo ["a" "b"]}} (store/load-state "alice")))
  (testing "setting a UI key keeps the extension state, and the other way round"
    (store/set-key! "alice" :theme "light")
    (store/set-ext! "alice" :notes {:n 2})
    (is (= {:theme "light" :ext {:notes {:n 2} :todo ["a" "b"]}} (store/load-state "alice"))))
  (testing "nil forgets one extension's entry"
    (store/set-ext! "alice" :notes nil)
    (is (= {:todo ["a" "b"]} (:ext (store/load-state "alice")))))
  (testing "another user has their own"
    (is (nil? (store/ext-state "bob" :todo)))))

(deftest store-refuses-extension-state-that-is-not-data
  (is (not (store/set-ext! "alice" :notes inc)))
  (is (not (store/set-ext! "alice" :notes (apply str (repeat 70000 "x")))))
  (is (not (store/set-ext! "alice" "notes" {:n 1})) "the extension id is a keyword")
  (is (not (.existsSync fs (store/file "alice"))) "nothing was written"))

(deftest store-lists-the-users-it-has-state-for
  (is (= [] (store/known-users)))
  (store/set-key! "bob" :theme "dark")
  (store/set-ext! "alice" :notes {:n 1})
  (is (= ["alice" "bob"] (store/known-users))))

(deftest store-rejects-invalid-writes
  (is (not (store/set-key! "alice" :theme "sepia")))
  (is (not (store/set-key! "alice" :unknown "x")))
  (is (not (.existsSync fs (store/file "alice"))) "nothing written"))

(deftest store-contains-hostile-user-ids
  ;; a client-claimed id becomes a file name: it must stay inside the dir
  (is (= (.join node-path @tmp "root.edn") (store/file "../../etc/passwd")))
  (is (= (.join node-path @tmp "root.edn") (store/file nil)))
  (is (= (.join node-path @tmp "alice.edn") (store/file "Alice"))))

(deftest store-survives-a-corrupt-file
  (.writeFileSync fs (store/file "alice") "{:theme \"dark\" :oops")
  (is (= {} (store/load-state "alice")))
  (testing "and a hand-edited file keeps only its valid entries"
    (.writeFileSync fs (store/file "alice") "{:theme \"dark\" :appearance 5 :nope 1}")
    (is (= {:theme "dark"} (store/load-state "alice")))))
