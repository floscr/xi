(ns xi.commands-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.agent :as agent]
            [xi.commands :as commands]
            [xi.core.events :as events]
            [xi.core.state :as state]))

(def all-handlers
  (merge events/core-handlers agent/handlers commands/handlers))

(defn- handle [st event]
  (events/handle-event all-handlers st event))

(defn- apply-events [st & evs]
  (reduce #(:state (handle %1 %2)) st evs))

(defn- with-room []
  (apply-events (state/initial-state)
                {:type :room/create :room-id "r"
                 :room {:model "claude-sonnet-4-5" :cwd "/tmp"
                        :system "AGENTS content"
                        :session {:id "sess-1"}}}))

(defn- history [st] (:history (state/get-room st "r")))

;; ── parse-input ──────────────────────────────────────────────────────────────

(deftest parse-input-variants
  (is (nil? (commands/parse-input "")))
  (is (nil? (commands/parse-input "   ")))
  (is (= {:type :prompt :text "hello"} (commands/parse-input "hello")))
  (is (= {:type :command :name "model" :args nil} (commands/parse-input "/model")))
  (is (= {:type :command :name "model" :args "opus"} (commands/parse-input "/model opus")))
  (testing "absolute paths are prompts, not commands"
    (is (= :prompt (:type (commands/parse-input "/home/user/file.txt explain this"))))))

;; ── input routing ────────────────────────────────────────────────────────────

(deftest input-submit-routes-prompt
  (let [{:keys [effects]} (handle (with-room)
                                  {:type :input/submit :room-id "r" :text "hi there"})]
    (is (= [[:app/dispatch {:type :prompt/submit :room-id "r" :text "hi there"}]]
           effects))))

(deftest input-submit-routes-command
  (let [{:keys [effects]} (handle (with-room)
                                  {:type :input/submit :room-id "r" :text "/model opus"})]
    (is (= [[:app/dispatch {:type :command/run :room-id "r"
                            :name "model" :args "opus"}]]
           effects))))

(deftest input-submit-with-pending-images
  (let [st (apply-events (with-room)
                         {:type :ui/attach-image :room-id "r" :image {:data "x"}})
        {:keys [state effects]} (handle st {:type :input/submit :room-id "r" :text "look"})
        [[fx-type payload]] effects]
    (is (= :image/process fx-type))
    (is (= "look" (:text payload)))
    (is (= [{:data "x"}] (:images payload)))
    (is (empty? (get-in state [:rooms "r" :ui :pending-images]))
        "pending images consumed")))

(deftest input-submit-images-only-sends-nil-text
  (let [st (apply-events (with-room)
                         {:type :ui/attach-image :room-id "r" :image {:data "x"}})
        {:keys [effects]} (handle st {:type :input/submit :room-id "r" :text ""})
        [[fx-type payload]] effects]
    (is (= :image/process fx-type))
    (is (nil? (:text payload))
        "no placeholder text — provider sends only the image blocks")
    (is (= [{:data "x"}] (:images payload)))))

(deftest input-submit-empty-noop
  (let [{:keys [state effects]} (handle (with-room)
                                        {:type :input/submit :room-id "r" :text "  "})]
    (is (empty? effects))
    (is (= [] (history state)))))

;; ── commands ─────────────────────────────────────────────────────────────────

(deftest unknown-command-appends-status
  (let [{:keys [state]} (handle (with-room)
                                {:type :command/run :room-id "r" :name "nope"})]
    (is (= [{:kind :status :text "Unknown command: /nope"}] (history state)))))

(deftest mirrored-unknown-command-stays-silent
  ;; A :remote? command the client has no code for is a server-side extension
  ;; command (/commit, /gtd, …); the client must not report it as unknown.
  (let [{:keys [state]} (handle (with-room)
                                {:type :command/run :room-id "r" :name "commit" :remote? true})]
    (is (= [] (history state)))))

(deftest model-command-sets-model
  (let [{:keys [state]} (handle (with-room)
                                {:type :command/run :room-id "r"
                                 :name "model" :args "opus"})]
    (is (= "opus" (get-in state [:rooms "r" :agent :model])))
    (is (= :status (:kind (peek (history state)))))))

(deftest model-command-bare-fetches-models
  (let [{:keys [effects]} (handle (with-room)
                                  {:type :command/run :room-id "r" :name "model"})]
    (is (= [[:models/fetch {:room-id "r"}]] effects))))

(deftest resume-command-parses-scope
  (let [cwd-fx (:effects (handle (with-room)
                                 {:type :command/run :room-id "r"
                                  :name "resume" :args "3"}))
        all-fx (:effects (handle (with-room)
                                 {:type :command/run :room-id "r"
                                  :name "resume" :args "all:2"}))]
    (is (= [[:session/load {:room-id "r" :scope :cwd :index 3}]] cwd-fx))
    (is (= [[:session/load {:room-id "r" :scope :all :index 2}]] all-fx))))

(deftest resume-command-bare-lists-sessions
  (let [{:keys [effects]} (handle (with-room)
                                  {:type :command/run :room-id "r" :name "resume"})]
    (is (= [[:session/list {:room-id "r"}]] effects))))

(deftest prompt-command-opens-buffer
  (let [{:keys [state]} (handle (with-room)
                                {:type :command/run :room-id "r" :name "prompt"})]
    (is (= "AGENTS content" (get-in state [:rooms "r" :ui :buffers :prompt :text])))
    (is (= :prompt (get-in state [:rooms "r" :ui :active-buffer])))))

(deftest tree-command-sets-flag
  (let [{:keys [state]} (handle (with-room)
                                {:type :command/run :room-id "r" :name "tree"})]
    (is (true? (get-in state [:rooms "r" :ui :tree-open?])))))

(deftest tree-navigate-truncates-history
  (let [st (apply-events (with-room)
                         {:type :prompt/submit :room-id "r" :text "hello"}
                         {:type :agent/text-delta :room-id "r" :text "world"}
                         {:type :agent/turn-end :room-id "r"}
                         {:type :prompt/submit :room-id "r" :text "again"}
                         {:type :agent/text-delta :room-id "r" :text "reply"})
        ;; Navigate to index 2 (keep first 2 entries)
        {:keys [state]} (handle st {:type :tree/navigate :room-id "r" :index 2})]
    (is (= 2 (count (history state))))
    (is (nil? (get-in state [:rooms "r" :session :provider-session-id])))
    (is (true? (get-in state [:rooms "r" :session :inject-history?]))
        "flags the session so the next turn injects the truncated history")
    (is (nil? (get-in state [:rooms "r" :ui :tree-open?])))))

(deftest tree-close-clears-flag
  (let [st (apply-events (with-room)
                         {:type :command/run :room-id "r" :name "tree"})
        {:keys [state]} (handle st {:type :tree/close :room-id "r"})]
    (is (nil? (get-in state [:rooms "r" :ui :tree-open?])))))

(deftest help-command-lists-commands
  (let [{:keys [state]} (handle (with-room)
                                {:type :command/run :room-id "r" :name "help"})
        text (:text (peek (history state)))]
    (is (str/includes? text "/resume"))
    (is (str/includes? text "/truncate"))))

(deftest clear-and-new-emit-session-new
  (is (= [[:session/new {:room-id "r"}]]
         (:effects (handle (with-room) {:type :command/run :room-id "r" :name "clear"}))))
  (is (= [[:session/new {:room-id "r" :save-current? true}]]
         (:effects (handle (with-room) {:type :command/run :room-id "r" :name "new"})))))

;; ── menus ────────────────────────────────────────────────────────────────────

(deftest menu-open-close
  (let [menu {:id :model :items [{:label "a"}]}
        st (apply-events (with-room) {:type :ui/menu-open :room-id "r" :menu menu})]
    (is (= menu (get-in st [:rooms "r" :ui :menu])))
    (let [st' (apply-events st {:type :ui/menu-close :room-id "r"})]
      (is (nil? (get-in st' [:rooms "r" :ui :menu]))))))

;; ── session lifecycle ────────────────────────────────────────────────────────

(deftest session-created-resets-room
  (let [st (apply-events (with-room)
                         {:type :prompt/submit :room-id "r" :text "hi"}
                         {:type :agent/text-delta :room-id "r" :text "yo"})
        {:keys [state]} (handle st {:type :session/created :room-id "r"
                                    :session {:id "sess-2"}})]
    (is (= [] (history state)))
    (is (= "sess-2" (get-in state [:rooms "r" :session :id])))
    (is (false? (get-in state [:rooms "r" :agent :busy?])))))

(deftest session-created-with-after-prompt
  (let [{:keys [effects]} (handle (with-room)
                                  {:type :session/created :room-id "r"
                                   :session {:id "s2"}
                                   :after-prompt "summary text"})]
    (is (= [[:app/dispatch {:type :prompt/submit :room-id "r" :text "summary text"}]]
           effects))))

(deftest session-resumed-rebuilds-history
  (let [messages [{:type :text :role "user" :text "question"}
                  {:type :text :role "assistant" :text "answer"}
                  {:type :tool-use :tool-use-id "t1" :name "bash"
                   :arguments {:command "ls"}}
                  {:type :tool-result :tool-use-id "t1" :content "files" :is-error false}]
        {:keys [state]} (handle (with-room)
                                {:type :session/resumed :room-id "r"
                                 :session {:id "old" :cli-session-id "cli-1" :name "My session"}
                                 :summary {:source :xi}
                                 :messages messages})
        h (history state)]
    (is (= :status (:kind (first h))))
    (is (str/includes? (:text (first h)) "My session"))
    (is (= {:kind :user :text "question"} (nth h 1)))
    (is (= {:kind :text :text "answer" :done? true} (nth h 2)))
    (is (= {:kind :tool-call :id "t1" :tool "bash" :arguments {:command "ls"}
            :status :done :result "files" :is-error false}
           (nth h 3)))
    (is (= "cli-1" (get-in state [:rooms "r" :session :provider-session-id]))
        "provider session id mirrored for resume")))

(deftest messages->history-attaches-image-counts
  (let [h (commands/messages->history
           [{:type :image}
            {:type :image}
            {:type :text :role "user" :text "see images"}])]
    (is (= [{:kind :user :text "see images" :image-count 2}] h))))

;; ── turn-end session sync (chained) ──────────────────────────────────────────

(deftest turn-end-chain-syncs-session
  (let [chained (events/chain (get agent/handlers :agent/turn-end)
                              commands/turn-end-session-sync)
        st (apply-events (with-room) {:type :prompt/submit :room-id "r" :text "hi"})
        {:keys [state effects]} (chained st {:type :agent/turn-end :room-id "r"
                                             :provider-session-id "cli-9"})]
    (is (false? (get-in state [:rooms "r" :agent :busy?])))
    (is (= "cli-9" (get-in state [:rooms "r" :session :provider-session-id])))
    (is (some #{[:session/sync {:room-id "r"}]} effects))))

(deftest turn-end-chain-no-sync-without-session-id
  (let [chained (events/chain (get agent/handlers :agent/turn-end)
                              commands/turn-end-session-sync)
        st (apply-events (with-room) {:type :prompt/submit :room-id "r" :text "hi"})
        {:keys [effects]} (chained st {:type :agent/turn-end :room-id "r"})]
    (is (not (some #(= :session/sync (first %)) effects)))))
