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

(deftest input-submit-keeps-the-sender
  ;; Typed input arrives as :input/submit and is re-dispatched as a prompt or
  ;; a command; the user the server stamped must survive that hop, or every
  ;; prompt would read as the server's own user's.
  (let [st (with-room)]
    (is (= [[:app/dispatch {:type :prompt/submit :room-id "r" :text "hi" :user "alice"}]]
           (:effects (handle st {:type :input/submit :room-id "r" :text "hi" :user "alice"}))))
    (is (= [[:app/dispatch {:type :command/run :room-id "r" :name "model" :args "opus" :user "alice"}]]
           (:effects (handle st {:type :input/submit :room-id "r" :text "/model opus" :user "alice"}))))
    (testing "through the image path too"
      (let [st (apply-events st {:type :ui/attach-image :room-id "r" :image {:data "x"}})]
        (is (= "alice" (-> (handle st {:type :input/submit :room-id "r" :text "look" :user "alice"})
                           :effects first second :user)))))
    (testing "and the whole way into the history entry"
      (let [{:keys [effects]} (handle st {:type :input/submit :room-id "r" :text "hi" :user "alice"})
            [_ prompt] (first effects)]
        (is (= "alice" (:user (first (history (:state (handle st prompt)))))))))))

(deftest command-handlers-learn-who-ran-them
  (let [seen    (atom nil)
        cmd     {:name "who" :handler (fn [_ ctx] (reset! seen ctx) nil)}
        run     (:command/run (commands/command-handlers [cmd]))
        st      (with-room)]
    (run st {:type :command/run :room-id "r" :name "who" :user "alice"})
    (is (= "alice" (:user @seen)) "the stamped sender")
    (run st {:type :command/run :room-id "r" :name "who"})
    (is (= "root" (:user @seen)) "no sender, nobody in the room: the process' own user")
    (run (assoc-in st [:rooms "r" :members] {"c1" {:user "bob"}})
         {:type :command/run :room-id "r" :name "who"})
    (is (= "bob" (:user @seen)) "no sender, one user in the room: that user")
    (run (assoc-in st [:rooms "r" :members] {"c1" {:user "bob"} "c2" {:user "dan"}})
         {:type :command/run :room-id "r" :name "who"})
    (is (= "root" (:user @seen)) "no sender, several users: not guessed")))

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

(deftest input-submit-with-model-applies-before-turn
  ;; A retry / edit-save from the web bubble menu rides a :model on the
  ;; resubmission — the user may have picked a new model after the original
  ;; turn, so the fork must run on the picked model, not the room's prior one.
  (let [{:keys [state effects]} (handle (with-room)
                                        {:type :input/submit :room-id "r"
                                         :text "retry me" :model "opencode/grok"})]
    (is (= "opencode/grok" (get-in state [:rooms "r" :agent :model]))
        "picked model applied to the room before the fork's turn")
    (is (= :zen (get-in state [:rooms "r" :agent :provider]))
        "provider re-routed for the new model")
    (is (= [[:app/dispatch {:type :prompt/submit :room-id "r" :text "retry me"}]]
           effects))))

(deftest input-submit-same-model-leaves-state
  ;; Resubmitting with the model the room already runs is a no-op on state —
  ;; only the effect fires, so we never rewrite the room needlessly. Assert on
  ;; the raw handler's result, since handle-event always normalizes :state to
  ;; the incoming state.
  (let [handler (:input/submit commands/handlers)
        {:keys [state effects]} (handler (with-room)
                                         {:type :input/submit :room-id "r"
                                          :text "retry me" :model "claude-sonnet-4-5"})]
    (is (nil? state) "unchanged model → no :state in result")
    (is (= [[:app/dispatch {:type :prompt/submit :room-id "r" :text "retry me"}]]
           effects))))

(deftest input-submit-command-with-model-applies
  ;; The model must apply even when the resubmission routes to a command.
  (let [{:keys [state effects]} (handle (with-room)
                                        {:type :input/submit :room-id "r"
                                         :text "/help" :model "opencode/grok"})]
    (is (= "opencode/grok" (get-in state [:rooms "r" :agent :model])))
    (is (= [[:app/dispatch {:type :command/run :room-id "r"
                            :name "help" :args nil}]]
           effects))))

;; ── commands ─────────────────────────────────────────────────────────────────

(deftest unknown-command-appends-status
  (let [{:keys [state]} (handle (with-room)
                                {:type :command/run :room-id "r" :name "nope"})]
    (is (= [{:kind :status :text "Unknown command: /nope"}] (history state)))))

