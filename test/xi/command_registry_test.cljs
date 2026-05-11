(ns xi.command-registry-test
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [xi.command-registry :as registry]))

(use-fixtures :each
  {:before #(registry/clear!)
   :after  #(registry/clear!)})

(deftest register-and-lookup
  (testing "registers a command and looks it up by name"
    (registry/register! {:name "test" :description "A test" :handler identity})
    (let [cmd (registry/get-command "test")]
      (is (some? cmd))
      (is (= "test" (:name cmd)))
      (is (= "A test" (:description cmd)))
      (is (= :runtime (:scope cmd)) "default scope is :runtime")))

  (testing "returns nil for unknown command"
    (is (nil? (registry/get-command "nonexistent")))))

(deftest scope-specific-lookup
  (testing "can register same name in both scopes"
    (registry/register! {:name "model" :description "Runtime model" :handler identity :scope :runtime})
    (registry/register! {:name "model" :description "Client model" :handler str :scope :client})

    (is (= "Runtime model" (:description (registry/get-command "model" :runtime))))
    (is (= "Client model" (:description (registry/get-command "model" :client)))))

  (testing "unscoped lookup prefers client over runtime"
    (registry/register! {:name "foo" :description "Runtime" :handler identity :scope :runtime})
    (registry/register! {:name "foo" :description "Client" :handler str :scope :client})

    (is (= "Client" (:description (registry/get-command "foo"))))))

(deftest list-commands-basics
  (testing "lists all non-hidden commands sorted by name"
    (registry/register! {:name "zebra" :description "Z" :handler identity})
    (registry/register! {:name "alpha" :description "A" :handler identity})
    (registry/register! {:name "hidden" :description "H" :handler identity :hidden true})

    (let [cmds (registry/list-commands)]
      (is (= ["alpha" "zebra"] (mapv :name cmds)))
      (is (not (some #(= "hidden" (:name %)) cmds)))))

  (testing "include-hidden shows hidden commands"
    (registry/register! {:name "secret" :description "S" :handler identity :hidden true})
    (let [cmds (registry/list-commands {:include-hidden true})]
      (is (some #(= "secret" (:name %)) cmds)))))

(deftest list-commands-scope-filter
  (testing "filters by scope"
    (registry/register! {:name "srv" :description "Server" :handler identity :scope :runtime})
    (registry/register! {:name "ui" :description "UI" :handler identity :scope :client})

    (let [runtime-cmds (registry/list-commands {:scope :runtime})
          client-cmds (registry/list-commands {:scope :client})]
      (is (= ["srv"] (mapv :name runtime-cmds)))
      (is (= ["ui"] (mapv :name client-cmds))))))

(deftest register-many-works
  (testing "registers multiple commands"
    (registry/register-many!
     [{:name "a" :description "A" :handler identity}
      {:name "b" :description "B" :handler identity}])

    (is (some? (registry/get-command "a")))
    (is (some? (registry/get-command "b")))))

(deftest defaults-applied
  (testing "defaults are merged"
    (registry/register! {:name "bare" :handler identity})
    (let [cmd (registry/get-command "bare")]
      (is (= :runtime (:scope cmd)))
      (is (= false (:show-busy cmd)))
      (is (= false (:hidden cmd))))))
