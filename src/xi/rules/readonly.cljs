(ns xi.rules.readonly
  "Allowlist argv parsers for the programs clj `(sh …)` runs without asking
   (`xi.rules.defaults/sh-read-only`). A read-only program is only read-only
   for the right arguments: `find -exec`, `fd -x`, `rg --pre`,
   `sort --compress-program` run a program, `git -c core.hooksPath=…` sets
   the one that git runs next, `git config k v` writes it. `violation`
   parses one argv and names the first argument that isn't read-only, or
   returns nil when the whole call is.

   Every parser is an allowlist: a flag the parser doesn't know is a
   violation (conservative — the call then asks instead of auto-running).
   The programs without a parser (`cat`, `ls`, `wc`, …) have no dangerous
   forms; they are listed in `plain-clis` so `clis` is the complete set the
   `:read-only` rule key vouches for. Pure — no I/O."
  (:require [clojure.string :as str]))

;; ── getopt / clap style parsers (fd, rg, sort, ss) ────────────────────────────

(defn- bundle
  "A short-flag bundle `-abc`: each char an allowed 0-arity flag, or a
   1-arity flag whose value is the rest of the token (or the next token when
   nothing follows). → :ok | :next (consume the next token) | the bad char."
  [short0 short1 chars]
  (loop [[c & cs] chars]
    (cond
      (nil? c)             :ok
      (contains? short1 c) (if (seq cs) :ok :next)
      (contains? short0 c) (recur cs)
      :else                c)))

(defn- getopt-violation
  "Walk `args` with GNU getopt / clap conventions against an allowlist spec
   {:short0 #{\\a} :short1 #{\\k} :long0 #{\"--x\"} :long1 #{\"--key\"}
   :long-opt #{\"--check\"} :long-re #\"…\"} (0 = takes no value, 1 = takes
   one — `--key=v`, `--key v`, `-kv`, `-k v`; `long-opt` takes an optional
   `=v`; `long-re` matches further value-less long flags). Everything after
   `--` is an operand. → the first offending token, or nil."
  [{:keys [short0 short1 long0 long1 long-opt long-re]} args]
  (loop [[t & more] args]
    (cond
      (nil? t)  nil
      (= t "--") nil

      (str/starts-with? t "--")
      (let [i    (str/index-of t "=")
            name (if i (subs t 0 i) t)]
        (cond
          (and (contains? long0 name) (nil? i))    (recur more)
          (contains? long1 name)                   (cond
                                                     i                   (recur more)
                                                     (some? (first more)) (recur (rest more))
                                                     :else               t)
          (contains? long-opt name)                (recur more)
          (and long-re (re-matches long-re name))  (recur more)
          :else                                    t))

      (and (str/starts-with? t "-") (> (count t) 1))
      (let [r (bundle short0 short1 (rest t))]
        (cond
          (= r :ok)   (recur more)
          (= r :next) (if (some? (first more)) (recur (rest more)) t)
          :else       t))

      :else (recur more))))

(def ^:private fd-spec
  "fd. Absent on purpose: `-x/--exec`, `-X/--exec-batch` (run a program per
   match), `-l/--list-details` (runs `ls`)."
  {:short0 #{\H \I \u \s \i \g \F \a \L \p \0 \q \1 \h \V}
   :short1 #{\d \t \e \S \o \E \c \j}
   :long0  #{"--hidden" "--no-ignore" "--unrestricted" "--no-ignore-vcs"
             "--no-ignore-parent" "--no-global-ignore-file" "--no-require-git"
             "--case-sensitive" "--ignore-case" "--glob" "--regex"
             "--fixed-strings" "--absolute-path" "--follow" "--full-path"
             "--print0" "--prune" "--quiet" "--has-results" "--show-errors"
             "--one-file-system" "--help" "--version"}
   :long1  #{"--and" "--max-depth" "--min-depth" "--exact-depth" "--type"
             "--extension" "--size" "--changed-within" "--changed-before"
             "--owner" "--format" "--exclude" "--ignore-file" "--color"
             "--threads" "--max-buffer-time" "--max-results" "--base-directory"
             "--path-separator" "--search-path"}
   :long-opt #{"--strip-cwd-prefix" "--hyperlink"}})

