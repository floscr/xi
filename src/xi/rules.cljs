(ns xi.rules
  "Pure rule matching for the rules engine.

   A rule is data:

     {:match  {:tool #{:write :edit}   ; keyword or set of tool kinds
               :tool-name \"spawn_subagent\" ; raw tool name (glob/exact, regex, set)
               :extension \"notes\"        ; user extension behind an xi.api.* call (true = any)
               :extension-data :own      ; path inside that extension's data dir (opt-in)
               :host \"api.example.com\"   ; :net / :browser request host (glob/exact, regex)
               :path #\"\\.sh$\"          ; regex OR glob string on the target path
               :command #\"\\brm\\b\"       ; regex OR substring on the bash command
               :repo \"config/dotfiles\"  ; substring of the effective repo root
               :dir  \"~/code/projects\"   ; prefix of the effective cwd (store expands ~)
               :mcp-server \"context7\"   ; MCP server id (glob/exact)
               :mcp-tool   \"*\"           ; MCP tool name (glob/exact)
               :when {:mode :plan}       ; submap match against room ext state
               :node {:type \"...\" :name #\"...\" :contains #\"...\"} ; tree-sitter (opt-in)
               :within :repo             ; every :sh operand inside the repo (opt-in)
               :tracked :git             ; every :sh operand git-tracked in the repo (opt-in)
               :chained true             ; a :bash command that composes shell commands (opt-in)
               :bb-trusted false         ; a :bb call whose bb.edn is (not) in the trust store (opt-in)
               :mcp-trusted false        ; an :mcp call whose server is (not) trusted (opt-in)
               :xi-rules-file true}      ; changes an xi rules.edn (opt-in)
      :action {:type :allow|:deny|:nudge|:ask
               :message \"...\"
               :options [:yes :no :always]}
      :scope  <keyword>}                 ; provenance, set by the store

   `:on-block` / `:do` are accepted as aliases for `:match` / `:action`.

   Matching is pure over a *decision request* the store builds from a tool
   call: {:tool :tool-name :path :command :repo :effective-cwd :mcp-server
          :mcp-tool :state :nodes}. This namespace does no I/O."
  (:require [clojure.string :as str]))

(defn canonical
  "Normalize a rule's `:on-block`/`:do` aliases to `:match`/`:action`."
  [rule]
  (-> rule
      (assoc :match  (or (:match rule) (:on-block rule))
             :action (or (:action rule) (:do rule)))
      (dissoc :on-block :do)))

;; ── Value matchers ─────────────────────────────────────────────────────────

(defn glob->re
  "Translate a simple glob to a regex source string. `**` → `.*`, `*` →
   `[^/]*`, `?` → `[^/]`; every other regex metacharacter is escaped."
  [glob]
  (-> (str glob)
      (str/replace #"[.*+?^${}()|\[\]\\]" "\\$&")
      (str/replace "\\*\\*" ".*")
      (str/replace "\\*" "[^/]*")
      (str/replace "\\?" "[^/]")))

(defn- match-glob
  "String spec matches `s` as a glob (full match)."
  [spec s]
  (boolean (re-matches (re-pattern (glob->re spec)) (str s))))

(defn- match-path
  "Path spec: regex → re-find (partial), glob string → full match."
  [spec s]
  (cond
    (nil? spec)     true
    (nil? s)        false
    (regexp? spec)  (boolean (re-find spec (str s)))
    (string? spec)  (match-glob spec s)
    :else           false))

(defn- match-command
  "Command spec: regex → re-find, string → substring."
  [spec s]
  (cond
    (nil? spec)    true
    (nil? s)       false
    (regexp? spec) (boolean (re-find spec (str s)))
    (string? spec) (str/includes? (str s) spec)
    :else          false))

(defn- match-substring
  "Repo spec: regex → re-find, string → substring of the repo root path."
  [spec s]
  (cond
    (nil? spec)    true
    (nil? s)       false
    (regexp? spec) (boolean (re-find spec (str s)))
    (string? spec) (str/includes? (str s) spec)
    :else          false))

(defn- match-prefix
  "Dir spec: the effective cwd must start with the (already expanded) prefix.
   Regex specs fall back to re-find."
  [spec s]
  (cond
    (nil? spec)    true
    (nil? s)       false
    (regexp? spec) (boolean (re-find spec (str s)))
    (string? spec) (str/starts-with? (str s) spec)
    :else          false))

(defn- match-name
  "MCP server/tool spec: regex → re-find, glob/exact string → full match."
  [spec s]
  (cond
    (nil? spec)    true
    (nil? s)       false
    (regexp? spec) (boolean (re-find spec (str s)))
    (string? spec) (match-glob spec s)
    :else          false))

(defn- as-set [x]
  (cond (set? x) x (coll? x) (set x) :else #{x}))

(defn- match-tool [spec tool]
  (or (nil? spec) (contains? (as-set spec) tool)))

(defn- match-tool-name
  "Raw tool-name spec (e.g. \"spawn_subagent\"): set → membership, regex →
   re-find, string → glob/exact full match. Targets a specific tool when its
   kind is only `:other` (extension tools)."
  [spec tool-name]
  (if (set? spec)
    (contains? spec tool-name)
    (match-name spec tool-name)))

(defn- match-extension
  "Extension spec for requests a user extension makes through xi.api.*:
   `true` → any extension, else like `:tool-name` (string glob/exact, regex,
   set). Tool calls carry no :extension, so an `:extension` rule never
   matches them."
  [spec ext]
  (cond
    (nil? spec)  true
    (nil? ext)   false
    (true? spec) true
    :else        (match-tool-name spec ext)))

(defn- match-extension-data
  "Own-data-dir match (opt-in). `:extension-data :own` matches when the target
   path resolves inside the requesting extension's data dir — the store
   computes `:own-data?` only when such a rule is in play."
  [spec req]
  (or (nil? spec)
      (case spec
        :own (boolean (:own-data? req))
        false)))

(defn- match-when
  "Submap match against room ext `state`: every k/v in `spec` must match the
   value in `state`. A map value matches recursively (nested submap), so a rule
   can target one ext's flag (e.g. `{:plan-mode {:enabled? true}}`) without
   pinning that ext's whole state map."
  [spec state]
  (or (nil? spec)
      (and (map? state)
           (every? (fn [[k v]]
                     (let [sv (get state k)]
                       (if (map? v)
                         (match-when v sv)
                         (= v sv))))
                   spec))))

(defn- match-node
  "Tree-sitter node match (opt-in). `nodes` is a seq of {:type :name :text}
   the store computes only when a `:node` rule is in play; nil `nodes` never
   matches a `:node` rule."
  [spec nodes]
  (or (nil? spec)
      (boolean (some (fn [n]
                       (and (match-name    (:type spec)     (:type n))
                            (match-path    (:name spec)     (:name n))
                            (match-command (:contains spec) (:text n))))
                     nodes))))

(defn- match-outside
  "Location match (opt-in). `:outside :cwd` matches when the target path
   resolves outside the effective cwd (and tmp) — the store computes and
   populates `:outside-cwd?` on the request only when an `:outside` rule is in
   play (nil never matches)."
  [spec req]
  (or (nil? spec)
      (case spec
        :cwd (boolean (:outside-cwd? req))
        false)))

(defn- match-credential
  "Credential-path match (opt-in). `:credential :read` matches when the target
   path resolves inside a hidden credential dir (.ssh, .gnupg, …) — the store
   computes and populates `:credential-path?` on the request only when a
   `:credential` rule is in play (nil never matches)."
  [spec req]
  (or (nil? spec)
      (case spec
        :read (boolean (:credential-path? req))
        false)))

(defn- match-within
  "Operand-location match (opt-in). `:within :repo` matches when every operand
   of a literal `:sh` command resolves inside the effective git repo (not its
   `.git/`) or tmp — the store computes and populates `:operands-within-repo?`
   on the request only when a `:within` rule is in play (nil never matches)."
  [spec req]
  (or (nil? spec)
      (case spec
        :repo (boolean (:operands-within-repo? req))
        false)))

(defn- match-tracked
  "Git-tracked match (opt-in). `:tracked :git` matches when every operand of a
   literal `:sh` command is git-tracked content inside the effective repo — a
   file in the index, or a directory whose files are all in the index — so
   deleting or moving it is recoverable from git. The store populates
   `:operands-tracked?` on the request only when a `:tracked` rule is in play
   (nil never matches) — as a delay, since the check spawns git: it is forced
   here, i.e. only once a `:tracked` rule's other fields (tool, cli, …) have
   matched, never for unrelated commands."
  [spec req]
  (or (nil? spec)
      (case spec
        :git (let [v (:operands-tracked? req)]
               (boolean (if (delay? v) @v v)))
        false)))

(defn- match-xi-rules-file
  "xi-rules-file match (opt-in). `:xi-rules-file true` matches when the call
   would change an xi rules file (a `rules.edn` carrying `:version`) — the store
   computes and populates `:xi-rules-file?` on the request only when an
   `:xi-rules-file` rule is in play (nil never matches)."
  [spec req]
  (or (nil? spec)
      (and (true? spec) (boolean (:xi-rules-file? req)))))

(defn- match-chained
  "Shell-composition match (opt-in). `:chained true` matches a `:bash` command
   that uses pipes, `;`/`&&`/`&`, command substitution, several lines or a
   leading VAR= binding — the store computes `:chained?` only when such a rule
   is in play (nil never matches)."
  [spec req]
  (or (nil? spec)
      (and (true? spec) (boolean (:chained? req)))))

(defn- match-bb-trusted
  "bb.edn trust match (opt-in). `:bb-trusted true|false` matches a `:bb` call
   by whether the project's bb.edn sha is in the trust store (xi.bb-trust) —
   the store computes `:bb-trusted?` only when such a rule is in play (nil,
   i.e. not a bb call, never matches)."
  [spec req]
  (or (nil? spec)
      (and (boolean? spec)
           (some? (:bb-trusted? req))
           (= spec (:bb-trusted? req)))))

(defn- match-mcp-trusted
  "MCP server trust match (opt-in). `:mcp-trusted true|false` matches an
   `:mcp` call by whether its server is trusted as it is now (xi.mcp.trust) —
   the store computes `:mcp-trusted?` only when such a rule is in play (nil,
   i.e. not an MCP call, never matches)."
  [spec req]
  (or (nil? spec)
      (and (boolean? spec)
           (some? (:mcp-trusted? req))
           (= spec (:mcp-trusted? req)))))

(defn- match-cli
  "CLI (binary) spec for `:tool :sh` shell-outs: string → exact binary match,
   set → membership, regex → re-find, against `(:cli req)` (the command's first
   token). nil → match. clj builds sh reqs carrying `:cli`; other tools never
   set it, so a `:cli` rule never matches a non-sh call."
  [spec cli]
  (cond
    (nil? spec)    true
    (nil? cli)     false
    (set? spec)    (contains? spec cli)
    (regexp? spec) (boolean (re-find spec (str cli)))
    (string? spec) (= spec (str cli))
    :else          false))

(defn- match-path*
  "Path spec matches the request's raw `:path`, its resolved absolute
   `:resolved-path`, OR the home-collapsed `:resolved-home-path` (the resolved
   path with a leading $HOME rewritten back to `~`). Absent spec is unconstrained
   (the nil-spec branch of `match-path` returns true). Raw matching is preserved
   unchanged; the resolved forms only ADD matches, so relative/`~`/absolute paths
   that name the same file all match one rule — a rule may be written with either
   an absolute (`/home/you/…`) or a `~/…` path — and deny rules can't be dodged
   with a relative path."
  [spec req]
  (or (match-path spec (:path req))
      (and (some? (:resolved-path req))
           (match-path spec (:resolved-path req)))
      (and (some? (:resolved-home-path req))
           (match-path spec (:resolved-home-path req)))))

(defn matches?
  "True when canonical `rule`'s `:match` matches decision request `req`.
   All present match keys are ANDed; absent keys are unconstrained."
  [rule req]
  (let [m (:match rule)]
    (and (match-tool       (:tool m)       (:tool req))
         (match-tool-name  (:tool-name m)  (:tool-name req))
         (match-extension  (:extension m)  (:extension req))
         (match-extension-data (:extension-data m) req)
         (match-tool-name  (:host m)       (:host req))
         (match-cli        (:cli m)        (:cli req))
         (match-path*      (:path m)       req)
         (match-command    (:command m)    (:command req))
         (match-substring  (:repo m)       (:repo req))
         (match-prefix     (:dir m)        (:effective-cwd req))
         (match-name       (:mcp-server m) (:mcp-server req))
         (match-name       (:mcp-tool m)   (:mcp-tool req))
         (match-when       (:when m)       (:state req))
         (match-node       (:node m)       (:nodes req))
         (match-outside    (:outside m)    req)
         (match-credential (:credential m) req)
         (match-within     (:within m)     req)
         (match-tracked    (:tracked m)    req)
         (match-chained    (:chained m)    req)
         (match-bb-trusted (:bb-trusted m) req)
         (match-mcp-trusted (:mcp-trusted m) req)
         (match-xi-rules-file (:xi-rules-file m) req))))

(defn first-match
  "First rule in `rules` (already in precedence order) whose match matches
   `req`, canonicalized; nil when none match."
  [rules req]
  (some (fn [r]
          (let [r (canonical r)]
            (when (matches? r req) r)))
        rules))

(defn needs-nodes?
  "True when any rule carries a `:node` matcher, so the store should parse the
   target with tree-sitter to populate `:nodes`."
  [rules]
  (boolean (some #(some-> (canonical %) :match :node) rules)))

(defn needs-resolved-path?
  "True when any rule carries a `:path` matcher, so the store should resolve the
   target path and populate `:resolved-path` on the request (lets `:path` rules
   match relative/`~` forms that name an absolute-rule's file)."
  [rules]
  (boolean (some #(some-> (canonical %) :match :path) rules)))

(defn needs-outside?
  "True when any rule carries an `:outside` matcher, so the store should resolve
   the target path and populate `:outside-cwd?` on the request."
  [rules]
  (boolean (some #(some-> (canonical %) :match :outside) rules)))

(defn needs-within?
  "True when any rule carries a `:within` matcher, so the store should check a
   `:sh` request's operands and populate `:operands-within-repo?`."
  [rules]
  (boolean (some #(some-> (canonical %) :match :within) rules)))

(defn needs-tracked?
  "True when any rule carries a `:tracked` matcher, so the store should check a
   `:sh` request's operands against the git index and populate
   `:operands-tracked?`."
  [rules]
  (boolean (some #(some-> (canonical %) :match :tracked) rules)))

(defn arg-scoped?
  "True when (canonical) `rule` constrains a `:sh` command's arguments — a
   `:command`, `:within` or `:tracked` matcher — so its allow covers only the
   exact command it matched, not the CLI at large."
  [rule]
  (boolean (some #(some? (get-in rule [:match %])) [:command :within :tracked])))

(defn needs-extension-data?
  "True when any rule carries an `:extension-data` matcher, so the store should
   resolve the target path and populate `:own-data?` on the request."
  [rules]
  (boolean (some #(some-> (canonical %) :match :extension-data) rules)))

(defn needs-chained?
  "True when any rule carries a `:chained` matcher, so the store should analyse
   a `:bash` command and populate `:chained?` on the request."
  [rules]
  (boolean (some #(some-> (canonical %) :match :chained) rules)))

(defn needs-mcp-trusted?
  "True when any rule carries an `:mcp-trusted` matcher (true or false), so
   the store should read the MCP trust store and populate `:mcp-trusted?`."
  [rules]
  (boolean (some #(some? (some-> (canonical %) :match :mcp-trusted)) rules)))

(defn needs-bb-trusted?
  "True when any rule carries a `:bb-trusted` matcher (true or false), so the
   store should read the bb.edn trust store and populate `:bb-trusted?`."
  [rules]
  (boolean (some #(some? (some-> (canonical %) :match :bb-trusted)) rules)))

(defn needs-credential?
  "True when any rule carries a `:credential` matcher, so the store should
   resolve the target path and populate `:credential-path?` on the request."
  [rules]
  (boolean (some #(some-> (canonical %) :match :credential) rules)))

(defn needs-xi-rules-file?
  "True when any rule carries an `:xi-rules-file` matcher, so the store should
   check whether the call changes an xi rules file and populate
   `:xi-rules-file?` on the request."
  [rules]
  (boolean (some #(some-> (canonical %) :match :xi-rules-file) rules)))
