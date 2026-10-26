# clj tool reference

Every helper of the agent's `clj` tool, what each one may touch, and how
commands get approved. The [introduction](clj-tool.md) explains the idea.

## The sandbox

Scripts get `clojure.core` and `clojure.string` (`str`), `clojure.set`
(`set`), `clojure.walk` (`walk`), `clojure.edn` (`edn`), plus the helpers
below. There is no host interop: no `js/`, no `aget`, no `eval`, no class
constructors. `Math`, `Date`, `System/currentTimeMillis` and
`System/getProperty` (`user.home`, `user.dir`, `java.io.tmpdir`, `os.name`,
the separators) exist; `System/getenv` does not (use `env`). Base64 works as in babashka:
`(String. (.decode (java.util.Base64/getDecoder) s))` and
`(.encodeToString (java.util.Base64/getEncoder) (.getBytes s))`, and so do
`(java.net.URLEncoder/encode s "UTF-8")` and `java.net.URLDecoder/decode`
(UTF-8 only).
`(catch Exception e …)` catches any error, and `(.getMessage e)` works.

In an [agent profile](agents.md)'s chats the tool is `:compute` unless the
profile sets `:clj :full`: of the helpers below only `curl`, `now`, `sleep`,
`basename` and `dirname` remain, plus `babashka.http-client`, and there is no
`process`, `socket` or `fs`.

The REPL persists per chat: `def` once, use later. `/clj reset` drops it.
A result longer than 30k characters is saved to a file under the temp
directory and the result names the file. Values inside the REPL are never
truncated: `(sh …)`, `(curl …)` and `(jq …)` return complete output (up to
64 MB).

## Files and directories

| Helper | Does |
| --- | --- |
| `(cat f)` `(slurp f)` | The file as a string |
| `(head f n)` `(tail f n)` | First or last n lines, default 10 |
| `(ls d)` | Entries; directories end in `/` |
| `(glob "src/**/*.clj")` | Matching paths under the working directory |
| `(grep re path)` | ripgrep, line-numbered; regex or string |
| `(find pat dir)` | fd |
| `(stat f)` | `{:size :dir? :file? :mode :mtime-ms :mtime :ctime}` |
| `(realpath p)` `(basename p)` `(dirname p)` | Path helpers |
| `(which "cmd")` | The program's path, or nil |
| `(spit f s)` | Write; `{:append true}` appends |
| `(mkdir d)` `(cp a b)` `(mv a b)` `(touch f)` | Write operations; `mv` renames a symlink itself; `cp` skips any `.git` directory in the tree |
| `(rm f …)` | Delete; recursive; no error when missing. A symlink is removed, never followed: `(rm link)` leaves the linked directory intact |
| `(tmpdir)` | A fresh directory under the temp directory |
| `(cwd)` `(env "KEY")` `(now)` | Working directory, an allowed environment variable, the time |

Paths expand a leading `~` and `$VAR` for allowed variables.

`babashka.fs` is there too, required as `fs`, with path strings where
babashka returns `Path`s. Its file access follows the same rules as the
helpers, and `fs/delete-tree` asks like `rm`.

| Kind | `fs/` functions |
| --- | --- |
| Paths | `path` `file` `file-name` `parent` `extension` `strip-ext` `split-ext` `absolute?` `relative?` `absolutize` `normalize` `relativize` `components` `expand-home` `hidden?` `home` `cwd` `temp-dir` |
| Reads | `exists?` `directory?` `regular-file?` `sym-link?` `readable?` `writable?` `executable?` `size` `canonicalize` `read-all-lines` `list-dir` `glob` `which` |
| Writes | `create-dirs` `create-dir` `create-file` `create-temp-dir` `write-lines` `delete` `delete-if-exists` `delete-tree` `copy` `copy-tree` `move` |

**Where they may go.** The working directory and the temp directory are
free. A literal path elsewhere asks before the script runs, with `r` to
allow the whole repository it is in. A computed path elsewhere asks at the
moment it is used. Credential directories are always refused, and so is
anything inside a `.git` directory (git runs what `.git/hooks` and
`.git/config` name; the write tool and `(sh "git" "config" …)` ask
instead). Deleting a directory with `rm` asks, unless a rule such as the
default `rm` of git-tracked content allows that exact command.

## Programs and the network

