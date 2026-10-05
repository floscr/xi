(ns xi.api.core
  "The one gate behind every xi.api.* call a user extension makes.

   User extensions run sandboxed (no node:*, no npm, no raw js/ globals); the
   only side effects they get are the xi.api.fs / .sh / .http functions, and
   each call is decided by the rules engine exactly like an agent tool call,
   with the request tagged `:extension <id>` (so rules can target an
   extension, and grants never leak to the agent or other extensions).

   The ctx an extension passes in is the one the loader hands it:
     {:extension id :xi.api/token t :room-id :cwd :get-state :confirm! :dispatch!}
   The caller is resolved from the token, never from :extension: the token is
   an opaque object the loader minted for that extension (`issue-token!`) and
   the sandbox cannot build one, so a ctx that names another extension's id
   is refused instead of borrowing its grants and declared permissions.
   Without a room cwd, paths resolve against the extension's data dir. An :ask
   with no confirm! (no room / no client) is a refusal, never a pass."
  (:require [clojure.string :as str]
            [xi.ext.rules :as rules-ext]
            [xi.paths :as paths]
            [xi.rules.store :as store]))

;; Extension id → its declared `:permissions` map, set by the loader
;; (xi.ext.user). Capabilities that need an up-front declaration read it here,
;; never from ctx, which the extension's own code passes in.
(defonce ^:private declared (atom {}))

;; token object → extension id, for every token ever issued. Keyed by object
;; identity, so a token stays valid exactly as long as some ctx closure holds
;; it and can't be forged from a value.
(defonce ^:private tokens (js/WeakMap.))

(defn set-permissions!
  "Replace the declared permissions of every loaded user extension."
  [id->permissions]
  (reset! declared id->permissions))

;; extension id → {key → dispose fn}: what an extension holds open (spawned
;; processes, its headless browser). `dispose!` releases all of it when the
;; extension unmounts (reload / removal), so old code leaves nothing running.
(defonce ^:private owned (atom {}))

(defonce ^:private own-seq (atom 0))

(defn issue-token!
  "Mint the capability token for extension `id` — an opaque object the loader
   stamps into every ctx it hands that extension (`:xi.api/token`, see
   xi.ext.user.guard). Call once per load."
  [id]
  (let [t (js/Object.freeze #js {})]
    (.set tokens t id)
    t))

(defn revoke!
  "Invalidate a token for good: every later xi.api.* call made with a ctx that
   carries it is refused. Done when its extension unmounts, so closures of the
   OLD code (a timer, an in-flight promise) can't act after a reload."
  [token]
  (when token (.delete tokens token)))

(defn active?
  "True while `token` is valid, i.e. its extension hasn't unmounted."
  [token]
  (boolean (and token (.has tokens token))))

(defn dispose!
  "Release everything extension `id` still owns → how many resources there were.
   A failing disposer is logged, never fatal."
  [id]
  (let [ds (vals (get @owned id))]
    (swap! owned dissoc id)
    (doseq [d ds]
      (try (d)
           (catch :default e
             (js/console.error (str "[user-ext " (name id) "] dispose failed: " (.-message e))))))
    (count ds)))

(defn caller
  "The id of the extension behind `ctx`, proven by its token. Throws when the
   ctx carries no token, or names an :extension other than the token's."
  [{:keys [extension] :as ctx}]
  (let [id (when-let [t (:xi.api/token ctx)] (.get tokens t))]
    (cond
      (nil? id)
      (throw (ex-info "xi.api: not an extension ctx — pass the ctx your extension was given" {}))

      (and extension (not= extension id))
      (throw (ex-info (str "xi.api: this ctx was issued to extension " (name id)
                           ", not " (name extension) " — pass the ctx your extension was given")
                      {:extension id}))

      :else id)))

(defn permission
  "The calling extension's declaration for permission `k`, or nil."
  [ctx k]
  (get-in @declared [(caller ctx) k]))

(defn own!
  "Register `dispose` (0-arg) as a resource the extension behind `ctx` holds
   open → a 0-arg release fn for when the resource ends on its own. `dispose!`
   runs whatever is still registered. Throws for a revoked / foreign ctx.
   With a `key`, the registration is idempotent: one resource per key (a
   shared browser, say), a later call replaces the earlier disposer."
  ([ctx dispose] (own! ctx (swap! own-seq inc) dispose))
  ([ctx key dispose]
   (let [id (caller ctx)]
     (swap! owned assoc-in [id key] dispose)
     (fn release [] (swap! owned update id dissoc key)))))

;; {:ask! :dispatch! :get-state} of the running app for xi.api.dialog: the
;; dialog opener (xi.ext.core/create-dialogs) plus the app's own, unguarded
;; dispatch. Set by the loader (xi.ext.user/start!); nil before that and in
;; processes without dialogs.
(defonce ^:private dialogs (atom nil))

(defn set-dialog-host!
  "Install (or with nil, remove) the app xi.api.dialog opens dialogs in."
  [h]
  (reset! dialogs h))

(defn dialog-host [] @dialogs)

(defn base-cwd
  "Where an extension's relative paths resolve: the room cwd, else its data dir."
  [{:keys [cwd] :as ctx}]
  (or cwd (paths/extension-data-dir (caller ctx))))

(defn- describe [{:keys [tool path command host mcp-server mcp-tool]}]
  (str (name tool) " " (or host command path
                             (when mcp-server (str mcp-server "/" mcp-tool)))))

(defn gate!
  "Decide request map `m` ({:tool …} plus its target) for the extension behind
   `ctx`. → Promise of the full decision request when allowed; rejects with an
   ex-info carrying the refusal otherwise."
  [ctx m]
  (let [id  (caller ctx)
        cwd (base-cwd ctx)
        req (store/request (assoc m :extension (name id))
                           (assoc ctx :cwd cwd :extension id))]
    (-> (rules-ext/decide! req ctx)
        (.then (fn [{:keys [decision message]}]
                 (case decision
                   (:allow :approved :pass) req
                   :unanswered
                   (throw (ex-info (str "xi.api: " (describe m) " needs approval, but "
                                        "there is no one to ask (no room/client)")
                                   {:decision decision}))
                   (throw (ex-info (if (str/blank? message)
                                     (str "xi.api: " (describe m) " was denied")
                                     message)
                                   {:decision decision}))))))))
