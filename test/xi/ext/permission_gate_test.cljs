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

;; The write / blocked-command / guarded / outside-repo policy gates moved to
;; the rules engine (xi.rules.defaults); the permission-gate tool-gate now only
;; guards the server-control tasks. So ordinary calls pass through untouched.

(deftest write-passes-through-gate
  (async done
    (let [calls (atom [])
          tc {:name "write"
              :arguments {:path (node-path/join (os/homedir) "outside.txt")}}]
      (-> (js/Promise.resolve (gate tc (ctx repo-cwd false calls)))
          (.then (fn [res]
                   (is (= tc res) "write passes the permission-gate unchanged")
                   (is (empty? @calls) "confirm! not invoked — writes are now gated by rules")
                   (done)))))))

(deftest ordinary-bash-passes-through
  (async done
    (let [calls (atom [])
          tc {:name "bash" :arguments {:command "rm -rf /home/x/build"}}]
      (-> (js/Promise.resolve (gate tc (ctx repo-cwd false calls)))
          (.then (fn [res]
                   (is (= tc res) "guarded bash is no longer gated here (rules do it)")
                   (is (empty? @calls) "confirm! not invoked")
                   (done)))))))

(deftest guarded-patterns-still-published
  ;; clj reuses these patterns for (sh …) argv strings.
  (is (some #{"fs/delete-dir"} pg/GUARDED_PATTERNS))
  (is (some #{"fs/delete-tree"} pg/GUARDED_PATTERNS))
  (is (some #{"git push"} pg/GUARDED_PATTERNS)))


