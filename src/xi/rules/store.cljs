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

   Config over runtime, so a configured rule overrides a careless 'always
   allow'. Config files are cached by mtime and reloaded on change (or via
   `/rules reload`)."
  (:require [clojure.string :as str]
            [cljs.tools.reader :as tr]
            [xi.rules :as rules]
            [xi.rules.defaults :as defaults]
            [xi.rules.nodes :as nodes]
            [xi.sandbox.core :as sandbox]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def ext-id :rules)

;; ── Paths ────────────────────────────────────────────────────────────────────

(defn config-dir [] (path/join (os/homedir) ".config" "xi"))
(defn global-file [] (path/join (config-dir) "rules.edn"))
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

(defn- read-rules-file
  "Read a rules EDN file into a vector of rule maps ([] when absent/unreadable),
   cached by mtime. A file may hold a vector of rules or a map {:rules [...]}."
  [file]
  (try
    (if-not (and file (fs/existsSync file))
      []
      (let [mtime (.-mtimeMs (fs/statSync file))
            cached (get @cache file)]
        (if (= mtime (:mtime cached))
          (:rules cached)
          (let [data (read-rule-edn (str (fs/readFileSync file "utf8")))
                rules (vec (if (map? data) (:rules data) data))]
            (swap! cache assoc file {:mtime mtime :rules rules})
            rules))))
    (catch :default e
      (js/console.error "[rules] failed to read" file (.-message e))
      [])))

;; ── Hard-coded immutable rules ──────────────────────────────────────────────

(def ^:private rules-file-re
  "Matches a resolved path that IS a rules file."
  #"(?:\.config/xi/rules\.edn|[/\\]\.xi/rules\.edn)$")

(def ^:private rules-file-loose-re
  "Matches a rules-file path mentioned anywhere in a command/code string."
  #"(?:\.config/xi/rules\.edn|\.xi/rules\.edn)")

(def ^:private write-token-re
  "Write indicators in a shell command or clj eval that would mutate a file."
  #"(?:>>?|\btee\b|\bsed\s+-i|\bcp\b|\bmv\b|\bdd\b|\bspit\b|writeFileSync|appendFileSync|\brm\b)")

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
      (if (str/starts-with? n "mcp__") :mcp :other))))

(defn hard-block
  "Immutable, non-overridable check: deny any tool call that would write a
   rules file. Returns an intercepted deny result, or nil to continue. This is
   deliberately imperative (not a data rule) — it is security-critical and must
   never be shadowed or disabled."
  [tool-call {:keys [cwd]}]
  (let [{:keys [name arguments]} tool-call
        kind (tool-kind name)]
    (case kind
      (:write :edit)
      (let [p (or (:path arguments) (:file_path arguments))]
        (when (and p (re-find rules-file-re (expand-path cwd (str p))))
          (deny-result hard-block-msg)))

      :bash
      (let [c (str (:command arguments))]
        (when (and (re-find rules-file-loose-re c) (re-find write-token-re c))
          (deny-result hard-block-msg)))

      :clj
      (let [c (str (:code arguments))]
        (when (and (re-find rules-file-loose-re c) (re-find write-token-re c))
          (deny-result hard-block-msg)))

      nil)))

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
        command (:command arguments)
        code    (:code arguments)
        eff-cwd (or cwd (.cwd js/process))
        repo    (git-root (if p (path/dirname (expand-path eff-cwd (str p))) eff-cwd))
        state   (when (and get-state room-id)
                  (get-in (get-state) [:rooms room-id :ext]))]
    (cond-> {:tool kind :effective-cwd eff-cwd :repo repo :state state}
      p         (assoc :path p)
      command   (assoc :command command)
      code      (assoc :command code)
      (seq arguments) (assoc :arguments arguments)
      (= kind :mcp) (merge (parse-mcp name)))))

(defn outside-cwd?
  "True when target `path` resolves outside both the effective `cwd` and the OS
   tmp dir — i.e. a write/edit that escapes the working tree. Tmp is always
   allowed. Symlinks are canonicalized (sandbox/real-resolve) so the check can't
   be laundered through a link created inside cwd. This is I/O, computed only
   when an `:outside` rule is in play."
  [cwd path]
  (boolean
   (when (and cwd path (not (str/blank? (str path))))
     (let [resolved (sandbox/real-resolve cwd (str path))
           real-cwd (sandbox/real-resolve cwd ".")
           tmp      (sandbox/real-resolve cwd (os/tmpdir))]
       (not (or (sandbox/path-within? resolved real-cwd)
                (sandbox/path-within? resolved tmp)))))))

(defn credential-path?
  "True when target `path` resolves inside one of the hidden credential dirs
   (.ssh, .gnupg, .password-store, …). Symlinks are canonicalized
   (sandbox/real-resolve) so the check can't be laundered through a link. I/O,
   computed only when a `:credential` rule is in play."
  [cwd path]
  (boolean
   (when (and cwd path (not (str/blank? (str path))))
     (let [resolved (sandbox/real-resolve cwd (str path))]
       (some #(sandbox/path-within? resolved %) (sandbox/hidden-paths))))))

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
   `:credential` rules, and `:nodes` (tree-sitter) for `:node` rules."
  [req ruleset]
  (let [resolved (when (and (:path req) (rules/needs-resolved-path? ruleset))
                   (sandbox/real-resolve (:effective-cwd req) (str (:path req))))]
    (cond-> req
      resolved (assoc :resolved-path resolved)
      (home-collapse resolved) (assoc :resolved-home-path (home-collapse resolved))
      (and (:path req) (rules/needs-outside? ruleset))
      (assoc :outside-cwd? (outside-cwd? (:effective-cwd req) (:path req)))
      (and (:path req) (rules/needs-credential? ruleset))
      (assoc :credential-path? (credential-path? (:effective-cwd req) (:path req)))
      (and (:path req) (rules/needs-nodes? ruleset))
      (assoc :nodes (nodes/nodes-for req)))))

;; ── Ordered ruleset ─────────────────────────────────────────────────────────

(defn config-rules
  "Repo rules then global rules, each tagged with its scope."
  [cwd]
  (let [repo (git-root (or cwd (.cwd js/process)))]
    (vec (concat (map #(assoc % :scope :repo)   (read-rules-file (repo-file repo)))
                 (map #(assoc % :scope :global) (read-rules-file (global-file)))))))

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
   session) then the built-in defaults last, so any user rule overrides a
   default but nothing overrides the hardened tier."
  [state room-id cwd]
  (vec (concat (hardened-rules)
               (config-rules cwd)
               (runtime-rules state room-id)
               defaults/default-rules)))

;; ── Config file writing (repo / global scopes) ───────────────────────────────

(defn- write-rules-file!
  "Persist a rule vector to `file` as pretty EDN (one rule per line), creating
   parent dirs. Regex literals round-trip via pr-str/read-string."
  [file rules]
  (fs/mkdirSync (path/dirname file) #js {:recursive true})
  (fs/writeFileSync
   file
   (str "[\n" (str/join "\n" (map pr-str rules)) "\n]\n")
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
   (:repo | :global), so the newest rule wins among file rules. Returns the
   file path on success, nil when the scope has no file. Clears the mtime
   cache so the next check reloads."
  [scope cwd rule]
  (when-let [file (scope-file scope cwd)]
    (let [existing (read-rules-file file)
          next-rules (vec (cons (dissoc rule :scope) existing))]
      (write-rules-file! file next-rules)
      (clear-cache!)
      file)))
