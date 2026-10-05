(ns xi.ext.user
  "Loads user extensions from ~/.config/xi/extensions/*.cljs at runtime — no
   build step. Each top-level file is evaluated in a capability sandbox (one
   hardened SCI context, xi.sandbox.sci): no node:*, no npm, no raw js/ access.
   The only side effects are the rules-gated xi.api.* capabilities; a file's
   `extension` var (a plain extension map, xi.ext.core) is registered into the
   live manager after the built-ins.

   This namespace does the eval + validation + registration; the capability
   wrappers (state-slice, dispatch/effect filtering) live in xi.ext.user.guard.
   Web halves (a `web-extension` var) are collected for the browser build to
   fetch; they are not composed here."
  (:require [clojure.string :as str]
            [sci.core :as sci]
            [xi.api.chrome]
            [xi.api.core :as api-core]
            [xi.api.dialog]
            [xi.api.fs]
            [xi.api.http]
            [xi.api.json]
            [xi.api.mcp]
            [xi.api.promise :as api-promise]
            [xi.api.sh]
            [xi.core.events]
            [xi.core.state]
            [xi.ext.manager :as manager]
            [xi.ext.user.guard :as guard]
            [xi.sandbox.sci :as sandbox]
            [xi.user-config :as user-config]
            [xi.tools.registry :as registry]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(defn extensions-dir []
  (node-path/join (os/homedir) ".config" "xi" "extensions"))

;; ── The sandbox context ──────────────────────────────────────────────────────

(def ^:private exposed-namespaces
  "The host namespaces a user extension may require. Pure xi helpers + the
   rules-gated capabilities. Nothing here performs an un-gated side effect."
  {'xi.api.chrome  (sci/copy-ns xi.api.chrome  (sci/create-ns 'xi.api.chrome))
   'xi.api.dialog  (sci/copy-ns xi.api.dialog  (sci/create-ns 'xi.api.dialog))
   'xi.api.fs      (sci/copy-ns xi.api.fs      (sci/create-ns 'xi.api.fs))
   'xi.api.sh      (sci/copy-ns xi.api.sh      (sci/create-ns 'xi.api.sh))
   'xi.api.http    (sci/copy-ns xi.api.http    (sci/create-ns 'xi.api.http))
   'xi.api.json    (sci/copy-ns xi.api.json    (sci/create-ns 'xi.api.json))
   'xi.api.mcp     (sci/copy-ns xi.api.mcp     (sci/create-ns 'xi.api.mcp))
   'xi.api.promise api-promise/sci-namespace
   'xi.core.state  (sci/copy-ns xi.core.state  (sci/create-ns 'xi.core.state))
   'xi.core.events (sci/copy-ns xi.core.events (sci/create-ns 'xi.core.events))})

(defn- file-ns->path
  "The .cljs file a required user namespace maps to, under `dir`:
   my-ext.util → <dir>/my_ext/util.cljs (munge `-` → `_`)."
  [dir ns-sym]
  (let [rel (-> (str ns-sym) (str/replace "-" "_") (str/replace "." "/"))]
    (node-path/join dir (str rel ".cljs"))))

(defn- make-ctx
  "A fresh sandbox SCI context for the extensions in `dir`. A `:load-fn` lets a
   user file require its own sibling namespaces; anything else is unresolvable."
  [dir]
  (sandbox/init
   {:namespaces exposed-namespaces
    :load-fn (fn [{:keys [namespace]}]
               (let [f (file-ns->path dir namespace)]
                 (when (fs/existsSync f)
                   {:file f :source (str (fs/readFileSync f "utf8"))})))}))

;; ── Validation ───────────────────────────────────────────────────────────────

(def ^:private allowed-keys
  "Extension-map keys a user extension may declare. No policy hook exists (that
   is the rules engine's job) and no :event-hooks (rewrites/blocks any event)."
  #{:id :init :handlers :fx :commands :tool-definitions :tool-registry
    :system-prompt :keybindings :prompt-badge :on-shutdown :on-enable
    :on-disable :permissions
    ;; load lifecycle: (fn [ctx]) after the extension is (re)registered / before
    ;; it is replaced or removed (see "Mount lifecycle" below)
    :on-mount :on-unmount
    ;; web half, collected but not composed node-side
    :routes :pages :nav-items :taps})

