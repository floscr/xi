(ns xi.rules.store
  "Rules store — loads the config rule files, merges the runtime scopes from
   app state, normalizes a tool call into a decision request, and enforces the
   immutable hard-block (agents may never write the global rules file; a
   repo's `.xi/rules.edn` asks every time).

   Precedence (first match wins, top → bottom):
     1. hard-coded immutable  (this namespace — see `hard-block-request`,
                              `repo-rules-ask-request`)
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
            [xi.task-trust :as task-trust]
            [xi.core.state :as core-state]
            [xi.mcp.trust :as mcp-trust]
            [xi.rules :as rules]
            [xi.rules.defaults :as defaults]
            [xi.rules.migrate :as migrate]
            [xi.rules.nodes :as nodes]
            [xi.rules.tasks :as tasks]
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

(defn- expand-match-dir
  "A rule with its `:dir` (`~/…` or absolute) in canonical form: home
   expanded, `..` and symlinks resolved. xi.rules/match-dir compares it with
   the canonical cwd (`:real-cwd`), so both sides must be in that form."
  [rule]
  (let [k (if (contains? rule :on-block) :on-block :match)
        d (get-in rule [k :dir])]
    (if (and (string? d) (or (= d "~") (str/starts-with? d "~/") (path/isAbsolute d)))
      (assoc-in rule [k :dir] (paths/real-resolve "/" d))
      rule)))

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

      ;; an ignored old key would widen its rule: refuse until migrated
      (seq (migrate/changes data))
      {:error (str "uses names an older xi had ("
                   (str/join "; " (migrate/changes data))
                   ") — run `xi migrate` to update it")}

      :else
      (try
        {:rules    (mapv expand-match-dir (:rules data))
         :defaults (when (contains? data :defaults)
                     (mapv expand-match-dir (defaults/expand (:defaults data))))}
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

;; The global file is denied outright; the repo's `.xi/rules.edn` asks, every
;; time (xi.ext.rules/decide*). The `*-loose-re` variants match a path
;; mentioned anywhere in a command/code string.
(def ^:private global-rules-file-re #"\.config/xi/rules\.edn$")
(def ^:private global-rules-file-loose-re #"\.config/xi/rules\.edn")
(def ^:private repo-rules-file-re #"[/\\]\.xi/rules\.edn$")
(def ^:private repo-rules-file-loose-re #"\.xi/rules\.edn")

(def ^:private write-token-re
  "Write indicators in a shell command or clj eval that would mutate a file."
  #"(?:>>?|\btee\b|\bsed\s+-i|\bcp\b|\bmv\b|\bdd\b|\bchmod\b|\bspit\b|writeFileSync|appendFileSync|\brm\b)")

(def ^:private hard-block-msg
  (str "Blocked (hard rule): agents may not modify the global rules file "
       "(~/.config/xi/rules.edn). Ask the user to edit it, or use the "
       "'recommend a rule' flow on a guard dialog."))

(def ^:private repo-rules-ask-msg
  "Change this repo's permission rules (.xi/rules.edn)?")

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
      "run_task" :task
      (if (str/starts-with? n "mcp__") :mcp :other))))

(defn task-runner
  "The runner a run_task call uses: its `:runner`, else the one of the
   nearest project file from `cwd` (xi.rules.tasks/detect-runner)."
  [arguments cwd]
  (or (not-empty (str (:runner arguments))) (tasks/detect-runner cwd)))

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
        eff-cwd (or cwd (.cwd js/process))
        runner  (when (= kind :task) (task-runner arguments eff-cwd))
        ;; the command line a run_task call runs, so `:command` rules match it
        command (if (= kind :task)
                  (some->> (tasks/task-argv runner (:task arguments) (:args arguments))
                           (str/join " "))
                  (:command arguments))
        code    (:code arguments)
        repo    (git-root (if p (path/dirname (expand-path eff-cwd (str p))) eff-cwd))
        state   (when (and get-state room-id)
                  (get-in (get-state) [:rooms room-id :ext]))]
    (cond-> {:tool kind :tool-name (some-> name str) :effective-cwd eff-cwd
             :repo repo :state state :user (request-user ctx)}
      p         (assoc :path p)
      command   (assoc :command command)
      code      (assoc :command code)
      (seq arguments) (assoc :arguments arguments)
      runner    (assoc :runner runner)
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

(defn- writes-rules-file?
  "A write/edit targeting the file `file-re` matches, or a shell-ish command
   naming it (`loose-re`) next to a write token."
  [file-re loose-re {:keys [tool path command effective-cwd]}]
  (boolean
   (case tool
     (:write :edit)   (and path (re-find file-re (expand-path effective-cwd (str path))))
     (:bash :clj :sh) (let [c (str command)]
                        (and (re-find loose-re c) (re-find write-token-re c)))
     false)))

(defn rules-file-path?
  "True when the resolved path `abs` is a canonical rules file (global or a
   repo's) or a `.xi` directory itself (a move onto it would swap one in);
   xi.ext.clj refuses these in its write helpers."
  [abs]
  (boolean (or (re-find global-rules-file-re abs)
               (re-find repo-rules-file-re abs)
               (re-find #"[/\\]\.xi$" abs))))

(defn hard-block-request
  "The non-overridable check: a deny message when `req` would write the global
   rules file, else nil. Imperative on purpose: it must never be shadowed."
  [req]
  (when (writes-rules-file? global-rules-file-re global-rules-file-loose-re req)
    hard-block-msg))

(defn repo-rules-ask-request
  "The confirm text when `req` would write a repo's `.xi/rules.edn`, else nil.
   Checked right after hard-block-request, before every rule, so no allow rule,
   grant or launch flag lifts it; xi.ext.rules asks as a hardened ask (never
   auto-answered, denied when no client can answer)."
  [req]
  (when (and (not (hard-block-request req))
             (writes-rules-file? repo-rules-file-re repo-rules-file-loose-re req))
    repo-rules-ask-msg))

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

(defn- clj-runner-call?
  "A clj `(sh <runner> …)` (bb, npm, …: xi.rules.tasks/runners) — not an
   extension's (xi.api.sh), which keeps its own per-command asks."
  [req]
  (and (= :sh (:tool req)) (contains? tasks/runners (:cli req)) (:argv req)
       (not (:extension req))))

(defn enrich-request
  "Add the I/O-derived match fields the given `ruleset` actually needs to
   `req`: :resolved-path / :resolved-home-path (`:path`), :operand-paths
   (`:path` on a `:sh` request), :outside-cwd?, :credential-path?, :nodes
   (`:node`), :operands-within-repo? (`:within`), :operands-tracked?
   (`:tracked`, a delay), :xi-rules-file?, :xi-config-file?, :own-data?
   (`:extension-data`), :chained?, :mcp-trusted?, :installed?, :runner-task
   (`:task`), :task-call? (a clj `(sh …)` running a task its project file
   defines, matched like run_task), :task-file and :task-file-trusted?,
   :user-record (map `:user` rules)."
  [req ruleset]
  (let [resolved    (when (and (:path req) (rules/needs-resolved-path? ruleset))
                      (paths/real-resolve (:effective-cwd req) (str (:path req))))
        runner-task (when (or (rules/needs-task? ruleset) (clj-runner-call? req))
                      (tasks/request-task req))
        task-call?  (and (clj-runner-call? req) (some? runner-task))
        task-file   (when (and (or (= :task (:tool req)) task-call?)
                               (rules/needs-task-file-trusted? ruleset))
                      (tasks/project-file (:effective-cwd req)
                                          (or (:runner runner-task) (:runner req))))]
    (cond-> req
      (and (:effective-cwd req) (rules/needs-real-cwd? ruleset))
      (assoc :real-cwd (paths/real-resolve (:effective-cwd req) "."))
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
      (rules/needs-task? ruleset)
      (assoc :runner-task runner-task)
      task-call?
      (assoc :task-call? true)
      task-file
      (assoc :task-file task-file)
      (and (or (= :task (:tool req)) task-call?) (rules/needs-task-file-trusted? ruleset))
      (assoc :task-file-trusted? (task-trust/trusted? task-file))
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

(defn- rule-key
  "`rule` comparable by value: regex literals are never `=` to each other."
  [rule]
  (walk/postwalk #(if (regexp? %) [::regex (.-source %) (.-flags %)] %) rule))

(defn- new-rules
  "`rules` minus their :scope tag and minus the ones `existing` (or an earlier
   entry) already holds."
  [existing rules]
  (first (reduce (fn [[acc seen] r]
                   (let [k (rule-key r)]
                     (if (seen k) [acc seen] [(conj acc r) (conj seen k)])))
                 [[] (into #{} (map rule-key) existing)]
                 (map #(dissoc % :scope) rules))))

(defn append-rules-file!
  "Prepend `rules` (in order, skipping ones the file already holds) to the
   config file for `scope` (:repo | :global), creating a missing file at the
   current type + version. → {:file :added n} or {:error msg} (invalid file
   left untouched), nil when the scope has no file."
  [scope cwd rules]
  (when-let [file (scope-file scope cwd)]
    (let [data  (if (fs/existsSync file)
                  (read-rules-data file)
                  {:type rules-file-type :version rules-file-version :rules []})
          error (:error (parse-rules-config data))]
      (if error
        {:error (str file " is invalid — " error)}
        (let [fresh (new-rules (:rules data) rules)]
          (when (seq fresh)
            (write-rules-file! file (update data :rules #(vec (concat fresh %))))
            (clear-cache!))
          {:file file :added (count fresh)})))))

(defn append-rule-file!
  "Prepend `rule` to the config file for `scope`, see `append-rules-file!`.
   → {:file} or {:error msg}, nil when the scope has no file."
  [scope cwd rule]
  (some-> (append-rules-file! scope cwd [rule]) (dissoc :added)))