(def ^:private rg-spec
  "ripgrep. Absent on purpose: `--pre` / `--pre-glob` (a preprocessor
   program per file), `--hostname-bin` (a program for hyperlinks),
   `-z/--search-zip` (external decompressors). `--no-…` negations take no
   value and turn a feature off, so they are matched by pattern."
  {:short0 #{\a \b \c \F \h \H \i \I \l \L \n \N \o \p \P \q \s \S \U \u \v \V
             \w \x \0 \.}
   :short1 #{\A \B \C \E \M \d \e \f \g \j \m \r \t \T}
   :long0  #{"--auto-hybrid-regex" "--binary" "--block-buffered" "--byte-offset"
             "--case-sensitive" "--column" "--count" "--count-matches" "--crlf"
             "--debug" "--files" "--files-with-matches" "--files-without-match"
             "--fixed-strings" "--follow" "--glob-case-insensitive" "--heading"
             "--help" "--hidden" "--ignore-case" "--ignore-file-case-insensitive"
             "--include-zero" "--invert-match" "--json" "--line-buffered"
             "--line-number" "--line-regexp" "--max-columns-preview" "--mmap"
             "--multiline" "--multiline-dotall" "--null" "--null-data"
             "--one-file-system" "--only-matching" "--passthru" "--passthrough"
             "--pcre2" "--pcre2-version" "--pretty" "--quiet" "--smart-case"
             "--sort-files" "--stats" "--stop-on-nonmatch" "--text" "--trace"
             "--trim" "--type-list" "--unrestricted" "--version" "--vimgrep"
             "--with-filename" "--word-regexp" "--hyperlink"}
   :long1  #{"--after-context" "--before-context" "--context" "--color"
             "--colors" "--context-separator" "--dfa-size-limit" "--encoding"
             "--engine" "--field-context-separator" "--field-match-separator"
             "--file" "--glob" "--iglob" "--ignore-file" "--max-columns"
             "--max-count" "--max-depth" "--max-filesize" "--path-separator"
             "--regex-size-limit" "--regexp" "--replace" "--sort" "--sortr"
             "--threads" "--type" "--type-add" "--type-clear" "--type-not"
             "--hyperlink-format" "--generate"}
   :long-re #"--no-[a-z0-9-]+"})

(def ^:private sort-spec
  "GNU sort. Absent on purpose: `--compress-program` (runs a program),
   `-o/--output` (writes a file), `-T/--temporary-directory`."
  {:short0 #{\b \d \f \g \h \i \M \m \n \R \r \s \u \V \z \c \C}
   :short1 #{\k \t \S}
   :long0  #{"--ignore-leading-blanks" "--dictionary-order" "--ignore-case"
             "--general-numeric-sort" "--human-numeric-sort"
             "--ignore-nonprinting" "--month-sort" "--numeric-sort"
             "--random-sort" "--reverse" "--version-sort" "--debug" "--merge"
             "--stable" "--unique" "--zero-terminated" "--help" "--version"}
   :long1  #{"--key" "--field-separator" "--buffer-size" "--batch-size"
             "--parallel" "--random-source" "--files0-from" "--sort"}
   :long-opt #{"--check"}})

(def ^:private ss-spec
  "iproute2 ss. Absent on purpose: `-K/--kill` (destroys sockets),
   `-D/--diag` (writes a raw dump file)."
  {:short0 #{\h \V \H \O \n \r \a \l \o \e \m \p \i \s \b \E \Z \z \4 \6 \0 \t
             \u \d \w \x \S \M \T \B \P}
   :short1 #{\f \A \F \N}
   :long0  #{"--help" "--version" "--no-header" "--oneline" "--numeric"
             "--resolve" "--all" "--listening" "--options" "--extended"
             "--memory" "--processes" "--info" "--summary" "--bpf" "--events"
             "--context" "--contexts" "--ipv4" "--ipv6" "--packet" "--tcp" "--udp"
             "--dccp" "--raw" "--unix" "--sctp" "--mptcp" "--tipc" "--xdp"
             "--vsock" "--tos" "--cgroup" "--inet-sockopt" "--tipcinfo"
             "--threads"}
   :long1  #{"--family" "--query" "--socket" "--filter" "--net"}})

