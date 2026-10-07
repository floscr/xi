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
   :image/process, :models/fetch, plus the TUI-owned :app/quit,
   :app/reload and :clipboard/copy.

   History gets a new entry kind here: {:kind :status :text ...} — command
   output and status lines, rendered dim by the TUI."
  (:require [clojure.string :as str]
            [xi.buffers :as buffers]
            [xi.core.state :as state]
            [xi.dialog :as dialog]
            [xi.util :as util]))

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn status-entry [text]
  {:kind :status :text text})

(def truncation-divider-text
  "── conversation truncated here — messages above are shown for reference only and are not sent to the model ──")

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

(def ^:private attachment-ref-re
  ;; The provider-only reference lines appended by
  ;; xi.agent/prompt-with-attachment-paths ("[Attached image: …]" /
  ;; "[Attached file: …]"). Stripped from the resumed bubble text so it stays
  ;; clean, matching the live (non-resumed) message.
  #"(?m)^\[Attached (?:image|file): .*\]$")

(defn- strip-attachment-refs [text]
  (some-> text
          (str/replace attachment-ref-re "")
          str/trimr))

(def ^:private cli-interrupt-marker-re
  ;; The user message the Claude CLI writes into its own transcript when a
  ;; turn is interrupted ("[Request interrupted by user]", "… for tool use]").
  ;; Not something anyone typed — dropped on resume, as the live turn never
  ;; showed it either (the :aborted history entry marks the interruption).
  #"^\s*\[Request interrupted by user[^\]]*\]\s*$")

(defn- cli-interrupt-marker? [text]
  (boolean (re-matches cli-interrupt-marker-re (str text))))

(defn messages->history
  "Convert session blocks (xi.session/read-session-messages) into history
   entries (see xi.agent). Tool results are folded into their tool-call;
   attached images (base64) are reattached to the user message they belong to
   (they follow that message's text block in the transcript) so a resumed
   conversation still shows the pictures."
  [messages]
  (let [results-by-id (into {}
                            (comp (filter #(= :tool-result (:type %)))
                                  (map (juxt :tool-use-id identity)))
                            messages)
        ;; Blocks read from a pre-truncation ancestor session are display-only:
        ;; tag their history entries :no-llm? so xi.agent never replays them.
        flag (fn [entry block]
               (cond-> entry (:pre-truncation? block) (assoc :no-llm? true)))
        ;; Attach buffered image blocks to the most recent user entry — in the
        ;; transcript they immediately follow that message's text block.
        flush-imgs (fn [out imgs]
                     (if (and (seq imgs) (seq out)
                              (= :user (:kind (peek out))))
                       (update out (dec (count out)) merge
                               {:images imgs :image-count (count imgs)})
                       out))]
    (loop [ms (seq messages) out [] imgs []]
      (if-not ms
        (flush-imgs out imgs)
        (let [block (first ms)]
          (case (:type block)
            :image
            (recur (next ms) out
                   (conj imgs {:media-type (:media-type block)
                               :data (:data block)}))

            :truncation-divider
            (recur (next ms)
                   (conj (flush-imgs out imgs) (status-entry truncation-divider-text))
                   [])

            :text
            (case (:role block)
              "user"
              ;; A skill/command/summary prompt carries a hidden collapse marker
              ;; (xi.agent begin-turn) in its transcript text; rebuild the
              ;; :collapsed-label from it and strip it so the entry reads clean.
              (let [raw   (:text block)
                    label (util/collapse-label raw)
                    text  (strip-attachment-refs (util/strip-collapse-marker raw))]
                (if (cli-interrupt-marker? raw)
                  (recur (next ms) out imgs)
                  (recur (next ms)
                         (conj (flush-imgs out imgs)
                               (flag (cond-> {:kind :user :text text}
                                       label (assoc :collapsed-label label))
                                     block))
                         [])))
              "assistant"
              (recur (next ms)
                     (conj (flush-imgs out imgs)
                           (flag {:kind :text :text (:text block) :done? true} block))
                     [])
              (recur (next ms) out imgs))

            :tool-use
            (let [out (flush-imgs out imgs)
                  result (get results-by-id (:tool-use-id block))]
              (recur (next ms)
                     (conj out (flag (cond-> {:kind      :tool-call
                                              :id        (:tool-use-id block)
                                              :tool      (:name block)
                                              :arguments (:arguments block)
                                              :status    (if (:is-error result) :error :done)}
                                       result (assoc :result (:content result)
                                                     :is-error (boolean (:is-error result))))
                                     block))
                     []))

            ;; :tool-result rendered inline with :tool-use; unknown → skip
            (recur (next ms) out imgs)))))))

;; ── Command handlers ─────────────────────────────────────────────────────────

(defn- cmd-help
  "Lists every command available in this assembly. The merged command
   vector (built-ins + extension commands) is threaded in via ctx :commands
   so /help reflects whatever extensions were composed at startup."
  [st {:keys [room-id commands]}]
  (status st room-id
          (str "Commands:\n"
               (str/join "\n"
                         (mapcat (fn [{:keys [name aliases description subcommands]}]
                                   (cons (str "  /" name
                                               (when (seq aliases)
                                                 (str " (" (str/join ", " (map #(str "/" %) aliases)) ")"))
                                               (when description (str " — " description)))
                                         (map (fn [{sub-name :name sub-desc :description}]
                                                (str "    " name " " sub-name
                                                     (when sub-desc (str " — " sub-desc))))
                                              subcommands)))
                                 commands)))))

(defn- cmd-answer
  "/allow [always|repo] and /deny [reason] — answer the room's pending
   permission confirm from the prompt, like its buttons/keys. A /deny reason
   is told to the model (xi.dialog/with-deny-reason). Dispatches the canonical
   :ui/dialog-response so the dialog owner resolves it and every client
   clears it (xi.client.ws-transport drops the dialog on the echo)."
  [verb]
  (fn [st {:keys [room-id args]}]
    (let [option (dialog/answer-option verb args)
          {:keys [dialog-id value error]}
          (if option
            (dialog/answer (state/get-room st room-id) option)
            {:error (str "Unknown /allow option \"" args "\" — use always (a) or repo (r).")})]
      (if error
        (status st room-id error)
        {:effects [[:app/dispatch (cond-> {:type :ui/dialog-response :room-id room-id
                                           :dialog-id dialog-id :value value}
                                    (and (= :deny verb) (not (str/blank? args)))
                                    (assoc :reason (str/trim args)))]]}))))

(defn- cmd-quit [_st _ctx]
  {:effects [[:app/quit {}]]})

(defn- cmd-reload [st {:keys [room-id]}]
  {:effects [[:app/reload {:session-id (get-in st [:rooms room-id :session :id])}]]})

(defn- cmd-model [st {:keys [room-id args user]}]
  (if (seq args)
    (let [provider (util/provider-for-model args)
          has-session? (get-in st [:rooms room-id :session :provider-session-id])]
      {:state (-> st
                  (assoc-in [:rooms room-id :agent :model] args)
                  (assoc-in [:rooms room-id :agent :provider] provider)
                  (append-history room-id (status-entry (str "Model set to: " args))))
       :effects (cond-> [[:model/persist-preferred {:model args :user user}]]
                  has-session? (conj [:session/sync {:room-id room-id}]))})
    {:effects [[:models/fetch {:room-id room-id}]]}))

(defn- cmd-resume [st {:keys [room-id args]}]
  (cond
    (nil? args)
    {:effects [[:session/list {:room-id room-id}]]}

    ;; `id:<session-id>` — resume a specific session (used by the palette Chats
    ;; section), not an index into the listing.
    (str/starts-with? args "id:")
    {:effects [[:session/load {:room-id room-id :scope :all :session-id (subs args 3)}]]}

    :else
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

(defn- cmd-fork [_st {:keys [room-id]}]
  {:effects [[:session/fork {:room-id room-id}]]})

(defn- cmd-compact [_st {:keys [room-id args]}]
  {:effects [[:app/dispatch {:type :compact/request :room-id room-id :focus args}]]})

(defn- cmd-summary [_st {:keys [room-id]}]
  {:effects [[:app/dispatch {:type :summary/request :room-id room-id}]]})

(def ^:private claude-preset-note
  (str "## [CLAUDE_SYSTEM_PROMPT]\n\n"
       "The Claude Code \"claude_code\" preset is injected by the Agent SDK "
       "ahead of everything below. Its text is supplied by the SDK and is not "
       "visible here; the parts that follow are appended after it."))

(defn- render-system-prompt
  "Render a room's system prompt for the buffer. Expanded? true shows the
   verbatim string that is actually inserted (the room's :agent :system);
   false shows a per-part overview clipped to a 200-char preview each.
   For Claude rooms a [CLAUDE_SYSTEM_PROMPT] header is prepended to surface
   the SDK-injected preset that precedes the appended parts."
  [st room-id expanded?]
  (let [parts   (get-in st [:rooms room-id :agent :system-parts])
        system  (get-in st [:rooms room-id :agent :system])
        claude? (= :anthropic (get-in st [:rooms room-id :agent :provider]))
        body    (cond
                  expanded?
                  (or system
                      (when (seq parts) (str/join "\n\n" (map :text parts)))
                      "(no system prompt)")

                  (seq parts)
                  (str/join "\n\n---\n\n"
                            (map (fn [{:keys [source text]}]
                                   (let [lines   (count (re-seq #"\n" (or text "")))
                                         preview (let [s (subs text 0 (min 200 (count text)))]
                                                   (if (< (count text) 200) s (str s "...")))]
                                     (str "## [" source "] (" lines " lines)\n\n" preview)))
                                 parts))

                  :else
                  (or system "(no system prompt)"))]
    (if claude?
      (str claude-preset-note "\n\n---\n\n" body)
      body)))

(defn- prompt-buffer [st room-id expanded?]
  {:kind      :prompt
   :title     (str "System Prompt — " (if expanded? "full (ctrl+o for overview)"
                                         "overview (ctrl+o for full)"))
   :text      (render-system-prompt st room-id expanded?)
   :expanded? expanded?})

(defn- cmd-prompt
  "Open the system-prompt buffer; only the client that ran /prompt switches to
   it (the buffer itself is shared, xi.buffers)."
  [st {:keys [room-id] :as ev}]
  {:state (-> st
              (update-in [:rooms room-id] buffers/install :prompt
                         (prompt-buffer st room-id false) (:event/ts ev))
              (cond-> (buffers/switch-here? st ev)
                (assoc-in [:rooms room-id :ui :active-buffer] :prompt)))})

(defn- prompt-toggle
  "Toggle full/preview rendering of the system-prompt buffer (ctrl+o)."
  [st {:keys [room-id] :as ev}]
  (when (get-in st [:rooms room-id :ui :buffers :prompt])
    (let [expanded? (not (get-in st [:rooms room-id :ui :buffers :prompt :expanded?]))]
      {:state (update-in st [:rooms room-id] buffers/install :prompt
                         (prompt-buffer st room-id expanded?) (:event/ts ev))})))

(defn- cmd-tree [st {:keys [room-id]}]
  (when (state/get-room st room-id)
    {:state (assoc-in st [:rooms room-id :ui :tree-open?] true)}))

(defn- cmd-events [_st {:keys [room-id]}]
  {:effects [[:events/load {:room-id room-id}]]})

(defn- cmd-buffers
  "The /buffers menu: the chat, the log, every open buffer of the room
   (xi.buffers — diffs, files, the system prompt, …) in opening order, the live
   sub-agents view, and — when any buffer is open — a line that closes them
   all. The switch is per client; the close drops them for everyone."
  [st {:keys [room-id]}]
  (let [room (state/get-room st room-id)
        active (get-in room [:ui :active-buffer])
        bufs   (get-in room [:ui :buffers])
        item (fn [label buffer-id]
               {:label label
                :description (when (= buffer-id active) "• active")
                :event {:type :ui/buffer-switch :room-id room-id :buffer-id buffer-id}})
        items (cond-> [(item "Chat" :chat)
                       (item "Logs" :logs)]
                :always
                (into (map (fn [[id buf]] (item (buffers/label id buf) id)))
                      (buffers/ordered bufs))
                ;; Live view over the subagent extension's room state (no
                ;; [:ui :buffers] entry — rendered from state each pass).
                (seq (get-in room [:ext :subagents :agents]))
                (conj (item "Subagents" :subagents))
                (seq bufs)
                (conj {:label "Close all buffers"
                       :description (str (count bufs))
                       :event {:type :ui/buffers-close-all :room-id room-id}}))]
    {:state (assoc-in st [:rooms room-id :ui :menu]
                      {:id :buffers :prompt "buffer> " :items items})}))

(defn- cmd-cd [st {:keys [room-id args]}]
  (let [cwd (get-in st [:rooms room-id :cwd])]
    (if (nil? args)
      (status st room-id (str "CWD: " cwd))
      {:effects [[:cwd/change {:room-id room-id :path args}]]})))

(defn debug-text
  "Build the debug info string from a room map."
  [room]
  (let [{:keys [model effort busy?]} (:agent room)
        sess (:session room)]
    (str "Session ID: " (or (:id sess) "(none)") "\n"
         "Use `xi_events` tool to look up the event log for this session.\n\n"
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
         "- Entries: " (count (:history room)))))

(defn- cmd-debug [st {:keys [room-id]}]
  (let [room (state/get-room st room-id)
        text (debug-text room)]
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
   {:name "fork"     :description "Split the conversation into a new session" :handler cmd-fork}
   {:name "truncate" :description "Summarize conversation to reduce context" :handler cmd-compact}
   {:name "summary"  :description "Describe what this session is about (cheap model)" :handler cmd-summary}
   {:name "prompt"   :description "Show system prompt"                 :handler cmd-prompt}
   {:name "tree"     :description "Navigate session history"             :handler cmd-tree}
   {:name "events"   :description "Show event log for this session"     :handler cmd-events}
   {:name "buffers"  :description "Switch buffer view"                 :handler cmd-buffers}
   {:name "cd"       :description "Change working directory"            :handler cmd-cd}
   {:name "debug"    :description "Copy debug info to clipboard"       :handler cmd-debug}
   {:name "allow"    :aliases ["a"]
    :description "Allow the pending permission request (always/a, repo/r)"
    :subcommands [{:name "always" :description "Allow and don't ask again"}
                  {:name "repo"   :description "Allow writes to this repo"}]
    :handler (cmd-answer :allow)}
   {:name "deny"     :aliases ["d"]
    :description "Deny the pending permission request (/deny <reason> tells the agent why)"
    :handler (cmd-answer :deny)}
   {:name "holds"    :description "Show who holds this room's shared resources (git index)"
    :handler (fn [_st {:keys [room-id]}] {:effects [[:holds/list {:room-id room-id}]]})}
   {:name "release"  :description "Force-release holds on this room's shared resources (git index)"
    :handler (fn [_st {:keys [room-id]}] {:effects [[:holds/release {:room-id room-id}]]})}
   {:name "reload"   :description "Restart Xi (picks up recompiled code)" :handler cmd-reload}
   {:name "quit"     :description "Exit Xi"                            :handler cmd-quit}])

;; ── Event handlers ───────────────────────────────────────────────────────────

(defn- input-submit
  "Raw editor submission — route to a command or a prompt. Pending images
   (room :ui :pending-images) ride along on prompts via :image/process.
   Event-level :images (from clipboard-image hook) merge with pending.

   An optional :model rides along on resubmissions (retry / edit-save from
   the web bubble menu): the user may have picked a new model after the
   original turn, so apply it to the room before the fork's turn starts —
   otherwise the fork would reuse the room's prior model."
  [st {:keys [room-id text images client-id model user]}]
  (when-let [room (state/get-room st room-id)]
    (let [model-change? (and model (not= model (get-in room [:agent :model])))
          st     (cond-> st
                   model-change?
                   (-> (assoc-in [:rooms room-id :agent :model] model)
                       (assoc-in [:rooms room-id :agent :provider]
                                 (util/provider-for-model model))))
          parsed (parse-input text)
          images (into (vec (get-in room [:ui :pending-images])) images)]
      (cond
        (= :command (:type parsed))
        (cond-> {:effects [[:app/dispatch (cond-> {:type :command/run :room-id room-id
                                                   :name (:name parsed) :args (:args parsed)}
                                            client-id (assoc :client-id client-id)
                                            user      (assoc :user user))]]}
          model-change? (assoc :state st))

        ;; Prompt — possibly images-only (nil text → provider omits the
        ;; empty text content block; only image blocks are sent).
        (or parsed (seq images))
        (let [prompt-text (:text parsed)]
          (if (seq images)
            {:state   (assoc-in st [:rooms room-id :ui :pending-images] [])
             :effects [[:image/process (cond-> {:room-id room-id :text prompt-text
                                                :images images}
                                         user (assoc :user user))]]}
            (cond-> {:effects [[:app/dispatch (cond-> {:type :prompt/submit :room-id room-id
                                                       :text prompt-text}
                                                user (assoc :user user))]]}
              model-change? (assoc :state st))))

        :else nil))))

(defn- make-command-run
  "Build a :command/run handler closed over the merged command vector
   (built-ins + extension commands). Each command handler is invoked with
   the full :commands list in its ctx so e.g. /help can enumerate them."
  [commands]
  (let [by-name (into {} (mapcat (fn [cmd]
                                   (map #(vector % cmd) (cons (:name cmd) (:aliases cmd)))))
                      commands)]
    (fn command-run [st {:keys [room-id name args remote? client-id] :as ev}]
      (when (state/get-room st room-id)
        (if-let [cmd (get by-name name)]
          ((:handler cmd) st {:room-id   room-id
                              :args      (when (seq args) args)
                              :commands  commands
                              :client-id client-id
                              ;; who ran it: the sender the server stamped,
                              ;; else the sole user in the room, else this
                              ;; process' own user (state/event-user)
                              :user      (state/event-user st ev)})
          ;; Mirrored (:remote?) command the client has no code for — it's a
          ;; server-side extension command (/commit, /kb, …). The server ran
          ;; the real work and broadcasts the resulting events separately, so
          ;; the client must stay silent; only a client that is authoritative
          ;; (standalone/server) reports a genuinely unknown command.
          (when-not remote?
            (status st room-id (str "Unknown command: /" name))))))))

(defn- ui-status [st {:keys [room-id text]}]
  (when (state/get-room st room-id)
    (status st room-id text)))

(defn- editor-insert
  "Insert text at the editor cursor (e.g. from a completion menu selection)."
  [_st {:keys [text]}]
  (when (seq text)
    {:effects [[:editor/insert-text {:text text}]]}))

(defn- menu-open
  "Open a menu as a fresh root frame — clears any existing drill stack."
  [st {:keys [room-id menu]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :ui] assoc
                       :menu menu :menu-stack [])}))

(defn- menu-push
  "Push a menu frame. If a menu is already active, it is pushed onto the
   stack and the new frame is marked :back? so the renderer shows a back
   affordance; Esc/cancel pops back to it. If no menu is active this behaves
   like menu-open (so typed commands that open a select-menu still work
   standalone). A frame's optional :load effect is fired to fetch async data."
  [st {:keys [room-id menu]}]
  (when (state/get-room st room-id)
    (let [active (get-in st [:rooms room-id :ui :menu])
          load  (:load menu)
          frame (cond-> (dissoc menu :load)
                  active (assoc :back? true))]
      (cond-> {:state (-> st
                          (cond-> active
                            (update-in [:rooms room-id :ui :menu-stack] (fnil conj []) active))
                          (assoc-in [:rooms room-id :ui :menu] frame))}
        load (assoc :effects [load])))))

(defn- menu-pop
  "Pop one frame off the drill stack, restoring the parent as active. If the
   stack is empty, close the menu entirely. Universal back-navigation."
  [st {:keys [room-id]}]
  (when (get-in st [:rooms room-id :ui :menu])
    (let [stack (get-in st [:rooms room-id :ui :menu-stack])]
      (if (seq stack)
        {:state (update-in st [:rooms room-id :ui] assoc
                           :menu (peek stack)
                           :menu-stack (pop stack))}
        {:state (update-in st [:rooms room-id :ui] dissoc :menu :menu-stack)}))))

(defn- menu-populate
  "Fill the active menu frame with fetched data (async result). Only applies
   when the active menu is still the one that requested the load — matched by
   :id — so a late-arriving fetch can't clobber a menu the user drilled away
   from. Clears :loading?."
  [st {:keys [room-id id menu]}]
  (let [active (get-in st [:rooms room-id :ui :menu])]
    (when (and active (or (nil? id) (= id (:id active))))
      {:state (assoc-in st [:rooms room-id :ui :menu]
                        (-> active (merge menu) (dissoc :loading?)))})))

(defn- menu-close [st {:keys [room-id]}]
  (when (get-in st [:rooms room-id :ui :menu])
    {:state (update-in st [:rooms room-id :ui] dissoc :menu :menu-stack)}))

(defn- tree-close [st {:keys [room-id]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :ui] dissoc :tree-open?)}))

(defn- tree-navigate
  "Truncate history to `index` (exclusive for :edit, inclusive + 1 for
   :navigate on user messages to include the response). Clears the provider
   session and flags :inject-history? so the next turn starts a fresh
   provider session with the truncated conversation injected as context
   (see xi.agent/history->context). The dropped provider session id is
   recorded as superseded so its transcript stays claimed by this session
   instead of resurfacing as a duplicate card (state/drop-provider-session)."
  [st {:keys [room-id index mode editor-text]}]
  (when-let [room (state/get-room st room-id)]
    (let [history (:history room)
          new-history (subvec (vec history) 0 index)]
      (cond-> {:state (-> st
                          (assoc-in [:rooms room-id :history] new-history)
                          (update-in [:rooms room-id :session]
                                     #(-> (state/drop-provider-session %)
                                          (assoc :inject-history? true)))
                          (update-in [:rooms room-id :ui] dissoc :tree-open?)
                          (update-in [:rooms room-id :agent] assoc :busy? false :queued []))}
        editor-text
        (assoc :effects [[:editor/insert-text {:text editor-text}]])))))

(defn- buffer-open
  "Generic buffer open — install a named buffer (xi.buffers) and switch to it
   on the client it was opened for (the event's :client-id, else everywhere)."
  [st {:keys [room-id buffer-id buffer] :as ev}]
  (when (state/get-room st room-id)
    {:state (-> st
                (update-in [:rooms room-id] buffers/install buffer-id buffer (:event/ts ev))
                (cond-> (buffers/switch-here? st ev)
                  (assoc-in [:rooms room-id :ui :active-buffer] buffer-id)))}))

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
   session through the normal prompt path. With :keep-history? (/truncate),
   the old conversation stays visible above a divider, flagged :no-llm? so
   it is never replayed to the model."
  [st {:keys [room-id session after-prompt keep-history?]}]
  (when-let [room (state/get-room st room-id)]
    (let [busy? (get-in room [:agent :busy?])
          ;; The session is being swapped out from under a live turn. Discard
          ;; the in-flight provider turn (kill the subprocess and drop its
          ;; handle) so the LLM stops responding into the fresh session and its
          ;; late turn-end can't clobber it.
          effects (cond-> []
                    busy?        (conj [:provider/discard {:room-id room-id}])
                    after-prompt (conj [:app/dispatch {:type :prompt/submit :room-id room-id
                                                       :text after-prompt
                                                       :collapsed-label "Summary"}]))]
      (cond-> {:state (-> st
                          (assoc-in [:rooms room-id :session] session)
                          (assoc-in [:rooms room-id :history]
                                    (if keep-history?
                                      (conj (mapv #(assoc % :no-llm? true) (:history room))
                                            (status-entry truncation-divider-text))
                                      []))
                          (update-in [:rooms room-id :agent]
                                     assoc :busy? false :queued []))}
        (seq effects) (assoc :effects effects)))))

(defn- session-forked
  "Split the conversation into a new session (from /fork): install a fresh
   session id but KEEP the current history. The new session has no provider
   session, so the next turn starts a fresh provider session with the existing
   conversation injected as context (see xi.agent/history->context). The
   original session is left untouched on disk, so the two branches diverge."
  [st {:keys [room-id session]}]
  (when (state/get-room st room-id)
    (let [session' (assoc session :provider-session-id nil :inject-history? true)]
      {:state (-> st
                  (assoc-in [:rooms room-id :session] session')
                  (append-history room-id
                                  (status-entry "Forked into a new session — the original is preserved."))
                  (update-in [:rooms room-id :agent] assoc :busy? false :queued []))})))

(defn- session-resume
  "Load a saved session by id into a room. What an extension's command
   dispatches (xi.ext.user.guard, `:session/resume`) to open a session it
   lists; /resume id:<id> does the same."
  [st {:keys [room-id session-id]}]
  (when (and (state/get-room st room-id) (string? session-id))
    {:effects [[:session/load {:room-id room-id :scope :all :session-id session-id}]]}))

(defn- session-resumed [st {:keys [room-id session summary messages msg-hash msg-count]}]
  (when-let [room (state/get-room st room-id)]
    (let [;; A resumed session may have been recorded in a different directory
          ;; than the room is currently in — notably when picked from the "All"
          ;; tab, which lists sessions across every cwd. Follow it there so the
          ;; agent and tools run in the session's own project. Claude
          ;; sessions load with :cwd nil (load-session can't know it), so take
          ;; the cwd from the picker summary; fall back to the room's cwd.
          resume-cwd (or (:cwd session) (:cwd summary))
          ;; backfill so :session/sync can persist without crashing in
          ;; xi-session-dir on a nil cwd.
          session (cond-> session
                    (nil? (:cwd session)) (assoc :cwd (or resume-cwd (:cwd room))))
          session' (assoc session :provider-session-id (:cli-session-id session))
          model (:model session)
          ;; Land the room in the session's cwd via the same validated path the
          ;; /cd command uses (rebuilds the system prompt + AGENTS.md list). The
          ;; worktree extension refines this for now-removed sibling worktrees.
          change-cwd? (and resume-cwd (not= resume-cwd (:cwd room)))]
      {:state (-> st
                  (assoc-in [:rooms room-id :session] session')
                  (assoc-in [:rooms room-id :history] (messages->history messages))
                  (assoc-in [:rooms room-id :msg-hash] msg-hash)
                  (assoc-in [:rooms room-id :msg-count] (or msg-count (count messages)))
                  (update-in [:rooms room-id :agent] assoc :busy? false :queued [])
                  (cond-> model
                    (->
                     (assoc-in [:rooms room-id :agent :model] model)
                     (assoc-in [:rooms room-id :agent :provider]
                               (util/provider-for-model model)))))
       :effects (cond-> []
                  change-cwd? (conj [:cwd/change {:room-id room-id :path resume-cwd}]))})))

(defn- session-updated
  "Persisted session came back from a save/touch effect — merge metadata."
  [st {:keys [room-id session]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :session] merge session)}))

;; ── Session sync on turn end (chained after xi.agent's handler) ──────────────

(defn- cwd-changed
  "Applied after the :cwd/change effect validates the path. Updates the
   room's cwd and, when provided, the system prompt."
  [st {:keys [room-id cwd system system-parts agents-files]}]
  (when (state/get-room st room-id)
    {:state (cond-> (assoc-in st [:rooms room-id :cwd] cwd)
              system       (assoc-in [:rooms room-id :agent :system] system)
              system-parts (assoc-in [:rooms room-id :agent :system-parts] system-parts)
              agents-files (assoc-in [:rooms room-id :agent :agents-files] agents-files))
     :effects [[:app/dispatch {:type :ui/status :room-id room-id
                               :text (str "CWD changed to: " cwd)}]]}))

(defn turn-end-session-sync
  "Chained onto :agent/turn-end — persist the session once the provider
   reports a session id (xi.agent has already stored it on the room)."
  [st {:keys [room-id]}]
  (when (get-in st [:rooms room-id :session :provider-session-id])
    {:effects [[:session/sync {:room-id room-id}]]}))

(defn session-init-mark-interrupted
  "Chained onto :agent/session-init — as soon as the provider reports a
   resumable session id (spinner shown / turn in flight), mark the on-disk
   session interrupted. A hard server restart mid-turn then leaves a signal
   that auto-resumes this agent when a client reconnects; a normal turn-end
   clears it via :session/sync."
  [st {:keys [room-id]}]
  (when (get-in st [:rooms room-id :session :provider-session-id])
    {:effects [[:session/mark-interrupted {:room-id room-id}]]}))

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
    :ui/menu-push    menu-push
    :ui/menu-pop     menu-pop
    :ui/menu-populate menu-populate
    :ui/menu-close   menu-close
    :editor/insert   editor-insert
    :ui/buffer-open  buffer-open
    :ui/prompt-toggle prompt-toggle
    :tree/close      tree-close
    :tree/navigate   tree-navigate
    :ui/attach-image attach-image
    :ui/clear-images clear-images
    :session/created session-created
    :session/forked  session-forked
    :session/resume  session-resume
    :session/resumed session-resumed
    :session/updated session-updated
    :cwd/changed    cwd-changed}))

(def handlers
  "Built-in-only command handlers (no extensions). Back-compat default;
   assemblies that compose extensions call (command-handlers extra)."
  (command-handlers))
