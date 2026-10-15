(ns xi.web.rename-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.rename :as rename]))

(defn- handle [st ev]
  ((get rename/handlers (:type ev)) st ev))

(def names [{:name "Parser Fix" :style :short}
            {:name "Fixing the Parser Bug" :style :descriptive}
            {:name "Parser: Bugfix" :style :topic}])

(deftest open-asks-for-suggestions-right-away
  (let [{:keys [state effects]} (handle {} {:type :rename/open :session-id "s1" :name "Old"})]
    (is (= :loading (get-in state [:web/rename :status])))
    (is (= "Old" (get-in state [:web/rename :name])))
    (is (= [[:ws/send {:type :session/suggest-name :session-id "s1" :exclude []}]] effects))))

(deftest reopening-reuses-the-last-suggestions
  (let [st (:state (handle {} {:type :session/name-suggestion :session-id "s1"
                               :names names :stats {:ms 900}}))
        {:keys [state effects]} (handle st {:type :rename/open :session-id "s1" :name "Old"})]
    (is (empty? effects))
    (is (= :ready (get-in state [:web/rename :status])))
    (is (= names (get-in state [:web/rename :names])))))

(deftest suggestions-fill-in-and-tab-cycles
  (let [st (-> (:state (handle {} {:type :rename/open :session-id "s1" :name "Old"}))
               (handle {:type :session/name-suggestion :session-id "s1" :names names :stats {}})
               :state)
        tab #(:state (handle % {:type :rename/tab :dir %2}))]
    (testing "the field keeps what you typed until you take one"
      (is (= "Old" (get-in st [:web/rename :name]))))
    (is (= "Parser Fix" (get-in (tab st 1) [:web/rename :name])))
    (is (= "Fixing the Parser Bug" (get-in (tab (tab st 1) 1) [:web/rename :name])))
    (is (= "Parser: Bugfix" (get-in (tab st -1) [:web/rename :name])))
    (testing "More excludes everything shown so far"
      (is (= #{"Parser Fix" "Fixing the Parser Bug" "Parser: Bugfix"}
             (-> (handle st {:type :rename/more}) :effects first second :exclude set))))))

(deftest an-empty-reply-marks-failure-and-keeps-the-cache
  (let [st (-> (:state (handle {} {:type :rename/open :session-id "s1" :name "Old"}))
               (handle {:type :session/name-suggestion :session-id "s1" :names []})
               :state)]
    (is (= :failed (get-in st [:web/rename :status])))
    (is (nil? (get-in st [:web/name-suggestions "s1"])))))

(deftest next-index-wraps
  (is (nil? (rename/next-index nil 0 1)))
  (is (= 0 (rename/next-index nil 3 1)))
  (is (= 2 (rename/next-index nil 3 -1)))
  (is (= 0 (rename/next-index 2 3 1))))

(deftest stats-line-reads-well
  (is (= "Haiku 4.5 · 1.2k in, 24 out · 0.12¢ · 2.3s"
         (rename/stats-line {:model "claude-haiku-4-5-20251001" :input-tokens 1200
                             :output-tokens 24 :cost 0.0012 :ms 2300}))))