;; ── find ─────────────────────────────────────────────────────────────────────

(def ^:private find-primaries
  "GNU find expression tokens → how many arguments follow. Absent on purpose:
   `-exec -execdir -ok -okdir` (run a program), `-delete`, `-fprint -fprint0
   -fprintf -fls` (write a file), `-D` / `-O…` (debug / optimizer options)."
  (merge
   (zipmap ["-H" "-L" "-P" "-daystart" "-depth" "-d" "-follow" "-mount" "-xdev"
            "-noleaf" "-ignore_readdir_race" "-noignore_readdir_race" "-empty"
            "-executable" "-false" "-true" "-nogroup" "-nouser" "-readable"
            "-writable" "-prune" "-print" "-print0" "-ls" "-quit" "-not" "-a"
            "-and" "-o" "-or" "!" "(" ")" ","]
           (repeat 0))
   (zipmap ["-maxdepth" "-mindepth" "-amin" "-anewer" "-atime" "-cmin" "-cnewer"
            "-ctime" "-mmin" "-mtime" "-newer" "-fstype" "-gid" "-group" "-ilname"
            "-iname" "-inum" "-ipath" "-iregex" "-iwholename" "-links" "-lname"
            "-name" "-path" "-perm" "-regex" "-regextype" "-samefile" "-size"
            "-type" "-uid" "-user" "-used" "-wholename" "-printf" "-xtype"
            "-context"]
           (repeat 1))))

