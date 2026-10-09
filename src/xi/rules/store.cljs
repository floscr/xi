(ns xi.rules.store
  "Rules store — loads the config rule files, merges the runtime scopes from
   app state, normalizes a tool call into a decision request, and enforces the
   immutable hard-block (agents may never write the rules files).

   Precedence (first match wins, top → bottom):
     1. hard-coded immutable  (this namespace — see `hard-block`)
     2. repo config           <repo>/.xi/rules.edn
     3. global config         ~/.config/xi/rules.edn
     4. server-session        process-local  [:ext :rules :rules]
     5. session runtime       room-scoped    [:rooms rid :ext :rules :rules]
     6. defaults              a rules file's :defaults (repo, else global),
                              else xi.rules.defaults/default-rules

   Config over runtime, so a configured rule overrides a careless 'always
   allow'. Config files are typed, versioned maps `{:type :xi/rules
   :version 1 :rules [...] :defaults [...]}`; an invalid file fails closed (a
   catch-all deny in its tier). They are cached by mtime and reloaded on change (or via
   `/rules reload`)."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [cljs.tools.reader :as tr]
            [xi.bb-trust :as bb-trust]
            [xi.core.state :as core-state]
            [xi.mcp.trust :as mcp-trust]
            [xi.rules :as rules]
            [xi.rules.defaults :as defaults]
            [xi.rules.nodes :as nodes]
            [xi.paths :as paths]
            [xi.user-config :as user-config]
            [xi.util :as util]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def ext-id :rules)

;; ── Paths ────────────────────────────────────────────────────────────────────

(defn config-dir [] (path/join (os/homedir) ".config" "xi"))
(defonce ^:private global-file-override (atom nil))

(defn set-global-file!
  "Point the global rules file at `file` (nil restores the default); the test
   runner uses it to keep the real rules.edn out of test runs."
  [file]
  (reset! global-file-override file))

(defn global-file []
  (or @global-file-override (path/join (config-dir) "rules.edn")))
(defn repo-file [repo-root] (when repo-root (path/join repo-root ".xi" "rules.edn")))

(defn expand-path
  "Resolve `p` to an absolute path: `~` → home, relative → against `cwd`."
  [cwd p]
  (let [p (str p)
        p (if (str/starts-with? p "~") (str (os/homedir) (subs p 1)) p)]
    (if (path/isAbsolute p)
      p
      (path/resolve (or cwd (.cwd js/process)) p))))

(defn git-root
  "Walk up from `start` looking for a `.git` entry; the repo root or nil.
   Works for not-yet-existing paths (missing dirs just fail the check)."
  [start]
  (loop [dir (str start)]
    (cond
      (and (seq dir) (fs/existsSync (path/join dir ".git"))) dir
      (or (empty? dir) (= dir (path/dirname dir))) nil
      :else (recur (path/dirname dir)))))

;; ── Config file loading (mtime-cached) ─────────────────────────────────────────

(defonce ^:private cache (atom {}))

(defn clear-cache! [] (reset! cache {}))

(defn read-rule-edn
  "Parse rule EDN with the full Clojure reader so regex literals round-trip;
   unknown tagged literals fall through to their value. nil on failure."
  [s]
  (try
    (binding [tr/*default-data-reader-fn* (fn [_tag v] v)]
      (tr/read-string (str s)))
    (catch :default _ nil)))

(def rules-file-version
  "The rules-file format version this xi reads; a missing or different
   `:version` is an error, never a silent misread."
  1)

(def rules-file-type
  "The `:type` tag every xi rules file must carry."
  :xi/rules)

(def ^:private rules-file-keys #{:type :version :rules :defaults})

(defn parse-rules-config
  "Validate parsed rules-file `data` → {:rules :defaults} (defaults expanded
   via defaults/expand) or {:error msg}."
  [data]
  (let [v       rules-file-version
        shape   (str "{:type " rules-file-type " :version " v
                     " :rules [...] :defaults [...]}")
        unknown (when (map? data) (remove rules-file-keys (keys data)))]
    (cond
      (nil? data)
      {:error "not valid EDN"}

      (not (map? data))
      {:error (str "must be a map " shape " — bare rule vectors aren't accepted")}

      (not (contains? data :version))
      {:error (str "missing required :version — add :version " v)}

      (not= v (:version data))
      {:error (str "unsupported :version " (pr-str (:version data))
                   " — this xi reads :version " v)}

      (not (contains? data :type))
      {:error (str "missing required :type — add :type " rules-file-type)}

      (not= rules-file-type (:type data))
      {:error (str "wrong :type " (pr-str (:type data))
                   " — a rules file is :type " rules-file-type)}

      ;; User extensions used to be enabled here; they live in config.edn now.
      (contains? data :extensions)
      {:error ":extensions moved to ~/.config/xi/config.edn — list user extensions there"}

      (seq unknown)
      {:error (str "unknown key(s) " (str/join " " (map pr-str unknown))
                   " — expected " shape)}

      (not (sequential? (:rules data [])))
      {:error ":rules must be a vector of rule maps"}

      (not-every? map? (:rules data))
      {:error (str ":rules entries must be rule maps (put "
                   ":xi.rules.defaults/… aliases under :defaults)")}

      (not (sequential? (:defaults data [])))
      {:error ":defaults must be a vector of aliases / rule maps"}

      :else
      (try
        {:rules    (vec (:rules data))
         :defaults (when (contains? data :defaults)
                     (defaults/expand (:defaults data)))}
        (catch :default e
          {:error (ex-message e)})))))

(defn- read-rules-data
  [file]
  (read-rule-edn (str (fs/readFileSync file "utf8"))))

(defn- load-rules-file
  "Read + validate rules `file`, cached by mtime; nil without a file."
  [file]
  (when (and file (fs/existsSync file))
    (try
      (let [mtime  (.-mtimeMs (fs/statSync file))
            cached (get @cache file)]
        (if (= mtime (:mtime cached))
          (:config cached)
          (let [config (parse-rules-config (read-rules-data file))]
            (swap! cache assoc file {:mtime mtime :config config})
            config)))
      (catch :default e
        {:error (str "unreadable: " (.-message e))}))))

(defn- invalid-file-rule
  "The fail-closed stand-in for an invalid rules file: a catch-all deny in the
   config tier, shadowing every rule below it until the file is fixed."
  [file error]
  {:match  {}
   :action {:type    :deny
            :message (str "Blocked: rules file " file " is invalid — " error
                          ". Every tool call is denied until it is fixed. "
                          "Agents can't edit rules files: ask the user to fix "
                          "it (expected {:type " rules-file-type " :version "
                          rules-file-version " :rules [...]}).")}})

;; ── Hard-coded immutable rules ──────────────────────────────────────────────

(def ^:private rules-file-re
  #"(?:\.config/xi/rules\.edn|[/\\]\.xi/rules\.edn)$")

(def ^:private rules-file-loose-re
  "Matches a rules-file path mentioned anywhere in a command/code string."
  #"(?:\.config/xi/rules\.edn|\.xi/rules\.edn)")

(def ^:private write-token-re
  "Write indicators in a shell command or clj eval that would mutate a file."
  #"(?:>>?|\btee\b|\bsed\s+-i|\bcp\b|\bmv\b|\bdd\b|\bchmod\b|\bspit\b|writeFileSync|appendFileSync|\brm\b)")

(def ^:private hard-block-msg
  (str "Blocked (hard rule): agents may not modify the rules files "
       "(~/.config/xi/rules.edn or <repo>/.xi/rules.edn). Ask the user to "
       "edit them, or use the 'recommend a rule' flow on a guard dialog."))

(defn- deny-result [msg]
  {:intercepted true
   :result {:content [{:type "text" :text msg}] :is-error true}})

(defn tool-kind
  [name]
  (let [n (str name)]
    (case (str/lower-case n)
      "write" :write
      "edit"  :edit
      "read"  :read
      "grep"  :grep
      "find"  :find
      "ls"    :ls
      "bash"  :bash
      "clj"   :clj
      "bb"    :bb
      (if (str/starts-with? n "mcp__") :mcp :other))))

(defn- bb-command
  "The command line a bb tool call runs (`bb <task> <args…>`, or `bb tasks`),
   so `:command` rules match bb calls."
  [{:keys [task args]}]
  (let [task (not-empty (str/trim (str task)))]
    (str/join " " (into ["bb"] (if task (into [task] (map str args)) ["tasks"])))))

;; ── Decision request ───────────────────────────────────────────────────────

(defn- parse-mcp [name]
  (when (and name (str/starts-with? (str name) "mcp__"))
    (let [body (subs (str name) 5)
          idx  (str/index-of body "__")]
      (when idx
        {:mcp-server (subs body 0 idx)
         :mcp-tool   (subs body (+ idx 2))}))))

(defn request-user
  "The user a decision acts for: the ctx's :user, else the room's turn user,
   else this process' own user."
  [{:keys [user get-state room-id]}]
  (util/user-id
   (or user
       (when-let [st (when get-state (get-state))]
         (if room-id
           (core-state/turn-user st room-id)
           (core-state/own-user st))))))

(defn- user-record
  "{:id :name :meta} of user `id` per config.edn's `:users`, or
   `rules/config-invalid` when config.edn is invalid."
  [id]
  (let [cfg (user-config/read-config)]
    (if (:error cfg)
      rules/config-invalid
      (let [{:keys [name meta]} (get (:users cfg) id)]
        (cond-> {:id id :meta (or meta {})}
          name (assoc :name name))))))

(defn decision-request
  "Normalize a tool call into the matcher's decision request: effective cwd,
   repo root, target path/command, arguments, MCP server/tool, room ext state
   and user."
  [tool-call {:keys [cwd get-state room-id] :as ctx}]
  (let [{:keys [name arguments]} tool-call
        kind    (tool-kind name)
        p       (or (:path arguments) (:file_path arguments))
        command (if (= kind :bb) (bb-command arguments) (:command arguments))
        code    (:code arguments)
        eff-cwd (or cwd (.cwd js/process))
        repo    (git-root (if p (path/dirname (expand-path eff-cwd (str p))) eff-cwd))
        state   (when (and get-state room-id)
                  (get-in (get-state) [:rooms room-id :ext]))]
    (cond-> {:tool kind :tool-name (some-> name str) :effective-cwd eff-cwd
             :repo repo :state state :user (request-user ctx)}
      p         (assoc :path p)
      command   (assoc :command command)
      code      (assoc :command code)
      (seq arguments) (assoc :arguments arguments)
      (= kind :mcp) (merge (parse-mcp name)))))

(defn request
  "A decision request for a non-tool call (xi.api.* from user extensions): `m`
   carries :tool plus its target keys; this fills :effective-cwd, :repo, :state
   and :user like decision-request."
  [m {:keys [cwd get-state room-id] :as ctx}]
  (let [eff (or (:effective-cwd m) cwd (.cwd js/process))
        p   (:path m)]
    (merge {:effective-cwd eff
            :repo          (git-root (if p (path/dirname (expand-path eff (str p))) eff))
            :state         (when (and get-state room-id)
                             (get-in (get-state) [:rooms room-id :ext]))
            :user          (request-user ctx)}
           m)))

(defn with-path-target
  "Append the target path and its git repo (or none) to a path ask's confirm `text`."
  [text path repo]
  (str text "\n\n"
       "Path: " path "\n"
       "Repo: " (or repo "none (not inside a git repo)")))

(defn hard-block-request
  "The non-overridable check: a deny message when `req` would write a rules
   file (a write/edit targeting one, or a shell-ish command naming one next to
   a write token), else nil. Imperative on purpose: it must never be shadowed."
  [{:keys [tool path command effective-cwd]}]
  (when (case tool
          (:write :edit)   (and path (re-find rules-file-re (expand-path effective-cwd (str path))))
          (:bash :clj :sh) (let [c (str command)]
                             (and (re-find rules-file-loose-re c) (re-find write-token-re c)))
          false)
    hard-block-msg))

(defn hard-block
  "`hard-block-request` for a tool call: an intercepted deny result, or nil."
  [tool-call ctx]
  (when-let [msg (hard-block-request (decision-request tool-call ctx))]
    (deny-result msg)))

(defn outside-cwd?
  "True when `path` resolves outside both the effective `cwd` and the OS tmp
   dir, symlinks canonicalized; `{:nofollow? true}` judges a final-component
   symlink by its own location (entry-level ops). I/O."
  [cwd path & [{:keys [nofollow?]}]]
  (boolean
   (when (and cwd path (not (str/blank? (str path))))
     (let [resolved (if nofollow?
                      (paths/real-resolve-nofollow cwd (str path))
                      (paths/real-resolve cwd (str path)))
           real-cwd (paths/real-resolve cwd ".")]
       (not (or (paths/path-within? resolved real-cwd)
                (paths/within-tmp? cwd resolved)))))))

(defn credential-path?
  "True when `path` resolves inside a hidden credential dir, symlinks canonicalized. I/O."
  [cwd path]
  (boolean
   (when (and cwd path (not (str/blank? (str path))))
     (let [resolved (paths/real-resolve cwd (str path))]
       (some #(paths/path-within? resolved %) (paths/hidden-paths))))))

(def ^:private version-key-re #":version\b")

(def ^:private config-type-re
  "The `:type :xi/config` tag of xi's user config file, in raw text."
  #":type\s+:xi/config\b")

(defn- xi-file-change?
  "True when `req` would change an xi-owned file named `file-name` whose
   content `tagged?` recognizes (parsed data, or raw text for a broken file),
   wherever it lives: a write/edit targeting one, or a bash/clj command naming
   an existing tagged one next to a write token. I/O."
  [file-name tagged? {:keys [tool path command arguments effective-cwd]}]
  (let [named?       (fn [p] (= file-name (path/basename (str p))))
        tagged-file? (fn [file]
                       (try
                         (and (fs/existsSync file)
                              (let [text (str (fs/readFileSync file "utf8"))]
                                (boolean (tagged? (read-rule-edn text) text))))
                         (catch :default _ false)))
        ;; a path-ish token naming the file inside a shell command / clj code
        token-re     (re-pattern (str "[^\\s'\"`()\\[\\]{}]*" (str/replace file-name "." "\\.")))]
    (boolean
     (case tool
       (:write :edit)
       (when path
         (let [resolved (paths/real-resolve effective-cwd (str path))]
           (and (or (named? path) (named? resolved))
                (or (tagged-file? resolved)
                    (some #(tagged? nil (str %))
                          (cons (:content arguments)
                                (map :newText (:edits arguments))))))))

       (:bash :clj)
       (when (and command (re-find write-token-re (str command)))
         (some #(tagged-file? (paths/real-resolve effective-cwd %))
               (re-seq token-re (str command))))

       false))))

