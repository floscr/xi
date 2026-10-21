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
  "Sessions from the lobby mirror: the user's pinned sessions at the very top
   (they must also survive the cut however old, see xi.session.recent), then
   rooms with a running agent, then by most-recently visited."
  [state]
  (let [rooms (get-in state [:lobby :rooms])
        busy? (fn [s] (boolean (some (fn [r] (and (= (:session-id r) (:session-id s))
                                                  (:busy? r)))
                                     rooms)))]
    (->> (get-in state [:lobby :sessions])
         (sort-by (juxt (comp boolean :pinned?) busy? session-time) #(compare %2 %1))
         (take 25))))

;; ── Commands ─────────────────────────────────────────────────────────────────

(def featured-commands
  "Command names listed first, in this order, on every surface (palettes and
   the web '/' popup); the rest of the published commands (xi.commands/specs)
   follow in registration order. A name nothing registers is skipped."
  ["help" "model" "resume" "sessions" "new" "clear" "truncate" "trim"
   "rollover" "lineage" "summary" "diff" "commit" "subagent" "review"
   "debug" "rules" "plan" "yolo" "allow" "deny"])

(defn order-commands
  "Command specs with the `featured-commands` first."
  [specs]
  (let [rank (zipmap featured-commands (range))]
    (vec (sort-by #(get rank (:name %) (count rank)) specs))))

(defn expand-commands
  "Flatten commands + their subcommands into a single suggestion list, where
   each subcommand becomes a `parent sub` entry (e.g. \"diff staged\").
   A parent's :aliases ride along for alias-aware matching."
  [commands]
  (mapcat (fn [{:keys [name description subcommands aliases takes-args?]}]
            (cons (cond-> {:name name :description description}
                    aliases     (assoc :aliases aliases)
                    takes-args? (assoc :takes-args? true))
                  (map (fn [{sub-name :name sub-desc :description}]
                         {:name (str name " " sub-name) :description sub-desc})
                       subcommands)))
          commands))

;; ── Actions ──────────────────────────────────────────────────────────────────

(def action-items
  "Curated palette Actions — shared label/icon/order across surfaces. Each
   surface maps :key to its own event/handler (see the mapping tables in
   xi.client.tui and xi.web.views). :room? gates the item on an active room.
   :action names the keyboard action (xi.keys) that does the same thing, so a
   surface can show its key next to the item."
  [{:key :new-chat     :label "New chat"           :icon :plus    :action :chat/new}
   {:key :change-model :label "Change model"       :icon :layers  :room? true :pending? true}
   {:key :skills       :label "Skills"             :icon :zap     :room? true}
   {:key :git-status   :label "Git status"         :icon :code    :room? true}
   {:key :copy-debug   :label "Copy debug info"    :icon :copy    :room? true}
   {:key :keys         :label "Keyboard shortcuts" :icon :terminal :action :keys/show}
   {:key :reload       :label "Reload"             :icon :refresh}])

(defn actions
  "Action items visible given `room?` (drops the room-scoped ones with no room).
   `pending?` — a not-yet-created chat (web) — keeps the ones flagged :pending?
   (the choice is remembered on the pending room until it exists)."
  ([room?] (actions room? false))
  ([room? pending?]
   (filterv #(or (not (:room? %)) room? (and pending? (:pending? %))) action-items)))