| Helper | Does |
| --- | --- |
| `(sh "cmd" "arg" …)` | Run a program; stdout on success, an error with `{:exit :out :err}` otherwise. Options first: `(sh {:dir "sub" :env {"PORT" 8080}} "bb" "test")`. |
| `(git "log" "-5")` | Git without a dialog, for ordinary repository subcommands. Refused here, with the reason, and left to `sh` (which asks): `push`, `clean`, `-c`, `-C`, `--git-dir`, `-p`, `config` writes, `rebase -x`, `merge -s`, `--ext-diff`, `--output`, `--no-index`, `--upload-pack`, `--template`, `bisect run`, `submodule foreach`, aliases and tools (`difftool`, `hook`, …). Another directory of the same repository: `(sh {:dir "sub"} "git" …)`. |
| `(curl url opts)` | `{:status :headers :body}`; `http(s)` only; follows redirects; opts `{:method :headers :body :max-time}` |
| `(jq filter input)` | jq over a JSON string or Clojure data; returns data, `{:raw true}` returns text. `{:args […]}` adds output flags (`-S`, `--arg n v`, `--indent 2`, …), not file-reading ones (`-f`, `--rawfile`, `--slurpfile`, `-L`). jq sees only the allowed environment variables. |
| `(ports)` `(ports 1414)` | Listening sockets as `{:proto :addr :port :process :pid}` |

`babashka.http-client` is there too, required as `http`, with `get`, `head`,
`post`, `put`, `patch`, `delete` and `request`. Each returns `{:status
:headers :body}`; a header sent more than once comes as a vector. Options:
`:headers`, `:body`, `:query-params`, `:form-params`, `:basic-auth`,
`:timeout` (ms), `:follow-redirects` (default true), `:throw` (default true:
a status other than 200–207, 300–304 or 307 throws with the response as
`ex-data`) and `:as` (`:string` or `:bytes`). There is no client, async or
streaming API.

`sh` takes one program and its arguments. There are no shell strings, and
`(sh "bash" "-c" …)` is refused, also by path or through `env`, `timeout`,
`xargs` and the like. `:dir` must exist and is treated as a read; for `git`
it must be inside the chat's repository, or the call asks. `status`, `diff`,
`log`, `show`, `ls-tree`, `ls-files`, `cat-file`, `rev-parse`, `rev-list`,
`merge-base`, `show-ref` and `for-each-ref` run in another repository
without asking, with that repository's hooks, `core.fsmonitor`, external
diff, textconv, gpg programs, submodules and lazy fetches switched off. They
still ask when its own config sets a `filter.*` driver or `core.worktree`
(for `log` and `show` also a `merge.*.driver`), or a flag turns textconv,
filters or submodules back on. `:env` may not
change what an approved program loads or runs (`PATH`, `HOME`, `LD_*`,
`NODE_OPTIONS`, `GIT_*`, `EDITOR`, `VISUAL`, `PAGER`, `XDG_CONFIG_HOME`,
`RIPGREP_CONFIG_PATH`, …).

Programs started by `sh` and `process/start` get `XI_SESSION_ID`, the id of
the chat that ran them, so a script can link back to it
(`http://<xi host>:1414/chat/<id>`).

### How a program gets approved

Before the script runs, Xi reads every literal `sh` call out of it and
decides each one:

