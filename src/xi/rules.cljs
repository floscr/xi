(ns xi.rules
  "Pure rule matching for the rules engine.

   A rule is data:

     {:match  {:tool #{:write :edit}   ; keyword or set of tool kinds
               :tool-name \"spawn_subagent\" ; raw tool name (glob/exact, regex, set)
               :extension \"notes\"        ; user extension behind an xi.api.* call (true = any)
               :extension-data :own      ; path inside that extension's data dir (opt-in)
               :host \"api.example.com\"   ; :net request host, or every host a read-only
                                         ; :sh curl requests (glob/exact, regex, set)
               :read-only true           ; a :sh call whose argv parses as read-only for
                                         ; its program (xi.rules.readonly; false = doesn't)
               :path #\"\\.sh$\"          ; regex OR glob string on the target path
               :command #\"\\brm\\b\"       ; regex OR substring on the bash command
               :repo \"config/dotfiles\"  ; substring of the effective repo root
               :dir  \"~/code/projects\"   ; prefix of the effective cwd (store expands ~)
               :mcp-server \"context7\"   ; MCP server id (glob/exact)
               :mcp-tool   \"*\"           ; MCP tool name (glob/exact)
               :when {:mode :plan}       ; submap match against room ext state
               :user \"alice\"             ; user the call acts for: id (glob/exact, regex, set)
                                         ; or {:meta {…}} submap of their config.edn profile
               :node {:type \"...\" :name #\"...\" :contains #\"...\"} ; tree-sitter (opt-in)
               :within :repo             ; every :sh operand inside the repo (opt-in)
               :tracked :git             ; every :sh operand git-tracked in the repo (opt-in)
               :chained true             ; a :bash command that composes shell commands (opt-in)
               :bb-trusted false         ; a :bb call whose bb.edn is (not) in the trust store (opt-in)
               :mcp-trusted false        ; an :mcp call whose server is (not) trusted (opt-in)
               :xi-rules-file true       ; changes an xi rules.edn (opt-in)
               :xi-config-file true      ; changes xi's config.edn (opt-in)
               :installed false}         ; a :sh program (not) found on PATH (opt-in)
      :action {:type :allow|:deny|:nudge|:ask|:hint
               :message \"...\"
               :options [:yes :no :always]}
      :scope  <keyword>}                 ; provenance, set by the store

   `:on-block` / `:do` are accepted as aliases for `:match` / `:action`.

   A `:hint` rule never decides: `first-match` collects the messages of the
   hint rules that match above the first deciding rule and attaches them to
   it as `:hints`, so a specific steering text (\"use bb for a static server\")
   composes with a generic deny/ask instead of competing with it. Messages
   may carry the placeholders `{cli}` and `{command}` (see `decision-message`).

   Matching is pure over a *decision request* the store builds from a tool
   call: {:tool :tool-name :path :command :repo :effective-cwd :mcp-server
          :mcp-tool :state :nodes :user :user-record}. This namespace does no I/O."
  (:require [clojure.string :as str]
            [xi.rules.curl :as curl]
            [xi.rules.readonly :as readonly]))

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

(defn- match-host
  "Host spec (string glob/exact, regex, set) against an extension's `:net`
   host, or every host a `:sh` curl call requests (xi.rules.curl); a curl that
   isn't a plain read-only request has no hosts and never matches."
  [spec req]
  (cond
    (nil? spec)  true
    (:host req)  (match-tool-name spec (:host req))
    :else        (boolean (some->> (curl/request-hosts req)
                                   (every? #(match-tool-name spec %))))))

(defn- match-read-only
  "`:read-only true|false`: whether a `:sh` call's argv parses as read-only for
   its program (xi.rules.readonly). An unknown program, shell syntax or no argv
   matches neither value."
  [spec req]
  (or (nil? spec)
      (= (boolean spec) (readonly/request-read-only? req))))

(defn- match-extension
  "Extension spec for xi.api.* requests: `true` → any extension, else like
   `:tool-name`. Tool calls carry no :extension."
  [spec ext]
  (cond
    (nil? spec)  true
    (nil? ext)   false
    (true? spec) true
    :else        (match-tool-name spec ext)))

(defn- match-extension-data
  "`:extension-data :own` matches when the target path resolves inside the
   requesting extension's data dir (`:own-data?`, computed only when such a
   rule is in play)."
  [spec req]
  (or (nil? spec)
      (case spec
        :own (boolean (:own-data? req))
        false)))

(defn- match-when
  "Submap match against room ext `state`; map values recurse, so a rule can
   target one ext's flag without pinning its whole state."
  [spec state]
  (or (nil? spec)
      (and (map? state)
           (every? (fn [[k v]]
                     (let [sv (get state k)]
                       (if (map? v)
                         (match-when v sv)
                         (= v sv))))
                   spec))))

(def config-invalid
  "The `:user-record` of a request when xi's config.edn is invalid: who the
   user is is known, what config says about them is not."
  ::config-invalid)

(defn- match-user-value
  "One value of a `:user` map spec against the user's `v`: a map recurses, a
   set is one-of, else equal, or contained when the user's value is a
   collection."
  [spec v]
  (cond
    (map? spec) (and (map? v)
                     (every? (fn [[k s]] (match-user-value s (get v k))) spec))
    (set? spec) (if (coll? v)
                  (boolean (some spec v))
                  (contains? spec v))
    (and (coll? v) (not (map? v))) (boolean (some #(= spec %) v))
    :else (= spec v)))

(defn- match-user
  "User spec against the user the call acts for: string / set / regex → the id;
   a map → submap match against `:user-record` (config.edn `:users`). With
   config.edn invalid a map spec fails closed."
  [spec req action-type]
  (cond
    (nil? spec)  true
    (map? spec)  (let [rec (:user-record req)]
                   (if (= config-invalid rec)
                     (not= :allow action-type)
                     (match-user-value spec rec)))
    :else        (match-tool-name spec (:user req))))

(defn- match-node
  "Tree-sitter node match against `nodes` ({:type :name :text}, computed only
   when a `:node` rule is in play)."
  [spec nodes]
  (or (nil? spec)
      (boolean (some (fn [n]
                       (and (match-name    (:type spec)     (:type n))
                            (match-path    (:name spec)     (:name n))
                            (match-command (:contains spec) (:text n))))
                     nodes))))

(defn- match-outside
  "`:outside :cwd` matches when the target resolves outside the effective cwd
   and tmp (`:outside-cwd?`, computed only when such a rule is in play)."
  [spec req]
  (or (nil? spec)
      (case spec
        :cwd (boolean (:outside-cwd? req))
        false)))

(defn- match-credential
  "`:credential :read` matches when the target resolves inside a hidden
   credential dir (`:credential-path?`, computed only when such a rule is in
   play)."
  [spec req]
  (or (nil? spec)
      (case spec
        :read (boolean (:credential-path? req))
        false)))

(defn- match-within
  "`:within :repo` matches when every operand of a literal `:sh` command
   resolves inside the effective repo or tmp (`:operands-within-repo?`,
   computed only when such a rule is in play)."
  [spec req]
  (or (nil? spec)
      (case spec
        :repo (boolean (:operands-within-repo? req))
        false)))

(defn- match-tracked
  "`:tracked :git` matches when every operand of a literal `:sh` command is
   git-tracked content (recoverable). `:operands-tracked?` is a delay forced
   here, so git is spawned only once the rule's other fields matched."
  [spec req]
  (or (nil? spec)
      (case spec
        :git (let [v (:operands-tracked? req)]
               (boolean (if (delay? v) @v v)))
        false)))

(defn- match-xi-rules-file
  "`:xi-rules-file true` matches when the call would change an xi rules file
   (`:xi-rules-file?`, computed only when such a rule is in play)."
  [spec req]
  (or (nil? spec)
      (and (true? spec) (boolean (:xi-rules-file? req)))))

(defn- match-xi-config-file
  "`:xi-config-file true` matches when the call would change xi's user config
   file (`:xi-config-file?`, computed only when such a rule is in play)."
  [spec req]
  (or (nil? spec)
      (and (true? spec) (boolean (:xi-config-file? req)))))

(defn- match-installed
  "`:installed true|false` matches a `:sh` call by whether its program resolves
   on PATH (`:installed?`, computed only when such a rule is in play)."
  [spec req]
  (or (nil? spec)
      (and (boolean? spec)
           (some? (:installed? req))
           (= spec (:installed? req)))))

(defn- match-chained
  "`:chained true` matches a `:bash` command using shell composition
   (`:chained?`, computed only when such a rule is in play)."
  [spec req]
  (or (nil? spec)
      (and (true? spec) (boolean (:chained? req)))))

(defn- match-bb-trusted
  "`:bb-trusted true|false` matches a `:bb` call by whether the project's
   bb.edn is in the trust store (xi.bb-trust)."
  [spec req]
  (or (nil? spec)
      (and (boolean? spec)
           (some? (:bb-trusted? req))
           (= spec (:bb-trusted? req)))))

(defn- match-mcp-trusted
  "`:mcp-trusted true|false` matches an `:mcp` call by whether its server is
   trusted (xi.mcp.trust)."
  [spec req]
  (or (nil? spec)
      (and (boolean? spec)
           (some? (:mcp-trusted? req))
           (= spec (:mcp-trusted? req)))))

(defn- match-cli
  "CLI spec for `:tool :sh` shell-outs against `(:cli req)`: string exact, set
   membership, regex re-find. Only clj's sh requests carry `:cli`."
  [spec cli]
  (cond
    (nil? spec)    true
    (nil? cli)     false
    (set? spec)    (contains? spec cli)
    (regexp? spec) (boolean (re-find spec (str cli)))
    (string? spec) (= spec (str cli))
    :else          false))

(defn- match-path*
  "Path spec against the raw `:path`, the resolved `:resolved-path` or the
   home-collapsed `:resolved-home-path`, so relative, `~` and absolute forms of
   one file all match, and denies can't be dodged with a relative path."
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
         (match-host       (:host m)       req)
         (match-cli        (:cli m)        (:cli req))
         (match-read-only  (:read-only m)  req)
         (match-path*      (:path m)       req)
         (match-command    (:command m)    (:command req))
         (match-substring  (:repo m)       (:repo req))
         (match-prefix     (:dir m)        (:effective-cwd req))
         (match-name       (:mcp-server m) (:mcp-server req))
         (match-name       (:mcp-tool m)   (:mcp-tool req))
         (match-when       (:when m)       (:state req))
         (match-user       (:user m)       req (get-in rule [:action :type]))
         (match-node       (:node m)       (:nodes req))
         (match-outside    (:outside m)    req)
         (match-credential (:credential m) req)
         (match-within     (:within m)     req)
         (match-tracked    (:tracked m)    req)
         (match-chained    (:chained m)    req)
         (match-bb-trusted (:bb-trusted m) req)
         (match-mcp-trusted (:mcp-trusted m) req)
         (match-xi-rules-file (:xi-rules-file m) req)
         (match-xi-config-file (:xi-config-file m) req)
         (match-installed  (:installed m)  req))))

(defn hint?
  "True when (canonical) `rule` is a `:hint` — steering text that never decides."
  [rule]
  (= :hint (get-in rule [:action :type])))

(defn render-message
  "Fill the `{cli}` and `{command}` placeholders of a rule message from the
   request; unfillable ones stay as written."
  [msg req]
  (when (some? msg)
    (-> (str msg)
        (cond-> (:cli req)     (str/replace "{cli}" (str (:cli req)))
                (:command req) (str/replace "{command}" (str (:command req)))))))

(defn first-match
  "First deciding rule in `rules` (precedence order) matching `req`,
   canonicalized, with the messages of matching `:hint` rules above it attached
   as `:hints`. nil when none match."
  [rules req]
  (loop [rules rules hints []]
    (when-let [r (first rules)]
      (let [r (canonical r)]
        (cond
          (not (matches? r req)) (recur (rest rules) hints)
          (hint? r)              (recur (rest rules)
                                        (conj hints (render-message
                                                     (get-in r [:action :message]) req)))
          :else (cond-> r
                  (some? (get-in r [:action :message]))
                  (update-in [:action :message] render-message req)
                  (seq hints) (assoc :hints hints)))))))

(defn with-hints
  "`msg` with `hints` appended as paragraphs; a nil `msg` yields just the hints."
  [msg hints]
  (let [hints (remove str/blank? hints)]
    (if (seq hints)
      (str/join "\n\n" (cond->> hints (some? msg) (cons msg)))
      msg)))

(defn decision-message
  "The text a decision shows for `rule`: its action's `:message`, else
   `fallback`, with the collected `:hints` appended."
  [rule fallback]
  (with-hints (or (get-in rule [:action :message]) fallback) (:hints rule)))

(defn needs-nodes?
  "True when any rule carries a `:node` matcher, so the store should parse the
   target with tree-sitter to populate `:nodes`."
  [rules]
  (boolean (some #(some-> (canonical %) :match :node) rules)))

(defn needs-resolved-path?
  "True when any rule carries a `:path` matcher, so the store resolves the target path."
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
  "True when any rule carries a `:tracked` matcher."
  [rules]
  (boolean (some #(some-> (canonical %) :match :tracked) rules)))

(defn arg-scoped?
  "True when `rule` constrains a `:sh` command's arguments (`:command`,
   `:within`, `:tracked`, `:host` or `:read-only`), so its allow covers only
   the exact command."
  [rule]
  (boolean (some #(some? (get-in rule [:match %]))
                 [:command :within :tracked :host :read-only])))

(defn needs-extension-data?
  "True when any rule carries an `:extension-data` matcher."
  [rules]
  (boolean (some #(some-> (canonical %) :match :extension-data) rules)))

(defn needs-chained?
  "True when any rule carries a `:chained` matcher, so the store should analyse
   a `:bash` command and populate `:chained?` on the request."
  [rules]
  (boolean (some #(some-> (canonical %) :match :chained) rules)))

(defn needs-mcp-trusted?
  "True when any rule carries an `:mcp-trusted` matcher."
  [rules]
  (boolean (some #(some? (some-> (canonical %) :match :mcp-trusted)) rules)))

(defn needs-bb-trusted?
  "True when any rule carries a `:bb-trusted` matcher."
  [rules]
  (boolean (some #(some? (some-> (canonical %) :match :bb-trusted)) rules)))

(defn needs-credential?
  "True when any rule carries a `:credential` matcher."
  [rules]
  (boolean (some #(some-> (canonical %) :match :credential) rules)))

(defn needs-xi-rules-file?
  "True when any rule carries an `:xi-rules-file` matcher."
  [rules]
  (boolean (some #(some-> (canonical %) :match :xi-rules-file) rules)))

(defn needs-xi-config-file?
  "True when any rule carries an `:xi-config-file` matcher."
  [rules]
  (boolean (some #(some-> (canonical %) :match :xi-config-file) rules)))

(defn needs-user-record?
  "True when any rule carries a map `:user` matcher, so the store should read
   the user's profile from config.edn and populate `:user-record`."
  [rules]
  (boolean (some #(map? (some-> (canonical %) :match :user)) rules)))

(defn needs-installed?
  "True when any rule carries an `:installed` matcher."
  [rules]
  (boolean (some #(some? (some-> (canonical %) :match :installed)) rules)))
