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
            [xi.api.fs]
            [xi.api.http]
            [xi.api.promise :as api-promise]
            [xi.api.sh]
            [xi.core.events]
            [xi.core.state]
            [xi.ext.manager :as manager]
            [xi.ext.user.guard :as guard]
            [xi.sandbox.sci :as sandbox]
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
  {'xi.api.fs      (sci/copy-ns xi.api.fs      (sci/create-ns 'xi.api.fs))
   'xi.api.sh      (sci/copy-ns xi.api.sh      (sci/create-ns 'xi.api.sh))
   'xi.api.http    (sci/copy-ns xi.api.http    (sci/create-ns 'xi.api.http))
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
  "Extension-map keys a user extension may declare. No :tool-gate (policy is
   the rules engine's) and no :event-hooks (rewrites/blocks any event)."
  #{:id :init :handlers :fx :commands :tool-definitions :tool-registry
    :system-prompt :keybindings :prompt-badge :on-shutdown :on-enable
    :on-disable
    ;; web half, collected but not composed node-side
    :routes :pages :nav-items :taps})

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
    (some taken-tools (map :name (:tool-definitions ext)))
    (str "tool name already in use: "
         (str/join ", " (filter taken-tools (map :name (:tool-definitions ext)))))
    :else nil))

;; ── Loading ──────────────────────────────────────────────────────────────────

(defn- list-files
  "Top-level *.cljs files in `dir`, sorted; [] when the dir is absent."
  [dir]
  (if (fs/existsSync dir)
    (->> (fs/readdirSync dir #js {:withFileTypes true})
         (keep (fn [^js e]
                 (when (and (.isFile e) (str/ends-with? (.-name e) ".cljs"))
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
   → a vector of {:file :id :extension :web-extension :hash :error} in load
   order; :error is a string when the file couldn't be turned into a valid
   extension (:extension nil then). Guards are applied to accepted extensions.
   `taken-ids` / `taken-tools`: ids and tool names already in use (built-ins,
   MCP) — a user extension may never replace one."
  ([dir] (load-dir dir nil))
  ([dir {:keys [taken-ids taken-tools] :or {taken-ids #{} taken-tools #{}}}]
  (loop [files      (list-files dir)
         taken-ids  taken-ids
         taken-tools taken-tools
         acc        []]
    (if-let [file (first files)]
      (let [src   (str (fs/readFileSync file "utf8"))
            base  {:file file :hash (content-hash src)}
            entry (try
                    (let [ctx (make-ctx dir)
                          nsn (source-ns src)
                          _   (sci/eval-string* ctx src)
                          ext (read-var ctx nsn 'extension)
                          web (read-var ctx nsn 'web-extension)]
                      (if-let [reason (validate ext taken-ids taken-tools)]
                        (assoc base :error reason)
                        (assoc base :id (:id ext)
                                    :extension (guard/wrap ext)
                                    :web-extension web)))
                    (catch :default e
                      (assoc base :error (str "eval error: " (.-message e)))))]
        (recur (rest files)
               (cond-> taken-ids  (:id entry) (conj (:id entry)))
               (into taken-tools (map :name (:tool-definitions (:extension entry))))
               (conj acc entry)))
      acc))))

(defonce ^:private loaded (atom []))

(defn loaded-entries [] @loaded)

(defn web-extensions
  "Source-carrying web halves the browser build should evaluate (Phase C)."
  []
  (keep (fn [e] (when (:web-extension e) (select-keys e [:id :file]))) @loaded))

(defn- register-all!
  "Register the accepted extensions of `entries` into `mgr`; unregister any
   previously-loaded user id that's no longer present (file deleted / renamed /
   now rejected). Logs a one-line summary."
  [mgr entries]
  (let [new-ids (set (keep :id entries))]
    (doseq [old (set (keep :id @loaded)) :when (not (contains? new-ids old))]
      (manager/unregister! mgr old))
    (reset! loaded entries)
    (doseq [{:keys [extension]} entries :when extension]
      (manager/register! mgr extension))
    (let [ok  (keep :id entries)
          bad (filter :error entries)]
      (when (seq ok)
        (js/console.error (str "[user-ext] loaded: " (str/join ", " (map name ok)))))
      (doseq [{:keys [file error]} bad]
        (js/console.error (str "[user-ext] rejected " (node-path/basename file) ": " error))))
    entries))

(defn- taken
  "Ids and tool names a user extension may not use: everything registered in
   `mgr` and every builtin tool, except what this loader registered itself
   (a reload replaces those). manager/register! replaces by id and the tool
   registry lets extensions win by name, so without this a user file could
   swap out :rules or the builtin `write`."
  [mgr]
  (let [own-ids   (set (keep :id @loaded))
        own-tools (set (mapcat #(map :name (get-in % [:extension :tool-definitions])) @loaded))]
    {:taken-ids   (into #{} (comp (map :id) (remove own-ids)) (manager/ext-list mgr))
     :taken-tools (-> (set (map :name (registry/tool-definitions)))
                      (into (comp (map :name) (remove own-tools))
                            (:tool-definitions (manager/composed mgr))))}))

(defn install!
  "Load ~/.config/xi/extensions and register each valid extension into `mgr`
   (call AFTER the built-ins + MCP are seeded). Returns the load report."
  [mgr]
  (register-all! mgr (load-dir (extensions-dir) (taken mgr))))

(defn reload!
  "Re-evaluate the extensions dir and re-register (dropping ids whose file is
   gone). Tool changes apply next turn; handler/command/keybinding changes need
   a restart, same as built-ins. → {:loaded [ids] :rejected [{:file :error}]}."
  [mgr]
  (let [entries (register-all! mgr (load-dir (extensions-dir) (taken mgr)))]
    {:loaded   (vec (keep :id entries))
     :rejected (mapv #(select-keys % [:file :error]) (filter :error entries))}))
