(ns xi.rules
  "Pure rule matching for the rules engine.

   A rule is data:

     {:match  {:tool #{:write :edit}   ; keyword or set of tool kinds
               :path #\"\\.sh$\"          ; regex OR glob string on the target path
               :command #\"\\brm\\b\"       ; regex OR substring on the bash command
               :repo \"config/dotfiles\"  ; substring of the effective repo root
               :dir  \"~/code/projects\"   ; prefix of the effective cwd (store expands ~)
               :mcp-server \"context7\"   ; MCP server id (glob/exact)
               :mcp-tool   \"*\"           ; MCP tool name (glob/exact)
               :when {:mode :plan}       ; submap match against room ext state
               :node {:type \"...\" :name #\"...\" :contains #\"...\"}} ; tree-sitter (opt-in)
      :action {:type :allow|:deny|:nudge|:ask
               :message \"...\"
               :options [:yes :no :always]}
      :scope  <keyword>}                 ; provenance, set by the store

   `:on-block` / `:do` are accepted as aliases for `:match` / `:action`.

   Matching is pure over a *decision request* the store builds from a tool
   call: {:tool :path :command :repo :effective-cwd :mcp-server :mcp-tool
          :state :nodes}. This namespace does no I/O."
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

(defn matches?
  "True when canonical `rule`'s `:match` matches decision request `req`.
   All present match keys are ANDed; absent keys are unconstrained."
  [rule req]
  (let [m (:match rule)]
    (and (match-tool       (:tool m)       (:tool req))
         (match-cli        (:cli m)        (:cli req))
         (match-path       (:path m)       (:path req))
         (match-command    (:command m)    (:command req))
         (match-substring  (:repo m)       (:repo req))
         (match-prefix     (:dir m)        (:effective-cwd req))
         (match-name       (:mcp-server m) (:mcp-server req))
         (match-name       (:mcp-tool m)   (:mcp-tool req))
         (match-when       (:when m)       (:state req))
         (match-node       (:node m)       (:nodes req))
         (match-outside    (:outside m)    req)
         (match-credential (:credential m) req))))

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

(defn needs-outside?
  "True when any rule carries an `:outside` matcher, so the store should resolve
   the target path and populate `:outside-cwd?` on the request."
  [rules]
  (boolean (some #(some-> (canonical %) :match :outside) rules)))

(defn needs-credential?
  "True when any rule carries a `:credential` matcher, so the store should
   resolve the target path and populate `:credential-path?` on the request."
  [rules]
  (boolean (some #(some-> (canonical %) :match :credential) rules)))
