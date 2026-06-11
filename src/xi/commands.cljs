(ns xi.commands
  "Slash commands as pure event handlers.

   Flow:
     :input/submit  ─► parse ─► :prompt/submit (via :app/dispatch)
                              │  or :image/process when images are pending
                              └► :command/run {:name :args}
     :command/run   ─► registry lookup ─► {:state :effects}

   Command handlers have the same contract as event handlers:
     (fn [state {:keys [room-id args]}]) → {:state :effects} | nil

   Everything here is pure — filesystem/network work happens in effects
   (see xi.fx): :session/list, :session/load, :session/new, :session/sync,
   :image/process, :models/fetch, :diff/load, plus the TUI-owned :app/quit,
   :app/reload and :clipboard/copy.

   History gets a new entry kind here: {:kind :status :text ...} — command
   output and status lines, rendered dim by the TUI."
  (:require [clojure.string :as str]
            [xi.core.state :as state]))

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn status-entry [text]
  {:kind :status :text text})

(defn- append-history [st room-id entry]
  (update-in st [:rooms room-id :history] conj entry))

(defn- status
  "Result map that just appends a status line to the room history."
  [st room-id text]
  {:state (append-history st room-id (status-entry text))})

;; ── Input parsing (pure) ─────────────────────────────────────────────────────

