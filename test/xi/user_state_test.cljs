(ns xi.user-state-test
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [xi.user-state :as user-state]
            [xi.user-state.store :as store]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(deftest valid-accepts-known-keys-with-allowed-values
  (is (user-state/valid? :theme "dark"))
  (is (user-state/valid? :appearance {:super-collapsed? false :tool-blocks :open}))
  (is (user-state/valid? :sidebar-collapsed [:projects :recent]))
  (is (user-state/valid? :preferred-model "claude-opus-4-6"))
  (is (user-state/valid? :recent-commands ["model" "clear"]))
  (is (user-state/valid? :recent-skills []))
  (is (user-state/valid? :themes {}))
  (is (user-state/valid? :themes {:active "Ocean" :themes {"Ocean" {:accent-hue 200 :gray-chroma 0.5 :bg-dark "#0a0a0f"}}}))
  (is (user-state/valid? :themes {:themes {"Ocean" {:bg-light "oklch(0.972 0.022 74.1)"}}})))

(deftest valid-rejects-unknown-keys-and-bad-values
  (testing "unknown key"
    (is (not (user-state/valid? :favorites ["a"])))
    (is (not (user-state/valid? "theme" "dark"))))
  (testing "bad values"
    (is (not (user-state/valid? :theme "sepia")))
    (is (not (user-state/valid? :theme :dark)))
    (is (not (user-state/valid? :appearance [:a])))
    (is (not (user-state/valid? :appearance {"super-collapsed?" true})))
    (is (not (user-state/valid? :appearance {:tool-blocks {:nested true}})))
    (is (not (user-state/valid? :sidebar-collapsed ["projects"])))
    (is (not (user-state/valid? :preferred-model "")))
    (is (not (user-state/valid? :recent-commands [1 2])))
    (is (not (user-state/valid? :themes {:bogus 1})))
    (is (not (user-state/valid? :themes {:active :ocean})))
    (is (not (user-state/valid? :themes {:themes {"" {}}})))
    (is (not (user-state/valid? :themes {:themes {"a" {:accent-hue :big}}})))
    (is (not (user-state/valid? :themes {:themes {"a" {:bg-dark "not a color"}}})))
    (is (not (user-state/valid? :themes {:themes {"a" {"accent-hue" 200}}}))))
  (testing "size bounds keep a client from growing the store"
    (is (not (user-state/valid? :recent-commands (vec (repeat 21 "x")))))
    (is (not (user-state/valid? :themes {:themes (into {} (map (fn [i] [(str "t" i) {}]) (range 21)))})))
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
  (is (true? (store/set-key! "alice" :appearance {:super-collapsed? false})))
  (is (true? (store/set-key! "bob" :theme "light")))
  (is (= {:theme "dark" :appearance {:super-collapsed? false}} (store/load-state "alice")))
  (is (= {:theme "light"} (store/load-state "bob")) "users never see each other's state")
  (is (= {} (store/load-state "root")) "root is a user like any other"))

(deftest read-markers-and-hidden-chats-are-server-only
  (testing "they validate"
    (is (user-state/valid? :read-state {"s1" 3 "s2" 0}))
    (is (user-state/valid? :dismissed ["s1" "s2"]))
    (is (not (user-state/valid? :read-state {"s1" -1})))
    (is (not (user-state/valid? :read-state {"s1" "3"})))
    (is (not (user-state/valid? :read-state {:s1 3})))
    (is (not (user-state/valid? :dismissed [""])))
    (is (not (user-state/valid? :dismissed "s1"))))
  (testing "a client may neither write nor see them"
    (is (not (user-state/client-valid? :read-state {"s1" 3})))
    (is (not (user-state/client-valid? :dismissed ["s1"])))
    (is (= {:theme "dark"}
           (user-state/client-view {:theme "dark" :read-state {"s1" 3} :dismissed ["s1"]})))))

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

(deftest store-never-keeps-a-chat-pinned-and-hidden-at-once
  (testing "pinning a hidden chat shows it again"
    (is (true? (store/toggle-dismissed! "dave" "s1")))
    (is (true? (store/toggle-pinned! "dave" "s1")))
    (is (= #{"s1"} (store/pinned "dave")))
    (is (= #{} (store/dismissed "dave"))))
  (testing "hiding a pinned chat unpins it"
    (is (true? (store/toggle-dismissed! "dave" "s1")))
    (is (= #{} (store/pinned "dave")))
    (is (= #{"s1"} (store/dismissed "dave"))))
  (testing "unpinning / un-hiding leaves the other flag alone"
    (store/toggle-pinned! "dave" "s2")
    (store/toggle-dismissed! "dave" "s3")
    (is (false? (store/toggle-pinned! "dave" "s2")))
    (is (= #{"s1" "s3"} (store/dismissed "dave")))
    (is (false? (store/toggle-dismissed! "dave" "s3")))
    (is (= #{} (store/pinned "dave")))
    (is (= #{"s1"} (store/dismissed "dave")))))

(def ^:private flags-state
  {:ext {:favorites {:session-flags {:favorite? ["s1" "s2"]}}
         :tags      {:session-flags {:favorite? ["s3"] :work? ["s1"]}}
         :other     {:n 1}}})

(deftest session-flags-come-from-the-extensions-entries
  (is (= {:favorite? #{"s1" "s2" "s3"} :work? #{"s1"}}
         (user-state/session-flags flags-state))
      "two extensions keeping one flag add up")
  (is (= {} (user-state/session-flags {})))
  (is (= {} (user-state/session-flags {:ext {:a {:n 1} :b [1 2] :c "x"}}))
      "entries without :session-flags (or not maps) are ignored"))

(deftest session-flags-are-only-well-formed-flags
  (is (= {:ok? #{"s1"}}
         (user-state/session-flags
          {:ext {:a {:session-flags {:ok?     ["s1"]
                                     :nope    ["s1"]        ; no trailing ?
                                     "str?"   ["s1"]        ; not a keyword
                                     :bad?    "s1"          ; not a sequence
                                     :dismissed? ["s1"]     ; the lobby's own
                                     :busy?   ["s1"]}}}})))
  (testing "non-string ids are dropped"
    (is (= {:ok? #{"s1"}}
           (user-state/session-flags {:ext {:a {:session-flags {:ok? ["s1" 2 nil]}}}})))))

(deftest annotate-session-flags-tags-every-flag-on-every-session
  (let [tagged (user-state/annotate-session-flags
                [{:session-id "s1" :name "one"} {:session-id "s9" :name "nine"}]
                (user-state/session-flags flags-state))]
    (is (= [true false] (mapv :favorite? tagged)))
    (is (= [true false] (mapv :work? tagged)))
    (is (= ["one" "nine"] (mapv :name tagged)) "other keys are preserved"))
  (is (= [{:session-id "x"}] (user-state/annotate-session-flags [{:session-id "x"}] {}))
      "no flags, nothing added"))

(deftest store-lists-every-flagged-session-of-every-user
  (store/set-ext! "alice" :favorites {:session-flags {:favorite? ["a1" "a2"]}})
  (store/set-ext! "bob" :favorites {:session-flags {:favorite? ["b1"]}})
  (store/set-key! "carol" :theme "dark")
  (is (= #{"a1" "a2" "b1"} (store/all-flagged-session-ids))))

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
