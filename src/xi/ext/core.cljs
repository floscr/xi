(ns xi.ext.core
  "Extension registry and lifecycle dispatcher.
   Extensions register via maps with :name, :hooks, :tools, :commands.
   Hooks are dispatched at lifecycle points matching Pi's extension API."
  (:require [xi.command-registry :as cmd-registry]))

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
    :input :session-before-compact})

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

(defn dispatch-hook
  "Dispatch a lifecycle hook synchronously. Returns nil.
   For hooks that transform data (context, input, tool-call), use dispatch-hook-transform."
  [event ctx]
  (doseq [{:keys [handler]} (get-in @registry [:hooks event])]
    (try
      (handler ctx)
      (catch :default e
        (js/console.error (str "[ext] Error in " (name event) " hook:") e)))))

(defn dispatch-hook-transform
  "Dispatch a transforming hook — each handler receives the result of the previous.
   Returns the final transformed value, or nil if any handler returns nil (= blocked)."
  [event initial-value ctx]
  (reduce
   (fn [value {:keys [handler]}]
     (if (nil? value)
       ;; Already blocked by a previous handler — short-circuit
       (reduced nil)
       (try
         (handler value ctx)
         (catch :default e
           (js/console.error (str "[ext] Error in " (name event) " transform hook:") e)
           value))))
   initial-value
   (get-in @registry [:hooks event])))

(defn dispatch-hook-async
  "Dispatch a lifecycle hook that may return promises. Returns a promise."
  [event ctx]
  (reduce
   (fn [chain {:keys [handler]}]
     (.then chain
            (fn [_]
              (try
                (let [result (handler ctx)]
                  (if (instance? js/Promise result) result (js/Promise.resolve)))
                (catch :default e
                  (js/console.error (str "[ext] Error in " (name event) " hook:") e)
                  (js/Promise.resolve))))))
   (js/Promise.resolve)
   (get-in @registry [:hooks event])))

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
