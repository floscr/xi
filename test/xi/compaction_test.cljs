(ns xi.compaction-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.compaction :as compaction]
            [xi.core.events :as events]
            [xi.core.state :as state]))

(defn- handle [st event]
  (events/handle-event compaction/handlers st event))

(defn- with-room [& [room-overrides]]
  (:state (events/handle-event
           events/core-handlers
           (state/initial-state)
           {:type :room/create :room-id "r"
            :room (merge {:model "m" :cwd "/tmp"
                          :session {:id "sess-1" :provider-session-id "cli-1"}}
                         room-overrides)})))

(defn- history [st] (:history (state/get-room st "r")))
(defn- busy? [st] (get-in st [:rooms "r" :agent :busy?]))

;; ── :compact/request ─────────────────────────────────────────────────────────

(deftest request-starts-compaction
  (let [{:keys [state effects]} (handle (with-room)
                                        {:type :compact/request :room-id "r"})]
    (is (true? (busy? state)))
    (is (= 1 (count (history state))))
    (is (str/includes? (:text (first (history state))) "Compacting"))
    (is (= [[:compact/start {:room-id "r" :session-id "cli-1" :focus nil}]]
           effects))))

(deftest request-requires-provider-session
  (let [st (with-room {:session {:id "sess-1"}})
        {:keys [state effects]} (handle st {:type :compact/request :room-id "r"})]
    (is (empty? effects))
    (is (not (busy? state)))
    (is (str/includes? (:text (first (history state))) "No active session"))))

(deftest request-blocked-while-busy
  (let [st (assoc-in (with-room) [:rooms "r" :agent :busy?] true)
        {:keys [state effects]} (handle st {:type :compact/request :room-id "r"})]
    (is (empty? effects))
    (is (str/includes? (:text (first (history state))) "busy"))))

;; ── :compact/done / :compact/failed ──────────────────────────────────────────

(deftest done-starts-fresh-session-with-summary
  (let [st (assoc-in (with-room) [:rooms "r" :agent :busy?] true)
        {:keys [state effects]} (handle st {:type :compact/done :room-id "r"
                                            :summary "the summary"})
        [[fx-type payload]] effects]
    (is (false? (busy? state)))
    (is (= :session/new fx-type))
    (is (= "r" (:room-id payload)))
    (is (str/includes? (:after-prompt payload) "<conversation-summary>"))
    (is (str/includes? (:after-prompt payload) "the summary"))))

(deftest failed-clears-busy-and-reports
  (let [st (assoc-in (with-room) [:rooms "r" :agent :busy?] true)
        {:keys [state effects]} (handle st {:type :compact/failed :room-id "r"
                                            :error "aborted"})]
    (is (false? (busy? state)))
    (is (empty? effects))
    (is (str/includes? (:text (first (history state))) "aborted"))))

;; ── abort chaining ───────────────────────────────────────────────────────────

(deftest abort-handler-emits-compact-abort-when-busy
  (let [st (assoc-in (with-room) [:rooms "r" :agent :busy?] true)
        result (compaction/abort-handler st {:room-id "r"})]
    (is (= [[:compact/abort {:room-id "r"}]] (:effects result))))
  (testing "no-op when idle"
    (is (nil? (compaction/abort-handler (with-room) {:room-id "r"})))))
