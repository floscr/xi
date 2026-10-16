(ns xi.naming-test
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.agent :as agent]
            [xi.commands :as commands]
            [xi.core.events :as events]
            [xi.core.state :as state]
            [xi.naming :as naming]))

(def all-handlers
  (-> (merge events/core-handlers agent/handlers commands/handlers naming/handlers)
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
        {st :state effects :effects}
        (handle st {:type :session/title-generated :room-id "r" :title "Parser Bugfix"})]
    (testing "model title replaces the provisional prompt-derived name"
      (is (= "Parser Bugfix" (get-in st [:rooms "r" :session :name])))
      (is (nil? (get-in st [:rooms "r" :agent :title-provisional?])))
      (is (nil? (get-in st [:rooms "r" :agent :title-pending?]))))
    (testing "persists the fresh title immediately so a late landing isn't lost"
      (is (some #(= :session/sync (first %)) effects)))))

(deftest failed-title-turn-clears-pending-keeps-provisional
  (let [st (:state (handle (with-room)
                           {:type :prompt/submit :room-id "r" :text "fix the parser bug"}))
        st (:state (handle st {:type :session/title-generated :room-id "r" :title nil}))]
    (testing "the pending guard is dropped so a retry can regenerate"
      (is (nil? (get-in st [:rooms "r" :agent :title-pending?]))))
    (testing "the prompt-derived provisional name stays in place"
      (is (= "fix the parser bug" (get-in st [:rooms "r" :session :name])))
      (is (true? (get-in st [:rooms "r" :agent :title-provisional?]))))))

(defn- retry-first
  "Fork at index 0 (tree/navigate) and resubmit `text`: it queues behind the
   rewind and is sent once the store answers (the drained :prompt/submit)."
  [st text]
  (let [st (:state (handle st {:type :tree/navigate :room-id "r" :index 0}))
        st (:state (handle st {:type :prompt/submit :room-id "r" :text text}))
        {st :state [[_ submit]] :effects}
        (handle st {:type :session/rewound :room-id "r" :result {:status :emptied}})]
    (handle st submit)))

(deftest retry-of-first-message-regenerates-title
  ;; The title turn failed (e.g. expired credentials); the user retries the
  ;; first message — fork at index 0 (tree/navigate) + resubmit. The resubmit
  ;; must kick off a fresh title turn.
  (let [st (:state (handle (with-room)
                           {:type :prompt/submit :room-id "r" :text "fix the parser bug"}))
        st (:state (handle st {:type :session/title-generated :room-id "r" :title nil}))
        {:keys [effects]} (retry-first st "fix the parser bug")]
    (is (some #(= :session/generate-title (first %)) effects))))

(deftest retry-of-first-message-after-successful-title-regenerates
  ;; Even a successfully generated title is re-armed by a first-message fork:
  ;; the conversation restarts, so the title regenerates with it.
  (let [st (:state (handle (with-room)
                           {:type :prompt/submit :room-id "r" :text "fix the parser bug"}))
        st (:state (handle st {:type :session/title-generated :room-id "r" :title "Parser Bugfix"}))
        {:keys [state effects]} (retry-first st "use a different parser")]
    (is (some #(= :session/generate-title (first %)) effects))
    (testing "the old title is provisional again until the new one lands"
      (is (true? (get-in state [:rooms "r" :agent :title-provisional?]))))))

(deftest errored-title-turn-never-names-the-session
  ;; An errored turn resolves with :is-error and the failure notice as
  ;; :result-text ("Failed to authenticate: …") — it must never become the
  ;; session name; a nil title is dispatched so the pending guard clears.
  (async done
    (let [fx (naming/create-fx
              {:anthropic {:start-turn!
                           (fn [_opts]
                             {:promise (js/Promise.resolve
                                        {:is-error true
                                         :result-text "Failed to authenticate: OAuth session expired"})})}}
              {})]
      ((:session/generate-title fx)
       {:dispatch! (fn [ev]
                     (is (= {:type :session/title-generated :room-id "r" :title nil} ev))
                     (done))
        :state {:rooms {"r" {:cwd "/tmp"}}}}
       {:room-id "r" :text "fix the parser bug"}))))

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


;; ── Rename + suggestions ─────────────────────────────────────────────────────

(deftest name-set-is-a-real-name
  (let [st (:state (handle (with-room) {:type :prompt/submit :room-id "r" :text "fix it"}))
        st (:state (handle st {:type :session/name-set :room-id "r" :name "My Name"}))
        st (:state (handle st {:type :session/title-generated :room-id "r" :title "Model Title"}))]
    (testing "a title turn landing after the rename never overwrites it"
      (is (= "My Name" (get-in st [:rooms "r" :session :name]))))))

(deftest suggestion-input-is-bounded
  (is (nil? (naming/suggestion-input [])))
  (testing "a short session goes in whole"
    (is (= "user: fix the parser\n\nassistant: done"
           (naming/suggestion-input [{:role "user" :text "fix the parser"}
                                     {:role "assistant" :text "done"}]))))
  (testing "a long one keeps the opening and the latest messages"
    (let [msgs  (into [{:role "user" :text "the opening request"}]
                      (for [i (range 200)]
                        {:role (if (even? i) "assistant" "user")
                         :text (str "message " i " " (apply str (repeat 1000 "x")))}))
          input (naming/suggestion-input msgs)]
      (is (str/starts-with? input "user: the opening request\n\n…"))
      (is (str/includes? input "message 199"))
      (is (< (count input) 5000)))))

(deftest parse-suggestions-strips-numbering-and-labels
  (is (= [{:name "Parser Fix" :style :short}
          {:name "Fixing the Flaky Parser Tests" :style :descriptive}
          {:name "Parser: Flaky Tests" :style :topic}]
         (naming/parse-suggestions
          "1. \"Parser Fix.\"\n\n2) descriptive: Fixing the Flaky Parser Tests\n- Parser: Flaky Tests\n4. Extra"))))

(deftest suggest-prompt-excludes-shown-names
  (is (str/includes? (naming/suggest-prompt "x" ["A" "B"]) "Don't repeat any of these: A; B."))
  (is (not (str/includes? (naming/suggest-prompt "x" []) "Don't repeat"))))

(deftest suggest-name-replies-to-the-client
  (async done
    (let [prompts (atom [])
          fx (naming/create-fx
              {:anthropic {:start-turn!
                           (fn [opts]
                             (swap! prompts conj opts)
                             {:promise (js/Promise.resolve
                                        {:result-text "Parser Fix\nFixing the Parser Bug\nParser: Bugfix"
                                         :usage {:input_tokens 900 :cache_read_input_tokens 300
                                                 :output_tokens 20}
                                         :cost 0.0012})})}}
              {:session-conversation
               (fn [sid]
                 (is (= "s1" sid))
                 {:cwd "/tmp" :messages [{:role "user" :text "fix the parser"}]})})]
      ((:session/suggest-name fx)
       {:dispatch! (fn [{:keys [names stats] :as ev}]
                     (is (= {:type :session/name-suggested :client-id "c1" :session-id "s1"}
                            (select-keys ev [:type :client-id :session-id])))
                     (is (= ["Parser Fix" "Fixing the Parser Bug" "Parser: Bugfix"] (map :name names)))
                     (is (= {:input-tokens 1200 :output-tokens 20 :cost 0.0012}
                            (select-keys stats [:input-tokens :output-tokens :cost])))
                     (is (true? (:no-tools? (first @prompts))))
                     (is (str/includes? (:prompt (first @prompts)) "Don't repeat any of these: Old"))
                     (done))}
       {:client-id "c1" :session-id "s1" :exclude ["Old"]}))))

(deftest suggest-name-without-conversation-replies-empty
  (let [evs (atom [])
        fx  (naming/create-fx {:anthropic {:start-turn! (fn [_] (throw (js/Error. "no turn")))}}
                              {:session-conversation (fn [_] nil)})]
    ((:session/suggest-name fx) {:dispatch! #(swap! evs conj %)}
                                {:client-id "c1" :session-id "s1"})
    (is (= [[]] (map :names @evs)))))
