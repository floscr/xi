(ns xi.ext.core
  "Extension layer — extensions are data maps composed at assembly time.

   An extension (like a provider) is plain data + fns, no registration
   atoms. Extension state lives in app state under [:ext <id>] — never in
   extension-local atoms. Runtime resources (child processes, pending
   dialog resolvers) live in fx closures.

   Extension state is scoped two ways (see xi.core.state):
     room-scoped   [:rooms rid :ext <id>] — rides in :room/joined snapshots,
                   mirrors to clients (plan-mode :enabled?)
     process-local [:ext <id>]            — never crosses the wire
                   (the rules engine's server-session rules)

   Extension map:
     :id           keyword (required)
     :init         initial extension state, a map with optional keys:
                     {:room    <state>   — template merged into each room's
                                           [:ext <id>] (mirrors to clients)
                      :process <state>}  — installed at top-level [:ext <id>]
     :handlers     {event-type (fn [state event] → {:state :effects}|nil)}
                   chained AFTER the base handler for that event type
     :fx           {fx-type (fn [ctx payload])} — effect handlers
     :event-hooks  {event-type (fn [event state] → event'|nil)}
                   pre-dispatch transforms on the app dispatch path;
                   nil blocks the event. Never run on :remote? (mirrored)
                   events — mirrors must replay the server verbatim.
     :tool-definitions [{:name :description :input_schema}]
     :tool-registry    {name (fn [args ctx] → result|Promise)} — ctx is
                   the per-turn tool ctx {:dispatch! :get-state :room-id
                   :confirm!} with the provider's {:cwd :client-pid} on top
                   (xi.agent/create-fx). A name matching a builtin tool
                   replaces its implementation (xi.tools.registry/with-extensions).
                   Whether a call runs at all is the rules engine's decision
                   (xi.ext.rules/tool-policy) — extensions have no policy hook.
     :remove-tools #{tool-name} — builtin tools to hide from the model
                   (dropped from the provider tool list; re-read per turn,
                   so /ext disable restores them). E.g. the clj extension
                   removes bash.
     :commands     [{:name :description :handler}] — same contract as
                   xi.commands: (fn [state {:keys [room-id args commands]}])
     :roomless-events #{event-type} — event types clients may send without
                   joining a room (connection-level, keyed by :client-id).
                   Unioned into the WS server's roomless whitelist.
     :no-broadcast #{event-type} — room-scoped event types the WS server
                   must NOT echo back to the room's clients.
     :originator-only #{event-type} — room-scoped event types the WS server
                   sends ONLY to the originating client (the event's
                   :client-id), not the whole room. Used for client-local
                   views (e.g. the diff viewer) that should not disturb
                   other connected clients. Falls back to a room broadcast
                   when the event carries no :client-id.
     :lobby-relevant #{event-type} — event types after which the WS server
                   pushes fresh :lobby/state to every client.
     :server-fx    (fn [{:keys [send!]}] → {fx-type (fn [ctx payload])})
                   fx that reply directly to a WS client. Instantiated by
                   the WS server with send! = (fn [client-id event]) —
                   event is a data map, encoding is the server's job.
                   Only exists in server mode (no-op elsewhere).
     :system-prompt str | (fn [cwd] → str|nil) — appended to room system
     :keybindings  [{:key \"alt+r\" :event {…} :when (fn [state])}] — TUI
                   client; :event is dispatched with :room-id added
     :prompt-badge (fn [state] → str|nil) — TUI prompt badge
     :on-shutdown  (fn []) — process-exit cleanup (TUI on-exit)
     :on-enable    (fn []) — runtime enable hook (xi.ext.manager): fired
                   when the extension is enabled at runtime via /ext or /mcp
                   (NOT during the initial seed). Not fired at assembly.
     :on-disable   (fn []) — runtime disable hook: fired when the extension
                   is disabled/removed at runtime; the extension owns its own
                   teardown here (e.g. close an MCP subprocess). Only tool
                   surfaces hot-swap — see xi.ext.manager for the honest scope.

   Web-client surface (browser-safe web halves only — composed by
   xi.web.core, ignored everywhere else):
     :routes       {\"seg\" {:parse (fn [segments] → route-map)
                           :path  {page-kw (fn [route] → url-path)}
                           :roomless-pages #{page-kw}}}
                   keyed by first URL segment; :parse gets the remaining
                   segments. :roomless-pages lists pages that imply
                   leaving the active room on navigation.
     :pages        {page-kw (fn [state dispatch!] → hiccup)} — root-view
                   page table entries
     :nav-items    [{:menu :sidebar|:palette|:home-topbar|:overflow
                     :mode … :label … :icon … :event {…}}] — data-only
                   nav entries rendered by the core views; :event is
                   dispatched on click (:overflow items get the menu's
                   ctx keys merged in; :mode scopes them to a ctx mode)
     :sidebar-groups [{:id kw :label str :where session-key :limit n
                     :more {:label str :icon kw :event {…}}}] — data-only
                   drawer sidebar groups, between Drafts and Recent: the
                   sessions whose `:where` key is truthy (a per-user flag the
                   server puts on every session, e.g. :favorite?), most
                   recently visited first, at most :limit of them. :more is
                   an optional trailing row shown when there are more
                   sessions than :limit.
     :session-menu-items [{:label str :label-on str :flag session-key
                         :icon kw :event {…}}] — data-only entries of every
                   session card's context menu, above the core ones. :event
                   is dispatched with the card's :session-id merged in;
                   :label-on replaces :label while the session's :flag key
                   is truthy (Add to favorites → Remove from favorites).
     :taps         [(fn [dispatch!] → tap-fn)] — app taps installed at init

   `compose` merges a list of extensions into the pieces the per-mode
   assembly (xi.cli) wires into the app, the provider effects and the TUI."
  (:require [clojure.string :as str]
            [xi.core.events :as events]))

;; ── Composition (pure) ───────────────────────────────────────────────────────

(defn- merge-handler-maps
  "Merge event-handler maps left→right; same event type chains in order."
  [acc m]
  (reduce (fn [acc [t h]]
            (update acc t #(if % (events/chain % h) h)))
          acc
          m))

(defn instantiate
  "Resolve config extension entries (xi.config) into extension maps:
   maps pass through, factory fns are called with `ctx`
   (a per-surface map, e.g. {:ring … :ask! …}). nil results are kept —
   compose drops them."
  [entries ctx]
  (mapv #(if (map? %) % (% ctx)) entries))

(defn compose
  "Compose extensions (in order) into one assembly map."
  [exts]
  (let [exts (vec (remove nil? exts))]
    {:extensions       exts
     :room-ext-init    (into {} (keep (fn [e] (when-let [s (:room (:init e))]
                                                [(:id e) s]))) exts)
     :process-ext-init (into {} (keep (fn [e] (when-let [s (:process (:init e))]
                                                [(:id e) s]))) exts)
     :handlers         (reduce merge-handler-maps {} (keep :handlers exts))
     :fx               (apply merge {} (keep :fx exts))
     :commands         (vec (mapcat :commands exts))
     :tool-definitions (vec (mapcat :tool-definitions exts))
     :tool-registry    (apply merge {} (keep :tool-registry exts))
     :event-hooks      (reduce (fn [acc e]
                                 (reduce (fn [acc [t hook]]
                                           (update acc t (fnil conj []) hook))
                                         acc
                                         (:event-hooks e)))
                               {} exts)
     :remove-tools     (into #{} (mapcat :remove-tools) exts)
     :roomless-events  (into #{} (mapcat :roomless-events) exts)
     :no-broadcast     (into #{} (mapcat :no-broadcast) exts)
     :originator-only  (into #{} (mapcat :originator-only) exts)
     :lobby-relevant   (into #{} (mapcat :lobby-relevant) exts)
     :server-fx-fns    (vec (keep :server-fx exts))
     :routes           (apply merge {} (keep :routes exts))
     :pages            (apply merge {} (keep :pages exts))
     :nav-items        (vec (mapcat :nav-items exts))
     :sidebar-groups   (vec (mapcat :sidebar-groups exts))
     :session-menu-items (vec (mapcat :session-menu-items exts))
     :taps             (vec (mapcat :taps exts))
     :keybindings      (vec (mapcat :keybindings exts))
     :badges           (vec (keep :prompt-badge exts))
     :system-prompts   (vec (keep :system-prompt exts))
     :on-shutdown-fns  (vec (keep :on-shutdown exts))}))

(defn merge-handlers
  "Chain the composed extension handlers onto a base handler map
   (extension handlers run after the base handler for the same type)."
  [base composed]
  (merge-handler-maps base (:handlers composed)))

(defn transform-event
  "Build the pre-dispatch transform for xi.core.app (:transform-event).
   Runs the event-hook chain for the event's type: each hook returns the
   (possibly transformed) event, or nil to block it. Mirrored (:remote?)
   events pass through untouched. Returns nil when no hooks exist."
  [composed]
  (let [hooks (:event-hooks composed)]
    (when (seq hooks)
      (fn [state event]
        (if (:remote? event)
          event
          (if-let [hs (get hooks (:type event))]
            (reduce (fn [ev hook]
                      (if (nil? ev)
                        (reduced nil)
                        (try
                          (hook ev state)
                          (catch :default e
                            (js/console.error "[ext] event hook failed:"
                                              (str (:type event)) e)
                            ev))))
                    event hs)
            event))))))

(defn system-prompt
  "Collect extension system prompts for a cwd. Returns a string or nil."
  [composed cwd]
  (let [parts (keep (fn [p]
                      (let [s (if (fn? p) (p cwd) p)]
                        (when (seq s) s)))
                    (:system-prompts composed))]
    (when (seq parts)
      (str/join "\n\n" parts))))

(defn system-prompt-parts
  "Collect extension system prompts as source-attributed parts.
   Returns a vector of {:source :text} maps (may be empty)."
  [composed cwd]
  (let [exts (:extensions composed)]
    (into []
          (keep (fn [ext]
                  (when-let [sp (:system-prompt ext)]
                    (let [s (if (fn? sp) (sp cwd) sp)]
                      (when (seq s)
                        {:source (str "ext/" (name (:id ext)))
                         :text   s})))))
          exts)))

(defn prompt-badges
  "Concatenated prompt badge string for the current state ('' when none)."
  [composed state]
  (->> (:badges composed)
       (keep (fn [badge-fn]
               (try (badge-fn state)
                    (catch :default _ nil))))
       (str/join "")))

(defn on-shutdown!
  "Run all extension shutdown fns (process exit cleanup)."
  [composed]
  (doseq [f (:on-shutdown-fns composed)]
    (try (f) (catch :default _ nil))))

;; ── Dialogs (contained impure edge) ──────────────────────────────────────────
;;
;; Extensions ask the user via dialogs-as-data: ask! pushes a dialog into
;; the room's :ui :dialogs (rendered by clients, mirrored over WS) and
;; returns a promise. The user's :ui/dialog-response event removes the
;; dialog and resolves the promise via the :dialog/resolve effect. Pending
;; resolvers are runtime resources living in this closure — in client mode
;; the response is forwarded and resolved server-side.

(defn- clientless? [st]
  (get-in st [:connection :clientless?]))

(defn create-dialogs
  "Returns {:handlers {…} :fx {…} :ask! (fn [fx-ctx {:keys [room-id dialog]}])}.
   ask! resolves to the selected value (boolean for :confirm). The dialog
   stays open in the room until a user responds — even if no client is
   viewing that room, or none is connected at all (a phone that went to
   sleep): it shows up when one (re)joins, and extensions can notify on its
   :ui/dialog-open. Only a process no client can ever attach to (prompt
   mode, `:clientless?` in its connection state) resolves to a safe default
   immediately (false/nil).

   The request may carry `:on-reason` (fn [reason]) — a runtime callback,
   never part of the mirrored dialog data. It marks the dialog
   :deny-reason? so clients offer *deny with reason*; a deny answered with
   one (:ui/dialog-response's :reason) calls it before the promise resolves
   to `false` (xi.dialog/capture-deny-reason)."
  []
  (let [pending (js/Map.)
        counter #js {:n 0}
        safe-default (fn [dialog]
                       (case (:type dialog) :confirm false nil))]
    {:ask!
     (fn [{:keys [dispatch! state]} {:keys [room-id dialog on-reason]}]
       (if (clientless? state)
         (js/Promise.resolve (safe-default dialog))
         (js/Promise.
          (fn [resolve _reject]
            (set! (.-n counter) (inc (.-n counter)))
            (let [id (str "dlg-" (.-n counter))]
              (.set pending id {:resolve resolve :on-reason on-reason})
              (dispatch! {:type :ui/dialog-open
                          :room-id room-id
                          :dialog (cond-> (assoc dialog :id id)
                                    on-reason (assoc :deny-reason? true))}))))))

     :handlers
     {:ui/dialog-response
      (fn [st {:keys [room-id dialog-id value reason]}]
        (when (some #(= dialog-id (:id %))
                    (get-in st [:rooms room-id :ui :dialogs]))
          {:state   (update-in st [:rooms room-id :ui :dialogs]
                               (fn [ds] (vec (remove #(= dialog-id (:id %)) ds))))
           :effects [[:dialog/resolve (cond-> {:dialog-id dialog-id :value value}
                                        ;; a reason only ever rides a deny
                                        (and (not value) (string? reason)
                                             (not (str/blank? reason)))
                                        (assoc :reason reason))]]}))}

     :fx
     {:dialog/resolve
      (fn [_ {:keys [dialog-id value reason]}]
        (when-let [{:keys [resolve on-reason]} (.get pending dialog-id)]
          (.delete pending dialog-id)
          (when (and reason on-reason) (on-reason reason))
          (resolve value)))}}))
