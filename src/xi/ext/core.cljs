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
                                   :hooks {}}))      ;; event → [{:ext-name :handler}]

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
      (cmd-registry/register!
       {:name (:name cmd)
        :description (:description cmd)
        :source "ext"
        :scope :runtime
        :handler (fn [{:keys [sess model cwd args]}]
                   (let [result ((:handler cmd) {:session @sess :model model :cwd cwd :args args})]
                     (if (and (map? result) (= :prompt (:type result)))
                       [{:type :dispatch-prompt :text (:text result)}]
                       [{:type :command-result :command (:name cmd) :text (str "Ran /" (:name cmd))}])))}))

    ;; Track extension
    (swap! registry update :extensions conj ext)
    nil))

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

;; ── Tool/Command Access ───────────────────────────────────────────────────────

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
