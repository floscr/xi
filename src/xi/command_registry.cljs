(ns xi.command-registry
  "Centralized command registry — single source of truth for all commands.

   Every command (built-in, extension, TUI-local) is a map:
     :name        - string, unique identifier (the part after /)
     :description - human-readable description (shown in palette & /help)
     :handler     - (fn [ctx] -> events-vec | nil | :pass-through)
     :scope       - :runtime | :client (where the command executes)
     :show-busy   - boolean, available when agent is busy? (default false)
     :hidden      - boolean, hide from /help & palette (default false)
     :source      - optional string tag (e.g. \"ext\" for extension commands)

   Scopes:
     :runtime — runs in the headless runtime (session, model, etc.)
     :client  — runs in the TUI client (git, buffers, palette)

   A command name can have entries in both scopes. The TUI tries :client first;
   if the handler returns :pass-through, it falls through to :runtime via dispatch.
   The palette shows the merged set (one entry per name, client description wins).")

;; ── Registry ──────────────────────────────────────────────────────────────────

;; name → {:runtime <cmd-map> :client <cmd-map>}
(defonce ^:private registry (atom {}))

(defn register!
  "Register a command. A name can have both a :runtime and :client entry."
  [{:keys [name scope] :as cmd}]
  {:pre [(string? name) (some? (:handler cmd))]}
  (let [scope (or scope :runtime)
        cmd (merge {:scope scope
                    :show-busy false
                    :hidden false}
                   cmd)]
    (swap! registry assoc-in [name scope] cmd))
  nil)

(defn register-many!
  "Register multiple commands."
  [cmds]
  (doseq [cmd cmds]
    (register! cmd)))

(defn get-command
  "Look up a command by name and optional scope.
   With scope: returns the entry for that scope, or nil.
   Without scope: returns the client entry if it exists, else the runtime entry."
  ([cmd-name]
   (let [entry (get @registry cmd-name)]
     (or (:client entry) (:runtime entry))))
  ([cmd-name scope]
   (get-in @registry [cmd-name scope])))

(defn list-commands
  "List all registered commands (one per name, for palette/help).
   Client description wins over runtime when both exist.
   Options:
     :scope          - filter to only :runtime or :client entries
     :include-hidden - include hidden commands (default false)"
  ([] (list-commands {}))
  ([{:keys [scope include-hidden]}]
   (let [entries (vals @registry)
         ;; For each name, pick the best entry for display
         cmds (if scope
                (->> entries
                     (keep #(get % scope))
                     vec)
                ;; Merge: prefer client description, but show all names
                (->> entries
                     (map (fn [entry]
                            ;; Client wins for display metadata, runtime as fallback
                            (or (:client entry) (:runtime entry))))
                     vec))]
     (cond->> cmds
       (not include-hidden) (remove :hidden)
       true (sort-by :name)
       true vec))))

(defn clear!
  "Clear all commands. Mainly for testing."
  []
  (reset! registry {}))