(defn- host-name? [h]
  (boolean (and (string? h)
                (re-matches #"(?i)[a-z0-9-]+(?:\.[a-z0-9-]+)+" h))))

(defn- permissions-error
  "→ nil when `perms` (an extension's `:permissions`) is well-formed, else why
   not. The only permission so far is :chrome-driver (xi.api.chrome)."
  [perms]
  (cond
    (nil? perms) nil
    (not (map? perms)) ":permissions must be a map"
    (seq (remove #{:chrome-driver} (keys perms)))
    (str "unknown permissions: " (str/join ", " (remove #{:chrome-driver} (keys perms))))

    (contains? perms :chrome-driver)
    (let [hosts (:hosts (:chrome-driver perms))]
      (when-not (and (map? (:chrome-driver perms)) (vector? hosts) (seq hosts)
                     (every? host-name? hosts))
        (str ":chrome-driver needs :hosts, a non-empty vector of host names "
             "like \"amazon.de\" (no scheme, port or wildcard)")))))

(defn validate
  "→ nil when `ext` is a usable user extension, else a rejection reason.
   `taken-ids` / `taken-tools` are the ids / tool names already in use."
  [ext taken-ids taken-tools]
  (cond
    (not (map? ext))          "no `extension` map"
    (not (keyword? (:id ext))) "missing keyword :id"
    (contains? taken-ids (:id ext)) (str "id " (:id ext) " already in use")
    (seq (remove allowed-keys (keys ext)))
    (str "disallowed keys: "
         (str/join ", " (sort (remove allowed-keys (keys ext)))))
    (permissions-error (:permissions ext)) (permissions-error (:permissions ext))
    (some taken-tools (map :name (:tool-definitions ext)))
    (str "tool name already in use: "
         (str/join ", " (filter taken-tools (map :name (:tool-definitions ext)))))
    :else nil))

;; ── Loading ──────────────────────────────────────────────────────────────────

(defn- file-entry?
  "A regular file, or a symlink to one (an extension that lives in another
   checkout, linked into the extensions dir)."
  [dir ^js e]
  (or (.isFile e)
      (and (.isSymbolicLink e)
           (try (.isFile (fs/statSync (node-path/join dir (.-name e))))
                (catch :default _ false)))))

(defn- list-files
  "Top-level *.cljs files in `dir` (symlinks to files included), sorted; []
   when the dir is absent."
  [dir]
  (if (fs/existsSync dir)
    (->> (fs/readdirSync dir #js {:withFileTypes true})
         (keep (fn [^js e]
                 (when (and (str/ends-with? (.-name e) ".cljs") (file-entry? dir e))
                   (node-path/join dir (.-name e)))))
         sort vec)
    []))

(defn- content-hash [s]
  (loop [h 2166136261 i 0]
    (if (< i (count s))
      (recur (bit-and (js/Math.imul (bit-xor h (.charCodeAt s i)) 16777619) 0xffffffff)
             (inc i))
      (.toString (unsigned-bit-shift-right h 0) 16))))

(defn- source-ns
  "The namespace a file's defs land in: its `(ns <name> …)` form, else `user`.
   sci/eval-string* resets *ns* between calls, so we qualify var reads with it."
  [src]
  (or (second (re-find #"\(ns\s+([A-Za-z0-9.*+!?_>-]+)" src)) "user"))

(defn- read-var
  "The value of `ns-name`/`sym` after eval, or nil when undefined. Reads the
   qualified var (the sandbox denies `resolve`/`find-var`, and bare symbols
   resolve in `user`, not the file's ns)."
  [ctx ns-name sym]
  (try (sci/eval-string* ctx (str ns-name "/" (name sym))) (catch :default _ nil)))

(defn load-dir
  "Evaluate every extension file in `dir`, each in its OWN sandbox context (a
   file is one extension; its sibling namespaces load on demand via :load-fn).
   → a vector of {:file :ns :id :extension :hash :error} in load order;
   :error is a string when the file couldn't be turned into a valid extension
   (:extension nil then). Guards are applied to accepted extensions.
   `taken-ids` / `taken-tools`: ids and tool names already in use (built-ins,
   MCP) — a user extension may never replace one.
   `enabled`: a set of file names; when given, any other file is left
   unevaluated and reported as {:file :skipped? true}."
  ([dir] (load-dir dir nil))
  ([dir {:keys [taken-ids taken-tools enabled] :or {taken-ids #{} taken-tools #{}}}]
  (loop [files      (list-files dir)
         taken-ids  taken-ids
         taken-tools taken-tools
         acc        []]
    (if-let [file (first files)]
      (if (and enabled (not (contains? enabled (node-path/basename file))))
        (recur (rest files) taken-ids taken-tools (conj acc {:file file :skipped? true}))
      (let [src   (str (fs/readFileSync file "utf8"))
            base  {:file file :hash (content-hash src)}
            entry (try
                    (let [ctx (make-ctx dir)
                          nsn (source-ns src)
                          _   (sci/eval-string* ctx src)
                          ext (read-var ctx nsn 'extension)]
                      (if-let [reason (validate ext taken-ids taken-tools)]
                        (assoc base :error reason)
                        ;; the token proves to xi.api.* which extension is
                        ;; calling (see xi.api.core/caller)
                        (let [token (api-core/issue-token! (:id ext))]
                          (cond-> (assoc base :id (:id ext)
                                              :ns nsn
                                              :token token
                                              :extension (guard/wrap (dissoc ext :permissions) token))
                            (:permissions ext) (assoc :permissions (:permissions ext))))))
                    (catch :default e
                      (assoc base :error (str "eval error: " (.-message e)))))]
        (recur (rest files)
               (cond-> taken-ids  (:id entry) (conj (:id entry)))
               (into taken-tools (map :name (:tool-definitions (:extension entry))))
               (conj acc entry))))
      acc))))

(defonce ^:private loaded (atom []))

(defn loaded-entries [] @loaded)

(defonce ^:private enabled-override
  ;; A 0-arg fn → set of file names that replaces the config file's top-level
  ;; `:extensions` for this process, or nil. Set once at startup from an agent
  ;; profile's :extensions (xi.agent-profile); a fn so `/ext reload` re-reads
  ;; the profile like it re-reads the config file.
  (atom nil))

(defn set-enabled-override!
  "Make `f` (0-arg → set of file names, or nil to clear) the source of the
   enabled-extensions list instead of the config file's top-level list."
  [f]
  (reset! enabled-override f))

(defn enabled-files
  "The user-extension file names this process may load: the agent profile's
   override when set, else the config file's `:extensions`
   (xi.user-config/enabled-extensions)."
  []
  (if-let [f @enabled-override]
    (set (f))
    (user-config/enabled-extensions)))

(defn- path->ns
  "<dir>/my_ext/util.cljs → \"my-ext.util\" (inverse of file-ns->path)."
  [dir file]
  (-> (node-path/relative dir file)
      (str/replace #"\.cljs$" "")
      (str/replace node-path/sep ".")
      (str/replace "_" "-")))

(defn- cljs-files-under
  "Every *.cljs file below `root` (recursive); [] when absent."
  [root]
  (if (fs/existsSync root)
    (->> (fs/readdirSync root #js {:withFileTypes true :recursive true})
         (keep (fn [^js e]
                 (when (and (.isFile e) (str/ends-with? (.-name e) ".cljs"))
                   (node-path/join (or (.-parentPath e) (.-path e)) (.-name e)))))
         sort vec)
    []))

(defn web-bundle
  "The browser half of a loaded extension, as source: its `<ns>.web`
   namespace plus every sibling under the extension's subdir (the browser
   resolves requires against these). nil when the extension has no
   `<name>/web.cljs`."
  [dir {:keys [id ns]}]
  (let [web-ns (str ns ".web")]
    (when (fs/existsSync (file-ns->path dir web-ns))
      (let [root (node-path/dirname (file-ns->path dir web-ns))]
        {:id      id
         :ns      web-ns
         :sources (into {}
                        (map (fn [f] [(path->ns dir f) (str (fs/readFileSync f "utf8"))]))
                        (cljs-files-under root))}))))

(defn web-bundles
  "Web halves of every loaded user extension (sent to browsers on request)."
  []
  (vec (keep #(when (:id %) (web-bundle (extensions-dir) %)) @loaded)))

(def server-extension
  "Built-in server extension (xi.config/server) that hands user web halves
   to browsers: a web client sends the roomless :user-ext/web-sources and gets
   :user-ext/web-sources-result {:extensions [bundle …]} back — only to that
   client. The browser evaluates them in its own sandbox (xi.web.user-ext).

   :user-ext/sync carries a user extension's changed room slice (emitted by
   xi.ext.user.guard) — a no-op here, it exists to be broadcast so clients,
   which lack the extension's server handlers, can mirror the slice."
  {:id              :user-extensions
   :handlers        {:user-ext/web-sources
                     (fn [_st {:keys [client-id]}]
                       {:effects [[:user-ext/web-sources-reply {:client-id client-id}]]})
                     :user-ext/sync
                     (fn [st {:keys [room-id ext-id state]}]
                       (when (get-in st [:rooms room-id])
                         {:state (assoc-in st [:rooms room-id :ext ext-id] state)}))
                     ;; an extension (re)mounted: its :init keys the live slices
                     ;; lack are seeded (existing values win, so a reload keeps
                     ;; its state), and changed room slices are synced to clients
                     :user-ext/mounted
                     (fn [st {:keys [ext-id room-init process-init]}]
                       (let [seed  (fn [init] #(merge init %))
                             st'   (cond-> st
                                     process-init (update-in [:ext ext-id] (seed process-init)))
                             st''  (if room-init
                                     (reduce #(update-in %1 [:rooms %2 :ext ext-id] (seed room-init))
                                             st' (keys (:rooms st')))
                                     st')
                             slice #(get-in %1 [:rooms %2 :ext ext-id])
                             moved (filter #(not= (slice st %) (slice st'' %)) (keys (:rooms st'')))]
                         (when-not (= st st'')
                           {:state   st''
                            :effects (mapv (fn [rid] [:app/dispatch {:type    :user-ext/sync
                                                                      :room-id rid
                                                                      :ext-id  ext-id
                                                                      :state   (slice st'' rid)}])
                                           moved)})))}
   :server-fx       (fn [{:keys [send!]}]
                      {:user-ext/web-sources-reply
                       (fn [_ {:keys [client-id]}]
                         (send! client-id {:type       :user-ext/web-sources-result
                                           :extensions (web-bundles)}))})
   :roomless-events #{:user-ext/web-sources}})

;; ── Mount lifecycle ───────────────────────────────────────────────────────

;; A loaded extension is *mounted* once the app is running (`start!`) and
;; *unmounted* when a reload replaces it or its file goes away:
;;
;;   mount    seed the extension's `:init` state into the rooms that already
;;            exist (new keys only, the existing slice wins — state survives a
;;            reload), then call its `:on-mount (fn [ctx])`;
;;   unmount  call its `:on-unmount (fn [ctx])`, release everything it still
;;            owns (xi.api.core/dispose!: spawned processes, its browser) and
;;            revoke its token, so closures of the old code (timers, in-flight
;;            promises) can neither call xi.api.* nor dispatch any more.
;;
;; `ctx` is {:dispatch! :get-state :extension :xi.api/token}: the capability
;; ctx handlers' fx get, minus a room. Room state is NOT reset on reload.

(defonce ^:private host
  ;; {:dispatch! :get-state} of the running app; nil until `start!`
  (atom nil))

(defn- mount! [{:keys [id extension]}]
  (when-let [h @host]
    ((:dispatch! h) {:type         :user-ext/mounted
                     :ext-id       id
                     :room-init    (get-in extension [:init :room])
                     :process-init (get-in extension [:init :process])})
    (when-let [f (:on-mount extension)] (f h))))

(defn- unmount! [{:keys [id extension token]}]
  (when-let [f (and @host (:on-unmount extension))] (f @host))
  (api-core/dispose! id)
  (api-core/revoke! token))

(defn start!
  "Tell the loader the app is running (`app` is xi.core.app/create-app's
   result): extensions mount from now on, and the ones already loaded mount now.
   Call once after create-app; before it, loading only registers.
   `ask!` (xi.ext.core/create-dialogs) powers xi.api.dialog; without it
   extension dialogs are refused."
  ([app] (start! app nil))
  ([{:keys [dispatch! state]} {:keys [ask!]}]
   (let [h {:dispatch! dispatch! :get-state (fn [] @state)}]
     (reset! host h)
     (api-core/set-dialog-host! (when ask! (assoc h :ask! ask!))))
   (doseq [entry @loaded :when (:extension entry)]
     (mount! entry))))

(defn stop!
  "Unmount every loaded extension and forget the app (shutdown; tests)."
  []
  (doseq [entry @loaded :when (:id entry)]
    (unmount! entry))
  (reset! host nil)
  (api-core/set-dialog-host! nil))

(defn- register-all!
  "Unmount the previously-loaded extensions, register the accepted extensions
   of `entries` into `mgr` (unregistering any previously-loaded user id that's
   no longer present: file deleted / renamed / now rejected) and mount them.
   Logs a one-line summary."
  [mgr entries]
  (let [new-ids (set (keep :id entries))]
    (doseq [old @loaded :when (:id old)]
      (unmount! old))
    (doseq [old (set (keep :id @loaded)) :when (not (contains? new-ids old))]
      (manager/unregister! mgr old))
    (reset! loaded entries)
    (api-core/set-permissions! (into {} (keep (fn [{:keys [id permissions]}]
                                                (when (and id permissions) [id permissions])))
                                     entries))
    (doseq [{:keys [extension]} entries :when extension]
      (manager/register! mgr extension))
    (doseq [entry entries :when (:extension entry)]
      (mount! entry))
    (let [ok      (keep :id entries)
          bad     (filter :error entries)
          skipped (filter :skipped? entries)]
      (when (seq ok)
        (js/console.error (str "[user-ext] loaded: " (str/join ", " (map name ok)))))
      (when (seq skipped)
        (js/console.error (str "[user-ext] not enabled (list under :extensions in "
                               (user-config/config-file) "): "
                               (str/join ", " (map #(node-path/basename (:file %)) skipped)))))
      (doseq [{:keys [file error]} bad]
        (js/console.error (str "[user-ext] rejected " (node-path/basename file) ": " error))))
    entries))

(defn- taken
  "load-dir opts for the live manager: the enabled file names, plus the ids
   and tool names a user extension may not use — everything registered in
   `mgr` and every builtin tool, except what this loader registered itself
   (a reload replaces those). manager/register! replaces by id and the tool
   registry lets extensions win by name, so without this a user file could
   swap out :rules or the builtin `write`."
  [mgr]
  (let [own-ids   (set (keep :id @loaded))
        own-tools (set (mapcat #(map :name (get-in % [:extension :tool-definitions])) @loaded))]
    {:enabled     (enabled-files)
     :taken-ids   (into #{} (comp (map :id) (remove own-ids)) (manager/ext-list mgr))
     :taken-tools (-> (set (map :name (registry/tool-definitions)))
                      (into (comp (map :name) (remove own-tools))
                            (:tool-definitions (manager/composed mgr))))}))

(defn install!
  "Load the enabled files of ~/.config/xi/extensions (config.edn `:extensions`,
   or the agent profile's — see `enabled-files`) and register each valid
   extension into `mgr` (call AFTER the built-ins + MCP are seeded). Returns
   the load report."
  ([mgr] (install! mgr (extensions-dir)))
  ([mgr dir] (register-all! mgr (load-dir dir (taken mgr)))))

(def ^:private mirror-keys
  "What a TUI client needs of a user extension: the handlers (an event type
   only forwards to the server when the client has a handler for it, and the
   server's echo replays through it), plus what the TUI presents locally."
  [:id :init :handlers :commands :keybindings :prompt-badge])

(defn mirror-extensions
  "The user extensions as a join/create TUI client mirrors them (see
   mirror-keys). `builtins` are the client's mirrored built-in extension maps;
   their ids and tools are taken, as on the server. Effects and tools are left
   out: they run on the server, which loads the directory itself."
  ([builtins] (mirror-extensions (extensions-dir) (enabled-files) builtins))
  ([dir enabled builtins]
   (let [builtins (remove nil? builtins)]
     (->> (load-dir dir {:enabled     enabled
                         :taken-ids   (set (map :id builtins))
                         :taken-tools (into (set (map :name (registry/tool-definitions)))
                                            (comp (mapcat :tool-definitions) (map :name))
                                            builtins)})
          (keep :extension)
          (mapv #(select-keys % mirror-keys))))))

(defn reload!
  "Re-evaluate the extensions dir (each file in a fresh sandbox, siblings
   included) and swap it in live: the old extensions unmount, the new ones
   register and mount, ids whose file is gone are dropped. Handlers, commands
   and fx follow at once where the assembly reads them through
   xi.ext.manager/live-view (server, standalone); tool changes apply next turn;
   keybindings need a restart. Room state is kept.
   → {:loaded [ids] :rejected [{:file :error}] :skipped [file names]}."
  ([mgr] (reload! mgr (extensions-dir)))
  ([mgr dir]
   (let [entries (register-all! mgr (load-dir dir (taken mgr)))]
     {:loaded   (vec (keep :id entries))
      :rejected (mapv #(select-keys % [:file :error]) (filter :error entries))
      :skipped  (mapv #(node-path/basename (:file %)) (filter :skipped? entries))})))
