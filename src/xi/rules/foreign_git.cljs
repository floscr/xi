(ns xi.rules.foreign-git
  "git in another repository than the chat's: a clj `(sh {:dir …} \"git\" …)`
   whose :dir lies in a different repo. git there runs what that repository
   names — hooks (post-index-change on an index refresh), core.fsmonitor,
   filter and diff drivers, diff.external, a promisor remote's fetch
   (core.sshCommand, credential helpers) and its submodules' own config.
   `status` and `diff` still auto-run, `harden`ed so all of that is off,
   unless the repository's own config sets what no flag switches off
   (`unsafe-keys`). Pure; xi.ext.clj probes the config and runs the call."
  (:require [clojure.string :as str]
            [xi.rules.readonly :as readonly]))

(def ^:private hardening-globals
  ["--no-pager" "--no-optional-locks" "--no-lazy-fetch"
   "-c" "core.hooksPath=/dev/null" "-c" "core.fsmonitor=false"])

(def ^:private hardening-flags
  "Placed right after the subcommand — `diff` takes no option after a
   revision or path — so a later flag of the call could undo them:
   `undoing-prefixes`."
  {"status" ["--ignore-submodules=all"]
   "diff"   ["--no-ext-diff" "--no-textconv" "--ignore-submodules=all"]})

(def ^:private undoing-prefixes
  "Flag names (before `=`) that re-enable what `hardening-flags` switches
   off. `status` takes unambiguous abbreviations (`--ignore-s=none`), `diff`
   none."
  {"status" ["--ignore-"]
   "diff"   ["--textconv" "--ext-diff" "--submodule" "--ignore-submodules"]})

(def ^:private unsafe-key-re
  "Own-config keys `harden` leaves on: filter drivers (status and diff run a
   modified file's clean filter) and core.worktree (moves the work tree the
   diff prints, e.g. to ~/.ssh)."
  #"^(?:filter\..+|core\.worktree)$")

(defn- split-subcommand
  "argv of a read-only git call → [globals subcommand args]. Its globals are
   all value-less (xi.rules.readonly), so the first operand is the subcommand."
  [argv]
  (let [[pre [sub & args]] (split-with #(str/starts-with? % "-") (rest argv))]
    [pre sub args]))

(defn hardenable?
  "True for a read-only (xi.rules.readonly) `git status` / `git diff` argv
   with no flag that would undo `harden`."
  [argv]
  (let [argv         (mapv str argv)
        [_ sub args] (split-subcommand argv)
        undoes?      (fn [t]
                       (let [name (first (str/split t #"=" 2))]
                         (some #(str/starts-with? name %) (undoing-prefixes sub))))]
    (boolean
     (and (= "git" (first argv))
          (contains? hardening-flags sub)
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
        (into (hardening-flags sub))
        (into args))))

(defn unsafe-keys
  "The keys in `listing` (`git config --list --show-scope --name-only`) from
   the repository's own scopes that `harden` can't switch off. Included files
   carry the scope of the file including them."
  [listing]
  (->> (str/split-lines (str listing))
       (keep (fn [line]
               (let [[scope k] (str/split line #"\t" 2)]
                 (when (and (contains? #{"local" "worktree"} scope)
                            k
                            (re-matches unsafe-key-re (str/lower-case k)))
                   k))))
       distinct
       vec))