(deftest mirrored-unknown-command-stays-silent
  ;; A :remote? command the client has no code for is a server-side extension
  ;; command (/commit, /kb, …); the client must not report it as unknown.
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
                                {:type :command/run :room-id "r" :name "prompt"})
        text (get-in state [:rooms "r" :ui :buffers :prompt :text])]
    ;; Claude rooms prepend a [CLAUDE_SYSTEM_PROMPT] note surfacing the
    ;; SDK-injected preset, ahead of the room's own system prompt.
    (is (str/includes? text "AGENTS content"))
    (is (str/includes? text "[CLAUDE_SYSTEM_PROMPT]"))
    (is (= :prompt (get-in state [:rooms "r" :ui :active-buffer])))))

(deftest tree-command-sets-flag
  (let [{:keys [state]} (handle (with-room)
                                {:type :command/run :room-id "r" :name "tree"})]
    (is (true? (get-in state [:rooms "r" :ui :tree-open?])))))

(deftest tree-navigate-truncates-history
  (let [st (apply-events (with-room)
                         {:type :prompt/submit :room-id "r" :text "hello"}
                         {:type :agent/text-delta :room-id "r" :text "world"}
                         {:type :agent/turn-end :room-id "r" :provider-session-id "cli-1"}
                         {:type :prompt/submit :room-id "r" :text "again"}
                         {:type :agent/text-delta :room-id "r" :text "reply"})
        ;; Navigate to index 2 (keep first 2 entries)
        {:keys [state]} (handle st {:type :tree/navigate :room-id "r" :index 2})]
    (is (= 2 (count (history state))))
    (is (nil? (get-in state [:rooms "r" :session :provider-session-id])))
    (is (= ["cli-1"] (get-in state [:rooms "r" :session :superseded-cli-session-ids]))
        "the dropped Claude session stays claimed so its transcript isn't listed as a duplicate")
    (is (true? (get-in state [:rooms "r" :session :inject-history?]))
        "flags the session so the next turn injects the truncated history")
    (is (nil? (get-in state [:rooms "r" :ui :tree-open?])))))

