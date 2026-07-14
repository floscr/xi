(ns xi.summary-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.core.events :as events]
            [xi.core.state :as state]
            [xi.summary :as summary]))

(defn- handle [st event]
  (events/handle-event summary/handlers st event))

(defn- with-room [& [session]]
  (:state (events/handle-event
           events/core-handlers
           (state/initial-state)
           {:type :room/create :room-id "r"
            :room {:model "m" :cwd "/tmp"
                   :session (or session {:id "s1" :provider-session-id "cli-1"})}})))

(defn- history [st] (:history (state/get-room st "r")))

;; ── parse-output (pure) ──────────────────────────────────────────────────────

(deftest parse-output-splits-title-and-description
  (let [{:keys [title description]}
        (summary/parse-output "TITLE: Fix Parser Bug\n\nThe session fixes a crash in the parser.")]
    (is (= "Fix Parser Bug" title))
    (is (= "The session fixes a crash in the parser." description))))

(deftest parse-output-cleans-title-quotes-and-punctuation
  (is (= "Parser Bugfix"
         (:title (summary/parse-output "TITLE: \"Parser Bugfix.\"\n\nWork on the parser.")))))

(deftest parse-output-tolerates-missing-title-line
  (let [{:keys [title description]}
        (summary/parse-output "Just a description with no title line.")]
    (is (nil? title))
    (is (= "Just a description with no title line." description))))

(deftest parse-output-is-case-insensitive-for-title-label
  (is (= "Lowercase Label"
         (:title (summary/parse-output "title: Lowercase Label\n\nBody text.")))))

(deftest parse-output-nil-input
  (is (nil? (summary/parse-output nil))))

;; ── :summary/generated (handler) ─────────────────────────────────────────────

(deftest generated-with-title-updates-name-and-persists
  (let [{:keys [state effects]}
        (handle (with-room {:id "s1" :name "Stale Title" :provider-session-id "cli-1"})
                {:type :summary/generated :room-id "r"
                 :title "Fresh Title" :summary "What the session is about."})]
    (testing "overwrites the stale session title"
      (is (= "Fresh Title" (get-in state [:rooms "r" :session :name]))))
    (testing "persists via :session/sync"
      (is (some #(= :session/sync (first %)) effects)))
    (testing "still prints the description as a status entry"
      (is (str/includes? (:text (last (history state))) "What the session is about.")))
    (testing "clears the pending flag"
      (is (false? (get-in state [:rooms "r" :agent :summary-pending?]))))))

(deftest generated-without-title-leaves-name-and-skips-sync
  (let [{:keys [state effects]}
        (handle (with-room {:id "s1" :name "Kept Title" :provider-session-id "cli-1"})
                {:type :summary/generated :room-id "r"
                 :title nil :summary "Description only."})]
    (is (= "Kept Title" (get-in state [:rooms "r" :session :name])))
    (is (not (some #(= :session/sync (first %)) effects)))
    (is (str/includes? (:text (last (history state))) "Description only."))))
