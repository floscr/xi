# Testing

Two suites:

| Task | What | Where |
| --- | --- | --- |
| `bb test` | Unit tests: `cljs.test`, pure functions, no processes. ~1100 tests. | `test/xi/**/*_test.cljs` (build `:test`) |
| `bb test:e2e` | End-to-end scenarios: the real `bun target/main.js` in throwaway HOMEs against a scripted fake LLM. ~10s. | `test/xi/e2e/*_e2e.cljs` (build `:e2e`) |

Both print one line when everything passes and only the failures otherwise;
`--full` streams the raw output. `bb test:e2e` uses the `target/main.js` the
shadow watch keeps fresh and compiles it first only when no watch runs.

## The fake LLM (`xi.providers.fake`)

With `XI_FAKE_LLM=<script.edn>` set, the fake is the only provider in
`xi.cli/providers`, under `:anthropic`. Side turns (titles, summaries,
compaction) ask for `:anthropic` by id, and `xi.agent/resolve-provider`
falls back to it for every other model (`qwen3:8b`, `opencode/…`), so no
turn reaches a model. xi prints `[fake-llm] …` on stderr at startup. The
model picker lists nothing (the fake has no `:list-models!`).

The script is re-read every turn, so a test can swap it between turns:

```clojure
{:rules   [{:when  {:prompt "edit the readme"}            ; substring of the prompt
            :reply [{:thinking "…"}
                    {:tool "edit" :args {:path "README.md"
                                         :edits [{:oldText "a" :newText "b"}]}}
                    {:text "Done: {{tool-result}}"}]}
           {:when {:prompt "slow"} :reply [{:text "a"} {:sleep 5000} {:text "b"}]}
           {:when {:prompt "boom"} :reply [{:error "fake 529"}]}]
 :default [{:text "ok"}]           ; main turns no rule matched
 :side    [{:text "A Title"}]}     ; side turns no rule matched
```

`:when` keys, all optional, all must hold, first matching rule wins:
`:prompt` (substring), `:re` (regex string), `:model` (substring),
`:provider` (the provider the model would use without the fake, e.g.
`:ollama` for `qwen3:8b`), `:system` (substring), `:user` (the
turn's user), `:tool` (a tool advertised this turn), `:side?`. Rules without
`:side? true` never match side turns (the title turn quotes the user's
prompt and would match otherwise).

Steps: `{:text s}`, `{:thinking s}`, `{:tool name :args m}`, `{:sleep ms}`
(abortable), `{:error s}` (reports a provider error, ends the turn),
`{:usage m}`. Strings expand `{{tool-result}}` (last tool result),
`{{prompt}}`, `{{cwd}}`, `{{user}}`.

What the fake does like a real provider:

- **Tool calls run the real path**: the rules engine (`:tool-policy`), the
  registry with extension tools and holds (`xi.tools.registry/execute-call`).
  A tool not advertised this turn (agent `:tools`, extension removals) fails
  with `Unknown tool: name`, as the API would refuse it.
- **Sessions**: main turns are written as a Claude-format transcript under
  `$CLAUDE_CONFIG_DIR|~/.claude/projects/<cwd>/<sid>.jsonl` and the id is
  reported via `:on-session`, so `--session` and a server's session resume
  see earlier turns. A resume id with no transcript
  answers `:resume-failed`, which re-runs the turn fresh.
- **Abort** wakes a `{:sleep}` and ends the turn as aborted.

`XI_FAKE_LLM_LOG=<file>` appends one JSON line per turn: `provider`,
`model`, `prompt`, `system`, `tools` (advertised names), `side?`, `user`,
`cwd`, `session-id`, `resume-session-id`, `transcript` (prior messages of a
resumed session), `history` (what the host replayed), `rule`, `tool-calls`
(name, arguments, content, is-error) and `stop-reason`. Assert on what the
model *saw* through it.

Without the harness: `XI_FAKE_LLM=script.edn bun target/main.js prompt "hi"`.

## The e2e harness (`xi.e2e.harness`)

Every scenario gets a temp root: `home/` (HOME and XDG_*), `project/` (a
fresh git repo with `README.md` and an `AGENTS.md` holding
`E2E-PROJECT-INSTRUCTIONS`), the script and the log. The child env drops
every `XI_*` variable and provider credential of the calling shell; it points
`OLLAMA_BASE_URL` and `XI_PORT` at port 9, so nothing leaks to a real
model or to the developer's own server on :7474.

| Helper | Does |
| --- | --- |
| `with-env! files script done f` | Fresh env, run promise-returning `(f env)`, fail on rejection, clean up, `done` |
| `prompt! env text & flags` | `xi prompt …` → `{:code :stdout :stderr :json}` |
| `run-xi! env args opts` | Any `xi` command (`:env`, `:stdin`, `:timeout-ms`) |
| `start-server! env` | `xi server --headless` on a free loopback port → `{:port :stop! :output}` |
| `connect! server opts` | WS client with the seeded trusted key (or `:key`, `:user`) → `{:events :send! :close!}` |
| `await-event client pred opts` | First event matching `pred` after `:from` |
| `http! server path opts` | HTTP request → `{:status :body}` |
| `llm-log env`, `main-turns env` | The fake's log (all turns / without side turns) |
| `path`, `home-path`, `slurp`, `files-under` | Inspect the project and HOME afterwards |

## Profiles (`xi.e2e.profiles`)

A profile is the `~/.config/xi` a user starts with, as data: a map of path
(relative to HOME) → content.

| Profile | Setup |
| --- | --- |
| `bare` | Nothing: a first-run user |
| `minimal` | `config.edn`, empty `rules.edn` |
| `extended` | The `notes` demo extension, the roles tutorial's `board.cljs`, one unreadable extension |
| `(team)` | The roles tutorial verbatim (read from the page): alice admin, bob guest, per-role rules, board |
| `locked-down` | Programs denied, edits ask |
| `agent` | An `:agents` profile `helper` with a prompt and two tools |
| `broken` | Unreadable `config.edn` and `rules.edn` |

## Writing a scenario

Add a `deftest` to the matching `*_e2e.cljs` (or a new `xi.e2e.<area>-e2e`
namespace; the `-e2e` suffix is what the build picks up):

```clojure
(deftest notes-are-saved
  (async done
    (h/with-env! profiles/extended
      {:rules [{:when {:tool "notes_add"}
                :reply [{:tool "notes_add" :args {:text "milk"}} {:text "{{tool-result}}"}]}]}
      done
      (fn [env]
        (-> (h/prompt! env "note it")
            (.then (fn [{:keys [code stdout]}]
                     (is (= 0 code))
                     (is (= "added\n" stdout)))))))))
```

Prompt mode answers every permission dialog with no, so an `:ask` rule
denies there; drive a server with `connect!` to answer dialogs. The default
tools have no `bash`: programs run through the `clj` tool's `(sh …)`.
