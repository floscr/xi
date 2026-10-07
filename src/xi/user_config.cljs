(ns xi.user-config
  "The user config file, ~/.config/xi/config.edn — a typed, version-locked
   EDN map (like rules.edn, which is `:xi/rules`):

     {:type       :xi/config
      :version    1
      :extensions [\"kb.cljs\" \"web.cljs\"]      ; user extensions xi may load
      :agents     {\"root\" {…}}               ; agent profiles (xi.agent-profile)
      :projects   {:browse […] :repos […]}      ; project dirs (xi.projects)
      :users      {\"alice\" {:name \"Alice\" :avatar \"https://…/a.png\" :meta {:team \"ops\"}}} ; who exists (see below)
      :trusted-mcp-servers [\"chrome\" \"shop/browser\"]  ; MCP servers that never ask (xi.mcp.trust)
      :keys       {:global {\"alt+n\" :chat/new}}}       ; keyboard shortcuts (xi.keys)

   `:users` declares users by id (xi.util/user-id slugs), each with an optional
   display `:name`, an `:avatar` (an http(s) image URL; without one the web UI
   draws the user's initials) and read-only `:meta` (plain data an extension can
   read, e.g. a team or role). `:name` and `:avatar` are public — every client
   sees them (xi.avatar); `:meta` stays on the server. `root` always exists and may be declared to give it a name.
   An id nobody declared still works: a user is whoever a connection says it is
   (xi.server.ws), the declaration only adds a profile to it. What a user *does*
   — their UI state and what extensions keep about them — is state, not config,
   and lives in ~/.config/xi/state/users/ (xi.user-state.store).

   `:extensions` names the files under ~/.config/xi/extensions/ that are
   evaluated at all — the only place that can enable one. An agent profile's
   own `:extensions` replaces this list for that run (xi.ext.user). The file
   lives under ~/.config/xi, a hidden path no agent can write
   (xi.paths/HIDDEN_PATHS), so what loads is the operator's call alone.

   An invalid file fails closed: nothing is enabled and every agent profile
   loads without tools, with the problem reported by the readers."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [xi.avatar :as avatar]
            [xi.keys :as xkeys]
            [xi.projects :as projects]
            [xi.user-state :as user-state]
            [xi.util :as util]
            ["node:fs" :as fs]
            ["node:path" :as node-path]))

(def ^:private HOME (aget js/process.env "HOME"))

(def CONFIG_DIR (.join node-path HOME ".config" "xi"))

(defonce ^:private file-override
  ;; Test seam: point the reader at a throwaway file (see `set-config-file!`).
  (atom nil))

(defn set-config-file!
  "Override the config file path (nil restores ~/.config/xi/config.edn).
   Test seam, like xi.rules.store/set-global-file!."
  [file]
  (reset! file-override file))

(defn config-file []
  (or @file-override (.join node-path CONFIG_DIR "config.edn")))

(def CONFIG_FILE_TYPE
  "The `:type` tag the config file must carry (rules.edn is `:xi/rules`)."
  :xi/config)

(def CONFIG_FILE_VERSION
  "The config-file format version this xi reads; a missing or different
   `:version` is an error, so a format change is never misread silently."
  1)

(def ^:private config-file-keys
  #{:type :version :extensions :agents :projects :users :trusted-mcp-servers :keys})

(defn- users-error
  "→ nil when `users` is a well-formed `:users` map, else why not."
  [users]
  (cond
    (not (map? users))
    ":users must be a map of user id → {:name … :meta …}"

    :else
    (some (fn [[id profile]]
            (cond
              (not (and (string? id) (= id (util/user-id id))))
              (str ":users ids must be lowercase letters, digits, '.', '_' or '-' (up to 64), not "
                   (pr-str id))

              (not (map? profile))
              (str ":users " id " must be a map like {:name \"Name\" :meta {…}}")

              (seq (remove #{:name :avatar :meta} (keys profile)))
              (str ":users " id " allows only :name, :avatar and :meta")

              (and (contains? profile :name)
                   (not (and (string? (:name profile))
                             (<= 1 (count (str/trim (:name profile))) 100))))
              (str ":users " id " :name must be a non-blank string of up to 100 characters")

              (and (contains? profile :avatar)
                   (not (avatar/url? (:avatar profile))))
              (str ":users " id " :avatar must be an http(s) image URL")

              (and (contains? profile :meta)
                   (not (and (map? (:meta profile))
                             (user-state/ext-value? (:meta profile)))))
              (str ":users " id " :meta must be a map of plain data (strings, numbers, "
                   "keywords, vectors, maps) under 64 KB")))
          users)))

(defn parse-config
  "Validate parsed config-file `data` (nil = unparseable) →
   `{:extensions #{…} :agents {…} :projects {…} :keys {…}}` (empty / defaults
   when absent; `:projects` per `xi.projects/parse-spec`, `:keys` per
   `xi.keys/config-error` and only present when set) or `{:error msg}`.
   Mirrors xi.rules.store/parse-rules-config: the file must be a map tagged
   `:type :xi/config` with the current `:version` and only known keys."
  [data]
  (let [shape   (str "{:type " CONFIG_FILE_TYPE " :version " CONFIG_FILE_VERSION
                     " :extensions [...] :agents {...} :projects {...}"
                     " :users {...} :trusted-mcp-servers [...] :keys {...}}")
        unknown (when (map? data) (remove config-file-keys (keys data)))
        spec    (when (map? data) (projects/parse-spec (:projects data)))]
    (cond
      (nil? data)
      {:error "not valid EDN"}

      (not (map? data))
      {:error (str "must be a map " shape)}

      (not (contains? data :version))
      {:error (str "missing required :version — add :version " CONFIG_FILE_VERSION)}

      (not= CONFIG_FILE_VERSION (:version data))
      {:error (str "unsupported :version " (pr-str (:version data))
                   " — this xi reads :version " CONFIG_FILE_VERSION)}

      (not (contains? data :type))
      {:error (str "missing required :type — add :type " CONFIG_FILE_TYPE)}

      (not= CONFIG_FILE_TYPE (:type data))
      {:error (str "wrong :type " (pr-str (:type data))
                   " — the config file is :type " CONFIG_FILE_TYPE)}

      (seq unknown)
      {:error (str "unknown key(s) " (str/join " " (map pr-str unknown))
                   " — expected " shape)}

      (not (and (sequential? (:extensions data []))
                (every? string? (:extensions data))))
      {:error ":extensions must be a vector of extension file names"}

      (not (map? (:agents data {})))
      {:error ":agents must be a map of agent id → profile"}

      (not (and (sequential? (:trusted-mcp-servers data []))
                (every? string? (:trusted-mcp-servers data))))
      {:error ":trusted-mcp-servers must be a vector of MCP server ids"}

      (users-error (:users data {}))
      {:error (users-error (:users data {}))}

      (xkeys/config-error (:keys data))
      {:error (xkeys/config-error (:keys data))}

      (:error spec)
      {:error (:error spec)}

      :else
      (cond-> {:extensions          (set (:extensions data))
               :agents              (or (:agents data) {})
               :projects            spec
               :users               (or (:users data) {})
               :trusted-mcp-servers (set (:trusted-mcp-servers data))}
        (contains? data :keys) (assoc :keys (:keys data))))))

(defn read-config
  "The validated user config (`parse-config`), all-empty when the file is
   absent, `{:error msg}` when it exists but is invalid."
  []
  (let [file (config-file)]
    (if (fs/existsSync file)
      (parse-config
       (try (edn/read-string (fs/readFileSync file "utf8"))
            (catch :default _ nil)))
      {:extensions #{} :agents {} :projects projects/default-spec
       :users {} :trusted-mcp-servers #{}})))

(defn projects-spec
  "The validated `:projects` spec (`xi.projects/parse-spec`): project dirs and
   per-project `:settings`. Defaults when the file is missing; defaults with
   the problem on stderr when it is invalid."
  []
  (let [cfg (read-config)]
    (when-let [e (:error cfg)]
      (js/console.error (str "xi: " (config-file) " is invalid — " e
                             " — using no project config")))
    (or (:projects cfg) projects/default-spec)))

(defn enabled-extensions
  "The user-extension file names the config file enables under `:extensions`
   — #{} when the file is missing, invalid (reported on stderr), or doesn't set
   the key."
  []
  (let [cfg (read-config)]
    (when-let [e (:error cfg)]
      (js/console.error (str "xi: " (config-file) " is invalid — " e
                             " — no user extensions enabled")))
    (or (:extensions cfg) #{})))

(defn users
  "The users the config file declares, {id {:name :meta}} — {} when the file
   is missing, invalid (reported on stderr) or doesn't set the key. `root` is
   always a user whether declared or not (see xi.users)."
  []
  (let [cfg (read-config)]
    (when-let [e (:error cfg)]
      (js/console.error (str "xi: " (config-file) " is invalid — " e
                             " — no users declared")))
    (or (:users cfg) {})))

(defn trusted-mcp-servers
  "The MCP server ids the config file trusts outright (`:trusted-mcp-servers`:
   mcp.edn ids, or an extension's \"<extension>/<name>\") — #{} when the file
   is missing, invalid or doesn't set the key. See xi.mcp.trust."
  []
  (or (:trusted-mcp-servers (read-config)) #{}))

(defn keys-config
  "The user's keyboard shortcuts (`:keys`, see xi.keys) — nil when the file is
   missing, invalid (reported on stderr: the built-in keys apply) or doesn't
   set the key."
  []
  (let [cfg (read-config)]
    (when-let [e (:error cfg)]
      (js/console.error (str "xi: " (config-file) " is invalid — " e
                             " — using the default keyboard shortcuts")))
    (:keys cfg)))
