(ns xi.runtime
  "Headless runtime — event bus, command dispatch, agent lifecycle.
   No UI dependencies. Clients connect and receive events."
  (:require [xi.ext.core :as ext]
            [xi.ext.clipboard-image :as ext-clipboard-image]
            [xi.image :as image]
            [xi.ext.commit :as ext-commit]
            [xi.ext.done-notify :as ext-done-notify]
            [xi.ext.kb :as ext-kb]
            [xi.ext.parmezan :as ext-parmezan]
            [xi.ext.perplexity :as ext-perplexity]
            [xi.ext.permission-gate :as ext-permission-gate]
            [xi.ext.plan-mode :as ext-plan-mode]
            [xi.ext.terminal-title :as ext-terminal-title]
            [xi.ext.web :as ext-web]
            [xi.loop :as loop]
            [xi.provider :as provider]
            [xi.command-registry :as cmd-registry]
            [xi.runtime.commands :as commands]
            [xi.runtime.events :as events]
            [xi.session :as session]
            [xi.system-prompt :as system-prompt]))

(def ^:private DEFAULT_MODEL "claude-sonnet-4-20250514")

(def ^:private THINKING_TO_EFFORT
  "Map Pi thinking levels → Claude SDK effort levels."
  {"minimal" "low"
   "low"     "low"
   "medium"  "medium"
   "high"    "high"
   "xhigh"   "max"})

(defn- load-settings []
  (try
    (let [path (str (aget js/process.env "HOME") "/.pi/agent/settings.json")
          content (.readFileSync (js/require "node:fs") path "utf8")]
      (js->clj (js/JSON.parse content) :keywordize-keys true))
    (catch :default _e {})))

(defn- register-extensions! []
  ;; Register built-in commands first
  (commands/register-builtin-commands!)
  ;; Then extensions (their commands also feed into the central registry)
  (doseq [ext [ext-clipboard-image/extension
               ext-plan-mode/extension
               ext-permission-gate/extension
               ext-kb/extension
               ext-commit/extension
               ext-web/extension
               ext-perplexity/extension
               ext-parmezan/extension
               ext-done-notify/extension
               ext-terminal-title/extension]]
    (ext/register-extension! ext)))

(defn- sync-hook-state!
  "Snapshot runtime state into the ext hook-state atom."
  [rt]
  (let [{:keys [model effort cwd]} @(:state rt)
        sess @(:sess rt)]
    (ext/set-state!
     {:session (dissoc sess :_dir)
      :model   model
      :effort  effort
      :cwd     cwd})))

;; ── Agent Turn ───────────────────────────────────────────────────────────────

(defn- run-agent-turn
  "Run one agent turn. Bridges loop callbacks to event bus.
   prompt-or-map: string or {:text ... :images [...]}"
  [rt prompt-or-map]
  (let [{:keys [emit!]} (:bus rt)
        {:keys [model effort sess cwd agents-md personal-agent?]} @(:state rt)
        cli-session-id (:cli-session-id @sess)
        abort-signal (:abort-signal rt)
        prompt (if (map? prompt-or-map) (:text prompt-or-map) prompt-or-map)
        raw-images (when (map? prompt-or-map) (:images prompt-or-map))
        images (image/process-images raw-images)]

    (emit! {:type :turn-start})

    (-> (loop/run-turn
         (cond-> {:model model
                  :prompt prompt
                  :cwd cwd
                  :effort effort
                  :abort-signal abort-signal
                  :personal-agent? personal-agent?

                  :on-text
                  (fn [text]
                    (emit! {:type :text-delta :text text}))

                  :on-thinking
                  (fn [text]
                    (emit! {:type :thinking :text text}))

                  :on-tool-start
                  (fn [{:keys [id name arguments]}]
                    (emit! {:type :tool-start :id id :name name :arguments arguments}))

                  :on-tool-args
                  (fn [{:keys [id name arguments]}]
                    (emit! {:type :tool-args :id id :name name :arguments arguments}))

                  :on-tool-result
                  (fn [{:keys [name content is-error]}]
                    (emit! {:type :tool-result :id name :content content :is-error is-error}))

                  :on-error
                  (fn [err]
                    (emit! {:type :error :error err}))}
           (seq images) (assoc :images images)
           cli-session-id (assoc :resume-session-id cli-session-id)
           (and agents-md (nil? cli-session-id)) (assoc :system agents-md)))

        (.then (fn [result]
                 (when (:aborted result)
                   (emit! {:type :aborted}))
                 ;; Save session
                 (when-let [sid (:session-id result)]
                   (swap! sess assoc :cli-session-id sid)
                   (when-not (:name @sess)
                     (swap! sess assoc :name (subs prompt 0 (min 60 (count prompt)))))
                   (session/save-session! @sess))
                 ;; Sync hook state so extensions see updated session
                 (sync-hook-state! rt)
                 (emit! {:type :turn-end
                         :session-id (:session-id result)
                         :usage (:usage result)
                         :cost (:cost result)})
                 result)))))

