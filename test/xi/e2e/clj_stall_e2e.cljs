(ns xi.e2e.clj-stall-e2e
  "A clj eval that outlives the stall limit: the call returns early, the REPL
   answers busy, and the agent waits for the eval or aborts it."
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.e2e.harness :as h]
            [xi.e2e.profiles :as profiles]))

(def ^:private stall-after-1s
  (merge profiles/minimal {".config/xi/ext/clj.edn" (pr-str {:stall-after-s 1})}))

(deftest a-stalled-eval-is-waited-for-or-aborted
  (async done
    (h/with-env! stall-after-1s
      {:default [{:tool "clj" :args {:code "(do (sleep 1500) :slept)"}}
                 {:tool "clj" :args {:code "(+ 1 2)"}}
                 {:tool "clj" :args {:wait true}}
                 {:tool "clj" :args {:code "(loop [] (recur))"}}
                 {:tool "clj" :args {:wait true}}
                 {:tool "clj" :args {:abort true}}
                 {:tool "clj" :args {:code "(+ 1 2)"}}
                 {:text "done"}]}
      done
      (fn [env]
        (-> (h/prompt! env "go")
            (.then
             (fn [{:keys [code stderr]}]
               (is (= 0 code) stderr)
               (let [[slow busy waited spin still aborted fresh]
                     (map :content (mapcat :tool-calls (h/main-turns env)))]
                 (testing "a slow eval hands the call back and holds the REPL"
                   (is (str/includes? slow "still running after 1s") slow)
                   (is (str/includes? busy "not run, the REPL is busy with `(do (sleep 1500) :slept)`") busy))
                 (testing "wait collects its result"
                   (is (= "The earlier eval `(do (sleep 1500) :slept)` finished:\n=> :slept" waited)))
                 (testing "a busy loop is still running after a wait, and abort kills the REPL"
                   (is (str/includes? spin "still running after 1s") spin)
                   (is (str/includes? still "still running after another 1s") still)
                   (is (str/includes? aborted "ignored the abort, so the REPL was reset") aborted))
                 (is (= "=> 3" fresh) "the next call gets a fresh REPL")))))))))
