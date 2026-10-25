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

(defn- account! [env & args]
  (let [[opts args] (if (map? (last args)) [(last args) (butlast args)] [nil args])]
    (h/run-xi! env (into ["account" "scripted"] args) (merge {:env {"XI_FAKE_LLM" ""}} opts))))

(deftest accounts-sign-in-switch-and-forget
  ;; the protocol's accounts capability, through `xi account`: the sign-in
  ;; asks for the page's code on stdin and sends it as login-code
  (async done
    (h/with-env! (config [{:dir scripted-dir}]) {} done
      (fn [env]
        (-> (account! env)
            (.then (fn [{:keys [code stdout stderr]}]
                     (is (= 0 code) stderr)
                     (is (= "Scripted accounts\n* 1  one@x.io  live\n" stdout))
                     (account! env "login" "--alias" "spare" {:stdin "nope\n"})))
            (.then (fn [{:keys [code stdout stderr]}]
                     (is (= 1 code))
                     (is (str/includes? stdout "Open https://scripted.example/sign-in"))
                     (is (str/includes? stderr "Scripted sign-in failed: wrong code"))
                     (account! env "login" "--alias" "spare" {:stdin "ok\n"})))
            (.then (fn [{:keys [code stdout stderr]}]
                     (is (= 0 code) stderr)
                     (is (str/includes? stdout "Signed in to Scripted as new@x.io."))
                     (account! env "one@x.io")))
            (.then (fn [{:keys [stdout]}]
                     (is (= "Scripted now runs on one@x.io. Chats use it from their next message.\n" stdout))
                     (account! env "forget" "one@x.io")))
            (.then (fn [{:keys [code stderr]}]
                     (is (= 1 code))
                     (is (str/includes? stderr "that is the live account") "the provider's refusal reaches the user")
                     (account! env "forget" "spare")))
            (.then (fn [{:keys [stdout]}]
                     (is (= "Forgot new@x.io (spare).\n" stdout))
                     (account! env "alias" "1" "main")))
            (.then (fn [_] (account! env "--json")))
            (.then (fn [{:keys [json]}]
                     (is (= {:accounts [{:id "1" :active true :email "one@x.io" :alias "main"}]} json)))))))))

(deftest an-unusable-provider-is-reported
  (async done
    (h/with-env! (config [{:dir "/nonexistent/provider"}]) {} done
      (fn [env]
        (-> (prompt! env "--model" "scripted/echo" "hello")
            (.then
             (fn [{:keys [stderr]}]
               (is (str/includes? stderr "[providers] no provider at /nonexistent/provider"))
               (is (str/includes? stderr "No provider runs scripted/echo")))))))))
