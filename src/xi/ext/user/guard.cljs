(ns xi.ext.user.guard
  "Capability wrappers for a user extension map. The SCI sandbox already denies
   raw host access; these confine what the extension does through xi's OWN
   surfaces, so a loaded extension can't escalate past the rules engine or
   reach into other extensions / rooms:

     - state:     handler/command results only change [:ext id] and
                  [:rooms * :ext id]; every other change is discarded.
     - dispatch:  events (handler effects, fx/tool dispatch!, keybindings) are
                  limited to the extension's own :ext.<id>/* plus a tiny
                  allowlist (:ui/status, :theme/set, and :prompt/submit / :subagent/spawn
                  from commands/keys).
     - effects:   only the extension's own :fx types and the filtered
                  :app/dispatch pass; anything else is dropped.
     - errors:    every user fn is wrapped so a throw is logged, not fatal.
     - sync:      a changed room slice is re-emitted as :user-ext/sync, so
                  clients (which can't replay the extension's server
                  reducers) mirror it.

   Policy (whether a tool call / xi.api.* op runs at all) is NOT here — that is
   the rules engine, which sees these calls tagged :extension id."
  (:require [clojure.string :as str]
            [xi.api.core :as api-core]))

;; ── Event allowlist ──────────────────────────────────────────────────────────

(def ^:private always-allowed
  "Event types any extension may dispatch, regardless of source."
  #{:ui/status :theme/set})

(def ^:private command-allowed
  "Additional events allowed from commands / keybindings (user-initiated), but
   NOT from tool fns — so an agent tool can't drive the turn loop, start a
   sub-agent without the spawn confirmation, or open chats on its own.
   :chat/start {:text :cwd :client-id} opens a new chat seeded with a user
   message (see xi.server.room-manager). :session/resume {:room-id
   :session-id} loads a saved session into the room, as /resume id:<id> does.
   :terminal/open {:room-id :argv :cwd :title :tag :client-id :user} runs a
   program in a terminal buffer (xi.ext.terminal, rules-gated as :terminal).
   :diff/show {:room-id :client-id :args} opens the diff viewer as /diff
   <args> does (xi.ext.diff.core)."
  #{:prompt/submit :subagent/spawn :chat/start :session/resume :terminal/open
    :diff/show})

(defn- own-event? [id ev-type]
  (= (some-> ev-type namespace) (str "ext." (name id))))

(def ^:private buffer-events #{:ui/buffer-set :ui/buffer-switch :ui/buffer-close})

(defn- own-buffer-event?
  "A buffer event on one of the extension's own buffers: id `\"<id>:…\"`, and
   for :ui/buffer-set a `:kind` namespaced by the id (drawn by its web half's
   :buffer-views, xi.web.user-ext.guard)."
  [id {:keys [type buffer-id buffer]}]
  (and (contains? buffer-events type)
       (string? buffer-id)
       (str/starts-with? buffer-id (str (name id) ":"))
       (or (not= :ui/buffer-set type)
           (and (map? buffer)
                (keyword? (:kind buffer))
                (= (name id) (namespace (:kind buffer)))))))

(defn- allowed?
  [id {ev-type :type :as ev} {:keys [user-initiated?]}]
  (or (own-event? id ev-type)
      (contains? always-allowed ev-type)
      (own-buffer-event? id ev)
      (and user-initiated? (contains? command-allowed ev-type))))

(defn- confine
  "An allowed event as the extension may send it. A :chat/start keeps its
   `:buffer-id` + `:buffer` (installed in the new chat, xi.server.ws) only for
   an own buffer, and its `:on-created` (dispatched with the new chat's
   :room-id) only as an own event, stamped user-initiated: :chat/start itself
   is only allowed from user-initiated sources."
  [id ev]
  (if (= :chat/start (:type ev))
    (let [{:keys [buffer-id buffer on-created]} ev]
      (cond-> (dissoc ev :buffer-id :buffer :on-created)
        (own-buffer-event? id {:type :ui/buffer-set :buffer-id buffer-id :buffer buffer})
        (assoc :buffer-id buffer-id :buffer buffer)

        (and (map? on-created) (own-event? id (:type on-created)))
        (assoc :on-created (assoc on-created ::user-initiated true))))
    ev))

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
             (map? payload)
             (allowed? id payload opts)))))

