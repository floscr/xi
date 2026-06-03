(ns xi.ext.core
  "Extension registry and lifecycle dispatcher.
   Extensions register via maps with :name, :hooks, :tools, :commands.
   Hooks are dispatched at lifecycle points matching Pi's extension API.

   All hooks receive a state map as their first (or only) argument.
   Use accessor fns from xi.state.session to read it."
  (:require [clojure.string :as str]
            [xi.command-registry :as cmd-registry]))

;; ── Registry ──────────────────────────────────────────────────────────────────

(defonce ^:private registry (atom {:extensions []
                                   :tools {}        ;; name → {:def :exec :ext-name}
                                   :commands {}      ;; name → {:desc :handler :ext-name}
                                   :hooks {}         ;; event → [{:ext-name :handler}]
                                   :keybindings []})) ;; [{:key :handler :ext-name}]

(def ^:private LIFECYCLE_EVENTS
  #{:session-start :session-shutdown
    :turn-start :turn-end
    :before-agent-start :agent-end
    :context :tool-call :tool-result :tool-execution-end
    :input :session-before-compact
    :prompt-badge})

;; ── Hook State ────────────────────────────────────────────────────────────────
;; Persistent state populated by the runtime.  All hook dispatchers
;; automatically merge this into the context passed to handlers.

(defonce ^:private hook-state (atom {}))

(defn set-state!
  "Replace the hook state.  Called by the runtime whenever
   session / model / cwd changes.
   Shape: {:session {:name :id :cli-session-id ...} :model :cwd :effort}"
  [state]
  (reset! hook-state state))

(defn update-state!
  "Merge keys into the current hook state."
  [m]
  (swap! hook-state merge m))

(defn get-state
  "Return the current hook state."
  []
  @hook-state)

;; ── Extension Registration ────────────────────────────────────────────────────

(defn register-extension!
  "Register an extension. Extension map:
     :name     - string, unique name
     :hooks    - map of event keyword → handler fn
     :tools    - vec of {:name :description :input_schema :execute}
     :commands - vec of {:name :description :handler}"
  [ext]
  (let [ext-name (:name ext)]
    ;; Register hooks
    (doseq [[event handler] (:hooks ext)]
      (when (contains? LIFECYCLE_EVENTS event)
        (swap! registry update-in [:hooks event]
               (fnil conj [])
               {:ext-name ext-name :handler handler})))

    ;; Register tools
    (doseq [tool (:tools ext)]
      (swap! registry assoc-in [:tools (:name tool)]
             {:def (dissoc tool :execute)
              :exec (:execute tool)
              :ext-name ext-name}))

    ;; Register commands — into both the ext-local registry AND the central
    ;; command registry so they appear in palette/help automatically.
    (doseq [cmd (:commands ext)]
      (swap! registry assoc-in [:commands (:name cmd)]
             {:desc (:description cmd)
              :handler (:handler cmd)
              :ext-name ext-name})
      ;; Central registry — wrap handler to match the ctx shape extensions expect
      (let [wrap-handler (fn [h cmd-name]
                           (fn [{:keys [sess model cwd args]}]
                             (let [result (h {:session @sess :model model :cwd cwd :args args})]
                               (if (and (map? result) (= :prompt (:type result)))
                                 [{:type :dispatch-prompt :text (:text result)}]
                                 [{:type :command-result :command cmd-name :text (str "Ran /" cmd-name)}]))))
            wrapped-subs (when (seq (:subcommands cmd))
                           (mapv (fn [sub]
                                   {:name (:name sub)
                                    :description (:description sub)
                                    :handler (wrap-handler (:handler sub) (str (:name cmd) " " (:name sub)))})
                                 (:subcommands cmd)))]
        (cmd-registry/register!
         (cond-> {:name (:name cmd)
                  :description (:description cmd)
                  :source "ext"
                  :scope :runtime
                  :handler (wrap-handler (:handler cmd) (:name cmd))}
           wrapped-subs (assoc :subcommands wrapped-subs)))))

    ;; Register keybindings
    (doseq [kb (:keybindings ext)]
      (swap! registry update :keybindings conj
             (assoc kb :ext-name ext-name)))

    ;; Track extension
    (swap! registry update :extensions conj ext)
    nil))

