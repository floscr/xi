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
    (is (not (user-state/valid? :favorites ["a"])))
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
