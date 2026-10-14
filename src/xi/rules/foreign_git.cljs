(ns xi.rules.foreign-git
  "git in another repository than the chat's: a clj `(sh {:dir …} \"git\" …)`
   whose :dir lies in a different repo. git there runs what that repository
   names — hooks (post-index-change on an index refresh), core.fsmonitor,
   filter and diff drivers, diff.external, gpg programs (log.showSignature),
   merge drivers (--remerge-diff), a promisor remote's fetch (core.sshCommand,
   credential helpers) and its submodules' own config. The read-only
   subcommands in `hardened` still auto-run, `harden`ed so all of that is off,
   unless the repository's own config sets what no flag switches off
   (`unsafe-keys`). Pure; xi.ext.clj probes the config and runs the call."
  (:require [clojure.string :as str]
            [xi.rules.readonly :as readonly]))

(def ^:private hardening-globals
  ["--no-pager" "--no-optional-locks" "--no-lazy-fetch"
   "-c" "core.hooksPath=/dev/null" "-c" "core.fsmonitor=false"
   "-c" "gpg.program=/dev/null" "-c" "gpg.ssh.program=/dev/null"
   "-c" "gpg.x509.program=/dev/null"])

(def ^:private diff-hardening
  ;; diff options take no abbreviations, so the full names are the prefixes.
  {:flags   ["--no-ext-diff" "--no-textconv" "--ignore-submodules=all"]
   :undoing ["--textconv" "--ext-diff" "--submodule" "--ignore-submodules"]})

(def ^:private hardened
  "Subcommands that auto-run hardened. `:flags` go right after the
   subcommand — `diff` takes no option after a revision or path — so a later
   flag of the call could undo them: `:undoing` holds the flag-name prefixes
   (before `=`) that do, or that run a driver themselves. Subcommands parsed
   with abbreviations (`status`, `ls-files`, `cat-file`) list prefixes short
   enough to cover them (`--ignore-s=none`, `--te` for `--textconv`)."
  {"status"       {:flags ["--ignore-submodules=all"] :undoing ["--ignore-"]}
   "diff"         diff-hardening
   "log"          diff-hardening
   "show"         diff-hardening
   "ls-files"     {:undoing ["--rec"]}
   "cat-file"     {:undoing ["--t" "--fi"]}
   "ls-tree"      {}
   "rev-parse"    {}
   "rev-list"     {}
   "merge-base"   {}
   "show-ref"     {}
   "for-each-ref" {}})

(defn- unsafe-key?
  "Own-config keys `harden` leaves on: filter drivers (a modified file's clean
   filter runs on any work-tree comparison), core.worktree (moves the work
   tree read, e.g. to ~/.ssh) and, for log/show (--remerge-diff), merge
   drivers."
  [sub k]
  (or (re-matches #"^(?:filter\..+|core\.worktree)$" k)
      (and (contains? #{"log" "show"} sub)
           (re-matches #"^merge\..+\.driver$" k))))

(defn- split-subcommand
  "argv of a read-only git call → [globals subcommand args]. Its globals are
   all value-less (xi.rules.readonly), so the first operand is the subcommand."
  [argv]
  (let [[pre [sub & args]] (split-with #(str/starts-with? % "-") (rest argv))]
    [pre sub args]))

(defn hardened-subcommands []
  (sort (keys hardened)))

(defn hardenable?
  "True for a read-only (xi.rules.readonly) git argv whose subcommand is in
   `hardened`, with no flag that would undo `harden`."
  [argv]
  (let [argv         (mapv str argv)
        [_ sub args] (split-subcommand argv)
        undoes?      (fn [t]
                       (let [name (first (str/split t #"=" 2))]
                         (some #(str/starts-with? name %) (get-in hardened [sub :undoing]))))]
    (boolean
     (and (= "git" (first argv))
          (contains? hardened sub)
          (nil? (readonly/violation argv))
          (not-any? undoes? (take-while #(not= "--" %) args))))))

(defn harden
  "A `hardenable?` argv with every program-running feature of the
   repository's config switched off."
  [argv]
  (let [[pre sub args] (split-subcommand (mapv str argv))]
    (-> ["git"]
        (into hardening-globals)
        (into pre)
        (conj sub)
        (into (get-in hardened [sub :flags]))
        (into args))))

(defn unsafe-keys
  "The keys in `listing` (`git config --list --show-scope --name-only`) from
   the repository's own scopes that `harden` can't switch off for `argv`.
   Included files carry the scope of the file including them."
  [argv listing]
  (let [[_ sub] (split-subcommand (mapv str argv))]
    (->> (str/split-lines (str listing))
         (keep (fn [line]
                 (let [[scope k] (str/split line #"\t" 2)]
                   (when (and (contains? #{"local" "worktree"} scope)
                              k
                              (unsafe-key? sub (str/lower-case k)))
                     k))))
         distinct
         vec)))