(deftest tree-navigate-accumulates-superseded-sessions
  (let [st (-> (apply-events (with-room)
                             {:type :prompt/submit :room-id "r" :text "hello"}
                             {:type :agent/turn-end :room-id "r" :provider-session-id "cli-1"})
               (assoc-in [:rooms "r" :session :superseded-cli-session-ids] ["cli-0"]))
        {:keys [state]} (handle st {:type :tree/navigate :room-id "r" :index 0})
        ;; A second fork before any new turn has nothing live to record.
        {again :state} (handle state {:type :tree/navigate :room-id "r" :index 0})]
    (is (= ["cli-0" "cli-1"] (get-in state [:rooms "r" :session :superseded-cli-session-ids])))
    (is (= ["cli-0" "cli-1"] (get-in again [:rooms "r" :session :superseded-cli-session-ids]))
        "no duplicate entries")))

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
      (is (nil? (get-in st' [:rooms "r" :ui :menu])))
      (is (nil? (get-in st' [:rooms "r" :ui :menu-stack]))))))

(deftest menu-open-clears-stack
  ;; A fresh root open wipes any leftover drill stack.
  (let [st (-> (with-room)
               (assoc-in [:rooms "r" :ui :menu] {:id :old})
               (assoc-in [:rooms "r" :ui :menu-stack] [{:id :ancient}]))
        st' (apply-events st {:type :ui/menu-open :room-id "r"
                              :menu {:id :palette}})]
    (is (= {:id :palette} (get-in st' [:rooms "r" :ui :menu])))
    (is (= [] (get-in st' [:rooms "r" :ui :menu-stack])))))

(deftest menu-push-with-no-active-acts-like-open
  (let [st (apply-events (with-room)
                         {:type :ui/menu-push :room-id "r" :menu {:id :model}})]
    (is (= {:id :model} (get-in st [:rooms "r" :ui :menu])))
    (is (nil? (:back? (get-in st [:rooms "r" :ui :menu]))))))

(deftest menu-push-drills-and-marks-back
  (let [st  (apply-events (with-room)
                          {:type :ui/menu-open :room-id "r" :menu {:id :palette}})
        st' (apply-events st
                          {:type :ui/menu-push :room-id "r" :menu {:id :model}})]
    (is (= :model (get-in st' [:rooms "r" :ui :menu :id])))
    (is (true? (get-in st' [:rooms "r" :ui :menu :back?])))
    (is (= [{:id :palette}] (get-in st' [:rooms "r" :ui :menu-stack])))))

(deftest menu-push-load-effect-fires
  (let [handler (get commands/handlers :ui/menu-push)
        result  (handler (with-room)
                         {:room-id "r"
                          :menu {:id :model :load [:models/fetch {:room-id "r"}]}})]
    (is (= [[:models/fetch {:room-id "r"}]] (:effects result)))
    ;; :load is stripped from the stored frame
    (is (nil? (get-in (:state result) [:rooms "r" :ui :menu :load])))))

(deftest menu-pop-restores-parent-then-closes
  (let [st  (-> (with-room)
                (apply-events {:type :ui/menu-open :room-id "r" :menu {:id :palette}})
                (apply-events {:type :ui/menu-push :room-id "r" :menu {:id :model}}))
        st1 (apply-events st {:type :ui/menu-pop :room-id "r"})]
    (is (= {:id :palette} (get-in st1 [:rooms "r" :ui :menu])))
    (is (= [] (get-in st1 [:rooms "r" :ui :menu-stack])))
    (let [st2 (apply-events st1 {:type :ui/menu-pop :room-id "r"})]
      (is (nil? (get-in st2 [:rooms "r" :ui :menu]))))))

(deftest menu-populate-fills-active-and-clears-loading
  (let [st  (apply-events (with-room)
                          {:type :ui/menu-push :room-id "r"
                           :menu {:id :model :loading? true}})
        st' (apply-events st
                          {:type :ui/menu-populate :room-id "r" :id :model
                           :menu {:items [{:label "gpt"}]}})]
    (is (= [{:label "gpt"}] (get-in st' [:rooms "r" :ui :menu :items])))
    (is (nil? (get-in st' [:rooms "r" :ui :menu :loading?])))))

(deftest menu-populate-ignores-stale-id
  ;; A late fetch must not clobber a menu the user drilled away from.
  (let [st  (apply-events (with-room)
                          {:type :ui/menu-open :room-id "r" :menu {:id :other}})
        st' (apply-events st
                          {:type :ui/menu-populate :room-id "r" :id :model
                           :menu {:items [{:label "gpt"}]}})]
    (is (= {:id :other} (get-in st' [:rooms "r" :ui :menu])))))

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

(deftest session-created-discards-in-flight-turn
  ;; /new or /clear mid-turn: the live provider turn must be discarded so the
  ;; LLM stops responding into the fresh session.
  (let [st (apply-events (with-room)
                         {:type :prompt/submit :room-id "r" :text "hi"}
                         {:type :agent/text-delta :room-id "r" :text "yo"})
        {:keys [effects]} (handle st {:type :session/created :room-id "r"
                                      :session {:id "sess-2"}})]
    (is (some #{[:provider/discard {:room-id "r"}]} effects)))
  (testing "idle room emits no discard"
    (let [{:keys [effects]} (handle (with-room)
                                    {:type :session/created :room-id "r"
                                     :session {:id "sess-2"}})]
      (is (not-any? #(= :provider/discard (first %)) (or effects []))))))

(deftest session-created-with-after-prompt
  (let [{:keys [effects]} (handle (with-room)
                                  {:type :session/created :room-id "r"
                                   :session {:id "s2"}
                                   :after-prompt "summary text"})]
    (is (= [[:app/dispatch {:type :prompt/submit :room-id "r" :text "summary text"
                            :collapsed-label "Summary"}]]
           effects))))

(deftest session-created-keep-history-flags-old-conversation
  ;; /truncate: the old conversation stays visible above a divider, flagged
  ;; :no-llm? so it is never replayed to the model.
  (let [st (apply-events (with-room)
                         {:type :prompt/submit :room-id "r" :text "hi"}
                         {:type :agent/text-delta :room-id "r" :text "yo"})
        {:keys [state]} (handle st {:type :session/created :room-id "r"
                                    :session {:id "sess-2"}
                                    :keep-history? true})
        h (history state)]
    (is (= "sess-2" (get-in state [:rooms "r" :session :id])))
    (is (= 3 (count h)) "user + assistant + divider")
    (is (every? :no-llm? (butlast h)))
    (is (= :status (:kind (last h))))
    (is (str/includes? (:text (last h)) "truncated"))))

(deftest messages->history-recovers-collapse-label
  ;; A skill/command/summary prompt persists a hidden collapse marker in its
  ;; transcript text (xi.agent begin-turn); on resume the label is rebuilt and
  ;; the marker stripped, so the block re-collapses instead of showing expanded.
  (is (= [{:kind :user :text "do the thing" :collapsed-label "/commit"}]
         (commands/messages->history
          [{:type :text :role "user"
            :text "<!--xi:collapse=/commit-->\ndo the thing"}])))
  ;; Plain user prompts are untouched (no marker → no label).
  (is (= [{:kind :user :text "just a question"}]
         (commands/messages->history
          [{:type :text :role "user" :text "just a question"}]))))

(deftest messages->history-pre-truncation-blocks
  ;; Blocks from a /truncate ancestor become :no-llm? entries; the divider
  ;; block becomes a status line between ancestor and descendant.
  (let [h (commands/messages->history
           [{:type :text :role "user" :text "old q" :pre-truncation? true}
            {:type :tool-use :tool-use-id "t1" :name "bash"
             :arguments {:command "ls"} :pre-truncation? true}
            {:type :tool-result :tool-use-id "t1" :content "files"
             :is-error false :pre-truncation? true}
            {:type :truncation-divider}
            {:type :text :role "user" :text "new q"}])]
    (is (= [{:kind :user :text "old q" :no-llm? true}
            {:kind :tool-call :id "t1" :tool "bash" :arguments {:command "ls"}
             :status :done :result "files" :is-error false :no-llm? true}
            (commands/status-entry commands/truncation-divider-text)
            {:kind :user :text "new q"}]
           h))))

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
    (is (= {:kind :user :text "question"} (nth h 0))
        "no \"Resumed: …\" status line — the loaded blocks speak for themselves")
    (is (= {:kind :text :text "answer" :done? true} (nth h 1)))
    (is (= {:kind :tool-call :id "t1" :tool "bash" :arguments {:command "ls"}
            :status :done :result "files" :is-error false}
           (nth h 2)))
    (is (= 3 (count h)))
    (is (= "cli-1" (get-in state [:rooms "r" :session :provider-session-id]))
        "provider session id mirrored for resume")))

(deftest messages->history-reattaches-images
  ;; In the transcript the user's text block comes first, then the image
  ;; blocks of the same message. They must reattach (with data) to that
  ;; preceding user entry so a resumed conversation shows the pictures.
  (let [h (commands/messages->history
           [{:type :text :role "user"
             :text "see images\n[Attached image: /tmp/a.jpg]"}
            {:type :image :media-type "image/jpeg" :data "AAAA"}
            {:type :image :media-type "image/png" :data "BBBB"}])]
    (is (= [{:kind :user :text "see images"
             :images [{:media-type "image/jpeg" :data "AAAA"}
                      {:media-type "image/png" :data "BBBB"}]
             :image-count 2}]
           h)
        "images carry base64 data and the [Attached image: …] ref is stripped"))
  (testing "image-only message keeps a clean (empty) bubble"
    (is (= [{:kind :user :text ""
             :images [{:media-type "image/jpeg" :data "AAAA"}]
             :image-count 1}]
           (commands/messages->history
            [{:type :text :role "user" :text "[Attached image: /tmp/a.jpg]"}
             {:type :image :media-type "image/jpeg" :data "AAAA"}]))))
  (testing "images attach to their own message, not the next turn"
    (is (= [{:kind :user :text "first"
             :images [{:media-type "image/png" :data "X"}] :image-count 1}
            {:kind :text :text "reply" :done? true}
            {:kind :user :text "second"}]
           (commands/messages->history
            [{:type :text :role "user" :text "first"}
             {:type :image :media-type "image/png" :data "X"}
             {:type :text :role "assistant" :text "reply"}
             {:type :text :role "user" :text "second"}])))))

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

;; ── /allow /deny ─────────────────────────────────────────────────────────────

(defn- with-dialog [st dialog]
  (assoc-in st [:rooms "r" :ui :dialogs] [dialog]))

(deftest allow-deny-answer-the-pending-confirm
  (let [st (with-dialog (with-room) {:id "dlg-1" :type :confirm :options [:yes :no :always :allow-repo]})
        run (fn [name args] (:effects (handle st {:type :command/run :room-id "r" :name name :args args})))
        answered (fn [value] [[:app/dispatch {:type :ui/dialog-response :room-id "r"
                                              :dialog-id "dlg-1" :value value}]])]
    (is (= (answered true) (run "allow" nil)))
    (is (= (answered true) (run "a" nil)) "/a aliases /allow")
    (is (= (answered :always) (run "a" "a")))
    (is (= (answered :always) (run "allow" "always")))
    (is (= (answered :repo) (run "a" "r")))
    (is (= (answered false) (run "deny" nil)))
    (is (= (answered false) (run "d" nil)))
    (testing "/deny <reason> carries the reason for the model"
      (is (= [[:app/dispatch {:type :ui/dialog-response :room-id "r" :dialog-id "dlg-1"
                              :value false :reason "use the test db"}]]
             (run "deny" "  use the test db "))))))

(deftest allow-reports-when-nothing-to-answer
  (let [st (:state (handle (with-room) {:type :command/run :room-id "r" :name "allow"}))]
    (is (= "No pending permission request." (:text (peek (history st))))))
  (let [st (with-dialog (with-room) {:id "dlg-1" :type :confirm})
        st (:state (handle st {:type :command/run :room-id "r" :name "a" :args "zzz"}))]
    (is (str/includes? (:text (peek (history st))) "Unknown /allow option"))))