1. A read-only program with read-only arguments runs: `ls` `cat` `head`
   `tail` `grep` `rg` `find` `fd` `pwd` `echo` `mktemp` `git` `stat` `du`
   `readlink` `realpath` `which` `basename` `dirname` `date` `ss` `netstat`
   `lsof` `wc` `sort` `uniq` `cut` `tr` `true` `false`, and `rm`. The arguments of `find`,
   `fd`, `rg`, `sort`, `ss` and `git` are parsed (the `:read-only`
   [rule field](rules-reference.md#match) lists what is refused: `find
   -exec`, `fd -x`, `rg --pre`, `sort -o`, `ss -K`, `git -c …`, `git push`,
   `git config k v`, …); a refused call asks instead, the dialog naming the
   reason, and a computed argument that turns out not to be read-only fails
   when the script runs. The result carries a hint to use the helper instead;
   turn hints off with `:helper-hints false` in `~/.config/xi/ext/clj.edn`.
2. A program in `:allow-clis` of `~/.config/xi/ext/clj.edn`, or allowed by a
   [rule](rules-reference.md), runs with any arguments. A rule with
   `:command`, `:path`, `:within`, `:tracked`, `:host` or `:read-only` allows
   only that exact, fully literal command (`:path` tests its glob or regex
   against every path the command names, so `{:cli "rm" :path "/tmp/**"}`
   covers deletions under `/tmp` and nothing else).
3. Anything else asks: once, always (for the chat), or deny.

Whatever the program, these still ask: destructive patterns (`rm -rf`,
`git push`, `kill`, `fs/delete-tree`), `bb serve:restart` and `serve:stop`
(then run detached), and an interpreter running inline code or a script
(`bb -e`, `bb -f x.clj`, `node -e`, `python x.py`, `bun x.ts`, …), which has
no "always". Remote shells (`ssh`, `scp`, `rsync`, `sftp`) never run.

A computed program name was never approved and fails; use a literal. With
nobody to answer (`xi prompt`), an ask is a deny.

**Several asks in one script.** Programs, paths outside the repository,
directory deletions and guarded commands are asked about one at a time.
While more than one is still to come, each ask also offers **Allow all**
(`b`, `/allow block`, `Alt+Shift+B` on the web). Its label shows how many
asks it covers. Hovering it on the web highlights every call it would
allow. Answering it allows the current ask and the rest of the script's asks
without further dialogs. Like plain Allow, it applies only to this run;
nothing is remembered. A computed path that asks while the script runs
still gets its own dialog.

## Background processes

| Call | Does |
| --- | --- |
| `(process/start "npm run dev")` | Spawn detached; `{:pid :log}`. Options map first for `:dir` and `:env`. |
| `(process/wait pid timeout-ms)` | Block until exit; `{:status :exited :exit :output}`, or `{:status :running}` after the timeout (default two minutes; call again) |
| `(process/output pid n)` | The last n log lines |
| `(process/list)` | This chat's processes |
| `(process/stop pid)` | Terminate one |
| `(process/poll-until "cmd" {:until :exit-zero :interval-ms 5000 :timeout-ms 120000})` | Rerun a command until it succeeds, or until its output matches `:pattern` (`:stdout-matches`, `:stdout-not-matches`) |
| `(process/poll-url "http://localhost:1416" {:status 200 :interval-ms 5000 :timeout-ms 120000})` | Probe an http(s) URL until it answers with `:status` (a number or a set; default any 2xx). `{:met? :attempts :status}`; `:status` is `nil` while nothing is listening. No shell, so no approval |

The command is a literal shell line, approved like a `bash` command. A
trailing `&` is ignored; the process is detached anyway. `wait` and
`poll-until` stop when the turn is aborted; the process keeps running.
`/ps` lists a chat's processes, `/kill <pid>` stops one, and a chat with a
live process is not closed.

## Loopback sockets

Raw TCP to services on this machine: nREPL, an editor, a daemon with an IPC
port.

| Call | Does |
| --- | --- |
| `(socket/connect "127.0.0.1" port)` | Open; `{:timeout-ms 10000}` optional |
| `(socket/write s data)` | A string or a seq of bytes |
| `(socket/read s)` | Block for the next chunk; `{:n k}` exactly k bytes, `{:until "\n"}` up to a delimiter (consumed), `{:bytes? true}` a byte vector, `{:timeout-ms 30000}` |
| `(socket/close s)` `(socket/open? s)` `(socket/list)` | Housekeeping |

Only `localhost`, `127.x.x.x` and `::1` are accepted; no dialog is shown.
Sockets persist with the REPL.

## The run_task tool

`run_task` runs a project task: a `bb.edn` task, a `package.json` script or a
`deno.json` task. Its arguments are `task`, `args` and `runner` (`bb`, `npm`,
`pnpm`, `yarn`, `deno`). Without `runner` it uses the nearest project file's;
for `package.json` a `pnpm-lock.yaml` or `yarn.lock` next to it picks pnpm or
yarn, else npm. Without `task` it lists the tasks.

It is gated by trusting the project file by content: the first run asks and
"always" records the file's hash in `~/.config/xi/ext/task-trust.edn`;
`/clj trust-tasks` does the same without a call. An edited file asks again.
A `(sh …)` call running a task the file defines (`(sh "npm" "run" "build")`)
is gated the same way. Guarded patterns in a task's command line still ask.

The hash covers the project file only, not the code it calls: bb scripts in
other files, npm `pre`/`post` scripts and `node_modules` binaries.

## Commands and files

| | Does |
| --- | --- |
| `/clj` | The allowed programs and which project files are trusted |
| `/clj allow <cli>` / `/clj revoke <cli>` | Allow or revoke a program for this chat (a chat rule; `/rules` shows it) |
| `/clj trust-tasks` | Trust the nearest `bb.edn`, `package.json` and `deno.json` |
| `/clj reset` | Drop the chat's REPL |
| `/ext disable clj` / `enable clj` | Turn the tool off (the agent gets `bash`) or on |
| `~/.config/xi/ext/clj.edn` | `{:allow-clis ["ffmpeg" "jq"] :helper-hints true}` |
| `~/.config/xi/ext/task-trust.edn` | Trusted project file hashes; written by Xi. Hashes in `bb-trust.edn` from earlier versions still count |
