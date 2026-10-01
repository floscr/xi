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
            [cljs.tools.reader :as tr]
            [xi.bb-trust :as bb-trust]
            [xi.rules :as rules]
            [xi.rules.defaults :as defaults]
            [xi.rules.nodes :as nodes]
            [xi.paths :as paths]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def ext-id :rules)

;; ── Paths ────────────────────────────────────────────────────────────────────

(defn config-dir [] (path/join (os/homedir) ".config" "xi"))
(defonce ^:private global-file-override (atom nil))

(defn set-global-file!
  "Point the global rules file at `file` (nil restores the default). Used by
   the test runner so the user's real ~/.config/xi/rules.edn never leaks into
   test runs."
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
  "Parse a rule/rules EDN string with the full Clojure reader (so regex
   literals `#\"…\"` in `:path`/`:command` round-trip — they are not valid EDN
   for the plain edn reader). Unknown tagged literals fall through to their
   value. Returns the parsed data, or nil on parse failure."
  [s]
  (try
    (binding [tr/*default-data-reader-fn* (fn [_tag v] v)]
      (tr/read-string (str s)))
    (catch :default _ nil)))

(def rules-file-version
  "The rules-file format version this xi reads. Every rules file must declare
   it as `:version`; a missing or different version is an error, so a format
   change (e.g. renamed default aliases) can never be misread silently."
  1)

(def rules-file-type
  "The `:type` tag every xi rules file must carry, so the file identifies
   itself (and can't be confused with another tool's rules.edn or with xi's
   own config.edn, `:xi/config`)."
  :xi/rules)

(def ^:private rules-file-keys #{:type :version :rules :defaults :extensions})

(defn parse-rules-config
  "Validate parsed rules-file `data` (nil = unparseable). Returns
   `{:rules [...] :defaults [...] :extensions [...]}` — `:defaults` expanded
   via `defaults/expand`, nil when the file doesn't set it; `:extensions` as
   written, nil when absent — or `{:error msg}`."
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

      (not (and (sequential? (:extensions data []))
                (every? string? (:extensions data))))
      {:error ":extensions must be a vector of extension file names"}

      :else
      (try
        (cond-> {:rules    (vec (:rules data))
                 :defaults (when (contains? data :defaults)
                             (defaults/expand (:defaults data)))}
          (contains? data :extensions) (assoc :extensions (vec (:extensions data))))
        (catch :default e
          {:error (ex-message e)})))))

(defn- read-rules-data
  "The raw parsed EDN of rules `file` (nil when unparseable)."
  [file]
  (read-rule-edn (str (fs/readFileSync file "utf8"))))

(defn- load-rules-file
  "Read + validate rules `file`, cached by mtime. nil when there is no file;
   otherwise the `parse-rules-config` result (`{:rules :defaults}` or
   `{:error}`)."
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
  "The fail-closed stand-in for an invalid rules `file`: a catch-all deny that
   takes the file's place in the config tier, so every rule below it (runtime
   grants, defaults) is shadowed until the file is fixed — a broken file must
   never silently drop the user's own denies."
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
  "Matches a resolved path that IS a rules file."
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
  "Classify a tool name into a rule tool kind."
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
  "The command line a bb tool call runs — `bb <task> <args…>`, or `bb tasks`
   without a task (mirrors xi.ext.clj's bb-argv) — so `:command` rules match
   bb calls like any other command."
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

(defn decision-request
  "Normalize a tool call into the pure matcher's decision request.
   Resolves the effective cwd, the repo root (of the target path, else cwd),
   the raw target path/command, the tool-call arguments (for informative ask
   dialogs), and any MCP server/tool + room ext state."
  [tool-call {:keys [cwd get-state room-id]}]
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
             :repo repo :state state}
      p         (assoc :path p)
      command   (assoc :command command)
      code      (assoc :command code)
      (seq arguments) (assoc :arguments arguments)
      (= kind :mcp) (merge (parse-mcp name)))))

(defn request
  "A decision request for a call that isn't a tool call — xi.api.* from user
   extensions. `m` carries :tool plus whatever it targets (:path :command :cli
   :argv :host :extension …); this fills :effective-cwd (m's own, else ctx's
   :cwd), :repo, and the room's ext :state, like decision-request does."
  [m {:keys [cwd get-state room-id]}]
  (let [eff (or (:effective-cwd m) cwd (.cwd js/process))
        p   (:path m)]
    (merge {:effective-cwd eff
            :repo          (git-root (if p (path/dirname (expand-path eff (str p))) eff))
            :state         (when (and get-state room-id)
                             (get-in (get-state) [:rooms room-id :ext]))}
           m)))

(defn with-path-target
  "Append the target of a path ask to its confirm `text`: the path and the git
   repo it sits in (or that it is in none), so a rule's generic :message —
   \"Write outside the project repo?\" — still says what is being approved."
  [text path repo]
  (str text "\n\n"
       "Path: " path "\n"
       "Repo: " (or repo "none (not inside a git repo)")))

(defn hard-block-request
  "Immutable, non-overridable check: a deny message when decision request
   `req` would write a rules file — a write/edit targeting one, or a shell-ish
   command (bash, clj code, (sh …)) naming one next to a write token — else
   nil. Deliberately imperative (not a data rule): it is security-critical and
   must never be shadowed or disabled."
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
  "True when target `path` resolves outside both the effective `cwd` and the OS
   tmp dir — i.e. a write/edit that escapes the working tree. Tmp is always
   allowed. Symlinks are canonicalized (paths/real-resolve) so the check can't
   be laundered through a link created inside cwd. This is I/O, computed only
   when an `:outside` rule is in play."
  [cwd path]
  (boolean
   (when (and cwd path (not (str/blank? (str path))))
     (let [resolved (paths/real-resolve cwd (str path))
           real-cwd (paths/real-resolve cwd ".")]
       (not (or (paths/path-within? resolved real-cwd)
                (paths/within-tmp? cwd resolved)))))))

(defn credential-path?
  "True when target `path` resolves inside one of the hidden credential dirs
   (.ssh, .gnupg, .password-store, …). Symlinks are canonicalized
   (paths/real-resolve) so the check can't be laundered through a link. I/O,
   computed only when a `:credential` rule is in play."
  [cwd path]
  (boolean
   (when (and cwd path (not (str/blank? (str path))))
     (let [resolved (paths/real-resolve cwd (str path))]
       (some #(paths/path-within? resolved %) (paths/hidden-paths))))))

(def ^:private rules-edn-token-re
  "A path-ish token naming a `rules.edn` inside a shell command / clj code."
  #"[^\s'\"`()\[\]{}]*rules\.edn")

(def ^:private version-key-re #":version\b")

(defn- rules-edn-name? [p]
  (= "rules.edn" (path/basename (str p))))

(defn- versioned-rules-edn?
  "True when `file` is an existing xi rules file: it parses to a map carrying
   `:version` — the version lock every xi rules file must declare, which tells
   it apart from any other tool's rules.edn. Unparseable content that mentions
   `:version` counts too (a broken xi file still gets confirmed)."
  [file]
  (try
    (and (fs/existsSync file)
         (let [text (str (fs/readFileSync file "utf8"))
               data (read-rule-edn text)]
           (if (some? data)
             (and (map? data) (contains? data :version))
             (boolean (re-find version-key-re text)))))
    (catch :default _ false)))

(defn xi-rules-file-change?
  "True when decision `req` would change an xi rules file (any `rules.edn`
   carrying `:version`, wherever it lives — e.g. a dotfiles source copy).
   write/edit: the target is named rules.edn (raw or symlink-resolved) and
   either the existing file is versioned or the new text introduces
   `:version` (creating or migrating one). bash/clj: the command carries a
   write token and names a rules.edn path that is an existing versioned file.
   I/O, computed only when an `:xi-rules-file` rule is in play."
  [{:keys [tool path command arguments effective-cwd]}]
  (boolean
   (case tool
     (:write :edit)
     (when path
       (let [resolved (paths/real-resolve effective-cwd (str path))]
         (and (or (rules-edn-name? path) (rules-edn-name? resolved))
              (or (versioned-rules-edn? resolved)
                  (some #(re-find version-key-re (str %))
                        (cons (:content arguments)
                              (map :newText (:edits arguments))))))))

     (:bash :clj)
     (when (and command (re-find write-token-re (str command)))
       (some #(versioned-rules-edn? (paths/real-resolve effective-cwd %))
             (re-seq rules-edn-token-re (str command))))

     false)))

(defn operands-within-repo?
  "True when literal `argv` (a `:sh` command, binary first) only touches paths
   strictly inside `repo` (not the root itself — `mv <repo> /tmp/x`) or tmp —
   never the repo's `.git/` (hooks = code execution) or `.xi/` (moving it away
   would drop the repo's rules file). Flags must be bare short-flag
   clusters (`-f`, `-rv`): a `--long[=value]` flag, `--`, or a value glued to a
   non-letter (`-t/etc`) could smuggle an unchecked path, so it never matches.
   Every other arg is treated as a path and resolved against `cwd` with
   symlinks canonicalized (a flag's separate value, e.g. `-m 755`, is checked
   too — stricter, never looser). I/O, computed only when a `:within` rule is
   in play."
  [cwd repo argv]
  (let [args     (map str (rest argv))
        flags    (filter #(str/starts-with? % "-") args)
        operands (remove #(str/starts-with? % "-") args)]
    (boolean
     (when (and repo (seq operands)
                (every? #(re-matches #"-[a-zA-Z]+" %) flags))
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
                 operands))))))

(defn strip-quoted
  "Remove single- and double-quoted spans so quoted `;`/`|` don't count,
   and redirection operators (`2>&1`, `>&2`, `&>`) so their `&`/`|` don't
   read as command separators."
  [s]
  (-> s
      (str/replace #"'[^']*'" "_")
      (str/replace #"\"(?:\\.|[^\"\\])*\"" "_")
      (str/replace #"\d*>&\d*" " ")
      (str/replace #"&>>?" " ")))

(defn chained-command?
  "True when a bash command uses shell composition — pipes, `;`/`&&`/`&`,
   command substitution, backticks, multiple lines, or a leading VAR= binding.
   These are the unreadable one-liners the clj tool exists to replace
   (`:chained` rules)."
  [cmd]
  (let [s (strip-quoted (str/trim (str cmd)))]
    (boolean (or (re-find #"[;|&\n]" s)
                 (re-find #"\$\(" s)
                 (str/includes? s "`")
                 (re-find #"^\w+=" s)))))

(defn- home-collapse
  "Rewrite a leading $HOME in absolute `abs` back to `~`, so a `:path` rule can
   be written home-relative (`~/…`) and still match a resolved absolute target.
   Returns nil when `abs` is nil or not under $HOME (nothing to collapse)."
  [abs]
  (when abs
    (let [home (os/homedir)]
      (cond
        (= abs home)                               "~"
        (str/starts-with? abs (str home path/sep)) (str "~" (subs abs (count home)))
        :else                                      nil))))

(defn enrich-request
  "Add the opt-in, I/O-derived match fields to a decision `req` that the given
   `ruleset` actually needs — `:resolved-path` (canonical absolute path) plus
   `:resolved-home-path` (that path with a leading $HOME collapsed to `~`) for
   `:path` rules, `:outside-cwd?` for `:outside` rules, `:credential-path?` for
   `:credential` rules, `:nodes` (tree-sitter) for `:node` rules,
   `:operands-within-repo?` for `:within` rules (from a literal `:sh` `:argv`),
   `:xi-rules-file?` for `:xi-rules-file` rules, `:own-data?` for
   `:extension-data` rules (symlink-canonical, so a link out of the data dir
   doesn't count), `:chained?` for `:chained` rules (`:bash` commands), and
   `:bb-trusted?` for `:bb-trusted` rules (`:bb` calls)."
  [req ruleset]
  (let [resolved (when (and (:path req) (rules/needs-resolved-path? ruleset))
                   (paths/real-resolve (:effective-cwd req) (str (:path req))))]
    (cond-> req
      resolved (assoc :resolved-path resolved)
      (home-collapse resolved) (assoc :resolved-home-path (home-collapse resolved))
      (and (:path req) (rules/needs-outside? ruleset))
      (assoc :outside-cwd? (outside-cwd? (:effective-cwd req) (:path req)))
      (and (:path req) (rules/needs-credential? ruleset))
      (assoc :credential-path? (credential-path? (:effective-cwd req) (:path req)))
      (and (:path req) (rules/needs-nodes? ruleset))
      (assoc :nodes (nodes/nodes-for req))
      (and (:argv req) (rules/needs-within? ruleset))
      (assoc :operands-within-repo? (operands-within-repo? (:effective-cwd req)
                                                           (:repo req) (:argv req)))
      (rules/needs-xi-rules-file? ruleset)
      (assoc :xi-rules-file? (xi-rules-file-change? req))
      (and (= :bash (:tool req)) (rules/needs-chained? ruleset))
      (assoc :chained? (chained-command? (:command req)))
      (and (= :bb (:tool req)) (rules/needs-bb-trusted? ruleset))
      (assoc :bb-trusted? (bb-trust/trusted? (:effective-cwd req)))
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
  "Repo rules then global rules, each tagged with its scope. An invalid file
   contributes a single catch-all deny instead (see `invalid-file-rule`)."
  [cwd]
  (vec (mapcat (fn [[file scope]]
                 (let [{:keys [rules error]} (load-rules-file file)]
                   (map #(assoc % :scope scope)
                        (if error [(invalid-file-rule file error)] rules))))
               (config-files cwd))))

(defn enabled-extensions
  "The user-extension file names the GLOBAL rules file enables under
   `:extensions` — the only place that can: agents can never write that file,
   so dropping a file into the extensions dir isn't enough to get it loaded.
   A repo rules file's `:extensions` is ignored. #{} when the file is missing,
   invalid, or doesn't set the key."
  []
  (set (:extensions (load-rules-file (global-file)))))

(defn default-rules
  "The lowest-precedence default tier: the first config file (repo, then
   global) that sets `:defaults`, expanded; else the built-in defaults. An
   invalid file is skipped here — its fail-closed deny already sits above."
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
  "The full ruleset in precedence order for `cwd`/`room-id`, excluding the
   imperative hard-block (that runs first, separately): the hardened tier
   (prepended, always wins) then config (repo, global) then runtime (server,
   session) then the default tier last, so any user rule overrides a default
   but nothing overrides the hardened tier."
  [state room-id cwd]
  (vec (concat (hardened-rules)
               (config-rules cwd)
               (runtime-rules state room-id)
               (default-rules cwd))))

;; ── Config file writing (repo / global scopes) ───────────────────────────────

(defn- write-rules-file!
  "Persist raw rules-file `data` ({:type :version :rules :defaults :extensions})
   to `file` as pretty EDN (one rule per line), creating parent dirs. Regex
   literals round-trip via pr-str/read-string."
  [file {:keys [version rules] :as data}]
  (fs/mkdirSync (path/dirname file) #js {:recursive true})
  (fs/writeFileSync
   file
   (str "{:type " (pr-str rules-file-type) "\n"
        " :version " (pr-str version) "\n"
        (when (contains? data :defaults)
          (str " :defaults " (pr-str (:defaults data)) "\n"))
        (when (contains? data :extensions)
          (str " :extensions " (pr-str (:extensions data)) "\n"))
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
  "Prepend `rule` (with any :scope stripped) to the config file for `scope`
   (:repo | :global), so the newest rule wins among file rules; a missing file
   is created at the current `:type` + `:version`. The file's `:defaults` are kept as
   written (aliases, unexpanded). Returns `{:file path}` on success,
   `{:error msg}` when the existing file is invalid (left untouched), nil when
   the scope has no file. Clears the mtime cache so the next check reloads."
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
