# clj — sandboxed Clojure scripting for the agent

`xi.ext.clj` gives the agent one tool, **`clj`**: a persistent, per-room
Clojure REPL evaluated by [SCI](https://github.com/babashka/sci), with a small
set of synchronous shell-ish helpers. It replaces unreadable bash pipe chains
with reviewable, structured Clojure — and keeps context small, because
computation happens in the runtime and only distilled values return to the
conversation.

```clojure
;; instead of: cat x | grep TODO | wc -l  … per file … in one giant bash line
(->> (glob "src/**/*.cljs")
     (map (fn [f] [f (count (re-seq #"TODO" (cat f)))]))
     (filter (fn [[_ n]] (pos? n)))
     (into {}))
;; => {"src/xi/agent.cljs" 3, "src/xi/fx.cljs" 1}
```

Disable it any time with `/ext disable clj` (the tool disappears from the
model on the next turn), or remove `clj-tool/extension` from the `server`
vector in `src/xi/config.cljc`.

## Why it keeps context small

- **Computation outside the context window** — scripts filter/aggregate
  in-process; only the final value (truncated `pr-str`) enters the
  conversation, not raw file dumps.
- **REPL persistence as external memory** — the SCI context lives for the
  room. `(def logs (cat "big.log"))` in one call, query `logs` in later calls;
  the 50k-line file never enters context.
- **One small tool schema** — a single ~90-token description instead of
  fat per-command schemas.

## Sandbox model

SCI is allowlist-only: scripts get `clojure.core` (+ `clojure.string` as
`str`, `clojure.set` as `set`, `clojure.walk` as `walk`, `clojure.edn` as
`edn`) and the injected helpers below. **No `js/` interop, no other I/O.**

The interop lockdown is load-bearing for the whole sandbox — reaching
`js/Function` would run arbitrary host code and defeat every path/sh gate:

- Configured classes (`Math`, `Date`, `Long`, `Instant`, …) are exposed as
  **null-prototype** objects holding only their intended static members, so
  `Class/constructor` reads `undefined` instead of a real constructor (SCI's
  cljs static-member access is an unchecked property read). Instance interop
  (`.getTime` on a `#inst`, …) is unaffected — it keys on the class name.
- Raw JS-property access and dynamic eval are **removed** (`:deny`): `aget`
  `aset` `unchecked-get`/`-set` `js-obj` `js-invoke`, `eval` `load-string`,
  and the var/namespace-manipulation fns (`intern` `resolve` `alter-var-root`
  …). These would otherwise sidestep the class gating or re-enter the reader.
- Instance-method interop through the class config still throws on anything
  not on a configured class (e.g. `(.-constructor "s")`).

See `test/xi/ext/clj_sandbox_test.cljs` for the escape corpus these close.

| Helper | Does |
| --- | --- |
| `(cat f)` / `(slurp f)` | read file as string |
| `(ls d?)` | dir entries (dirs suffixed `/`) |
| `(head f n?)` `(tail f n?)` | first/last n lines (default 10) as vector |
| `(glob "src/**/*.clj")` | glob under cwd |
| `(grep re path?)` | ripgrep (`rg -n`), regex or string |
| `(find pat dir?)` | `fd` |
| `(spit f s opts?)` | write file (`{:append true}` supported) |
| `(mkdir d)` `(cp a b)` `(mv a b)` `(rm f …)` | fs ops (`rm` force-deletes within cwd/tmp, recursive, no-op if missing) |
| `(tmpdir)` | fresh `/tmp/xi-clj-*` dir — never needs cleanup |
| `(stat f)` | file metadata → `{:size :dir? :file? :mode :mtime-ms :mtime :ctime}` |
| `(realpath p)` | canonical path (readlink -f) |
| `(which "cmd")` | PATH lookup → absolute path or nil |
| `(basename p ext?)` `(dirname p)` | path components |
| `(touch f)` | create file / bump mtime (write-guarded) |
| `(now)` | current time as ISO-8601 string |
| `(cwd)` `(env "KEY")` | cwd; env restricted to the sandbox env allowlist |
| `(ports port?)` | **pre-approved** listening-socket lister (`ss -lntupH` under the hood) — `(ports)` → vector of `{:proto :addr :port :process :pid}` for every listening TCP/UDP socket; `(ports 7474)` filters to that port. `:process`/`:pid` are nil for sockets owned by other users |
| `(curl url opts?)` | HTTP request → `{:status :body}` — http(s) only (no `file://`); opts: `{:method :headers :body :max-time}` |
| `(jq filter input opts?)` | **pre-approved** jq — pipes `input` (a JSON string, or any Clojure value, encoded to JSON) to jq on stdin (no tmp file). Default parses jq's output → Clojure data (keywordized keys; one value → the value, many → a vector, none → nil). Opts: `{:raw true}` returns `jq -r` text as a trimmed string; `{:args ["--arg" "k" "v"]}` adds flags |
| `(git "status" "--short")` | **pre-approved** git — stdout string on exit 0, throws otherwise; `push`/`clean` refused (→ `(sh "git" …)`) |
| `(sh "cmd" "arg" …)` | run a real CLI — **gated**, see below. Returns stdout string on exit 0 (falls back to stderr when stdout is empty — ffmpeg-style tools); throws on non-zero exit with `{:exit :out :err}` in ex-data |

Path arguments (including glob patterns) expand a leading `~` and a leading
`$VAR` / `${VAR}` for env vars on the sandbox allowlist — so
`(glob "$HOME/.cache/**/*.edn")` and `(cat "~/notes.md")` both work. There is
no JVM: `System/getProperty` and other Java interop don't exist here.

Guards, enforced inside every helper:

- **Reads** (`cat`/`slurp`/`ls`/`head`/`tail`/`stat`/`realpath`/`grep`/`find`/
  `glob`/`cp`-source): the room cwd and the OS tmp dir are readable freely. A
  **literal** path that escapes both raises the same outside-repo approval
  dialog the `read` tool uses — [y]/[n], plus [r] *allow all reads from this
  repo* when the target sits inside another git repo (the [r] answer persists a
  repo-scoped session allow-rule in the shared rules store — `{:match {:tool
  :read :repo <root>} :action {:type :allow}}`; a `write`-repo grant is stored
  as `{:tool #{:write :edit} …}`, so it also covers reads and an already-approved
  write repo is auto-allowed). The gate statically scans
  the code for these helpers' read-target args (for `glob`, the literal base dir
  before the first `* ? [ {` metacharacter, so `/etc/**` and `../x/*` are gated
  too), approves the out-of-repo ones, and injects the approved roots into the
  worker so its `resolve-read` allows them. A **dynamic** (computed, non-string)
  out-of-repo path is invisible to that pre-scan, so it raises the same dialog
  **at runtime** instead: the worker posts a `gateRequest` to the main thread
  and blocks (`Atomics.wait` on a per-request `SharedArrayBuffer`, waking on
  the eval's abort flag) until the dialog is answered; on allow the approved
  root is written back into the buffer and added to the worker's allowed reads,
  on deny the read throws ("user denied reading outside the repo"). Headless
  (no confirmer attached) auto-approves, matching the static gate. Credential paths
  (`xi.paths/hidden-paths` — `~/.ssh`, `~/.gnupg`, …) are always blocked,
  symlink-canonicalized, even inside an approved repo.
- **Writes** (`spit`/`mkdir`/`cp`/`mv`/`touch`/`rm`): the room cwd and the OS
  tmp dir are writable freely. A **literal** path that escapes both raises the
  same outside-repo approval dialog the `write`/`edit` tools use — [y]/[n], plus
  [r] *allow all writes to this repo* when the target sits inside another git
  repo (the [r] answer persists a repo-scoped session allow-rule in the shared
  rules store — `{:match {:tool #{:write :edit} :repo <root>} :action {:type
  :allow}}`, so the grant covers edits too and is visible to `/rules`). The gate
  statically scans the code for these helpers'
  write-target args, approves the out-of-repo ones, and injects the approved
  roots into the worker so its `resolve-write` allows them. A **dynamic**
  (computed, non-string) out-of-repo path raises the same dialog **at runtime**
  via the worker’s `gateRequest` round-trip (see reads above); on deny the
  write throws ("user denied writing outside the repo").
- **Directory deletion**: the builtin `(rm dir)` recursively deletes a whole
  tree, so the gate statically scans `rm`'s literal path args and, for any that
  resolve to an **existing directory**, raises a confirm before the eval runs —
  the prompt calls out when the target is *outside the project repo*. Files are
  unaffected (auto-run). A `[y]` on an out-of-repo directory also injects it as
  an approved write root so `resolve-write` permits the delete. **Dynamic**
  (computed) `rm` paths are invisible to this scan — an in-repo dynamic
  directory delete isn't pre-confirmed; an out-of-repo one falls through to
  the runtime write gate above (approval dialog, no tree-deletion-specific
  prompt). Shelling a dir delete out —
  `(sh "bb" "-e" "(fs/delete-tree …)")` or a bash `fs/delete-dir`/`fs/delete-tree`
  — is caught separately as a guarded pattern.
- **Env**: only `xi.paths`' env allowlist; secret-bearing keys throw.
- Printed output is captured; results are truncated (30k chars; `sh` /
  `grep` / `curl` output at 20k). On truncation the full text is saved to
  `$TMPDIR/xi-output/clj-….log` and the marker names that file with a nudge
  to `(grep re f)` / `(tail f n)` / `read` it instead of re-running
  (`xi.tools.truncate`; the bash tool does the same, keeping the tail).

Quick scripts: `(spit (str (tmpdir) "/x.py") src)` then `(sh "python3" …)` —
the interpreter needs allowlisting like any other CLI.

## `(sh …)` permissions

`sh` is argv-style only (`(sh "ffmpeg" "-i" x)`) — no shell strings, so no
quoting/pipe smuggling. `(sh "bash" "-c" …)` is rejected with a teaching
error. To run in another directory, pass a bb-style **leading opts map** with
`:dir` (relative to the room cwd, absolute, or `~`-prefixed) instead of
`cd … &&` chains: `(sh {:dir "sub/project"} "bb" "build")`. The dir must
exist and is gated like a read — an out-of-repo `:dir` raises the same
approval dialog as reading there. Approval happens **before** eval, in the
`:tool-gate`:

1. The code is parsed (edamame) and all `(sh …)` call sites collected.
2. Literal command names are checked against the **global allowlist**
   (`~/.config/xi/ext/clj.edn` → `:allow-clis`, see
   [config.md](config.md#clj-tool-sandboxed-clojure-allowlist)) and the
   **shared rules engine** — each scanned command is modeled as a synthetic
   `{:tool :sh :cli <binary> :command <cmd>}` request and run through
   `rules/first-match` over the ordered ruleset (hardened tier + config +
   session/server runtime + defaults). The request carries the effective
   cwd's git root as `:repo`, so `:repo`-scoped rules match. A rule `:deny`
   blocks it (with the rule's message). A rule `:allow` grants at the rule's
   granularity: one **without** a `:command` pre-approves the binary for the
   eval; an **arg-scoped** one (`:command` or `:within`) grants only that
   exact, fully-literal command (injected as `:_allowed-commands`). An
   arg-scoped allow
   never covers other calls to the same CLI in the eval: if any `(sh …)`
   call to that CLI isn't granted, or has a dynamic arg, the CLI still goes
   through approval. This is how the built-in `sed -n '<range>p' file` and
   in-repo `mv`/`cp`/`mkdir`/… defaults run without making `sed -i` or
   `mv ~/x /etc` runnable. Session
   allow grants (`/clj allow`, or an `:always` answer) are stored here as
   session allow-rules, so they show up in `/rules` — there is no separate
   private allowlist. (See [rules.md](rules.md).)
3. Unknown commands raise a confirm dialog per binary — *allow once /
   always (= rest of session) / deny*. Parse errors block eval (the gate and
   the evaluator must agree on what runs).
4. The approved set is injected into the tool call (`:_allowed`, plus the
   exact-command grants in `:_allowed-commands`); `sh` re-checks it at
   runtime (binary in `:_allowed`, or the space-joined argv in
   `:_allowed-commands`). So a *dynamically computed* command name that was
   never approved fails with instructions to use a literal.

With no client attached (e.g. `xi prompt`), confirms resolve to deny.

`(sh …)` to a CLI that has a builtin helper (`HELPER_EQUIV`) never raises an
approval dialog. It splits into two tiers:

- **Auto-run + hint** (`SAFE_AUTORUN`): read-only CLIs (`ls`, `cat`, `head`,
  `tail`, `grep`/`rg`, `find`/`fd`, `pwd`, `echo`, `mktemp`, `git`, `stat`,
  `du`, `readlink`/`realpath`, `which`, `basename`, `dirname`, `date`,
  `ss`/`netstat`/`lsof`, and
  the text-pipeline CLIs `wc`, `sort`, `uniq`, `cut`, `tr`) run anyway —
  bouncing would cost the model a retry turn for no safety gain. The result
  comes back normally with a hint appended ("prefer the builtin helpers
  over sh: `ls` → (ls dir), …") teaching the helper for next time.
  `rm` is the one write CLI in this tier: `(sh "rm" …)` is auto-allowed —
  including `rm -rf`, which is exempted from the guarded-pattern confirm here
  (bash's `rm -rf` stays guarded) — so deleting scratch files never needs
  approval. The hint points at `(rm f)`; when the target is under `/tmp` it
  also notes the deletion is usually unnecessary since `/tmp` is temporary.
  The builtin `(rm …)` helper is the same: deleting a **file** is auto-run,
  but deleting an existing **directory** (a recursive tree delete) always
  raises a confirm — see *Directory deletion* below.
  `ss` auto-runs only for its read-only uses: `-K`/`--kill` (destroys
  matching sockets) and `-D`/`--diag` (writes a raw socket dump to an
  arbitrary file, bypassing the write guards) escalate to the normal
  per-CLI approval dialog instead (`ss-escalated-command?`, bundled short
  flags like `-tK` included).
- **Approval + hint**: write CLIs (`mkdir`, `cp`, `mv`, `touch`, `sed`,
  `awk`) and network CLIs (`curl`, `wget`) aren't auto-run — raw `sh` would
  bypass the helpers' write-path and http(s)-only guards — but they're **not
  hard-blocked** either. They fall through to the normal per-CLI approval
  dialog (like any other non-allowlisted CLI), carrying the helper hint that
  points to the guarded helper (`(mkdir d)`, `(curl url)`,
  `(str/replace …)` + `(spit …)`, …). So the nudge is a warning, not a
  dead-end error that wastes a turn — approve it and the raw command runs.

Allowlisting the CLI (globally, `/clj allow`, or any rules-engine `:allow`)
skips the approval dialog and runs it dialog-free when its real flags are
needed.

If the appended hints degrade model output (noise, the model parroting the
hint, …), disable them without losing the auto-run behavior by setting
`:helper-hints false` in `~/.config/xi/ext/clj.edn`:

```clojure
{:allow-clis   #{"bb" "jq"}
 :helper-hints false}
```

Auto-run CLIs then still run dialog-free — the result just comes back
clean. To silence a *specific* CLI entirely (no hint, no bounce), add it
to `:allow-clis` instead; to turn the whole tool off, `/ext disable clj`
(which restores the bash tool).

### The `git` helper

Git is by far the most common CLI in a coding session, so it gets a
dedicated pre-approved helper — `(git "log" "--oneline" "-15")` runs with
no approval dialog and returns the stdout string (throws with stderr on
non-zero exit). Deny-listed subcommands (`GIT_DENY`: `push` — remote
mutating + guarded, `clean` — deletes untracked files) are refused **at
runtime** (so dynamically computed subcommands are covered too) with a
pointer to `(sh "git" …)`, which walks the normal approval flow — and
`git push` additionally hits the guarded-pattern confirm. The gate’s
helper bounce for `(sh "git" …)` is skipped when a deny-listed subcommand
is present, so escalation isn’t a dead loop. Subcommand detection skips
option-with-value globals (`-C`, `-c`, `--git-dir`, …) so `git -C dir push`
can’t sneak past.

## Background processes (`process/…`)

Long-running commands — dev servers, watchers, slow builds, anything that
outlives a single eval — use the `process` namespace instead of `(sh …)`
(which is synchronous and times out) or `(sleep n)` guessing games:

| Call | Does |
| --- | --- |
| `(process/start "npm run dev")` | spawn detached → `{:pid :log}` (log = capture file) |
| `(process/start {:dir "sub"} "bb build")` | same, run in another directory (bb-style leading opts map; also on `poll-until`) |
| `(process/wait pid timeout-ms?)` | block until exit → `{:status :exited :exit :output}`, or `{:status :running …}` after the timeout (default 120s — call again to keep waiting) |
| `(process/output pid n?)` | last n log lines (default 50) |
| `(process/list)` | this room's processes → `[{:pid :command :alive? :uptime-ms :log :exit} …]` |
| `(process/stop pid)` | SIGTERM the process group → `{:pid :killed? :command}` |
| `(process/poll-until "cmd" opts?)` | rerun `cmd` until a condition holds → `{:met? :attempts :exit :output}`; opts `{:until :exit-zero\|:stdout-matches\|:stdout-not-matches :pattern "re" :interval-ms 5000 :timeout-ms 120000}` — for waiting on external state you didn't spawn |

`process/start` and `process/poll-until` take a **literal shell command
string** (bash) — optionally preceded by a `{:dir …}` opts map to set the
working directory (gated like a read; must be an existing directory) — and
are gated exactly like the rest of the tool: the
pre-scan collects the literals, walks them through the same checks as bash
commands (sudo → block, remote shells → block, command-scoped `:ask` rules →
confirm, guarded patterns → confirm, unknown CLIs → per-binary
approval with `SAFE_AUTORUN` passing free), and injects the approved strings
into the call (`:_allowed-bg`). The worker re-checks membership at runtime,
so a **dynamically computed** command was never approved and is rejected with
instructions to use a literal; command substitution (`$( )`, backticks) in
the literal is blocked at the gate for the same reason. `process/stop`/`wait`
/`output` only accept pids returned by `process/start` in this room — the
sandbox can't signal arbitrary system pids.

Mechanics: the command is spawned detached (own process group, `.unref`)
wrapped as `{ cmd } > logfile 2>&1; echo $? > logfile.exit` — stdout/stderr
go to a tmp logfile and the exit code to a `.exit` sidecar. Liveness and
outcome are read from those files, which matters because eval is synchronous:
while `wait` blocks the worker, node can't reap a dead child (it lingers as a
zombie and `kill(pid, 0)` still succeeds), so the sidecar — written by the
shell itself — is the authoritative exit signal. A trailing `&` in the
command is stripped (detachment is built in).

`wait` and `poll-until` sleep abortably: when the turn ends (ESC / abort),
the main thread flags a per-eval `SharedArrayBuffer` and the worker's
`Atomics.wait` wakes and throws, so a blocked wait can't outlive its turn.
Started processes themselves keep running — they're detached by design.

Processes are mirrored to the main thread and show up in the
`process-manager` extension's registry: `/ps` lists them, `/kill <pid|%n>`
stops one, and a room with live processes is kept alive instead of
auto-destroyed. `process-manager` no longer exposes any tools of its own —
spawning happens only through `clj`, inside the gate.

## Loopback TCP sockets (`socket/…`)

Raw TCP to **loopback** services — nREPL servers, mpv/daemon IPC, anything
speaking a line- or length-framed protocol on localhost — uses the `socket`
namespace. There is no JVM here (`java.net.Socket` doesn't exist); this is
the sandbox-native replacement.

| Call | Does |
| --- | --- |
| `(socket/connect host port opts?)` | open a TCP connection → handle `{:id :host :port}`; opts `{:timeout-ms 10000}` |
| `(socket/write s data)` | send `data` (string → UTF-8, or seq of ints 0–255) → bytes sent |
| `(socket/read s opts?)` | blocking read, see below |
| `(socket/close s)` | close + tear down the bridge |
| `(socket/open? s)` | connection still open? |
| `(socket/list)` | this worker's sockets → `[{:id :host :port :status :buffered} …]` |

`read` blocks until its condition is met (default timeout 30s, override with
`{:timeout-ms …}`), returning a string by default or a vector of ints with
`{:bytes? true}`:

- no opts — block until ≥1 byte is available, return everything buffered;
  `nil` on clean EOF.
- `{:n k}` — exactly `k` bytes (throws if the peer closes first).
- `{:until "\n"}` — everything **before** the delimiter; the delimiter is
  consumed but not returned. Bytes after it stay buffered for the next read.

```clojure
;; length-prefixed JSON framing ("<len>:<json>"):
(def s (socket/connect "127.0.0.1" 3828))
(socket/write s (let [payload (json/write-str [0 1 "ping" []])]
                  (str (count payload) ":" payload)))
(let [len (parse-long (socket/read s {:until ":"}))]
  (json/read-str (socket/read s {:n len})))
```

Guards: `connect` refuses non-loopback hosts (`localhost` / `127.x.x.x` /
`::1`) **at runtime**, so a dynamically built host string can't bypass it —
raw TCP to remote hosts stays out of the sandbox (`(curl …)` covers remote
http(s)). No approval dialog is raised: loopback IPC is the same trust tier
as the pre-approved `(ports)` helper.

Mechanics (`xi.ext.clj-socket`): SCI evals are synchronous but JS sockets
are async-only, so each `connect` spawns a **nested worker** from the same
bundle (`workerData :role "xi-socket-bridge"`, dispatched in `xi.cli/main`)
that owns the async `net.Socket`. Received bytes stream through a
`SharedArrayBuffer` ring buffer (1MB; the bridge pauses the socket when
full); the eval thread blocks with `Atomics.wait` — abortable like
`process/wait`, so ESC/turn-end wakes a blocked read — and buffers
locally so `:until`/`:n` reads stop exactly at their boundary. Writes are
`postMessage`'d to the bridge. Sockets persist across evals like the rest
of the room's REPL state and die with the room's worker (nested workers
terminate with their parent) or on `(socket/close s)`.

## No bash tool

Adding the tool isn't enough — the model keeps reaching for the familiar
`bash` tool. So the extension **removes bash from the model's tool list**
(`:remove-tools #{"bash"}`, a new extension surface: builtin tools dropped
from the provider tool defs, re-read per turn, so `/ext disable clj`
restores bash). All shell work goes through `clj`; real CLIs via `(sh …)`
with its approval/allowlist flow. The `:system-prompt` blurb states this so
the model doesn't try to call a tool it doesn't have.

Because `(sh …)` replaces bash, bash's policy is mirrored in the clj gate:

- **Remote shells** (`ssh`/`scp`/`rsync`/`sftp`) — always blocked.
- **Command-scoped `:ask` rules** — a rule whose match constrains `:command`
  (or `:within`) confirms that exact scanned command, even when its CLI is
  allowed. The `server-control` default rule on `bb serve:restart|stop` is one:
  once approved, `sh` runs it *detached* (`xi.server-control`) and returns an
  explicit result, since `sh` is synchronous and running it inline would kill
  the server hosting the agent mid-eval.
- **Guarded patterns** (`rm -rf`, `fs/delete-dir` / `fs/delete-tree`, `sudo`,
  `git push`, `kill …`) — confirm
  dialog even when the CLI is allowlisted. Pattern-matched on each call's
  joined literal argv (`:commands` from the pre-scan); dynamic args are
  invisible to this check — known gap, same class as bash string matching.

For providers that don't consume `:remove-tools` yet (openai_compat &co
build their tool list from the builtin registry only), a `chained-bash?`
gate still bounces bash commands using shell composition (`;`, `&&`, `|`,
`$( )`, backticks, multi-line, leading `VAR=`) to the clj tool; single plain
commands pass.

## The `bb` tool + bb.edn trust

Running this project's build/test/serve tasks is common enough that `bb` gets
a dedicated tool (alongside `clj`), gated by **trusting the project's `bb.edn`
by content-hash** instead of allowlisting the bare `bb` CLI.

- **Tool**: `bb` — input `{"task": "test"}` runs `bb test`; `{"args": ["…"]}`
  appends extra CLI args; omitting `task` runs `bb tasks` (the task list).
  Output is the captured stdout/stderr, truncated like `(sh …)`.
- **Trust store**: `~/.config/xi/ext/bb-trust.edn` — `{:shas #{"<sha256>" …}}`.
  A `bb.edn` is trusted when its sha256 is in the set. Content-addressed, so a
  copied `bb.edn` is trusted too, and **editing `bb.edn` auto-revokes trust**
  (the sha no longer matches) until re-trusted.
- **Granting trust** — two ways:
  - `/clj trust-bb` — hashes the nearest `bb.edn` (walking up from the room
    cwd) and records its sha.
  - The first-run approval dialog for `bb`: its **"always"** answer records the
    `bb.edn` sha (not the bare `bb` CLI); plain "yes" runs once.
- **Scope of the hash**: only `bb.edn` itself — its inline tasks, `:init`, and
  `:requires`. Task code that lives in *separate* files is outside the hash.
- **Gating parity**: `bb serve:restart`/`serve:stop` are asked by the
  `server-control` rule even on a trusted `bb.edn` (rules run before the
  trust check), and the bb tool always runs them detached
  (`xi.server-control`) — trust never lets them run inline and kill the host
  server. Guarded patterns still confirm. With no client attached, an untrusted `bb.edn` is blocked.
- `(sh "bb" …)` inside `clj` shares the same trust check: a trusted `bb.edn`
  makes `bb` an allowed CLI for the eval, and an untrusted one's "always"
  approval records the sha rather than session-allowlisting the string.

## Commands & state

- `/clj` — status: global + session allowlists, bb.edn trust state
- `/clj allow <cli>` / `/clj revoke <cli>` — add/remove a session allow-rule
  for that CLI (stored in the rules engine, visible in `/rules`)
- `/clj trust-bb` — trust the current project's `bb.edn` (records its sha)
- `/clj reset` — drop the room's REPL context (defs, loaded data)
- `/ext disable clj` / `enable clj` — runtime kill switch

Session-allowed CLIs are stored as session rules in the rules engine
(`[:rooms rid :ext :rules :rules]`, shape `{:tool :sh :cli "…"} → :allow`),
mirrored to clients — the clj extension keeps no private allowlist. The SCI
contexts themselves are
runtime objects that live in the room's eval worker thread; `/clj reset` and
room destroy (`:room/close`) terminate that worker (a fresh one spawns lazily
on the next eval), and `/ext disable clj` terminates all of them.

## Implementation notes

- `org.babashka/sci` (0.15.58) in `deps.edn`; node builds only — the web
  bundle is untouched.
- Eval is **synchronous** (`sci/eval-string*` on a long-lived ctx);
  helpers use sync `node:fs` / `child_process.spawnSync` (works under Bun
  and under node, where the test target runs). This is what makes scripts
  plain imperative Clojure with no promise plumbing.
- **Eval runs in a per-room worker thread.** Because `sh`/`bb` shell out via
  that synchronous `spawnSync`, running eval on the server's single event loop
  froze every room / WS client / HTTP request for the command's whole
  duration (up to the 120s `sh` timeout) — a long or hung command read as a
  server "crash". The `clj`/`bb` tools post the request to the room's
  long-lived worker thread and await the reply: `target/main.js` re-enters
  itself as a `node:worker_threads` Worker (dispatched by `xi.cli/main`'s
  `isMainThread` guard → `xi.ext.clj-worker` → `xi.ext.clj/eval-message`).
  Workers are **one per room** (~85MB RSS each, spawned lazily on first use,
  terminated on `:room/close`): a blocking eval — `process/wait`,
  `poll-until`, a slow `bb test` — stalls only its own room, never other
  rooms' `clj`/`bb` calls. Each worker holds its own `runtimes` atom, so
  `(def x …)` persists across evals within the room; `/clj reset` and
  `/ext disable clj` terminate the worker, and a crashed/exited worker
  resolves any in-flight evals with an error and respawns lazily on the next
  call.
- The gate injects per-call data by *modifying the tool-call arguments* —
  supported since the providers execute `(or (:arguments gated) args)`.
  All three provider tool loops (claude, openai_compat, openai/responses)
  also honor `{:intercepted …}` gate results.
- `cat`/`find` shadow `clojure.core/cat`/`find` (overridden in the sci
  `clojure.core` namespace) — the shell meaning is what agents expect.
- Tests: `test/xi/ext/clj_test.cljs` (pre-scan extraction, eval, persistence,
  path guards, sh allowlist, background-process gating + lifecycle).
- The `process/*` machinery lives in `xi.ext.clj-process` (worker side);
  registry mirroring reaches the main thread via `parentPort` messages
  translated into `:ext.process-manager/register`/`deregister` events.

## Known limitations / future work

- **No hard timeout on eval** — eval already runs off the main event loop in a
  per-room worker thread (see Implementation notes), so a blocking `sh` no
  longer freezes the server, and since workers are per room a hung eval only
  serializes that room's later `clj`/`bb` calls behind it (other rooms are
  unaffected). Hardening option: a per-eval watchdog that terminates +
  respawns the room's worker (losing its REPL state).
- **Live rules** (planned, not built): scan user prompts for "do not touch X"
  phrasings and offer to inject deny-glob rules enforced in the path guards
  and the builtin-tool gate; `/rule add|list|rm`.
- The builtin `bash`/`grep`/`find`/`ls` tools still coexist (bash is
  restricted to single plain commands by the gate, see above). A later step
  could drop them from the registry when `clj` is enabled, making `clj` the
  only execution surface.
- `(env …)` uses the OS sandbox's narrow env allowlist; a confirm-based
  escape hatch for other keys could be added.
