(ns xi.ext.user.guard
  "Capability wrappers for a user extension map. The SCI sandbox already denies
   raw host access; these confine what the extension does through xi's OWN
   surfaces, so a loaded extension can't escalate past the rules engine or
   reach into other extensions / rooms:

     - state:     handler/command results only change [:ext id] and
                  [:rooms * :ext id]; every other change is discarded.
     - dispatch:  events (handler effects, fx/tool dispatch!, keybindings) are
                  limited to the extension's own :ext.<id>/* plus a tiny
                  allowlist (:ui/status, and :prompt/submit from commands/keys).
     - effects:   only the extension's own :fx types and the filtered
                  :app/dispatch pass; anything else is dropped.
     - errors:    every user fn is wrapped so a throw is logged, not fatal.
     - sync:      a changed room slice is re-emitted as :user-ext/sync, so
                  clients (which can't replay the extension's server
                  reducers) mirror it.

   Policy (whether a tool call / xi.api.* op runs at all) is NOT here — that is
   the rules engine, which sees these calls tagged :extension id."
  (:require [clojure.string :as str]))

;; ── Event allowlist ──────────────────────────────────────────────────────────

(def ^:private always-allowed
  "Event types any extension may dispatch, regardless of source."
  #{:ui/status})

(def ^:private command-allowed
  "Additional events allowed from commands / keybindings (user-initiated), but
   NOT from tool fns — so an agent tool can't drive the turn loop."
  #{:prompt/submit})

(defn- own-event? [id ev-type]
  (= (some-> ev-type namespace) (str "ext." (name id))))

(defn- allowed?
  [id ev-type {:keys [user-initiated?]}]
  (or (own-event? id ev-type)
      (contains? always-allowed ev-type)
      (and user-initiated? (contains? command-allowed ev-type))))

(defn- log-blocked [id what]
  (js/console.error (str "[user-ext " (name id) "] blocked " what)))

;; ── State slice ──────────────────────────────────────────────────────────────

(defn- restrict-state
  "`after` merged into `before` keeping ONLY the extension's slices: the
   process-level [:ext id] and each existing room's [:rooms rid :ext id]. Any
   other change `after` made is dropped. New rooms are never created."
  [id before after]
  (reduce (fn [s rid]
            (assoc-in s [:rooms rid :ext id] (get-in after [:rooms rid :ext id])))
          (assoc-in before [:ext id] (get-in after [:ext id]))
          (keys (:rooms before))))

;; ── Effects ──────────────────────────────────────────────────────────────────

(defn- allow-effect? [id own-fx opts effect]
  (let [[fx-type payload] effect]
    (or (contains? own-fx fx-type)
        (and (= :app/dispatch fx-type)
             (allowed? id (:type payload) opts)))))

(defn- sync-effects
  "One :user-ext/sync per room whose [:rooms rid :ext id] slice changed.
   Clients mirror room state by replaying the server's reducers, but they
   don't have a user extension's server handlers — so the changed slice
   rides along explicitly (see xi.ext.user/server-extension)."
  [id before after]
  (vec (for [rid   (keys (:rooms before))
             :let  [slice (get-in after [:rooms rid :ext id])]
             :when (not= slice (get-in before [:rooms rid :ext id]))]
         [:app/dispatch {:type :user-ext/sync :room-id rid :ext-id id :state slice}])))

(defn- restrict-result
  "Sanitize a handler/command result {:state :effects} against `before`."
  [id own-fx opts before result]
  (when result
    (let [state   (some->> (:state result) (restrict-state id before))
          effects (filterv #(or (allow-effect? id own-fx opts %)
                                (do (log-blocked id (str "effect " (first %))) false))
                           (:effects result))
          effects (cond-> effects state (into (sync-effects id before state)))]
      (cond-> {}
        state         (assoc :state state)
        (seq effects) (assoc :effects effects)))))

;; ── Wrappers ─────────────────────────────────────────────────────────────────

(defn- guard-handler [id own-fx opts f]
  (fn [before event]
    (try (restrict-result id own-fx opts before (f before event))
         (catch :default e (log-blocked id (str "handler threw: " (.-message e))) nil))))

(defn- guard-dispatch [id opts dispatch!]
  (when dispatch!
    (fn [ev]
      (if (allowed? id (:type ev) opts)
        (dispatch! ev)
        (log-blocked id (str "dispatch " (:type ev)))))))

(defn- guard-ctx [id token opts ctx]
  (cond-> ctx
    (:dispatch! ctx) (assoc :dispatch! (guard-dispatch id opts (:dispatch! ctx)))
    :always          (assoc :extension id)
    token            (assoc :xi.api/token token)))

(defn- guard-fx [id token opts f]
  (fn [ctx payload]
    (try (f (guard-ctx id token opts ctx) payload)
         (catch :default e (log-blocked id (str "fx threw: " (.-message e))) nil))))

(defn- guard-tool [id token f]
  (fn [args ctx]
    (try (f args (guard-ctx id token {:user-initiated? false} ctx))
         (catch :default e
           (log-blocked id (str "tool threw: " (.-message e)))
           {:content [{:type "text" :text (str "extension error: " (.-message e))}]
            :is-error true}))))

(defn- guard-command [id own-fx f]
  (let [opts {:user-initiated? true}]
    (fn [before ev]
      (try (restrict-result id own-fx opts before (f before ev))
           (catch :default e (log-blocked id (str "command threw: " (.-message e))) nil)))))

(defn wrap
  "Return `ext` with every user fn confined (see ns doc). :id / :init /
   :system-prompt / :prompt-badge / :tool-definitions pass through unchanged —
   they carry no capability.

   `token` (xi.api.core/issue-token!) is stamped into every ctx handed to the
   extension's fx and tool fns as :xi.api/token — the proof xi.api.* resolves
   the caller from, so the extension can't pass a ctx naming another id.
   Without one the ctx carries no xi.api capability at all."
  ([ext] (wrap ext nil))
  ([ext token]
   (let [id     (:id ext)
         own-fx (set (keys (:fx ext)))
         h-opts {:user-initiated? false}]
     (cond-> ext
       (:handlers ext)
       (update :handlers update-vals (partial guard-handler id own-fx h-opts))

       (:fx ext)
       (update :fx update-vals (partial guard-fx id token h-opts))

       (:tool-registry ext)
       (update :tool-registry update-vals (partial guard-tool id token))

       (:commands ext)
       (update :commands
               (fn [cmds]
                 (mapv (fn [c] (update c :handler (partial guard-command id own-fx))) cmds)))

       (:keybindings ext)
       (update :keybindings
               (fn [kbs]
                 (filterv (fn [{:keys [event]}]
                            (or (allowed? id (:type event) {:user-initiated? true})
                                (do (log-blocked id (str "keybinding " (:type event))) false)))
                          kbs)))))))
