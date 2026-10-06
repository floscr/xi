# The clj tool

Xi agents run sandboxed Clojure instead of single-line shell commands. The
`clj` tool is a Clojure REPL with file helpers that runs inside Xi, so commands
are readable scripts rather than pipe chains. They filter and aggregate in the
runtime, real commands run one at a time, and only the result enters the
conversation.

## What an agent executes

```clojure
;; count TODOs per file — only the summary comes back
(->> (glob "src/**/*.cljs")
     (map (fn [f] [f (count (re-seq #"TODO" (cat f)))]))
     (filter (fn [[_ n]] (pos? n)))
     (into {}))
;; => {"src/app/core.cljs" 3, "src/app/db.cljs" 1}
```

Instead of a piped shell command:

```sh
grep -c TODO $(find src -name '*.cljs') | grep -v ':0$'
```

The REPL is persistent for the chat: something `def`'d in one call is there
in the next. A large log file can be loaded once and queried many times
without ever being pasted into the conversation.

There is no `bash` tool while `clj` is on. The agent runs real programs
through `sh`, argv-style:

```clojure
(sh "ffmpeg" "-i" "talk.mp4" "-vn" "talk.mp3")
(sh {:dir "packages/api"} "bb" "test")
```

No shell strings, so no quoting tricks and no hidden pipes. Each call names
one program, and you see exactly which.

## The helpers

| Helper | Does |
| --- | --- |
| `cat` `head` `tail` `ls` `glob` `grep` `find` `stat` | Read files and directories |
| `spit` `mkdir` `cp` `mv` `rm` `touch` | Write files and directories |
| `git` | Run git; `push` and `clean` are refused here and go through `sh` |
| `curl` | HTTP requests |
| `jq` | Query JSON, returns Clojure data |
| `ports` | Which processes listen on which ports |
| `sh` | Run a program |
| `process/start` `process/wait` `process/output` `process/stop` | Long-running commands: dev servers, watchers, slow builds |

Reads and writes inside the project and the temp directory run freely. A path
outside them asks once; `r` allows the whole repository it lives in.

## Approving commands

Every `sh` call is checked **before** the script runs:

1. Read-only programs (`ls`, `cat`, `grep`, `git`, `wc`, `sort`, …) run
   without asking.
2. A program you have allowed runs without asking.
3. Anything else shows a dialog naming the program: allow once, always for
   this chat, or deny.

Destructive commands (`rm -rf`, `git push`, `kill`) ask every time, even
for an allowed program. So does running a script or inline code through an
interpreter (`bb -e`, `node -e`, `python x.py`), because the gate cannot see
what the script does.

To allow a program for good, on every project:

```clojure
;; ~/.config/xi/ext/clj.edn
{:allow-clis ["ffmpeg" "jq" "pandoc"]}
```

Or as a [rule](rules.md), which can also be narrowed to a command line:

```clojure
{:match  {:tool :sh :cli "bb" :command #"^bb -f scripts/"}
 :action {:type :allow}}
```

In a chat, `/clj allow <cli>` allows a program until the chat ends, and
`/clj` shows what is currently allowed.

## The bb tool

Running a project's Babashka tasks is common enough that `bb <task>` is its own
tool. It is gated by trusting the project's `bb.edn` rather than the `bb`
program: the first run asks, "always" records that file, and editing the file
asks again. `/clj trust-bb` does the same without waiting for a call.

## Background processes

A dev server or a long build runs in the background:

```clojure
(def p (process/start "npm run dev"))     ; → {:pid … :log …}
(process/output (:pid p))                 ; last lines of its log
(process/stop (:pid p))
```

`/ps` lists a chat's processes and `/kill` stops one. A chat with a running
process is kept open.

## Turning it off

`/ext disable clj` removes the tool for the next turn and gives the agent a
plain `bash` tool instead. `/ext enable clj` brings it back.

## Reference

Every helper, option and gate: [clj tool reference](clj-tool-reference.md).
