(ns xi.ext.process-manager-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.core.state :as state]
            [xi.ext.process-manager :as pm]
            [xi.ext.process-manager.web :as pm-web]))

(def ^:private proc {:pid 42 :command "bb e2e" :started 1 :logfile "/tmp/xi-proc-1.log"})

(defn- with-room [procs]
  (-> (state/initial-state {:mode :standalone})
      (assoc-in [:rooms "r1"] (state/make-room "r1" {:ext {:process-manager {:processes procs}}}))
      (assoc :active-room "r1")))

(defn- handler [ext k] (get-in ext [:handlers k]))

(deftest open-log-resolves-only-tracked-processes
  (let [open (handler pm/extension :process-manager/open-log)]
    (testing "a tracked pid follows its own log, for the asking client"
      (is (= [[:process-manager/follow-log {:room-id "r1" :process proc :client-id "c1"}]]
             (:effects (open (with-room [proc]) {:room-id "r1" :pid 42 :client-id "c1"})))))
    (testing "an unknown pid names no file"
      (is (nil? (open (with-room [proc]) {:room-id "r1" :pid 7}))))
    (testing "the request is not broadcast"
      (is (contains? (:no-broadcast pm/extension) :process-manager/open-log)))))

(deftest web-mirrors-the-registry
  (let [register   (handler pm-web/extension :ext.process-manager/register)
        deregister (handler pm-web/extension :ext.process-manager/deregister)
        procs      #(get-in % [:rooms "r1" :ext :process-manager :processes])]
    (testing "the server's broadcasts build the list"
      (let [st (:state (register (with-room []) {:room-id "r1" :process proc :remote? true}))]
        (is (= [proc] (procs st)))
        (is (= [] (procs (:state (deregister st {:room-id "r1" :pid 42 :remote? true})))))))
    (testing "a local copy changes nothing"
      (is (nil? (register (with-room []) {:room-id "r1" :process proc}))))))

(deftest web-open-log-forwards-to-the-server
  (let [open (handler pm-web/extension :process-manager/open-log)]
    (is (= [[:ws/send {:type :process-manager/open-log :room-id "r1" :pid 42}]]
           (:effects (open (with-room [proc]) {:type :process-manager/open-log :pid 42}))))
    (is (nil? (open (with-room [proc]) {:type :process-manager/open-log :pid 42 :remote? true})))))
