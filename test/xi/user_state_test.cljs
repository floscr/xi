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