;; ── Keybinding Helpers ─────────────────────────────────────────────────────────────

(def ^:private ESC (str (char 27)))

(defn- parse-key-descriptor
  "Parse a key descriptor like \"alt+p\" into a match function (fn [data] -> bool).
   Supports: alt+<char>, ctrl+shift+<char>."
  [desc]
  (let [parts (str/split (str/lower-case desc) #"\+")
        key-char (last parts)
        modifiers (butlast parts)]
    (case (vec modifiers)
      ["alt"]
      (let [code (.charCodeAt key-char 0)]
        (fn [data]
          (or (= data (str ESC key-char))
              (= data (str ESC "[" code ";3u")))))

      ["ctrl" "shift"]
      (let [code (.charCodeAt key-char 0)]
        (fn [data]
          (= data (str ESC "[" code ";6u"))))

      ;; Fallback — exact match
      (fn [data] (= data desc)))))

(defn get-keybindings
  "Return registered keybindings as [{:key-fn (fn [data]) :handler fn}]."
  []
  (mapv (fn [{:keys [key handler]}]
          {:key-fn (parse-key-descriptor key)
           :handler handler})
        (:keybindings @registry)))

;; ── Hook Dispatch ─────────────────────────────────────────────────────────────

(defn- build-state
  "Merge the persistent hook-state with event-specific overrides."
  ([] @hook-state)
  ([event-ctx] (merge @hook-state event-ctx)))

(defn dispatch-hook
  "Dispatch a lifecycle hook synchronously. Returns nil.
   `event-ctx` is merged on top of the persistent hook state."
  ([event] (dispatch-hook event nil))
  ([event event-ctx]
   (let [state (build-state event-ctx)]
     (doseq [{:keys [handler]} (get-in @registry [:hooks event])]
       (try
         (handler state)
         (catch :default e
           (js/console.error (str "[ext] Error in " (name event) " hook:") e)))))))

(defn dispatch-hook-transform
  "Dispatch a transforming hook — each handler receives the result of the previous.
   Returns the final transformed value, or nil if any handler returns nil (= blocked).
   `event-ctx` is merged on top of the persistent hook state."
  ([event initial-value] (dispatch-hook-transform event initial-value nil))
  ([event initial-value event-ctx]
   (let [state (build-state event-ctx)]
     (reduce
      (fn [value {:keys [handler]}]
        (if (nil? value)
          (reduced nil)
          (try
            (handler value state)
            (catch :default e
              (js/console.error (str "[ext] Error in " (name event) " transform hook:") e)
              value))))
      initial-value
      (get-in @registry [:hooks event])))))

(defn dispatch-hook-async
  "Dispatch a lifecycle hook that may return promises. Returns a promise.
   `event-ctx` is merged on top of the persistent hook state."
  ([event] (dispatch-hook-async event nil))
  ([event event-ctx]
   (let [state (build-state event-ctx)]
     (reduce
      (fn [chain {:keys [handler]}]
        (.then chain
               (fn [_]
                 (try
                   (let [result (handler state)]
                     (if (instance? js/Promise result) result (js/Promise.resolve)))
                   (catch :default e
                     (js/console.error (str "[ext] Error in " (name event) " hook:") e)
                     (js/Promise.resolve))))))
      (js/Promise.resolve)
      (get-in @registry [:hooks event])))))

(defn collect-prompt-badges
  "Collect prompt badge strings from all :prompt-badge hooks.
   Returns concatenated string of all non-nil results."
  []
  (let [state (build-state)
        handlers (get-in @registry [:hooks :prompt-badge])]
    (->> handlers
         (keep (fn [{:keys [handler]}]
                 (try (handler state)
                      (catch :default _ nil))))
         (str/join ""))))

;; ── Confirmation ──────────────────────────────────────────────────────────────
;; Extensions can ask the user for confirmation before proceeding.
;; The TUI (or other client) registers a handler at startup.

(defonce ^:private confirm-handler (atom nil))

(defn set-confirm-handler!
  "Set the confirmation handler. Called by TUI/client at startup.
   handler-fn is (fn [message] -> Promise<boolean>)."
  [handler-fn]
  (reset! confirm-handler handler-fn))

(defn confirm!
  "Ask the user for confirmation. Returns Promise<boolean>.
   If no handler is set (headless mode), denies by default."
  [message]
  (if-let [handler @confirm-handler]
    (handler message)
    (js/Promise.resolve false)))

;; ── Runtime Bridge ────────────────────────────────────────────────────────────
;; Extensions can update session metadata.  The runtime registers handlers
;; at startup so that extensions don't need direct access to atoms.

(defonce ^:private session-name-handler (atom nil))

(defn set-session-name-handler!
  "Set the session-name handler. Called by the runtime at startup.
   handler-fn is (fn [name-str]) — sets the session name."
  [handler-fn]
  (reset! session-name-handler handler-fn))

(defn set-session-name!
  "Set the session name from an extension.
   No-op if no runtime is connected."
  [name-str]
  (when-let [handler @session-name-handler]
    (handler name-str)))

;; ── TUI Bridge ────────────────────────────────────────────────────────────────
;; Extensions can show completion menus and insert text into the editor.
;; The TUI (or other client) registers handlers at startup.

(defonce ^:private completion-handler (atom nil))
(defonce ^:private insert-text-handler (atom nil))

(defn set-completion-handler!
  "Set the completion menu handler. Called by TUI/client at startup.
   handler-fn is (fn [opts]) where opts matches completion/make-completion-menu."
  [handler-fn]
  (reset! completion-handler handler-fn))

(defn set-insert-text-handler!
  "Set the editor insert-text handler. Called by TUI/client at startup.
   handler-fn is (fn [text]) — inserts text at cursor in the editor."
  [handler-fn]
  (reset! insert-text-handler handler-fn))

(defn show-completion!
  "Show a completion menu from an extension.
   opts: {:items [{:label :value :description}] :prompt :on-select :on-cancel :key-bindings :header-fn}
   No-op if no TUI is connected."
  [opts]
  (when-let [handler @completion-handler]
    (handler opts)))

(defn insert-text!
  "Insert text at the editor cursor from an extension.
   No-op if no TUI is connected."
  [text]
  (when-let [handler @insert-text-handler]
    (handler text)))

;; ── Async Transform Dispatch ─────────────────────────────────────────────────

(defn dispatch-hook-transform-async
  "Like dispatch-hook-transform but supports handlers that return Promises.
   Always returns a Promise resolving to the final value (or nil if blocked)."
  ([event initial-value] (dispatch-hook-transform-async event initial-value nil))
  ([event initial-value event-ctx]
   (let [state (build-state event-ctx)
         handlers (get-in @registry [:hooks event])]
     (reduce
      (fn [chain {:keys [handler]}]
        (.then chain
               (fn [value]
                 (if (nil? value)
                   nil
                   (try
                     (let [result (handler value state)]
                       (if (instance? js/Promise result)
                         result
                         (js/Promise.resolve result)))
                     (catch :default e
                       (js/console.error (str "[ext] Error in " (name event) " async transform hook:") e)
                       value))))))
      (js/Promise.resolve initial-value)
      handlers))))

;; ── Tool/Command Access ───────────────────────────────────────────────────────

(defn get-ext-system-prompts
  "Collect :system-prompt strings from all registered extensions.
   Returns a single string or nil."
  []
  (let [prompts (->> (:extensions @registry)
                     (keep :system-prompt)
                     seq)]
    (when prompts
      (str/join "\n\n" prompts))))

(defn get-ext-tool-definitions
  "Return vec of tool definitions from registered extensions."
  []
  (mapv :def (vals (:tools @registry))))

(defn get-ext-tool-registry
  "Return map of tool-name → execute fn from registered extensions."
  []
  (into {} (map (fn [[k v]] [k (:exec v)])) (:tools @registry)))

(defn get-command
  "Look up a registered command by name. Returns {:desc :handler} or nil."
  [cmd-name]
  (get-in @registry [:commands cmd-name]))

(defn list-commands
  "Return all registered commands as [{:name :desc :ext-name}]."
  []
  (mapv (fn [[name {:keys [desc ext-name]}]]
          {:name name :desc desc :ext-name ext-name})
        (:commands @registry)))

(defn list-extensions
  "Return names of all registered extensions."
  []
  (mapv :name (:extensions @registry)))
