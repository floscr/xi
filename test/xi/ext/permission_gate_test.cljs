(ns xi.ext.permission-gate-test
  (:require [cljs.test :refer [deftest is async]]
            [xi.ext.permission-gate :as pg]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(def ^:private gate (:tool-gate pg/extension))

(defn- ctx
  "Gate ctx with a confirm! that records its calls and resolves to `answer`."
  [cwd answer calls]
  {:cwd cwd
   :confirm! (fn [msg] (swap! calls conj msg) (js/Promise.resolve answer))})

(def ^:private repo-cwd (.cwd js/process))

(deftest write-inside-repo-passes-without-confirmation
  (async done
    (let [calls (atom [])
          tc {:name "write"
              :arguments {:path (node-path/join repo-cwd "target" "scratch.txt")}}]
      (-> (js/Promise.resolve (gate tc (ctx repo-cwd false calls)))
          (.then (fn [res]
                   (is (= tc res) "in-repo write is allowed unchanged")
                   (is (empty? @calls) "confirm! not invoked for in-repo path")
                   (done)))))))

(deftest write-to-tmp-passes-without-confirmation
  (async done
    (let [calls (atom [])
          tc {:name "write"
              :arguments {:path (node-path/join (os/tmpdir) "scratch.txt")}}]
      (-> (js/Promise.resolve (gate tc (ctx repo-cwd false calls)))
          (.then (fn [res]
                   (is (= tc res) "tmp write is allowed unchanged")
                   (is (empty? @calls) "confirm! not invoked for tmp path")
                   (done)))))))

(deftest write-outside-repo-requires-confirmation-blocks-on-no
  (async done
    (let [calls (atom [])
          out (node-path/join (os/homedir) "xi-outside-write-test.txt")
          tc {:name "edit" :arguments {:path out}}]
      (-> (js/Promise.resolve (gate tc (ctx repo-cwd false calls)))
          (.then (fn [res]
                   (is (nil? res) "denied outside-repo write is blocked")
                   (is (= 1 (count @calls)) "confirm! invoked once")
                   (is (re-find #"outside the project repo" (first @calls)))
                   (done)))))))

(deftest write-outside-repo-skips-confirm-when-repo-allowed
  (async done
    (let [calls (atom [])
          home  (os/homedir)
          out   (node-path/join home "xi-outside-write-test.txt")
          tc    {:name "write" :arguments {:path out}}
          st    {:rooms {"r" {:ext {:permission-gate
                                    {:allowed-write-repos #{home}}}}}}
          ctx'  (assoc (ctx repo-cwd false calls)
                       :room-id "r"
                       :get-state (fn [] st))]
      (-> (js/Promise.resolve (gate tc ctx'))
          (.then (fn [res]
                   (is (= tc res) "write under an allowed repo passes through")
                   (is (empty? @calls) "confirm! not invoked for allowed repo")
                   (done)))))))

(deftest write-outside-repo-allowed-on-yes
  (async done
    (let [calls (atom [])
          out (node-path/join (os/homedir) "xi-outside-write-test.txt")
          tc {:name "write" :arguments {:path out}}]
      (-> (js/Promise.resolve (gate tc (ctx repo-cwd true calls)))
          (.then (fn [res]
                   (is (= tc res) "approved outside-repo write passes through")
                   (is (= 1 (count @calls)) "confirm! invoked once")
                   (done)))))))