(defn parse-input
  "Parse user input text. Returns
     {:type :prompt :text ...} | {:type :command :name ... :args ...} | nil."
  [input]
  (let [text (str/trim (or input ""))]
    (cond
      (empty? text)
      nil

      (not (str/starts-with? text "/"))
      {:type :prompt :text text}

      ;; First word contains another / → absolute path, not a command
      (str/includes? (subs (first (str/split text #"\s" 2)) 1) "/")
      {:type :prompt :text text}

      :else
      (let [parts (str/split text #"\s+" 2)]
        {:type :command
         :name (subs (first parts) 1)
         :args (when (second parts) (str/trim (second parts)))}))))

;; ── Resumed-session messages → history entries (pure) ────────────────────────

(defn messages->history
  "Convert session blocks (xi.session/read-session-messages) into history
   entries (see xi.agent). Tool results are folded into their tool-call;
   images attach a count to the following user message."
  [messages]
  (let [results-by-id (into {}
                            (comp (filter #(= :tool-result (:type %)))
                                  (map (juxt :tool-use-id identity)))
                            messages)]
    (loop [ms (seq messages) out [] img-count 0]
      (if-not ms
        out
        (let [block (first ms)]
          (case (:type block)
            :image
            (recur (next ms) out (inc img-count))

            :text
            (case (:role block)
              "user"
              (recur (next ms)
                     (conj out (cond-> {:kind :user :text (:text block)}
                                 (pos? img-count) (assoc :image-count img-count)))
                     0)
              "assistant"
              (recur (next ms)
                     (conj out {:kind :text :text (:text block) :done? true})
                     img-count)
              (recur (next ms) out img-count))

            :tool-use
            (let [result (get results-by-id (:tool-use-id block))]
              (recur (next ms)
                     (conj out (cond-> {:kind      :tool-call
                                        :id        (:tool-use-id block)
                                        :tool      (:name block)
                                        :arguments (:arguments block)
                                        :status    (if (:is-error result) :error :done)}
                                 result (assoc :result (:content result)
                                               :is-error (boolean (:is-error result)))))
                     img-count))

            ;; :tool-result rendered inline with :tool-use; unknown → skip
            (recur (next ms) out img-count)))))))

;; ── Command handlers ─────────────────────────────────────────────────────────

(defn- cmd-help
  "Lists every command available in this assembly. The merged command
   vector (built-ins + extension commands) is threaded in via ctx :commands
   so /help reflects whatever extensions were composed at startup."
  [st {:keys [room-id commands]}]
  (status st room-id
          (str "Commands:\n"
               (str/join "\n"
                         (map (fn [{:keys [name description]}]
                                (str "  /" name
                                     (when description (str " — " description))))
                              commands)))))

(defn- cmd-quit [_st _ctx]
  {:effects [[:app/quit {}]]})

(defn- cmd-reload [st {:keys [room-id]}]
  {:effects [[:app/reload {:session-id (get-in st [:rooms room-id :session :id])}]]})

(defn- cmd-model [st {:keys [room-id args]}]
  (if (seq args)
    {:state (-> st
                (assoc-in [:rooms room-id :agent :model] args)
                (append-history room-id (status-entry (str "Model set to: " args))))}
    {:effects [[:models/fetch {:room-id room-id}]]}))

(defn- cmd-resume [st {:keys [room-id args]}]
  (if (nil? args)
    {:effects [[:session/list {:room-id room-id}]]}
    (let [[scope idx-str] (if (str/starts-with? args "all:")
                            [:all (subs args 4)]
                            [:cwd args])
          n (js/parseInt idx-str 10)]
      (if (js/isNaN n)
        (status st room-id (str "Invalid session: " args))
        {:effects [[:session/load {:room-id room-id :scope scope :index n}]]}))))

(defn- cmd-sessions [_st {:keys [room-id]}]
  {:effects [[:session/list {:room-id room-id}]]})

(defn- cmd-new [_st {:keys [room-id]}]
  {:effects [[:session/new {:room-id room-id :save-current? true}]]})

(defn- cmd-clear [_st {:keys [room-id]}]
  {:effects [[:session/new {:room-id room-id}]]})

(defn- cmd-compact [_st {:keys [room-id args]}]
  {:effects [[:app/dispatch {:type :compact/request :room-id room-id :focus args}]]})

(defn- cmd-prompt [st {:keys [room-id]}]
  (let [text (or (get-in st [:rooms room-id :agent :system])
                 "(no AGENTS.md found)")]
    {:state (-> st
                (assoc-in [:rooms room-id :ui :buffers :prompt]
                          {:title "System Prompt" :text text})
                (assoc-in [:rooms room-id :ui :active-buffer] :prompt))}))

(defn- cmd-events [_st {:keys [room-id]}]
  {:effects [[:events/load {:room-id room-id}]]})

(defn- cmd-diff
  "Open the diff viewer. Subcommands ride in args: git | staged | unstaged;
   nil → session diff; anything else is passed to git diff directly."
  [_st {:keys [room-id args]}]
  {:effects [[:diff/load {:room-id room-id :args args}]]})

(defn- cmd-buffers [st {:keys [room-id]}]
  (let [room (state/get-room st room-id)
        active (get-in room [:ui :active-buffer])
        item (fn [label buffer-id]
               {:label label
                :description (when (= buffer-id active) "• active")
                :event {:type :ui/buffer-switch :room-id room-id :buffer-id buffer-id}})
        items (cond-> [(item "Chat" :chat)
                       (item "Logs" :logs)]
                (get-in room [:ui :buffers :prompt])
                (conj (item "Prompt" :prompt))
                (get-in room [:ui :buffers :diff])
                (conj (item "Diff" :diff)))]
    {:state (assoc-in st [:rooms room-id :ui :menu]
                      {:id :buffers :prompt "buffer> " :items items})}))

(defn- cmd-debug [st {:keys [room-id]}]
  (let [room (state/get-room st room-id)
        {:keys [model effort busy?]} (:agent room)
        sess (:session room)
        text (str "# Xi Debug Info\n\n"
                  "## Runtime\n"
                  "- Model: " (or model "(none)") "\n"
                  "- Effort: " (or effort "(default)") "\n"
                  "- CWD: " (:cwd room) "\n"
                  "- Busy: " (boolean busy?) "\n"
                  "- Provider: " (str (get-in room [:agent :provider])) "\n"
                  "\n## Session\n"
                  "- Xi ID: " (or (:id sess) "(none)") "\n"
                  "- Provider Session ID: " (or (:provider-session-id sess) "(none)") "\n"
                  "- Name: " (or (:name sess) "(unnamed)") "\n"
                  "\n## History\n"
                  "- Entries: " (count (:history room)))]
    {:state   (:state (status st room-id "Debug info copied to clipboard."))
     :effects [[:clipboard/copy {:text text}]]}))

(def built-in-commands
  "Built-in commands. Each :handler is (fn [state {:keys [room-id args]}])."
  [{:name "help"     :description "Show available commands"            :handler cmd-help}
   {:name "model"    :description "Show or set model"                  :handler cmd-model}
   {:name "resume"   :description "Resume a previous session"          :handler cmd-resume}
   {:name "sessions" :description "List previous sessions"             :handler cmd-sessions}
   {:name "new"      :description "Start a new session"                :handler cmd-new}
   {:name "clear"    :description "Clear current session"              :handler cmd-clear}
   {:name "compact"  :description "Summarize conversation to reduce context" :handler cmd-compact}
   {:name "prompt"   :description "Show system prompt"                 :handler cmd-prompt}
   {:name "diff"     :description "Show diff viewer (git|staged|unstaged|<ref>)" :handler cmd-diff}
   {:name "events"   :description "Show event log for this session"     :handler cmd-events}
   {:name "buffers"  :description "Switch buffer view"                 :handler cmd-buffers}
   {:name "debug"    :description "Copy debug info to clipboard"       :handler cmd-debug}
   {:name "reload"   :description "Restart Xi (picks up recompiled code)" :handler cmd-reload}
   {:name "quit"     :description "Exit Xi"                            :handler cmd-quit}])

;; ── Event handlers ───────────────────────────────────────────────────────────

(defn- input-submit
  "Raw editor submission — route to a command or a prompt. Pending images
   (room :ui :pending-images) ride along on prompts via :image/process.
   Event-level :images (from clipboard-image hook) merge with pending."
  [st {:keys [room-id text images]}]
  (when-let [room (state/get-room st room-id)]
    (let [parsed (parse-input text)
          images (into (vec (get-in room [:ui :pending-images])) images)]
      (cond
        (= :command (:type parsed))
        {:effects [[:app/dispatch {:type :command/run :room-id room-id
                                   :name (:name parsed) :args (:args parsed)}]]}

        ;; Prompt — possibly images-only (placeholder text)
        (or parsed (seq images))
        (let [prompt-text (or (:text parsed)
                              (if (= 1 (count images))
                                "[Attached image]"
                                (str "[Attached " (count images) " images]")))]
          (if (seq images)
            {:state   (assoc-in st [:rooms room-id :ui :pending-images] [])
             :effects [[:image/process {:room-id room-id :text prompt-text
                                        :images images}]]}
            {:effects [[:app/dispatch {:type :prompt/submit :room-id room-id
                                       :text prompt-text}]]}))

        :else nil))))

(defn- make-command-run
  "Build a :command/run handler closed over the merged command vector
   (built-ins + extension commands). Each command handler is invoked with
   the full :commands list in its ctx so e.g. /help can enumerate them."
  [commands]
  (let [by-name (into {} (map (juxt :name identity)) commands)]
    (fn command-run [st {:keys [room-id name args]}]
      (when (state/get-room st room-id)
        (if-let [cmd (get by-name name)]
          ((:handler cmd) st {:room-id  room-id
                              :args     (when (seq args) args)
                              :commands commands})
          (status st room-id (str "Unknown command: /" name)))))))

(defn- ui-status [st {:keys [room-id text]}]
  (when (state/get-room st room-id)
    (status st room-id text)))

(defn- menu-open [st {:keys [room-id menu]}]
  (when (state/get-room st room-id)
    {:state (assoc-in st [:rooms room-id :ui :menu] menu)}))

(defn- menu-close [st {:keys [room-id]}]
  (when (get-in st [:rooms room-id :ui :menu])
    {:state (update-in st [:rooms room-id :ui] dissoc :menu)}))

(defn- buffer-open
  "Generic buffer open — install a named buffer and switch to it."
  [st {:keys [room-id buffer-id buffer]}]
  (when (state/get-room st room-id)
    {:state (-> st
                (assoc-in [:rooms room-id :ui :buffers buffer-id] buffer)
                (assoc-in [:rooms room-id :ui :active-buffer] buffer-id))}))

(defn- diff-open
  "Diff text came back from :diff/load — install it as the :diff buffer
   and switch to it. :diff? marks it for the TUI's interactive viewer."
  [st {:keys [room-id title text]}]
  (buffer-open st {:room-id room-id :buffer-id :diff
                   :buffer {:title title :text text :diff? true}}))

(defn- attach-image [st {:keys [room-id image label]}]
  (when (state/get-room st room-id)
    (let [st' (update-in st [:rooms room-id :ui :pending-images]
                         (fnil conj []) image)
          n (count (get-in st' [:rooms room-id :ui :pending-images]))]
      {:state (append-history st' room-id
                              (status-entry (str "📎 " (or label "image")
                                                 " (" n " attached)")))})))

(defn- clear-images [st {:keys [room-id]}]
  (when (state/get-room st room-id)
    (let [n (count (get-in st [:rooms room-id :ui :pending-images]))]
      {:state (-> st
                  (assoc-in [:rooms room-id :ui :pending-images] [])
                  (append-history room-id
                                  (status-entry (if (pos? n)
                                                  (str "Cleared " n " image(s)")
                                                  "No images to clear"))))})))

;; ── Session lifecycle handlers ───────────────────────────────────────────────

(defn- session-created
  "Fresh session installed (from /new, /clear or compaction): reset the
   room. :after-prompt (compaction summary) is re-submitted into the new
   session through the normal prompt path."
  [st {:keys [room-id session after-prompt]}]
  (when (state/get-room st room-id)
    (cond-> {:state (-> st
                        (assoc-in [:rooms room-id :session] session)
                        (assoc-in [:rooms room-id :history] [])
                        (update-in [:rooms room-id :agent]
                                   assoc :busy? false :queued []))}
      after-prompt
      (assoc :effects [[:app/dispatch {:type :prompt/submit :room-id room-id
                                       :text after-prompt}]]))))

(defn- session-resumed [st {:keys [room-id session summary messages]}]
  (when (state/get-room st room-id)
    (let [session' (assoc session :provider-session-id (:cli-session-id session))
          label (str "Resumed: "
                     (or (:name session) (:cli-session-id session) (:id session))
                     (case (:source summary) :claude " [claude]" :pi " [pi]" "")
                     " (" (count messages) " messages)"
                     (when (= :pi (:source session))
                       "\n  (read-only — Pi sessions can't be continued)"))]
      {:state (-> st
                  (assoc-in [:rooms room-id :session] session')
                  (assoc-in [:rooms room-id :history]
                            (into [(status-entry label)] (messages->history messages)))
                  (update-in [:rooms room-id :agent] assoc :busy? false :queued []))})))

(defn- session-updated
  "Persisted session came back from a save/touch effect — merge metadata."
  [st {:keys [room-id session]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :session] merge session)}))

;; ── Session sync on turn end (chained after xi.agent's handler) ──────────────

(defn turn-end-session-sync
  "Chained onto :agent/turn-end — persist the session once the provider
   reports a session id (xi.agent has already stored it on the room)."
  [st {:keys [room-id]}]
  (when (get-in st [:rooms room-id :session :provider-session-id])
    {:effects [[:session/sync {:room-id room-id}]]}))

(defn all-commands
  "Merge built-in commands with the extension-provided ones. Extension
   commands are appended so built-ins take precedence on name clashes
   (first match wins in make-command-run's by-name map)."
  ([] (all-commands nil))
  ([extra-commands] (into built-in-commands (or extra-commands []))))

(defn command-handlers
  "Build the command/event handler map for an assembly. extra-commands are
   extension-provided commands that join the built-ins for dispatch, /help
   and TUI completion."
  ([] (command-handlers nil))
  ([extra-commands]
   {:input/submit    input-submit
    :command/run     (make-command-run (all-commands extra-commands))
    :ui/status       ui-status
    :ui/menu-open    menu-open
    :ui/menu-close   menu-close
    :ui/buffer-open  buffer-open
    :ui/diff-open    diff-open
    :ui/attach-image attach-image
    :ui/clear-images clear-images
    :session/created session-created
    :session/resumed session-resumed
    :session/updated session-updated}))

(def handlers
  "Built-in-only command handlers (no extensions). Back-compat default;
   assemblies that compose extensions call (command-handlers extra)."
  (command-handlers))