(defn- find-violation [args]
  (loop [[t & more] args]
    (cond
      (nil? t) nil
      (or (contains? find-primaries t) (re-matches #"-newer[aBcmt][aBcmt]" t))
      (let [n (get find-primaries t 1)]
        (if (< (count (take n more)) n)
          t
          (recur (drop n more))))
      (str/starts-with? t "-") t
      :else (recur more))))

;; ── git ──────────────────────────────────────────────────────────────────────

(def ^:private git-globals
  "Value-less global options allowed before the subcommand. Absent on
   purpose: `-c` / `--config-env` (set config: core.hooksPath, core.pager,
   aliases, …), `--exec-path` (where git finds its commands), `-C` /
   `--git-dir` / `--work-tree` (relocate git — the `:dir` option is checked
   instead), `-p` / `--paginate` (runs the pager)."
  #{"-P" "--no-pager" "--literal-pathspecs" "--glob-pathspecs" "--noglob-pathspecs"
    "--icase-pathspecs" "--no-optional-locks" "--no-replace-objects"
    "--no-lazy-fetch" "--bare" "--version" "--html-path" "--man-path" "--info-path"})

(def ^:private git-subcommands
  "Subcommands the helper runs, each with the flags it refuses (`:short`
   chars, `:long` names — a token equal to, abbreviating, or `=`-valued from
   one). Not listed: `push` and `clean` (mutate the remote / delete untracked
   files), `difftool` `mergetool` `filter-branch` `bisect run` `hook`
   `maintenance` `credential…` `daemon` `instaweb` `gui` `for-each-repo`
   `archive` `bundle` `fast-import` `help` and aliases — whatever runs a
   program, opens a tool, or isn't a plain repository operation."
  (let [diff   {:long #{"--ext-diff" "--output" "--no-index"}}
        none   {}
        remote {:long #{"--upload-pack"}}]
    {"status" none "log" diff "show" diff "diff" diff "diff-tree" diff
     "diff-index" diff "diff-files" diff "whatchanged" diff "range-diff" diff
     "stash" diff
     "format-patch" {:short #{\o} :long #{"--ext-diff" "--output" "--output-directory"}}
     "rev-parse" none "rev-list" none "ls-files" none "ls-tree" none
     "ls-remote" remote "cat-file" none "blame" none "annotate" none
     "shortlog" none "describe" none "name-rev" none "merge-base" none
     "cherry" none "count-objects" none "fsck" none "check-ignore" none
     "check-attr" none "check-ref-format" none "show-ref" none
     "for-each-ref" none "symbolic-ref" none "var" none "verify-commit" none
     "verify-tag" none "verify-pack" none "patch-id" none "stripspace" none
     "interpret-trailers" none "version" none
     "grep" {:short #{\O} :long #{"--open-files-in-pager"}}
     "reflog" none "worktree" none "submodule" none "notes" none "config" none
     "remote" none "branch" none "tag" none "bisect" none
     "add" none "rm" none "mv" none "commit" none "checkout" none "switch" none
     "restore" none "reset" none
     "merge" {:short #{\s} :long #{"--strategy"}}
     "rebase" {:short #{\x \s} :long #{"--exec" "--strategy"}}
     "cherry-pick" {:long #{"--strategy"}}
     "revert" {:long #{"--strategy"}}
     "apply" {:long #{"--unsafe-paths"}}
     "am" none
     "fetch" remote
     "pull" {:short #{\s} :long #{"--upload-pack" "--strategy"}}
     "clone" {:short #{\u \c} :long #{"--upload-pack" "--template" "--config"
                                      "--separate-git-dir"}}
     "init" {:long #{"--template" "--separate-git-dir"}}
     "sparse-checkout" none "gc" none "prune" none "repack" none "pack-refs" none
     "hash-object" none "update-index" none "update-ref" none "write-tree" none
     "read-tree" none "commit-tree" none "mktree" none}))

(def ^:private git-reasons
  "Why a refused token is refused, for the approval prompt / error."
  {"-c" "sets configuration" "--config-env" "sets configuration"
   "--exec-path" "changes where git finds its commands"
   "-C" "runs git in another directory (use the :dir option)"
   "--git-dir" "relocates the repository" "--work-tree" "relocates the work tree"
   "-p" "opens the pager" "--paginate" "opens the pager"
   "--ext-diff" "runs the configured external diff" "--output" "writes a file"
   "--output-directory" "writes files" "-o" "writes files"
   "--no-index" "compares files outside the repository"
   "-O" "opens files in the pager" "--open-files-in-pager" "opens files in the pager"
   "-x" "runs a command" "--exec" "runs a command"
   "-s" "runs a merge strategy program" "--strategy" "runs a merge strategy program"
   "--upload-pack" "runs a program as the remote side" "-u" "runs a program as the remote side"
   "--template" "copies hooks from a template directory"
   "--config" "sets configuration" "--separate-git-dir" "relocates the repository"
   "--unsafe-paths" "writes outside the work tree"
   "push" "mutates the remote" "clean" "deletes untracked files"
   "foreach" "runs a command per submodule" "run" "runs a command per step"
   "visualize" "opens gitk" "view" "opens gitk"})

(defn- git-token-refused?
  "True when `t` is (or abbreviates, or `=`-values) a refused long flag, or
   is a short bundle holding a refused char."
  [{:keys [short long]} t]
  (cond
    (str/starts-with? t "--")
    (let [name (first (str/split t #"=" 2))]
      (boolean (some #(and (>= (count name) 3) (str/starts-with? % name)) long)))

    (str/starts-with? t "-")
    (boolean (some #(contains? (or short #{}) %) (rest t)))

    :else false))

(defn- git-flag-reason
  "Why a refused git token is refused: the reason of the long flag it names
   or abbreviates (git accepts unambiguous prefixes), or of a refused short
   flag in the bundle; else generic."
  [t]
  (let [name (first (str/split t #"=" 2))]
    (or (get git-reasons name)
        (when (str/starts-with? t "--")
          (some (fn [[k v]] (when (and (str/starts-with? k "--") (str/starts-with? k name)) v))
                git-reasons))
        (when-not (str/starts-with? t "--")
          (some (fn [[k v]] (when (and (str/starts-with? k "-") (not (str/starts-with? k "--"))
                                       (str/includes? t (subs k 1)))
                              v))
                git-reasons))
        "is not a read-only option")))

(defn- git-config-violation
  "`git config` may only read: `--get…`, `-l/--list`, or a lone key. A
   second positional (`config k v`), the write flags, `-e/--edit` and the
   `set`/`unset`/… subcommands write."
  [args]
  (let [write-long #{"--add" "--replace-all" "--unset" "--unset-all"
                     "--rename-section" "--remove-section" "--edit"}
        value-flags #{"-f" "--file" "--blob" "--type" "--default"}]
    (loop [[t & more] args positionals [] read? false]
      (cond
        (nil? t)
        (cond
          read?                      nil
          (contains? #{"get" "list"} (first positionals)) nil
          (<= (count positionals) 1) nil
          :else                      (str "`config " (str/join " " positionals)
                                          "` sets a value"))

        (= t "--") (recur nil (into positionals more) read?)

        (and (str/starts-with? t "--")
             (let [name (first (str/split t #"=" 2))]
               (some #(and (>= (count name) 3) (str/starts-with? % name)) write-long)))
        (str "`" t "` writes configuration")

        (and (str/starts-with? t "-") (not (str/starts-with? t "--")) (str/includes? t "e"))
        (str "`" t "` opens the editor")

        (and (contains? value-flags t) (some? (first more)))
        (recur (rest more) positionals read?)

        (str/starts-with? t "--")
        (recur more positionals (or read? (str/starts-with? t "--get") (= t "--list")))

        (str/starts-with? t "-")
        (recur more positionals (or read? (str/includes? t "l")))

        :else
        (if (contains? #{"set" "unset" "edit" "rename-section" "remove-section"} t)
          (str "`config " t "` writes configuration")
          (recur more (conj positionals t) read?))))))

(defn- git-sub-violation
  "Check the tokens after an allowlisted subcommand."
  [sub args]
  (let [spec (get git-subcommands sub)
        args (take-while #(not= "--" %) args)]
    (case sub
      "config"    (git-config-violation args)
      "bisect"    (when-let [t (some #{"run" "visualize" "view"} (take 1 args))]
                    (str "`bisect " t "` " (git-reasons t)))
      "submodule" (let [t (first (remove #(str/starts-with? % "-") args))]
                    (when (and t (not (contains? #{"status" "summary" "init" "update"
                                                   "sync" "add" "deinit" "absorbgitdirs"
                                                   "set-branch" "set-url"} t)))
                      (str "`submodule " t "` " (get git-reasons t "is not an allowlisted form"))))
      (when-let [t (some #(when (git-token-refused? spec %) %) args)]
        (str "`" sub " " t "` " (git-flag-reason t))))))

(defn- git-violation [args]
  (loop [[t & more] args]
    (cond
      (nil? t) "has no subcommand"
      ;; informational globals that are a whole command by themselves
      (contains? #{"--version" "--html-path" "--man-path" "--info-path"} t) nil
      (contains? git-globals t) (recur more)
      (str/starts-with? t "-")
      (str "`" t "` " (git-flag-reason t))
      (contains? git-subcommands t) (git-sub-violation t more)
      :else (str "`" t "` " (get git-reasons t "is not an allowlisted subcommand")))))

;; ── Public ───────────────────────────────────────────────────────────────────

(def ^:private getopt-reasons
  "Why the well-known refused flags of the getopt-style programs are refused."
  {"fd"   {"-x" "runs a program per match" "--exec" "runs a program per match"
           "-X" "runs a program" "--exec-batch" "runs a program"
           "-l" "runs ls" "--list-details" "runs ls"}
   "rg"   {"--pre" "runs a preprocessor program" "--pre-glob" "runs a preprocessor program"
           "--hostname-bin" "runs a program" "-z" "runs decompressors" "--search-zip" "runs decompressors"}
   "sort" {"--compress-program" "runs a program" "-o" "writes a file" "--output" "writes a file"
           "-T" "writes temporary files elsewhere" "--temporary-directory" "writes temporary files elsewhere"}
   "ss"   {"-K" "destroys sockets" "--kill" "destroys sockets"
           "-D" "writes a dump file" "--diag" "writes a dump file"}
   "find" {"-exec" "runs a program" "-execdir" "runs a program" "-ok" "runs a program"
           "-okdir" "runs a program" "-delete" "deletes files" "-fprint" "writes a file"
           "-fprint0" "writes a file" "-fprintf" "writes a file" "-fls" "writes a file"}})

(defn- describe
  "Reason text for a refused token `t` of `cli`: the known reason of the
   flag (long: by name before `=`; short: any refused char in the bundle),
   else generic."
  [cli t]
  (let [reasons (get getopt-reasons cli)
        name    (first (str/split t #"=" 2))]
    (str "`" t "` "
         (or (get reasons name)
             (when (and (str/starts-with? t "-") (not (str/starts-with? t "--")))
               (some (fn [[k v]] (when (and (= 2 (count k)) (str/includes? t (subs k 1))) v))
                     reasons))
             "is not a read-only option"))))

(defn- getopt-parser [cli spec]
  (fn [args]
    (when-let [t (getopt-violation spec args)]
      (describe cli t))))

(def parsers
  "Program → parser: (fn [args]) → a reason string naming the first
   non-read-only argument, or nil when the call is read-only."
  {"find" (fn [args] (when-let [t (find-violation args)] (describe "find" t)))
   "fd"   (getopt-parser "fd" fd-spec)
   "rg"   (getopt-parser "rg" rg-spec)
   "sort" (getopt-parser "sort" sort-spec)
   "ss"   (getopt-parser "ss" ss-spec)
   "git"  git-violation})

(def plain-clis
  "Read-only programs with no argument that runs a program or writes a file
   — no parser needed."
  #{"ls" "cat" "head" "tail" "grep" "pwd" "echo" "mktemp" "stat" "du"
    "readlink" "realpath" "which" "basename" "dirname" "date" "wc" "uniq"
    "cut" "tr" "netstat" "lsof" "true" "false"})

(def clis
  "Every program the `:read-only` rule key can vouch for: the parsed ones
   and `plain-clis`."
  (into plain-clis (keys parsers)))

(defn violation
  "The reason the call `argv` (program first) is not read-only, or nil when
   it is. Only programs in `clis` are judged — for any other program the
   answer is nil, so callers must pair this with a program allowlist (the
   `:read-only` rule key is only meaningful next to `:cli`)."
  [argv]
  (when-let [parse (get parsers (first argv))]
    (parse (map str (rest argv)))))

(defn read-only?
  "True when `argv` runs a program in `clis` with read-only arguments."
  [argv]
  (and (contains? clis (first argv)) (nil? (violation argv))))

(def ^:private plain-command-re
  "A background command string with no shell syntax at all — no quoting,
   expansion, globbing, redirection or control operators — so splitting on
   spaces yields the argv bash runs it with. Stricter than the curl
   variant: `?` and `[` could glob-expand into a flag."
  #"[A-Za-z0-9._~:/@+,=%\- ]+")

(defn request-read-only?
  "For a `:sh` decision request: true / false when its argv — the literal
   `:argv` of a clj `(sh …)` call, or a background `:command` split on
   spaces when it has no shell syntax — is / isn't a read-only call of a
   program in `clis`; nil when there is nothing to judge (another tool, an
   unknown program, shell syntax, or no argv)."
  [{:keys [tool argv command]}]
  (when (= :sh tool)
    (when-let [argv (or argv
                        (when (and command (re-matches plain-command-re (str command)))
                          (str/split (str/trim command) #" +")))]
      (when (contains? clis (first argv))
        (nil? (violation argv))))))
