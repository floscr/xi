# clj tool reference

Every helper of the agent's `clj` tool, what each one may touch, and how
commands get approved. The [introduction](clj-tool.md) explains the idea.

## The sandbox

Scripts get `clojure.core` and `clojure.string` (`str`), `clojure.set`
(`set`), `clojure.walk` (`walk`), `clojure.edn` (`edn`), plus the helpers
below. There is no host interop: no `js/`, no `aget`, no `eval`, no class
constructors. `Math`, `Date` and `System/currentTimeMillis` exist;
`System/getenv` does not (use `env`). `(catch Exception e …)` catches any
error, and `(.getMessage e)` works.

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
| `(curl url opts)` | `{:status :body}`; `http(s)` only; opts `{:method :headers :body :max-time}` |
| `(jq filter input)` | jq over a JSON string or Clojure data; returns data, `{:raw true}` returns text. `{:args […]}` adds output flags (`-S`, `--arg n v`, `--indent 2`, …), not file-reading ones (`-f`, `--rawfile`, `--slurpfile`, `-L`). jq sees only the allowed environment variables. |
| `(ports)` `(ports 7474)` | Listening sockets as `{:proto :addr :port :process :pid}` |

`sh` takes one program and its arguments. There are no shell strings, and
`(sh "bash" "-c" …)` is refused, also by path or through `env`, `timeout`,
`xargs` and the like. `:dir` must exist and is treated as a read; for `git`
it must be inside the chat's repository, or the call asks. `:env` may not
change what an approved program loads or runs (`PATH`, `HOME`, `LD_*`,
`NODE_OPTIONS`, `GIT_*`, `EDITOR`, `VISUAL`, `PAGER`, `XDG_CONFIG_HOME`,
`RIPGREP_CONFIG_PATH`, …).

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
| `(process/poll-url "http://localhost:7476" {:status 200 :interval-ms 5000 :timeout-ms 120000})` | Probe an http(s) URL until it answers with `:status` (a number or a set; default any 2xx). `{:met? :attempts :status}`; `:status` is `nil` while nothing is listening. No shell, so no approval |

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

## The bb tool

`bb <task>` runs a Babashka task of the project. It is gated by trusting the
project's `bb.edn` by content: the first run asks and "always" records the
file's hash in `~/.config/xi/ext/bb-trust.edn`; `/clj trust-bb` does the
same without a call. An edited `bb.edn` asks again. Guarded patterns in a
task's command line still ask.

## Commands and files

| | Does |
| --- | --- |
| `/clj` | The allowed programs and the `bb.edn` trust state |
| `/clj allow <cli>` / `/clj revoke <cli>` | Allow or revoke a program for this chat (a chat rule; `/rules` shows it) |
| `/clj trust-bb` | Trust the project's `bb.edn` |
| `/clj reset` | Drop the chat's REPL |
| `/ext disable clj` / `enable clj` | Turn the tool off (the agent gets `bash`) or on |
| `~/.config/xi/ext/clj.edn` | `{:allow-clis ["ffmpeg" "jq"] :helper-hints true}` |
| `~/.config/xi/ext/bb-trust.edn` | Trusted `bb.edn` hashes; written by Xi |
