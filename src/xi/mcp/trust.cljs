(ns xi.mcp.trust
  "Trust for MCP servers: approve a server once, then its calls run without
   asking — until its code changes.

   Trust is content-addressed like bb.edn trust (xi.bb-trust). A server's
   fingerprint is a sha256 over its ~/.config/xi/mcp.edn entry (transport,
   command, args, url — not :env or :enabled) and the content of its code:
     - every file named by the entry's :command or :args (a value that is a
       path to an existing file, relative paths against :cwd)
     - every file under the entry's :code-paths (files or directories), for
       code a command line doesn't name (e.g. a bb server's src/)
   A rebuild that changes the code, or an edited entry, changes the
   fingerprint, and the server is asked about again. Code a command line
   downloads (`npx -y pkg@latest`) is outside it — pin the version.

   Servers user extensions declare (:mcp-servers) are trusted the same way,
   under their \"<extension>/<name>\" id.

   Or trust a server in ~/.config/xi/config.edn (`:trusted-mcp-servers`, a
   file agents can't write): a listed server is trusted as it is, without
   a fingerprint — the config is the decision.

   The store, ~/.config/xi/ext/mcp-trust.edn, keeps one trusted fingerprint
   per server id: {:servers {\"chrome\" \"<sha256>\"}}. Written by the MCP
   ask's \"Always\" answer and `/mcp trust <id>`.

   Read by the rules engine (`:mcp-trusted` match field, xi.rules.store); the
   default `::mcp-confirm` rule asks only for untrusted servers."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [xi.user-config :as user-config]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

;; ── mcp.edn ──────────────────────────────────────────────────────────────────

(defn- config-dir [] (node-path/join (os/homedir) ".config" "xi"))

(defonce ^:private registry-file-override (atom nil))

(defn set-registry-file!
  "Point mcp.edn at `path` (nil restores the default) — for tests."
  [path]
  (reset! registry-file-override path))

(defn registry-file []
  (or @registry-file-override (node-path/join (config-dir) "mcp.edn")))

(defn- read-edn [file]
  (try
    (when (fs/existsSync file)
      (reader/read-string {:default (fn [_ v] v)} (str (fs/readFileSync file "utf8"))))
    (catch :default _ nil)))

(defn read-registry
  "The MCP registry map (id -> entry), or {} when absent/unreadable."
  []
  (or (read-edn (registry-file)) {}))

;; ext-id → {"<ext>/<name>" entry}: the servers user extensions declare
;; (:mcp-servers, see xi.ext.mcp), so they're trusted the same way.
(defonce ^:private extension-entries (atom {}))

(defn set-extension-entries!
  "Record (or with nil, forget) the servers extension `ext` declares."
  [ext entries]
  (if (seq entries)
    (swap! extension-entries assoc ext entries)
    (swap! extension-entries dissoc ext)))

(defn- id-str
  "A server id as the store keys it: \"chrome\", \"product-search/browser\"."
  [id]
  (if (keyword? id) (subs (str id) 1) (str id)))

(defn entry-for
  "The entry of server `id`: an extension's \"<ext>/<name>\" server, else the
   mcp.edn entry; nil when there is none."
  [id]
  (let [id (id-str id)]
    (or (some #(get % id) (vals @extension-entries))
        (get (read-registry) (keyword id)))))

;; ── Fingerprint ──────────────────────────────────────────────────────────────

(defn- sha256 [x]
  (-> (.createHash crypto "sha256") (.update x) (.digest "hex")))

;; path → [mtime size sha]: a server's code is hashed once per change, not on
;; every tool call
(defonce ^:private file-shas (atom {}))

(defn- file-sha [file]
  (let [st  (fs/statSync file)
        key [(.-mtimeMs st) (.-size st)]
        hit (get @file-shas file)]
    (if (= key (pop hit))
      (peek hit)
      (let [s (sha256 (fs/readFileSync file))]
        (swap! file-shas assoc file (conj key s))
        s))))

(defn- stat [p] (try (fs/statSync p) (catch :default _ nil)))

(defn- files-under
  "Every regular file at or under `p`, sorted; .git and node_modules skipped."
  [p]
  (when-let [st (stat p)]
    (cond
      (.isFile st) [p]
      (.isDirectory st)
      (->> (fs/readdirSync p)
           (remove #{".git" "node_modules"})
           sort
           (mapcat #(files-under (node-path/join p %))))
      :else [])))

(defn- code-files
  "The files whose content is part of `entry`'s fingerprint (absolute, sorted,
   distinct)."
  [{:keys [command args cwd code-paths]}]
  (let [base     (or cwd (.cwd js/process))
        resolve  #(node-path/resolve base (str %))
        named    (->> (cons command args)
                      (filter #(and (string? %) (str/includes? % "/")))
                      (map resolve)
                      (filter #(some-> (stat %) .isFile)))
        declared (mapcat (comp files-under resolve) code-paths)]
    (->> (concat named declared) distinct sort)))

(defn fingerprint
  "sha256 of `entry`'s identity and code (see ns doc)."
  [entry]
  (sha256
   (str (pr-str (select-keys entry [:transport :command :args :url :cwd :code-paths]))
        (str/join (for [f (code-files entry)]
                    (str "\n" f "\u0000" (try (file-sha f) (catch :default _ "unreadable"))))))))

;; ── Store ────────────────────────────────────────────────────────────────────

(defonce ^:private trust-file-override (atom nil))

(defn set-trust-file!
  "Point the trust store at `path` (nil restores the default) — for tests."
  [path]
  (reset! trust-file-override path))

(defn- trust-file []
  (or @trust-file-override (node-path/join (config-dir) "ext" "mcp-trust.edn")))

(defn- read-store [] (or (read-edn (trust-file)) {}))

(defn- write-store! [m]
  (let [f (trust-file)]
    (fs/mkdirSync (node-path/dirname f) #js {:recursive true})
    (fs/writeFileSync f (str (pr-str m) "\n"))))

(defn configured?
  "Whether config.edn's `:trusted-mcp-servers` lists server `id`."
  [id]
  (contains? (user-config/trusted-mcp-servers) (id-str id)))

(defn trusted-entry?
  "Whether server `id` with registry `entry` is trusted as it is now: listed
   in config.edn, or its fingerprint is the one the store trusted."
  [id entry]
  (boolean
   (when entry
     (or (configured? id)
         (when-let [s (get-in (read-store) [:servers (id-str id)])]
           (= s (fingerprint entry)))))))

(defn trusted?
  "Whether server `id` (string or keyword; an mcp.edn id or an extension's
   \"<ext>/<name>\") is trusted. An unknown id is not."
  [id]
  (trusted-entry? id (entry-for id)))

(defn trust!
  "Trust server `id` as it is configured now → {:id :sha}, or nil when no
   such server is configured."
  [id]
  (when-let [entry (entry-for id)]
    (let [s (fingerprint entry)]
      (write-store! (assoc-in (read-store) [:servers (id-str id)] s))
      {:id (id-str id) :sha s})))

(defn untrust!
  "Forget server `id`'s trust; its next call asks again."
  [id]
  (write-store! (update (read-store) :servers dissoc (id-str id))))
