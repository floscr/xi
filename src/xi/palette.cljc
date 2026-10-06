(ns xi.palette
  "Shared command-palette content for the web Cmd/Ctrl+K palette and the TUI
   Ctrl+/ palette. Produces palette pieces as neutral data — labels, icons and
   semantic actions — that each surface maps to its own events/handlers, so the
   two palettes stay in lock-step instead of drifting.

   Both surfaces store the server's lobby mirror under [:lobby ...], so the
   `recent-sessions` / session-sorting helpers work identically on either.")

;; ── Recent sessions (Chats) ──────────────────────────────────────────────────

(defn session-time
  "Numeric last-visited timestamp for a session, for sorting."
  [s]
  (let [t (or (:last-accessed s) (:timestamp s))]
    (cond
      (number? t) t
      (string? t) (let [n (.getTime (js/Date. t))] (if (js/isNaN n) 0 n))
      :else 0)))

(defn recent-sessions
  "Sessions from the lobby mirror: rooms with a running agent pinned to the
   top, then by most-recently visited."
  [state]
  (let [rooms (get-in state [:lobby :rooms])
        busy? (fn [s] (boolean (some (fn [r] (and (= (:session-id r) (:session-id s))
                                                  (:busy? r)))
                                     rooms)))]
    (->> (get-in state [:lobby :sessions])
         (sort-by (juxt busy? session-time) #(compare %2 %1))
         (take 25))))

;; ── Commands ─────────────────────────────────────────────────────────────────

(def palette-commands
  "Curated command list shown in both palettes' Commands section (web Cmd/K
   and TUI Ctrl+P) and the web '/' suggestion popup — the same items on every
   surface. `:while-busy?` marks non-interrupting commands: read-only views
   that neither mutate session state nor interrupt the running turn, so they
   may be submitted while the agent is busy. Each entry is display metadata;
   the actual handler is resolved by name in the assembly's command registry."
  [{:name "help"     :description "Show available commands" :while-busy? true}
   {:name "model"    :description "Show or set model"}
   {:name "resume"   :description "Resume a previous session"}
   {:name "sessions" :description "List previous sessions"}
   {:name "new"      :description "Start a new session"}
   {:name "clear"    :description "Clear current session"}
   {:name "truncate" :description "Summarize conversation to reduce context"}
   {:name "trim"     :description "Trim bloated tool results from the transcript"
    :subcommands [{:name "yes"    :description "Apply the pending trim preview"}
                  {:name "cancel" :description "Abandon the pending trim preview"}]}
   {:name "rollover" :description "Fresh session with lineage pointers to this one"}
   {:name "lineage"  :description "Show this session's ancestor chain" :while-busy? true}
   {:name "summary"  :description "Describe this session and refresh its title"}
   {:name "diff"     :description "Show changes from this session" :while-busy? true
    :subcommands [{:name "git"             :description "All git changes (staged + unstaged + untracked)"}
                  {:name "staged"          :description "Staged changes"}
                  {:name "unstaged"        :description "Unstaged changes"}
                  {:name "session-edits"   :description "Diff of files edited this session"}
                  {:name "session-git"     :description "Session edits still uncommitted (vs the last commit)"}
                  {:name "session-commits" :description "Diff of commits made this session"}]}
   {:name "commit"   :description "Review changes and create a git commit"}
   {:name "review"   :description "Review git changes against the code-review methodology"
    :subcommands [{:name "staged" :description "Review staged changes vs HEAD"}]}
   {:name "debug"    :description "Copy debug info to clipboard" :while-busy? true}
   ;; Answer the pending permission ask — only ever useful mid-turn.
   {:name "allow"    :aliases ["a"] :description "Allow the pending permission request" :while-busy? true
    :subcommands [{:name "always" :description "Allow and don't ask again"}
                  {:name "repo"   :description "Allow writes to this repo"}]}
   {:name "deny"     :aliases ["d"] :description "Deny the pending permission request (/deny <reason> tells the agent why)" :while-busy? true}])

(defn expand-commands
  "Flatten commands + their subcommands into a single suggestion list, where
   each subcommand becomes a `parent sub` entry (e.g. \"diff staged\").
   A parent's :aliases ride along for alias-aware matching."
  [commands]
  (mapcat (fn [{:keys [name description subcommands aliases]}]
            (cons (cond-> {:name name :description description}
                    aliases (assoc :aliases aliases))
                  (map (fn [{sub-name :name sub-desc :description}]
                         {:name (str name " " sub-name) :description sub-desc})
                       subcommands)))
          commands))

;; ── Actions ──────────────────────────────────────────────────────────────────

(def action-items
  "Curated palette Actions — shared label/icon/order across surfaces. Each
   surface maps :key to its own event/handler (see the mapping tables in
   xi.client.tui and xi.web.views). :room? gates the item on an active room."
  [{:key :new-chat     :label "New chat"        :icon :plus}
   {:key :change-model :label "Change model"    :icon :layers  :room? true}
   {:key :skills       :label "Skills"          :icon :zap     :room? true}
   {:key :git-status   :label "Git status"      :icon :code    :room? true}
   {:key :copy-debug   :label "Copy debug info" :icon :copy    :room? true}
   {:key :reload       :label "Reload"          :icon :refresh}])

(defn actions
  "Action items visible given `room?` (drops the room-scoped ones with no room)."
  [room?]
  (filterv #(or (not (:room? %)) room?) action-items))