(defn- confine-dispatches
  "A handler's `[:app/dispatch ev]` never carries the client stamp, and is
   `confine`d."
  [id effects]
  (mapv (fn [[fx-type payload :as effect]]
          (if (and (= :app/dispatch fx-type) (map? payload))
            [fx-type (confine id (dissoc payload ::user-initiated))]
            effect))
        effects))

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

(defn- mark-fx-effects
  "Stamp `::user-initiated` into the payload of a command's own-fx effects, and
   strip it from a handler's, so the fx (guard-fx) knows it was started by the
   user and may dispatch the command-only events — e.g. gather a diff in an
   effect, then :prompt/submit. Only command results pass through here with
   `user-initiated?`; handlers (reachable from a tool's own events) never mark."
  [own-fx user-initiated? effects]
  (mapv (fn [[fx-type payload :as effect]]
          (if (and (contains? own-fx fx-type) (map? payload))
            [fx-type (if user-initiated?
                       (assoc payload ::user-initiated true)
                       (dissoc payload ::user-initiated))]
            effect))
        effects))

(defn- restrict-result
  "Sanitize a handler/command result {:state :effects} against `before`."
  [id own-fx opts before result]
  (when result
    (let [state   (some->> (:state result) (restrict-state id before))
          effects (filterv #(or (allow-effect? id own-fx opts %)
                                (do (log-blocked id (str "effect " (first %))) false))
                           (:effects result))
          effects (->> effects
                       (confine-dispatches id)
                       (mark-fx-effects own-fx (:user-initiated? opts)))
          effects (cond-> effects state (into (sync-effects id before state)))]
      (cond-> {}
        state         (assoc :state state)
        (seq effects) (assoc :effects effects)))))

;; ── Wrappers ─────────────────────────────────────────────────────────────────

(defn- guard-handler
  "An own event a connected client sent (a click in a browser half) arrives
   stamped `::user-initiated` by the WS server, so its handler may use the
   command-only events. Anything the extension itself dispatches is stripped
   of the stamp (guard-dispatch, restrict-result), so it can't forge one."
  [id own-fx opts f]
  (fn [before event]
    (let [opts  (if (::user-initiated event) {:user-initiated? true} opts)
          event (dissoc event ::user-initiated)]
      (try (restrict-result id own-fx opts before (f before event))
           (catch :default e (log-blocked id (str "handler threw: " (.-message e))) nil)))))

(defn- guard-dispatch [id token opts dispatch!]
  (when dispatch!
    (fn [ev]
      (cond
        ;; code of an unmounted (reloaded / removed) extension: its late
        ;; callbacks must not touch the app any more
        (and token (not (api-core/active? token))) nil
        (allowed? id ev opts) (dispatch! (confine id (dissoc ev ::user-initiated)))
        :else (log-blocked id (str "dispatch " (:type ev)))))))

(defn- guard-ctx [id token opts ctx]
  (cond-> ctx
    (:dispatch! ctx) (assoc :dispatch! (guard-dispatch id token opts (:dispatch! ctx)))
    :always          (assoc :extension id)
    token            (assoc :xi.api/token token)))

(defn- guard-fx [id token opts f]
  (fn [ctx payload]
    (let [opts    (if (and (map? payload) (::user-initiated payload))
                    {:user-initiated? true}
                    opts)
          payload (cond-> payload (map? payload) (dissoc ::user-initiated))]
      (try (f (guard-ctx id token opts ctx) payload)
           (catch :default e (log-blocked id (str "fx threw: " (.-message e))) nil)))))

(defn- open-view!
  "After a tool with a view (`:ui {:resource-uri}` on its definition) ran for
   the agent, ask the agent blocks to show it (xi.ext.blocks.app), with the
   host's own dispatch: :ext.blocks/* aren't the extension's events. Not
   when the call came from that view itself."
  [id tool resource-uri args {:keys [dispatch! room-id sub-id call from-app?]} result]
  (when (and dispatch! room-id (not from-app?) (map? result) (not (:is-error result)))
    (dispatch! {:type :ext.blocks/app-open :room-id room-id :sub-id sub-id :call call
                :source {:type :ext :id id}
                :resource-uri resource-uri
                :tool-input args
                :tool-result (select-keys result [:content :structured-content])})))

