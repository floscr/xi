(ns xi.e2e.providers-e2e
  "Providers are programs: a config.edn `:providers` entry runs one
   (test/providers/scripted, the protocol written by hand), its options reach
   it, its tool call runs through Xi, and a broken entry is reported. No fake
   LLM here: the program is the model."
  (:require [cljs.test :refer [deftest is async]]
            [clojure.string :as str]
            [xi.e2e.harness :as h]
            ["node:path" :as node-path]))

(def ^:private scripted-dir (node-path/resolve "test/providers/scripted"))

(defn- config [providers]
  {".config/xi/config.edn" (pr-str {:type :xi/config :version 1 :providers providers})
   ".config/xi/rules.edn"  (pr-str {:type :xi/rules :version 1 :rules []})})

(defn- prompt! [env & args]
  ;; XI_FAKE_LLM empty: the configured providers run, not the fake
  (h/run-xi! env (into ["prompt"] args) {:env {"XI_FAKE_LLM" ""}}))

(deftest a-provider-is-a-program
  (async done
    (h/with-env! (config [{:dir scripted-dir :greeting "hi"}]) {} done
      (fn [env]
        (-> (prompt! env "--model" "scripted/echo" "read the instructions")
            (.then
             (fn [{:keys [code stdout stderr]}]
               (is (= 0 code) stderr)
               (is (= "hi! read: instructions\n" stdout)
                   "the option reached it and its read ran through Xi's tools"))))))))

(deftest without-a-default-model-xi-asks-for-one
  (async done
    ;; the scripted provider names no defaultModel, and no Claude runs
    (h/with-env! (config [{:dir scripted-dir}]) {} done
      (fn [env]
        (-> (prompt! env "hello")
            (.then
             (fn [{:keys [stderr]}]
               (is (str/includes? stderr "No model picked: choose one with /model or --model.")))))))))

(deftest an-unusable-provider-is-reported
  (async done
    (h/with-env! (config [{:dir "/nonexistent/provider"}]) {} done
      (fn [env]
        (-> (prompt! env "--model" "scripted/echo" "hello")
            (.then
             (fn [{:keys [stderr]}]
               (is (str/includes? stderr "[providers] no provider at /nonexistent/provider"))
               (is (str/includes? stderr "No provider runs scripted/echo")))))))))
