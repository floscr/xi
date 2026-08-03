(ns xi.ext.manager-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.ext.manager :as manager]))

(defn- tool-ext
  "A fake extension contributing one tool named after its id, recording
   enable/disable calls into `log`."
  [id log]
  {:id id
   :tool-definitions [{:name (name id) :description "t" :input_schema {}}]
   :tool-registry    {(name id) (fn [_ _] {:content [] :is-error false})}
   :on-enable  (fn [] (swap! log conj [:on-enable id]))
   :on-disable (fn [] (swap! log conj [:on-disable id]))})

(defn- tool-names [mgr]
  (set (map :name (:tool-definitions (manager/composed mgr)))))

(deftest seed-enables-all
  (let [log (atom [])
        mgr (manager/create)]
    (manager/seed! mgr [(tool-ext :a log) (tool-ext :b log)])
    (is (= [{:id :a :enabled? true} {:id :b :enabled? true}]
           (manager/ext-list mgr)))
    (is (= #{"a" "b"} (tool-names mgr)))
    (testing "seed! does not fire :on-enable hooks"
      (is (= [] @log)))))

(deftest disable-removes-tools-and-fires-hook
  (let [log (atom [])
        mgr (doto (manager/create) (manager/seed! [(tool-ext :a log) (tool-ext :b log)]))]
    (is (= :a (manager/disable! mgr :a)))
    (is (= #{"b"} (tool-names mgr)) "disabled ext's tools drop from composed")
    (is (= [{:id :a :enabled? false} {:id :b :enabled? true}]
           (manager/ext-list mgr)))
    (is (= [[:on-disable :a]] @log))
    (testing "disabling again is a no-op"
      (is (nil? (manager/disable! mgr :a)))
      (is (= [[:on-disable :a]] @log)))))

(deftest enable-restores-tools-and-fires-hook
  (let [log (atom [])
        mgr (doto (manager/create) (manager/seed! [(tool-ext :a log)]))]
    (manager/disable! mgr :a)
    (reset! log [])
    (is (= :a (manager/enable! mgr :a)))
    (is (= #{"a"} (tool-names mgr)))
    (is (= [[:on-enable :a]] @log))
    (testing "enabling an already-enabled ext is a no-op"
      (is (nil? (manager/enable! mgr :a)))
      (is (= [[:on-enable :a]] @log)))))

(deftest register-adds-runtime-extension
  (let [log (atom [])
        mgr (doto (manager/create) (manager/seed! [(tool-ext :a log)]))]
    (is (= :b (manager/register! mgr (tool-ext :b log))))
    (is (= #{"a" "b"} (tool-names mgr)))
    (is (manager/known? mgr :b))
    (is (= [[:on-enable :b]] @log) "register! fires :on-enable when enabled")
    (testing "register! with :enable? false stays out of composed"
      (let [log2 (atom [])]
        (manager/register! mgr (tool-ext :c log2) {:enable? false})
        (is (not (contains? (tool-names mgr) "c")))
        (is (= [] @log2))
        (is (= :c (manager/enable! mgr :c)))
        (is (contains? (tool-names mgr) "c"))))))

(deftest unknown-toggles-are-noops
  (let [mgr (doto (manager/create) (manager/seed! [(tool-ext :a (atom []))]))]
    (is (nil? (manager/enable! mgr :nope)))
    (is (nil? (manager/disable! mgr :nope)))
    (is (not (manager/known? mgr :nope)))))

(deftest unregister-removes-and-fires-teardown
  (let [log (atom [])
        mgr (doto (manager/create) (manager/seed! [(tool-ext :a log) (tool-ext :b log)]))]
    (is (= :a (manager/unregister! mgr :a)))
    (is (not (manager/known? mgr :a)) "unregistered ext is gone from :known")
    (is (= #{"b"} (tool-names mgr)) "its tools drop from composed")
    (is (= [{:id :b :enabled? true}] (manager/ext-list mgr)))
    (is (= [[:on-disable :a]] @log) "unregister! runs :on-disable teardown")
    (testing "unregistering an unknown id is a no-op"
      (is (nil? (manager/unregister! mgr :nope))))))

(deftest compose-order-preserved
  (let [mgr (doto (manager/create)
              (manager/seed! [(tool-ext :a (atom [])) (tool-ext :b (atom []))]))]
    (manager/disable! mgr :a)
    (manager/enable! mgr :a)
    (testing "re-enabled ext keeps its original registration slot"
      (is (= [:a :b] (map :id (manager/ext-list mgr)))))))
