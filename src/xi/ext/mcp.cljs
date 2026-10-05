(ns xi.ext.mcp
  "MCP-as-extension helper. An MCP server is nothing but a bag of tools
   reachable over a wire protocol — exactly what Xi's extension tool surface
   (:tool-definitions + :tool-registry) already models. So instead of teaching
   the provider about MCP, we wrap each configured MCP server as an ordinary
   extension and register it into the live extension manager (xi.ext.manager).
   Enable/disable, hot-swap, and the /ext list all then work for free.

   Registry: ~/.config/xi/mcp.edn, an EDN map keyed by server id:

     {:context7 {:transport :stdio
                 :command   \"npx\"
                 :args      [\"-y\" \"@upstash/context7-mcp\"]
                 :enabled   true}
      :render   {:transport :http
                 :url       \"https://mcp.render.com/mcp\"
                 ;; API key resolved at connect time from the gitignored
                 ;; per-extension config (xi.ext.config) -- not stored here:
                 :auth      {:ext-config \"render\" :key \"RENDER_API_KEY\"
                             :header \"Authorization\" :scheme \"Bearer\"}
                 :enabled   false}}

   Optional per entry: `:timeout-ms` (per-request cap, default 120000, <= 0
   off) and `:hidden-tools` (tool names not offered to the agent, still
   callable by user extensions through xi.api.mcp).

   Each server's discovered tools are cached to
   ~/.config/xi/mcp/<id>/tools.edn so they advertise synchronously on the
   next start; the actual subprocess is spawned lazily on first tool call.
   Tools are namespaced `mcp__<id>__<tool>` (the ecosystem convention) to
   avoid colliding with Xi's built-in tools; the wrapper strips the prefix
   before forwarding to the server.

   /mcp list | add <id> <command...> | enable <id> | disable <id>
        | remove <id> | refresh <id> | auth <id>
   is the control command (a factory gated on a :manager in ctx, like /ext)."
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [xi.ext.config :as ext-config]
            [xi.ext.manager :as manager]
            [xi.mcp.client :as client]
            [xi.mcp.trust :as trust]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

;; ── Paths ────────────────────────────────────────────────────────────────────

(defn- config-dir [] (path/join (os/homedir) ".config" "xi"))
(defn- registry-file [] (trust/registry-file))
(defn- server-dir [id] (path/join (config-dir) "mcp" (name id)))
(defn- tools-cache-file [id] (path/join (server-dir id) "tools.edn"))

;; ── Pure helpers ──────────────────────────────────────────────────────────────

(defn ext-id
  "Manager extension id for a server id, e.g. :context7 -> :mcp-context7."
  [id]
  (keyword (str "mcp-" (name id))))

(defn qualify-name
  "Namespaced advertised tool name: mcp__<id>__<tool>."
  [id tool-name]
  (str "mcp__" (name id) "__" tool-name))

(defn parse-qualified-name
  "Inverse of qualify-name: mcp__<id>__<tool> -> {:server <id> :tool <tool>},
   or nil when `n` is not an external MCP tool name. Built-in Xi tools have
   bare names (bash, read, …), so they yield nil and the gate skips them."
  [n]
  (when (and n (str/starts-with? n "mcp__"))
    (let [body (subs n (count "mcp__"))
          idx  (str/index-of body "__")]
      (when idx
        {:server (subs body 0 idx)
         :tool   (subs body (+ idx 2))}))))

(defn normalize-tool-def
  "MCP tool map -> Xi tool-definition (namespaced, :input_schema key)."
  [id t]
  {:name         (qualify-name id (:name t))
   :description  (or (:description t) "")
   :input_schema (or (:inputSchema t) {:type "object" :properties {}})})

(defn normalize-result
  "MCP tools/call result (raw JS {content, isError}) -> Xi tool result."
  [js-result]
  (let [m (js->clj js-result :keywordize-keys true)]
    {:content  (or (:content m) [{:type "text" :text ""}])
     :is-error (boolean (:isError m))}))

(defn enabled-entry?
  "An entry is enabled unless it explicitly sets :enabled false."
  [entry]
  (not (false? (:enabled entry))))

(defn parse-command
  "Parse /mcp args into {:sub :id :command :args}. `add` takes the rest of the
   line as a command + its args; the other subs take a single id."
  [args]
  (let [toks (remove str/blank? (str/split (str/trim (or args "")) #"\s+"))]
    (if (empty? toks)
      {:sub "list"}
      (let [[sub id & more] toks]
        (cond-> {:sub sub}
          id            (assoc :id id)
          (= sub "add") (assoc :command (first more) :args (vec (rest more))))))))

;; ── Registry + cache I/O ──────────────────────────────────────────────────────

(defn- read-edn [file]
  (try
    (when (fs/existsSync file)
      (reader/read-string {:default (fn [_ v] v)} (str (fs/readFileSync file "utf8"))))
    (catch :default _ nil)))

(defn read-registry
  "The MCP registry map (id -> entry), or {} when absent/unreadable."
  []
  (trust/read-registry))

(defn- write-registry! [m]
  (fs/mkdirSync (config-dir) #js {:recursive true})
  (fs/writeFileSync (registry-file) (pr-str m)))

(defn- read-cached-tools [id]
  (read-edn (tools-cache-file id)))

(defn- write-cached-tools! [id tools]
  (fs/mkdirSync (server-dir id) #js {:recursive true})
  (fs/writeFileSync (tools-cache-file id) (pr-str (vec tools))))

;; ── Transport ─────────────────────────────────────────────────────────────────

(defn- resolve-auth-header
  "Resolve an entry's :auth descriptor into a single [header value] pair, or
   nil when the secret is absent. Keeps API keys out of mcp.edn: the descriptor
   names where to read the secret ({:ext-config <id> :key <ENV_KEY>}) and how
   to shape the header ({:header <name> :scheme <prefix>})."
  [{:keys [ext-config key header scheme]}]
  (when (and header key)
    (when-let [secret (ext-config/get-value (or ext-config :xi) key)]
      [header (if (str/blank? scheme) secret (str scheme " " secret))])))

(defn- http-headers
  "Headers for an :http entry: its literal :headers plus the resolved :auth
   header (if any)."
  [entry]
  (let [base (or (:headers entry) {})]
    (if-let [auth (some-> (:auth entry) resolve-auth-header)]
      (conj base auth)
      base)))

(defn- connect-entry
  "Open a client for a registry entry. :stdio spawns a subprocess; :http POSTs
   to a hosted server (auth resolved from the gitignored per-extension config).
   Returns a promise of the client, or a rejected promise for unknown
   transports."
  [entry]
  (case (or (:transport entry) :stdio)
    :stdio (client/connect (select-keys entry [:command :args :env :cwd :timeout-ms]))
    :http  (client/connect-http {:url        (:url entry)
                                 :headers    (http-headers entry)
                                 :timeout-ms (:timeout-ms entry)})
    (js/Promise.reject
     (js/Error. (str "Unknown MCP transport " (pr-str (:transport entry)))))))

;; ── Extension construction ────────────────────────────────────────────────────

(defn call-meta
  "The `_meta` sent with every tools/call: the turn context a server can't get
   from the tool arguments. Keys are `xi/`-prefixed (the spec's reserved-prefix
   convention); nil values are left out.
     xi/cwd        the room's working directory
     xi/roomId     the room making the call
     xi/clientPid  OS pid of the client driving that room (a TUI), so a
                   server can act near the user's terminal
     xi/extension  the user extension calling, when it isn't the agent"
  [{:keys [cwd room-id client-pid extension]}]
  (cond-> {}
    cwd        (assoc "xi/cwd" cwd)
    room-id    (assoc "xi/roomId" room-id)
    client-pid (assoc "xi/clientPid" client-pid)
    extension  (assoc "xi/extension" (name extension))))

;; server id → (fn [tool args ctx] → Promise<xi tool result>) for every built
;; server, so xi.api.mcp reaches the same connection the agent's tools use (a
;; stateful server like a browser must not be started twice).
(defonce ^:private callers (atom {}))

;; The manager install! registered the servers into: whether a server is
;; enabled is the manager's call, also for xi.api.mcp.
(defonce ^:private manager* (atom nil))

(defn- close-conn! [conn]
  (when-let [p @conn]
    (reset! conn nil)
    (-> p (.then (fn [c] ((:close c)))) (.catch (fn [_] nil)))))

(defn build-extension
  "Build an MCP server extension from a registry entry (which must carry :id).
   Advertises the on-disk cached tools synchronously; spawns the server lazily
   (memoized) on the first tool call, and again after it died; closes it on
   :on-disable and at shutdown. Each call carries the turn context as `_meta`
   (see call-meta). Tools named in the entry's `:hidden-tools` aren't offered
   to the agent; user extensions still reach them (xi.api.mcp)."
  [{:keys [id] :as entry}]
  (let [hidden   (set (:hidden-tools entry))
        cached   (remove #(contains? hidden (:name %)) (read-cached-tools id))
        conn     (atom nil)                    ;; memoized client promise
        live     (atom nil)                    ;; the connected client, once up
        connect! (fn []
                   (when-let [c @live]
                     (when ((:dead? c))
                       (reset! conn nil)
                       (reset! live nil)))
                   (or @conn
                       (reset! conn (-> (connect-entry entry)
                                        (.then (fn [c] (reset! live c) c))))))
        call     (fn [tool-name args ctx]
                   (-> (connect!)
                       (.then (fn [c] (client/call-tool c tool-name (clj->js args)
                                                        (call-meta ctx))))
                       (.then normalize-result)
                       (.catch (fn [e]
                                 ;; drop a dead / failed client so the next call
                                 ;; reconnects
                                 (when (or (nil? @live) ((:dead? @live)))
                                   (reset! conn nil)
                                   (reset! live nil))
                                 {:content  [{:type "text"
                                              :text (str "MCP server '" (name id)
                                                         "' error: " (.-message e))}]
                                  :is-error true}))))
        stop!    (fn [] (reset! live nil) (close-conn! conn))]
    (swap! callers assoc (keyword id) call)
    {:id               (ext-id id)
     :tool-definitions (mapv #(normalize-tool-def id %) cached)
     :tool-registry    (into {} (map (fn [t] [(qualify-name id (:name t))
                                              (fn [args ctx] (call (:name t) args ctx))]))
                             cached)
     :on-disable       stop!
     :on-shutdown      stop!}))

(defn enabled?
  "Whether server `id` is registered and enabled in the live manager."
  [id]
  (boolean
   (when-let [mgr @manager*]
     (some #(and (= (:id %) (ext-id id)) (:enabled? %)) (manager/ext-list mgr)))))

(defn call-tool!
  "Call `tool` (unqualified) on configured server `id` over the agent's own
   connection → Promise<xi tool result>. Rejects when the server isn't
   configured or is disabled. No policy here: callers gate first
   (xi.api.mcp)."
  [id tool args ctx]
  (let [kid (keyword id)
        f   (get @callers kid)]
    (cond
      (nil? f)
      (js/Promise.reject (js/Error. (str "MCP server '" (name kid) "' is not configured (mcp.edn)")))

      (not (enabled? kid))
      (js/Promise.reject (js/Error. (str "MCP server '" (name kid) "' is disabled")))

      :else (f tool args ctx))))

;; ── Install (called from xi.cli after seed!) ──────────────────────────────────

(defn install!
  "Register every configured MCP server into the manager (enabled per its
   :enabled flag). Lazy — no servers are spawned here; tools advertise from
   the cache. Call AFTER manager/seed! (seed! resets the registry)."
  [mgr]
  (reset! manager* mgr)
  (doseq [[id entry] (read-registry)]
    (let [entry (assoc entry :id id)]
      (manager/register! mgr (build-extension entry)
                         {:enable? (enabled-entry? entry)})))
  mgr)

;; ── Discovery (connect once, cache tools) ─────────────────────────────────────

(defn- discover!
  "Connect to `entry`, fetch + cache its tool list, close the connection.
   Returns a promise of the tool vector."
  [{:keys [id] :as entry}]
  (-> (connect-entry entry)
      (.then (fn [c]
               (-> (client/list-tools c)
                   (.then (fn [tools]
                            (write-cached-tools! id tools)
                            (try ((:close c)) (catch :default _ nil))
                            tools))
                   (.catch (fn [e]
                             (try ((:close c)) (catch :default _ nil))
                             (throw e))))))))

;; ── /mcp command ──────────────────────────────────────────────────────────────

(defn- status! [dispatch! room-id text]
  (dispatch! {:type :history/append :room-id room-id
              :entry {:kind :status :text text}}))

(defn- discover-and-register!
  "Connect to `entry`, cache its tools, then (re)register the extension in the
   manager (enabled per `entry`'s :enabled). Reports progress via dispatch!.
   Returns the discover promise."
  [mgr dispatch! room-id kid entry]
  (-> (discover! (assoc entry :id kid))
      (.then (fn [tools]
               (manager/unregister! mgr (ext-id kid))
               (manager/register! mgr (build-extension (assoc entry :id kid))
                                  {:enable? (enabled-entry? entry)})
               (status! dispatch! room-id
                        (str "Refreshed '" (name kid) "': " (count tools) " tools cached."
                             " Takes effect on the next turn."))))
      (.catch (fn [e]
                (status! dispatch! room-id
                         (str "Failed to refresh '" (name kid) "': " (.-message e)))))))

(defn- render-list [mgr]
  (let [reg (read-registry)]
    (if (empty? reg)
      "No MCP servers configured. Add one with /mcp add <id> <command> [args...]"
      (str "MCP servers:\n"
           (str/join "\n"
                     (map (fn [[id entry]]
                            (let [enabled? (manager/known? mgr (ext-id id))
                                  on?      (some #(and (= (:id %) (ext-id id)) (:enabled? %))
                                                 (manager/ext-list mgr))
                                  n        (count (read-cached-tools id))]
                              (str "  " (if on? "[x]" "[ ]") " " (name id)
                                   " (" (name (or (:transport entry) :stdio)) ", "
                                   n " tool" (when (not= n 1) "s") ", "
                                   (if (trust/trusted-entry? id entry) "trusted" "asks") ")"
                                   (when-not enabled? "  — not loaded, restart or /mcp refresh"))))
                          reg))))))

(defn- add-fx [mgr {:keys [dispatch!]} {:keys [room-id id command args]}]
  (cond
    (str/blank? id)
    (status! dispatch! room-id "Usage: /mcp add <id> <command> [args...]")

    (str/blank? command)
    (status! dispatch! room-id (str "Usage: /mcp add " id " <command> [args...]"))

    :else
    (let [kid   (keyword id)
          entry {:id kid :transport :stdio :command command :args (vec args) :enabled true}]
      (status! dispatch! room-id (str "Connecting to MCP server '" id "'…"))
      (-> (discover! entry)
          (.then (fn [tools]
                   (write-registry! (assoc (read-registry) kid
                                           (dissoc entry :id)))
                   (manager/register! mgr (build-extension entry) {:enable? true})
                   (status! dispatch! room-id
                            (str "Added MCP server '" id "' with " (count tools)
                                 " tool" (when (not= (count tools) 1) "s")
                                 ". Takes effect on the next turn."))))
          (.catch (fn [e]
                    (status! dispatch! room-id
                             (str "Failed to add '" id "': " (.-message e)))))))))

(defn- refresh-fx [mgr {:keys [dispatch!]} {:keys [room-id id]}]
  (let [kid   (keyword id)
        entry (get (read-registry) kid)]
    (cond
      (str/blank? id) (status! dispatch! room-id "Usage: /mcp refresh <id>")
      (nil? entry)    (status! dispatch! room-id (str "Unknown MCP server '" id "'. Use /mcp list."))
      :else
      (do
        (status! dispatch! room-id (str "Refreshing MCP server '" id "'…"))
        (discover-and-register! mgr dispatch! room-id kid entry)))))

(defn- toggle-fx [mgr {:keys [dispatch!]} {:keys [room-id action id]}]
  (let [kid   (keyword id)
        reg   (read-registry)
        entry (get reg kid)]
    (cond
      (str/blank? id) (status! dispatch! room-id (str "Usage: /mcp " (name action) " <id>"))
      (nil? entry)    (status! dispatch! room-id (str "Unknown MCP server '" id "'. Use /mcp list."))
      :else
      (let [enable? (= action :enable)
            entry'  (assoc entry :enabled enable?)]
        (write-registry! (assoc reg kid entry'))
        (cond
          ;; enabling a server whose tools were never fetched — connect, cache
          ;; the tool list, and (re)register so the tools actually surface.
          (and enable? (empty? (read-cached-tools kid)))
          (do (status! dispatch! room-id (str "Connecting to MCP server '" id "'…"))
              (discover-and-register! mgr dispatch! room-id kid entry'))

          ;; enabling a server that was never registered this session (added to
          ;; the file out of band, or disabled at startup) — register it fresh
          (and enable? (not (manager/known? mgr (ext-id kid))))
          (do (manager/register! mgr (build-extension (assoc entry :id kid)) {:enable? true})
              (status! dispatch! room-id (str id " enabled. Takes effect on the next turn.")))

          :else
          (let [changed (if enable?
                          (manager/enable! mgr (ext-id kid))
                          (manager/disable! mgr (ext-id kid)))]
            (status! dispatch! room-id
                     (if changed
                       (str id " " (if enable? "enabled." "disabled.") " Takes effect on the next turn.")
                       (str id " already " (if enable? "enabled." "disabled."))))))))))

(defn- remove-fx [mgr {:keys [dispatch!]} {:keys [room-id id]}]
  (let [kid (keyword id)
        reg (read-registry)]
    (cond
      (str/blank? id)         (status! dispatch! room-id "Usage: /mcp remove <id>")
      (not (contains? reg kid)) (status! dispatch! room-id (str "Unknown MCP server '" id "'. Use /mcp list."))
      :else
      (do
        (manager/unregister! mgr (ext-id kid))
        (write-registry! (dissoc reg kid))
        (try (fs/rmSync (server-dir kid) #js {:recursive true :force true})
             (catch :default _ nil))
        (status! dispatch! room-id (str "Removed MCP server '" id "'."))))))

(defn- trust-fx
  "Trust (or untrust) server `id` as it is configured now."
  [{:keys [dispatch!]} {:keys [room-id id untrust?]}]
  (cond
    (str/blank? id)
    (status! dispatch! room-id (str "Usage: /mcp " (if untrust? "untrust" "trust") " <id>"))

    (not (contains? (read-registry) (keyword id)))
    (status! dispatch! room-id (str "Unknown MCP server '" id "'. Use /mcp list."))

    untrust?
    (do (trust/untrust! id)
        (status! dispatch! room-id (str "MCP server '" id "' untrusted — its calls ask again.")))

    :else
    (let [{:keys [sha]} (trust/trust! id)]
      (status! dispatch! room-id
               (str "MCP server '" id "' trusted — its calls run without asking until "
                    "its code or mcp.edn entry changes (sha256 " (subs sha 0 12) "…).")))))

(defn- mcp-command
  [_st {:keys [room-id args]}]
  (let [{:keys [sub id command args]} (parse-command args)]
    (case sub
      "trust"   {:effects [[:mcp/trust-server {:room-id room-id :id id}]]}
      "untrust" {:effects [[:mcp/trust-server {:room-id room-id :id id :untrust? true}]]}
      "add"     {:effects [[:mcp/add     {:room-id room-id :id id :command command :args args}]]}
      "enable"  {:effects [[:mcp/toggle  {:room-id room-id :action :enable  :id id}]]}
      "disable" {:effects [[:mcp/toggle  {:room-id room-id :action :disable :id id}]]}
      "remove"  {:effects [[:mcp/remove  {:room-id room-id :id id}]]}
      "refresh" {:effects [[:mcp/refresh {:room-id room-id :id id}]]}
      "auth"    {:effects [[:mcp/auth    {:room-id room-id :id id}]]}
      {:effects [[:mcp/list {:room-id room-id}]]})))

(defn- list-fx [mgr {:keys [dispatch!]} {:keys [room-id]}]
  (status! dispatch! room-id (render-list mgr)))

(defn- auth-fx [_mgr {:keys [dispatch!]} {:keys [room-id]}]
  (status! dispatch! room-id
           "/mcp auth (OAuth for hosted MCP servers) is not implemented yet — Phase C."))

(defn create
  "Factory — the /mcp control extension, or nil when no manager is in ctx.
   Also installs configured MCP servers into the manager as a side effect the
   first time it runs with a manager (see xi.cli, which calls install!).

   Every external MCP tool call is confirmed before it runs — but that gate now
   lives in the rules engine (xi.rules.defaults has a default `{:match {:tool
   :mcp} :action {:type :ask …}}` rule, and the rules ext renders the
   informative server/tool/arguments block). [a]lways there persists a session
   allow-rule narrowed to that mcp server + tool, so this ext no longer carries
   its own policy hook or allow-list."
  [{:keys [manager]}]
  (when manager
    {:id        :mcp
     :commands [{:name "mcp"
                 :description "Manage MCP servers (list/add/enable/disable/remove/refresh)"
                 :handler mcp-command
                 :subcommands [{:name "list"    :description "List configured MCP servers"}
                               {:name "add"     :description "Add a stdio MCP server: add <id> <command> [args...]"}
                               {:name "enable"  :description "Enable a server by id"}
                               {:name "disable" :description "Disable a server by id"}
                               {:name "remove"  :description "Remove a server by id"}
                               {:name "refresh" :description "Reconnect and re-cache a server's tools"}
                               {:name "trust"   :description "Run a server's tools without asking (until its code changes)"}
                               {:name "untrust" :description "Ask again before a server's tool calls"}]}]
     ;; the MCP ask's "Always (trust this server)" answer (xi.rules.defaults
     ;; ::mcp-confirm) dispatches this, carrying the server of the call
     :handlers {:mcp/trust
                (fn [_st {:keys [room-id mcp-server]}]
                  {:effects [[:mcp/trust-server {:room-id room-id :id mcp-server}]]})}
     :fx       {:mcp/list    (partial list-fx    manager)
                :mcp/add     (partial add-fx     manager)
                :mcp/toggle  (partial toggle-fx  manager)
                :mcp/remove  (partial remove-fx  manager)
                :mcp/refresh (partial refresh-fx manager)
                :mcp/auth    (partial auth-fx    manager)
                :mcp/trust-server trust-fx}}))