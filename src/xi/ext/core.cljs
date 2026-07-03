(ns xi.ext.core
  "Extension layer — extensions are data maps composed at assembly time.

   An extension (like a provider) is plain data + fns, no registration
   atoms. Extension state lives in app state under [:ext <id>] — never in
   extension-local atoms. Runtime resources (child processes, pending
   dialog resolvers) live in fx closures.

   Extension state is scoped two ways (see xi.core.state):
     room-scoped   [:rooms rid :ext <id>] — rides in :room/joined snapshots,
                   mirrors to clients (plan-mode/done-notify :enabled?)
     process-local [:ext <id>]            — never crosses the wire
                   (dictation :recording? on a client process)

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
     :tool-gate    (fn [tool-call ctx] → tool-call | nil
                    | {:intercepted true :result …} | Promise<…>)
                   async chain on provider tool execution. ctx:
                   {:dispatch! :get-state :room-id :cwd :confirm!}
     :tool-definitions [{:name :description :input_schema}]
     :tool-registry    {name (fn [args ctx] → result|Promise)} — ctx is
                   the provider tool ctx merged with the gate ctx above
     :commands     [{:name :description :handler}] — same contract as
                   xi.commands: (fn [state {:keys [room-id args commands]}])
     :roomless-events #{event-type} — event types clients may send without
                   joining a room (connection-level, keyed by :client-id).
                   Unioned into the WS server's roomless whitelist.
     :no-broadcast #{event-type} — room-scoped event types the WS server
                   must NOT echo back to the room's clients.
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
     :tool-gates       (vec (keep :tool-gate exts))
     :roomless-events  (into #{} (mapcat :roomless-events) exts)
     :no-broadcast     (into #{} (mapcat :no-broadcast) exts)
     :lobby-relevant   (into #{} (mapcat :lobby-relevant) exts)
     :server-fx-fns    (vec (keep :server-fx exts))
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

(defn tool-gate
  "Compose the extensions' tool gates into one async gate:
   (fn [tool-call ctx] → Promise<tool-call | nil | {:intercepted …}>).
   nil short-circuits (blocked); {:intercepted …} short-circuits (the
   gate already produced the result). Returns nil when no gates exist."
  [composed]
  (let [gates (:tool-gates composed)]
    (when (seq gates)
      (fn [tool-call ctx]
        (reduce
         (fn [chain gate]
           (.then chain
                  (fn [value]
                    (cond
                      (nil? value) nil
                      (:intercepted value) value
                      :else
                      (try
                        (let [result (gate value ctx)]
                          (if (instance? js/Promise result)
                            result
                            (js/Promise.resolve result)))
                        (catch :default e
                          (js/console.error "[ext] tool gate failed:" e)
                          value))))))
         (js/Promise.resolve tool-call)
         gates)))))

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

(defn- any-clients? [st]
  (seq (get-in st [:connection :clients])))

(defn create-dialogs
  "Returns {:handlers {…} :fx {…} :ask! (fn [fx-ctx {:keys [room-id dialog]}])}.
   ask! resolves to the selected value (boolean for :confirm). In server
   mode with no clients connected at all (truly headless) it resolves to a
   safe default immediately (false/nil). Otherwise the dialog stays open
   in the room until a user responds — even if no client is currently
   viewing that room."
  []
  (let [pending (js/Map.)
        counter #js {:n 0}
        safe-default (fn [dialog]
                       (case (:type dialog) :confirm false nil))]
    {:ask!
     (fn [{:keys [dispatch! state]} {:keys [room-id dialog]}]
       (if (and (= :server (get-in state [:connection :mode]))
                (not (any-clients? state)))
         (js/Promise.resolve (safe-default dialog))
         (js/Promise.
          (fn [resolve _reject]
            (set! (.-n counter) (inc (.-n counter)))
            (let [id (str "dlg-" (.-n counter))]
              (.set pending id resolve)
              (dispatch! {:type :ui/dialog-open
                          :room-id room-id
                          :dialog (assoc dialog :id id)}))))))

     :handlers
     {:ui/dialog-response
      (fn [st {:keys [room-id dialog-id value]}]
        (when (some #(= dialog-id (:id %))
                    (get-in st [:rooms room-id :ui :dialogs]))
          {:state   (update-in st [:rooms room-id :ui :dialogs]
                               (fn [ds] (vec (remove #(= dialog-id (:id %)) ds))))
           :effects [[:dialog/resolve {:dialog-id dialog-id :value value}]]}))}

     :fx
     {:dialog/resolve
      (fn [_ {:keys [dialog-id value]}]
        (when-let [resolve (.get pending dialog-id)]
          (.delete pending dialog-id)
          (resolve value)))}}))