(defn xi-rules-file-change?
  "xi-file-change? for any `rules.edn` carrying `:version`; computed only for
   `:xi-rules-file` rules."
  [req]
  (xi-file-change? "rules.edn"
                   (fn [data text]
                     (if (some? data)
                       (and (map? data) (contains? data :version))
                       (re-find version-key-re text)))
                   req))

(defn xi-config-file-change?
  "xi-file-change? for any `config.edn` tagged `:type :xi/config`; computed
   only for `:xi-config-file` rules."
  [req]
  (xi-file-change? "config.edn"
                   (fn [data text]
                     (if (some? data)
                       (and (map? data) (= :xi/config (:type data)))
                       (re-find config-type-re text)))
                   req))

(defn- literal-operands
  "The non-flag args of literal `argv`, or nil when there are none or a flag
   isn't a bare short-flag cluster (a `--long` flag or glued value could
   smuggle a path). A flag's separate value counts as an operand."
  [argv]
  (let [args     (map str (rest argv))
        flags    (filter #(str/starts-with? % "-") args)
        operands (remove #(str/starts-with? % "-") args)]
    (when (and (seq operands) (every? #(re-matches #"-[a-zA-Z]+" %) flags))
      operands)))

(defn operands-within-repo?
  "True when literal `argv` only touches paths strictly inside `repo` (never
   the root, `.git/` or `.xi/`) or tmp, symlinks canonicalized. I/O."
  [cwd repo argv]
  (boolean
   (when-let [operands (and repo (literal-operands argv))]
     (let [root     (paths/real-resolve cwd repo)
           reserved (map #(path/join root %) [".git" ".xi"])]
       (every? (fn [op]
                 ;; root / reserved checks come first so a repo living
                 ;; under tmp can't launder them through the tmp clause.
                 (let [resolved (paths/real-resolve cwd op)]
                   (and (not= resolved root)
                        (not-any? #(paths/path-within? resolved %) reserved)
                        (or (paths/path-within? resolved root)
                            (paths/within-tmp? cwd resolved)))))
               operands)))))

(defn- git-ls-files
  "`git ls-files -z <flags> -- rel` entries under repo `root`, or nil when git
   fails. `--literal-pathspecs` keeps globs literal."
  [root rel flags]
  (let [r (cp/spawnSync "git"
                        (clj->js (concat ["--literal-pathspecs" "-C" root "ls-files" "-z"]
                                         flags ["--" rel]))
                        #js {:encoding "utf8"})]
    (when (= 0 (.-status r))
      (remove str/blank? (str/split (str (.-stdout r)) #"\u0000")))))

(defn git-tracked?
  "True when canonical path `abs` is git-tracked content in `root`: an indexed
   file, or a directory of indexed files with nothing untracked (ignored counts
   as untracked). The root never counts. I/O."
  [root abs]
  (boolean
   (when (and root abs (not= abs root) (paths/path-within? abs root))
     (let [rel (path/relative root abs)]
       (and (seq (git-ls-files root rel []))
            (empty? (git-ls-files root rel ["--others"])))))))

(defn operands-git-tracked?
  "True when literal `argv` only names git-tracked content inside `repo` (see
   git-tracked?), so removing or moving it is recoverable. I/O."
  [cwd repo argv]
  (boolean
   (when-let [operands (and repo (literal-operands argv))]
     (let [root (paths/real-resolve cwd repo)]
       (every? #(git-tracked? root (paths/real-resolve cwd %)) operands)))))

(defn strip-quoted
  "Remove quoted spans and redirection operators so their `;` `|` `&` don't
   read as command separators."
  [s]
  (-> s
      (str/replace #"'[^']*'" "_")
      (str/replace #"\"(?:\\.|[^\"\\])*\"" "_")
      (str/replace #"\d*>&\d*" " ")
      (str/replace #"&>>?" " ")))

(defn chained-command?
  "True when a bash command uses shell composition (pipes, `;`/`&&`/`&`,
   substitution, backticks, multiple lines, a leading VAR= binding)."
  [cmd]
  (let [s (strip-quoted (str/trim (str cmd)))]
    (boolean (or (re-find #"[;|&\n]" s)
                 (re-find #"\$\(" s)
                 (str/includes? s "`")
                 (re-find #"^\w+=" s)))))

(defn- on-path
  "The absolute path of `program` on this process's PATH, or nil."
  [program]
  (let [path-env (or (aget js/process.env "PATH") "")]
    (if (exists? js/Bun)
      (js/Bun.which program #js {:PATH path-env})
      (some (fn [dir]
              (let [p (path/join dir program)]
                (when (and (seq dir) (fs/existsSync p)) p)))
            (str/split path-env #":")))))

(defn program-installed?
  "True when `program` can be executed (on PATH, or relative to `cwd` with a
   `/`); nil for a token that isn't a program. I/O."
  [cwd program]
  (let [p (str program)]
    (cond
      (or (str/blank? p) (re-find #"^\w+=" p)) nil
      (str/includes? p "/") (fs/existsSync (path/resolve (or cwd ".") (paths/expand-home p)))
      :else                 (some? (on-path p)))))

(defn- home-collapse
  "Absolute `abs` with a leading $HOME rewritten to `~`, so home-relative
   `:path` rules match; nil when not under $HOME."
  [abs]
  (when abs
    (let [home (os/homedir)]
      (cond
        (= abs home)                               "~"
        (str/starts-with? abs (str home path/sep)) (str "~" (subs abs (count home)))
        :else                                      nil))))

(defn operand-paths
  "The forms a `:path` rule is tested against for each literal operand of a
   `:sh` `argv` — [as given, resolved absolute, ~-collapsed when under $HOME] —
   so `(sh \"rm\" \"-r\" \"/tmp/x\")` matches `\"/tmp/**\"` and a `~/…` glob
   matches every spelling. nil when the operands aren't literal (see
   `literal-operands`). I/O (symlinks are canonicalized)."
  [cwd argv]
  (when-let [operands (literal-operands argv)]
    (mapv (fn [op]
            (let [resolved (paths/real-resolve (or cwd (.cwd js/process)) op)]
              (cond-> [op resolved]
                (home-collapse resolved) (conj (home-collapse resolved)))))
          operands)))

(defn enrich-request
  "Add the I/O-derived match fields the given `ruleset` actually needs to
   `req`: :resolved-path / :resolved-home-path (`:path`), :operand-paths
   (`:path` on a `:sh` request), :outside-cwd?, :credential-path?, :nodes
   (`:node`), :operands-within-repo? (`:within`), :operands-tracked?
   (`:tracked`, a delay), :xi-rules-file?, :xi-config-file?, :own-data?
   (`:extension-data`), :chained?, :bb-trusted?, :mcp-trusted?, :installed?,
   :user-record (map `:user` rules)."
  [req ruleset]
  (let [resolved (when (and (:path req) (rules/needs-resolved-path? ruleset))
                   (paths/real-resolve (:effective-cwd req) (str (:path req))))]
    (cond-> req
      resolved (assoc :resolved-path resolved)
      (home-collapse resolved) (assoc :resolved-home-path (home-collapse resolved))
      (and (= :sh (:tool req)) (:argv req) (rules/needs-resolved-path? ruleset))
      (assoc :operand-paths (operand-paths (:effective-cwd req) (:argv req)))
      (and (:path req) (rules/needs-outside? ruleset))
      (assoc :outside-cwd? (outside-cwd? (:effective-cwd req) (:path req)))
      (and (:path req) (rules/needs-credential? ruleset))
      (assoc :credential-path? (credential-path? (:effective-cwd req) (:path req)))
      (and (:path req) (rules/needs-nodes? ruleset))
      (assoc :nodes (nodes/nodes-for req))
      (and (:argv req) (rules/needs-within? ruleset))
      (assoc :operands-within-repo? (operands-within-repo? (:effective-cwd req)
                                                           (:repo req) (:argv req)))
      (and (:argv req) (rules/needs-tracked? ruleset))
      (assoc :operands-tracked? (let [{:keys [effective-cwd repo argv]} req]
                                  (delay (operands-git-tracked? effective-cwd repo argv))))
      (rules/needs-xi-rules-file? ruleset)
      (assoc :xi-rules-file? (xi-rules-file-change? req))
      (rules/needs-xi-config-file? ruleset)
      (assoc :xi-config-file? (xi-config-file-change? req))
      (and (= :bash (:tool req)) (rules/needs-chained? ruleset))
      (assoc :chained? (chained-command? (:command req)))
      (and (:user req) (not (contains? req :user-record)) (rules/needs-user-record? ruleset))
      (assoc :user-record (user-record (:user req)))
      (and (= :sh (:tool req)) (:cli req) (rules/needs-installed? ruleset))
      (assoc :installed? (program-installed? (:effective-cwd req) (:cli req)))
      (and (= :bb (:tool req)) (rules/needs-bb-trusted? ruleset))
      (assoc :bb-trusted? (bb-trust/trusted? (:effective-cwd req)))
      (and (= :mcp (:tool req)) (:mcp-server req) (rules/needs-mcp-trusted? ruleset))
      (assoc :mcp-trusted? (mcp-trust/trusted? (:mcp-server req)))
      (and (:path req) (:extension req) (rules/needs-extension-data? ruleset))
      (assoc :own-data? (paths/path-within?
                         (paths/real-resolve (:effective-cwd req) (str (:path req)))
                         (paths/real-resolve "/" (paths/extension-data-dir (:extension req))))))))

;; ── Ordered ruleset ─────────────────────────────────────────────────────────

(defn- config-files
  "The config rules files for `cwd`, highest precedence first, as
   [[file scope] …] (the repo file only when cwd is inside a git repo)."
  [cwd]
  (let [repo (git-root (or cwd (.cwd js/process)))]
    (cond-> []
      repo (conj [(repo-file repo) :repo])
      true (conj [(global-file) :global]))))

(defn config-rules
  "Repo rules then global rules, tagged with their scope; an invalid file
   contributes `invalid-file-rule` instead."
  [cwd]
  (vec (mapcat (fn [[file scope]]
                 (let [{:keys [rules error]} (load-rules-file file)]
                   (map #(assoc % :scope scope)
                        (if error [(invalid-file-rule file error)] rules))))
               (config-files cwd))))

(defn default-rules
  "The lowest-precedence tier: the first config file setting `:defaults` (repo,
   then global), expanded; else the built-in defaults."
  [cwd]
  (or (some (fn [[file _]] (:defaults (load-rules-file file)))
            (config-files cwd))
      defaults/default-rules))

(defn runtime-rules
  "Server-session rules then session-runtime rules, each tagged."
  [state room-id]
  (vec (concat (map #(assoc % :scope :server)  (get-in state [:ext ext-id :rules]))
               (map #(assoc % :scope :session) (get-in state [:rooms room-id :ext ext-id :rules])))))

(defonce ^:private hardened-disabled
  ;; Process-global toggle set at launch from the CLI flag; the agent can never
  ;; flip it (it can't relaunch the process), so the hardened tier is effectively
  ;; immutable to the agent.
  (atom false))

(defn set-hardened-disabled!
  "Disable (true) / enable (false) the hardened rules tier. Called once at
   startup from the `--no-hardened-rules` CLI flag."
  [disabled?]
  (reset! hardened-disabled (boolean disabled?)))

(defn hardened-rules
  "The hardened rules tier, or an empty vector when disabled via CLI flag."
  []
  (if @hardened-disabled [] defaults/hardened-rules))

(defn ordered-rules
  "The full ruleset in precedence order for `cwd`/`room-id`: hardened, config
   (repo, global), runtime (server, session), default. The imperative
   hard-block runs first, separately."
  [state room-id cwd]
  (vec (concat (hardened-rules)
               (config-rules cwd)
               (runtime-rules state room-id)
               (default-rules cwd))))

;; ── Config file writing (repo / global scopes) ───────────────────────────────

(defn- write-rules-file!
  "Persist rules-file `data` to `file` as pretty EDN (one rule per line); regex
   literals round-trip via pr-str/read-string."
  [file {:keys [version rules] :as data}]
  (fs/mkdirSync (path/dirname file) #js {:recursive true})
  (fs/writeFileSync
   file
   (str "{:type " (pr-str rules-file-type) "\n"
        " :version " (pr-str version) "\n"
        (when (contains? data :defaults)
          (str " :defaults " (pr-str (:defaults data)) "\n"))
        " :rules\n [" (str/join "\n  " (map pr-str rules)) "]}\n")
   "utf8"))

(defn scope-file
  "Resolve the on-disk rules file for a config `scope` (:repo | :global),
   or nil when the scope has no file (e.g. :repo with no git root)."
  [scope cwd]
  (case scope
    :repo   (repo-file (git-root (or cwd (.cwd js/process))))
    :global (global-file)
    nil))

(defn append-rule-file!
  "Prepend `rule` to the config file for `scope` (:repo | :global), creating a
   missing file at the current type + version. → {:file} or {:error msg}
   (invalid file left untouched), nil when the scope has no file."
  [scope cwd rule]
  (when-let [file (scope-file scope cwd)]
    (let [data  (if (fs/existsSync file)
                  (read-rules-data file)
                  {:type rules-file-type :version rules-file-version :rules []})
          error (:error (parse-rules-config data))]
      (if error
        {:error (str file " is invalid — " error)}
        (do (write-rules-file! file (update data :rules
                                            #(vec (cons (dissoc rule :scope) %))))
            (clear-cache!)
            {:file file})))))
