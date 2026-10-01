(ns xi.api.core
  "The one gate behind every xi.api.* call a user extension makes.

   User extensions run sandboxed (no node:*, no npm, no raw js/ globals); the
   only side effects they get are the xi.api.fs / .sh / .http functions, and
   each call is decided by the rules engine exactly like an agent tool call,
   with the request tagged `:extension <id>` (so rules can target an
   extension, and grants never leak to the agent or other extensions).

   The ctx an extension passes in is the one the loader hands it:
     {:extension id :room-id :cwd :get-state :confirm! :dispatch!}
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

(defn set-permissions!
  "Replace the declared permissions of every loaded user extension."
  [id->permissions]
  (reset! declared id->permissions))

(defn permission
  "The extension in `ctx`'s declaration for permission `k`, or nil."
  [{:keys [extension]} k]
  (get-in @declared [extension k]))

(defn base-cwd
  "Where an extension's relative paths resolve: the room cwd, else its data dir."
  [{:keys [cwd extension]}]
  (or cwd (paths/extension-data-dir extension)))

(defn- describe [{:keys [tool path command host]}]
  (str (name tool) " " (or host command path)))

(defn gate!
  "Decide request map `m` ({:tool …} plus its target) for the extension in
   `ctx`. → Promise of the full decision request when allowed; rejects with an
   ex-info carrying the refusal otherwise."
  [{:keys [extension] :as ctx} m]
  (when-not extension
    (throw (ex-info "xi.api: missing :extension in ctx — pass the ctx your extension was given" {})))
  (let [cwd (base-cwd ctx)
        req (store/request (assoc m :extension (name extension)) (assoc ctx :cwd cwd))]
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
