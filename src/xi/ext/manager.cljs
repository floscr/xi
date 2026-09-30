(ns xi.ext.manager
  "Live registry of the active extension set, with runtime enable/disable.

   Assembly (xi.cli) normally freezes one `ext/compose` snapshot for the
   whole process. This manager keeps that snapshot *live*: it holds the
   registered extensions plus which are enabled, and recomposes on every
   change. The provider tooling seam reads `(composed mgr)` fresh each turn
   (see xi.cli/tooling-opts + xi.providers.anthropic/resolve-tooling), so an
   extension's tools appear/vanish the moment it is enabled/disabled — no
   restart.

   Scope, honestly: only the *use-time* surfaces hot-swap — the tool
   definitions and tool registry, which the provider re-reads per turn. The *construction-time* surfaces (reducer :handlers,
   :event-hooks, command dispatch, :keybindings, :system-prompt, :taps,
   :routes) are captured once into create-app / the TUI client / the WS
   server at assembly, so toggling an extension that contributes those does
   not fully take effect until a reload. MCP extensions only ever contribute
   tools, so they are covered completely.

   Lifecycle hooks on an extension map (both optional):
     :on-enable  (fn [])  — called when the extension becomes enabled
     :on-disable (fn [])  — called when it becomes disabled; the extension
                            owns its own teardown (close MCP clients, …)

   The manager is created in xi.cli and threaded into the server-extension
   instantiate ctx as :manager, so control extensions (/ext, /mcp) can
   mutate it."
  (:require [xi.ext.core :as ext]))

(defn create
  "Create an empty manager. Seed it with the assembly's extension list via
   `seed!`, then read the initial composition with `composed`."
  []
  {:order    (atom [])              ;; ids in registration order (compose order)
   :known    (atom {})              ;; id -> extension map
   :enabled  (atom #{})             ;; set of enabled ids
   :composed (atom (ext/compose []))})

(defn- recompose!
  [{:keys [order known enabled composed]}]
  (reset! composed
          (ext/compose (keep (fn [id] (when (contains? @enabled id) (@known id)))
                             @order))))

(defn seed!
  "Register the assembly's initial extensions (all enabled) in list order,
   then compose. nils are dropped. Returns the manager."
  [{:keys [order known enabled] :as mgr} exts]
  (let [exts (vec (remove nil? exts))]
    (reset! order   (mapv :id exts))
    (reset! known   (into {} (map (juxt :id identity)) exts))
    (reset! enabled (into #{} (map :id) exts))
    (recompose! mgr)
    mgr))

(defn composed
  "The current composed assembly (of the enabled extensions)."
  [{:keys [composed]}]
  @composed)

(defn ext-list
  "Snapshot of the registry in registration order:
   [{:id kw :enabled? bool}]."
  [{:keys [order enabled]}]
  (mapv (fn [id] {:id id :enabled? (contains? @enabled id)}) @order))

(defn known?
  [{:keys [known]} id]
  (contains? @known id))

(defn register!
  "Register a runtime-built extension (e.g. an MCP wrapper). Enabled by
   default; pass {:enable? false} to register it disabled. Re-registering an
   id replaces its map. Fires :on-enable when it ends up enabled."
  [{:keys [order known enabled] :as mgr} ext & [{:keys [enable?] :or {enable? true}}]]
  (let [id (:id ext)]
    (when-not (some #{id} @order) (swap! order conj id))
    (swap! known assoc id ext)
    (if enable? (swap! enabled conj id) (swap! enabled disj id))
    (recompose! mgr)
    (when (and enable? (:on-enable ext)) ((:on-enable ext)))
    id))

(defn enable!
  "Enable a registered extension by id. No-op if unknown or already enabled.
   Returns the id when it changed state, else nil."
  [{:keys [known enabled] :as mgr} id]
  (when (and (contains? @known id) (not (contains? @enabled id)))
    (swap! enabled conj id)
    (recompose! mgr)
    (when-let [f (:on-enable (@known id))] (f))
    id))

(defn disable!
  "Disable an enabled extension by id (it stays registered, so it can be
   re-enabled). No-op if not enabled. Returns the id when it changed state,
   else nil."
  [{:keys [known enabled] :as mgr} id]
  (when (contains? @enabled id)
    (swap! enabled disj id)
    (recompose! mgr)
    (when-let [f (:on-disable (@known id))] (f))
    id))

(defn unregister!
  "Remove an extension from the registry entirely (disabling it first so its
   :on-disable teardown runs). No-op if unknown. Returns the id when removed,
   else nil. Used by /mcp remove — /ext only ever disables."
  [{:keys [order known enabled] :as mgr} id]
  (when (contains? @known id)
    (disable! mgr id)
    (swap! order   (fn [o] (vec (remove #{id} o))))
    (swap! known   dissoc id)
    (swap! enabled disj id)
    (recompose! mgr)
    id))