;; ── Public API ───────────────────────────────────────────────────────────────

(defn create!
  "Create a headless runtime. Returns runtime map.
   opts:
     :model - model id (default: claude-sonnet-4)
     :cwd   - working directory (default: process.cwd)
     :personal-agent? - personal assistant mode (no tools)"
  [opts]
  (register-extensions!)
  (let [settings (load-settings)
        personal-agent? (:personal-agent? opts)
        model (or (:model opts)
                  (aget js/process.env "XI_MODEL")
                  (:defaultModel settings)
                  DEFAULT_MODEL)
        effort (or (aget js/process.env "XI_EFFORT")
                   (get THINKING_TO_EFFORT (:defaultThinkingLevel settings))
                   "high")
        cwd (or (:cwd opts) (aget js/process.env "XI_CWD") (.cwd js/process))
        agents-files (if personal-agent? [] (system-prompt/find-agents-md cwd))
        agents-md (if personal-agent?
                    system-prompt/PERSONAL_AGENT_PROMPT
                    (system-prompt/load-agents-md cwd))
        bus (events/create-bus)
        sess (atom (session/create-session cwd))
        state (atom {:model model
                     :effort effort
                     :cwd cwd
                     :sess sess
                     :agents-md agents-md
                     :personal-agent? personal-agent?})
        event-history (atom [])
        rt {:bus bus
            :state state
            :sess sess
            :busy (atom false)
            :abort-signal (atom false)
            :pending-prompt (atom nil)
            :clients (atom #{})
            :event-history event-history}]

    ((:subscribe! bus) :*
     (fn [event]
       (let [t (:type event)]
         (if (= t :session-cleared)
           (reset! event-history [])
           (when-not (#{:ready :quit} t)
             (swap! event-history conj event))))))

    ;; Seed the hook state so extensions can read it immediately
    (sync-hook-state! rt)

    ((:emit! bus) {:type :ready
                   :model model
                   :cwd cwd
                   :agents-files agents-files
                   :extensions (ext/list-extensions)})
    rt))

(defn subscribe!
  "Subscribe to runtime events. Returns unsubscribe fn."
  [rt event-type handler]
  ((:subscribe! (:bus rt)) event-type handler))

(defn connect!
  "Connect a client to the runtime. Client is a map with :on-event (required),
   :on-connect (optional), :on-disconnect (optional)."
  [rt client]
  (swap! (:clients rt) conj client)
  ;; Subscribe client to all events
  (let [unsub ((:subscribe! (:bus rt)) :* (:on-event client))]
    ;; Store unsub fn on client for disconnect
    (swap! (:clients rt) disj client)
    (let [client-with-unsub (assoc client ::unsub unsub)]
      (swap! (:clients rt) conj client-with-unsub)
      ;; Call on-connect
      (when-let [on-connect (:on-connect client)]
        (on-connect rt))
      ;; Replay ready event
      (let [{:keys [model cwd agents-md]} @(:state rt)
            agents-files (system-prompt/find-agents-md cwd)]
        ((:on-event client) {:type :ready
                             :model model
                             :cwd cwd
                             :agents-files agents-files
                             :extensions (ext/list-extensions)}))
      ;; Replay event history for late-joining clients
      (let [history @(:event-history rt)]
        (when (seq history)
          ((:on-event client) {:type :history :events history})))
      client-with-unsub)))

(defn disconnect!
  "Disconnect a client from the runtime."
  [rt client]
  (when-let [unsub (::unsub client)]
    (unsub))
  (swap! (:clients rt) disj client)
  (when-let [on-disconnect (:on-disconnect client)]
    (on-disconnect)))

(defn dispatch!
  "Send a command to the runtime. Returns a promise."
  [rt command]
  (let [{:keys [emit!]} (:bus rt)
        {:keys [model cwd effort]} @(:state rt)
        sess (:sess rt)
        parsed (if (string? command)
                 (commands/parse-input command)
                 command)]
    (when parsed
      (case (:type parsed)
        :prompt
        ;; Run :input hook to allow extensions to transform (e.g. clipboard images)
        (let [input-event (ext/dispatch-hook-transform
                           :input
                           {:text (:text parsed) :images (:images parsed)})
              prompt-data (if (seq (:images input-event))
                           {:text (:text input-event) :images (:images input-event)}
                           (:text input-event))]
        (cond
          ;; Not busy — run immediately
          (not @(:busy rt))
          (do (reset! (:busy rt) true)
              (reset! (:abort-signal rt) false)
              (reset! (:pending-prompt rt) nil)
              (emit! (cond-> {:type :user-message :text (:text input-event)}
                       (seq (:images input-event)) (assoc :images (:images input-event))))
              (emit! {:type :busy-changed :busy true})
              (-> (run-agent-turn rt prompt-data)
                  (.then (fn [result]
                           (reset! (:busy rt) false)
                           (emit! {:type :busy-changed :busy false})
                           ;; Check for queued prompt from interrupt-then-type
                           (when-let [queued @(:pending-prompt rt)]
                             (reset! (:pending-prompt rt) nil)
                             (dispatch! rt queued))
                           result))
                  (.catch (fn [err]
                            (reset! (:busy rt) false)
                            (emit! {:type :busy-changed :busy false})
                            (emit! {:type :error :error {:type "error" :message (.-message err)}})
                            ;; Check for queued prompt even after error
                            (when-let [queued @(:pending-prompt rt)]
                              (reset! (:pending-prompt rt) nil)
                              (dispatch! rt queued))
                            nil))))

          ;; Busy but aborting — queue prompt for after turn settles
          @(:abort-signal rt)
          (do (reset! (:pending-prompt rt) prompt-data)
              (js/Promise.resolve nil))

          ;; Busy, not aborting — drop
          :else
          (js/Promise.resolve nil)))

        :command
        (let [events (commands/handle-command parsed {:sess sess :cwd cwd :model model
                                                        :effort effort
                                                        :busy @(:busy rt)
                                                        :event-history @(:event-history rt)})
              prompt-event (first (filter #(= :dispatch-prompt (:type %)) events))
              other-events (remove #(= :dispatch-prompt (:type %)) events)]
          (doseq [event other-events]
            (when (= :model-changed (:type event))
              (swap! (:state rt) assoc :model (:model event))
              (provider/clear-session!))
            (emit! event))
          (if prompt-event
            (dispatch! rt (:text prompt-event))
            (js/Promise.resolve events)))

        :abort
        (do (when (and @(:busy rt) (not @(:abort-signal rt)))
              (reset! (:abort-signal rt) true)
              (emit! {:type :aborted}))
            (js/Promise.resolve nil))

        :quit
        (do (emit! {:type :quit})
            (js/Promise.resolve nil))

        (js/Promise.resolve nil)))))

(defn busy?
  "Is the runtime currently running an agent turn?"
  [rt]
  @(:busy rt))