(defn- guard-tool
  ([id token f] (guard-tool id token f nil))
  ([id token f resource-uri]
   (fn [args ctx]
     (try (let [r (f args (guard-ctx id token {:user-initiated? false} ctx))]
            (when resource-uri
              (-> (js/Promise.resolve r)
                  (.then #(open-view! id nil resource-uri args ctx %))))
            r)
          (catch :default e
            (log-blocked id (str "tool threw: " (.-message e)))
            {:content [{:type "text" :text (str "extension error: " (.-message e))}]
             :is-error true})))))

(defn- guard-ui-resources
  "An extension's MCP Apps views (`:ui-resources {\"ui://<id>/name\" html |
   {:html :csp :prefers-border}}`), kept only under its own ui://<id>/ and
   tagged with the owner."
  [id resources]
  (into {}
        (keep (fn [[uri r]]
                (let [r (if (string? r) {:html r} r)]
                  (if (and (string? uri) (str/starts-with? uri (str "ui://" (name id) "/"))
                           (map? r) (string? (:html r)))
                    [uri (-> (select-keys r [:html :csp :prefers-border]) (assoc :ext id))]
                    (do (log-blocked id (str "ui resource " (pr-str uri) " (must be ui://" (name id) "/…)"))
                        nil)))))
        (when (map? resources) resources)))

(defn- guard-hook
  "A lifecycle hook (:on-mount / :on-unmount): called with the host ctx
   ({:dispatch! :get-state}) turned into the extension's capability ctx."
  [id token f]
  (fn [ctx]
    (try (f (guard-ctx id token {:user-initiated? false} ctx))
         (catch :default e (log-blocked id (str "lifecycle hook threw: " (.-message e))) nil))))

(defn- guard-command [id own-fx f]
  (let [opts {:user-initiated? true}]
    (fn [before ev]
      (try (restrict-result id own-fx opts before (f before ev))
           (catch :default e (log-blocked id (str "command threw: " (.-message e))) nil)))))

(defn- guard-block-kinds
  "An extension's agent block kinds (xi.blocks) named <id>/<name>, so they
   can't shadow a built-in kind or another extension's. A throwing :normalize
   reads as invalid data, a throwing :text as no text. Malformed entries are
   dropped."
  [id kinds]
  (into {}
        (keep (fn [[k d]]
                (let [n (if (keyword? k) (name k) (str k))]
                  (if (and (map? d) (re-matches #"[a-z0-9][a-z0-9-]*" n))
                    [(str (name id) "/" n)
                     (cond-> (select-keys d [:describe :actions :send])
                       (fn? (:normalize d)) (assoc :normalize (:normalize d))
                       (fn? (:text d))
                       (assoc :text (let [f (:text d)]
                                      (fn [spec state]
                                        (try (f spec state)
                                             (catch :default e
                                               (log-blocked id (str "block kind text threw: " (.-message e)))
                                               nil))))))]
                    (do (log-blocked id (str "block kind " (pr-str k))) nil)))))
        (when (map? kinds) kinds)))

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
       (update :tool-registry
               (fn [reg]
                 (let [views (into {} (keep (fn [d] (when-let [u (get-in d [:ui :resource-uri])]
                                                      (when (str/starts-with? (str u) (str "ui://" (name id) "/"))
                                                        [(:name d) u]))))
                                   (:tool-definitions ext))]
                   (into {} (map (fn [[n f]] [n (guard-tool id token f (get views n))])) reg))))

       (:ui-resources ext) (update :ui-resources (partial guard-ui-resources id))

       (:block-kinds ext) (update :block-kinds (partial guard-block-kinds id))

       (:on-mount ext)   (update :on-mount (partial guard-hook id token))
       (:on-unmount ext) (update :on-unmount (partial guard-hook id token))

       ;; a usage source's fetch gets the same capability ctx as a hook
       (:usage-sources ext)
       (update :usage-sources
               (fn [srcs]
                 (mapv (fn [s] (update s :fetch (partial guard-hook id token))) srcs)))

       (:commands ext)
       (update :commands
               (fn [cmds]
                 (mapv (fn [c] (update c :handler (partial guard-command id own-fx))) cmds)))

       (:keybindings ext)
       (update :keybindings
               (fn [kbs]
                 (filterv (fn [{:keys [event]}]
                            (or (allowed? id event {:user-initiated? true})
                                (do (log-blocked id (str "keybinding " (:type event))) false)))
                          kbs)))))))
