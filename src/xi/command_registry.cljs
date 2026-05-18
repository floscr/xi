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

   Commands may declare :subcommands — named sub-handlers that appear as
   separate palette entries and route automatically:

     {:name \"diff\"
      :description \"Show diff viewer\"
      :subcommands [{:name \"git\"    :description \"All git changes\" :handler fn}
                    {:name \"staged\" :description \"Staged changes\"  :handler fn}]
      :handler fn}  ;; default when no subcommand matches

   Dispatch: /diff git → resolves to git subcommand handler with nil args.
   Palette:  /diff, /diff git, /diff staged all appear as separate items.

   Scopes:
     :runtime — runs in the headless runtime (session, model, etc.)
     :client  — runs in the TUI client (git, buffers, palette)

   A command name can have entries in both scopes. The TUI tries :client first;
   if the handler returns :pass-through, it falls through to :runtime via dispatch.
   The palette shows the merged set (one entry per name, client description wins)."
  (:require [clojure.string :as str]))

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

;; ── Subcommand routing ────────────────────────────────────────────────────────

(defn resolve-handler
  "Route to the correct handler for a command given its args string.
   If the command has :subcommands and the first word of args matches one,
   returns [subcommand-handler remaining-args].
   Otherwise returns [command-handler original-args]."
  [cmd args]
  (let [subcmds (:subcommands cmd)]
    (if (and (seq subcmds) (some? args))
      (let [trimmed (str/trim args)
            idx (.indexOf trimmed " ")
            first-word (if (neg? idx) trimmed (subs trimmed 0 idx))
            rest-args (when-not (neg? idx)
                        (let [r (str/trim (subs trimmed (inc idx)))]
                          (when (seq r) r)))]
        (if-let [sub (some #(when (= (:name %) first-word) %) subcmds)]
          [(:handler sub) rest-args]
          [(:handler cmd) args]))
      [(:handler cmd) args])))

;; ── Listing ───────────────────────────────────────────────────────────────────

(defn- expand-subcommands
  "Expand a command with :subcommands into multiple entries.
   Returns the parent + one entry per subcommand (with composite name)."
  [cmd]
  (let [parent cmd
        subs (mapv (fn [sub]
                     (-> parent
                         (merge (select-keys sub [:description :handler]))
                         (assoc :name (str (:name parent) " " (:name sub)))
                         (dissoc :subcommands)))
                   (:subcommands cmd))]
    (into [parent] subs)))

(defn list-commands
  "List all registered commands for palette/help display.
   Commands with :subcommands are expanded into separate entries.
   Client description wins over runtime when both exist.
   Options:
     :scope          - filter to only :runtime or :client entries
     :include-hidden - include hidden commands (default false)"
  ([] (list-commands {}))
  ([{:keys [scope include-hidden]}]
   (let [entries (vals @registry)
         cmds (if scope
                (->> entries (keep #(get % scope)) vec)
                (->> entries
                     (map (fn [entry]
                            (or (:client entry) (:runtime entry))))
                     vec))
         ;; Expand subcommands into separate entries
         expanded (mapcat (fn [cmd]
                            (if (seq (:subcommands cmd))
                              (expand-subcommands cmd)
                              [cmd]))
                          cmds)]
     (cond->> expanded
       (not include-hidden) (remove :hidden)
       true (sort-by :name)
       true vec))))

(defn clear!
  "Clear all commands. Mainly for testing."
  []
  (reset! registry {}))
