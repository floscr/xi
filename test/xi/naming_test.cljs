(ns xi.naming-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.agent :as agent]
            [xi.core.events :as events]
            [xi.core.state :as state]
            [xi.naming :as naming]))

(def all-handlers
  (-> (merge events/core-handlers agent/handlers naming/handlers)
      (assoc :prompt/submit (events/chain (:prompt/submit agent/handlers)
                                          naming/maybe-generate-title))))

(defn- handle [st event]
  (events/handle-event all-handlers st event))

(defn- with-room [& [session]]
  (:state (handle (state/initial-state)
                  {:type :room/create :room-id "r"
                   :room {:model "claude-sonnet-4-5" :cwd "/tmp"
                          :session (or session {:id "s1"})}})))

(deftest first-message-triggers-title-generation
  (let [{:keys [state effects]}
        (handle (with-room) {:type :prompt/submit :room-id "r" :text "fix the parser bug"})]
    (testing "emits the generate-title effect with the message text"
      (is (some #(= :session/generate-title (first %)) effects))
      (is (= "fix the parser bug"
             (-> (some #(when (= :session/generate-title (first %)) %) effects)
                 second :text))))
    (testing "marks title generation pending"
      (is (true? (get-in state [:rooms "r" :agent :title-pending?]))))
    (testing "sets a provisional name from the prompt instead of \"New session\""
      (is (= "fix the parser bug" (get-in state [:rooms "r" :session :name])))
      (is (true? (get-in state [:rooms "r" :agent :title-provisional?]))))))

(deftest title-generated-overrides-provisional-name
  (let [st (:state (handle (with-room)
                           {:type :prompt/submit :room-id "r" :text "fix the parser bug"}))
        st (:state (handle st
                           {:type :session/title-generated :room-id "r" :title "Parser Bugfix"}))]
    (testing "model title replaces the provisional prompt-derived name"
      (is (= "Parser Bugfix" (get-in st [:rooms "r" :session :name])))
      (is (nil? (get-in st [:rooms "r" :agent :title-provisional?]))))))

(deftest already-named-session-skips-generation
  (let [{:keys [effects]}
        (handle (with-room {:id "s1" :name "Existing Title"})
                {:type :prompt/submit :room-id "r" :text "do a thing"})]
    (is (not (some #(= :session/generate-title (first %)) effects)))))

(deftest second-message-does-not-refire
  (let [st1 (:state (handle (with-room)
                            {:type :prompt/submit :room-id "r" :text "first"}))
        ;; mark busy so the second prompt is queued, not started
        st1 (assoc-in st1 [:rooms "r" :agent :busy?] true)
        {:keys [effects]} (handle st1 {:type :prompt/submit :room-id "r" :text "second"})]
    (is (not (some #(= :session/generate-title (first %)) effects)))))

(deftest blank-text-skips-generation
  (let [{:keys [effects]}
        (handle (with-room) {:type :prompt/submit :room-id "r" :text "   "})]
    (is (not (some #(= :session/generate-title (first %)) effects)))))

(deftest title-generated-sets-name-when-unnamed
  (let [st (:state (handle (with-room)
                           {:type :session/title-generated :room-id "r" :title "Parser Bugfix"}))]
    (is (= "Parser Bugfix" (get-in st [:rooms "r" :session :name])))))

(deftest title-generated-respects-existing-name
  (let [st (:state (handle (with-room {:id "s1" :name "Resumed Session"})
                           {:type :session/title-generated :room-id "r" :title "Generated"}))]
    (is (= "Resumed Session" (get-in st [:rooms "r" :session :name])))))
