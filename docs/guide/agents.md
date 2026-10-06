# Agent profiles

Run Xi as an assistant with a fixed set of tools and its own instructions
instead of as a coding agent. A profile is a few lines of config; the sessions
it creates are kept apart from your coding chats.

## A profile

```clojure
;; ~/.config/xi/config.edn
{:type    :xi/config
 :version 1
 :agents
 {"research" {:system-prompt "You answer questions with sources. Search before you answer."
              :extensions    ["hn.cljs"]
              :tools         ["web_search" "fetch" "hn_search"]}}}
```

```sh
xi --agent research              # terminal
xi server --headless --agent research --port 7475
xi prompt --agent research "what changed in Bun 1.3?"
```

| Key | Does |
| --- | --- |
| `:tools` | The tools the model gets, by name, or `:all`. A tool not listed is not offered, so it cannot be called. **No key means no tools.** |
| `:extensions` | The extension files this profile loads, instead of the top-level list. `[]` loads none. |
| `:system-prompt` | The instructions. They replace the project's `AGENTS.md`, skills and extension prompts entirely. |
| `:system-prompt-file` | The same, read from a file; relative paths are under `~/.config/xi/`. |
| `:model` | The model to use, unless `--model` says otherwise. |

The profile is read when a chat starts, so an edit applies to the next one.

## What a profile changes

- **Only the listed tools exist.** Without `read`, `edit` or `clj` in the list,
  the model cannot touch files or run programs at all. A typo in the list
  removes a tool; it never adds one.
- **Sessions live in `~/.config/xi/personal-agent/<id>/`**, one directory per
  profile. A server started with `--agent` shows only those; the web client
  shows a flat list with no projects.
- **Rules still apply.** A listed tool may run only if the [rules](rules.md)
  allow it. A rule can single out a profile with `:when {:agent {:id "research"}}`.
- **No pairing.** An agent server does not ask new browsers for a pairing
  code. Run it only where the network is trusted.

## From other programs

A profile plus [`xi prompt`](prompt.md) is how another program holds a
conversation with Xi:

```sh
xi prompt --agent research --json "find three sources on X"
# → {"session-id":"0198…","text":"…"}
xi prompt --agent research --session 0198… --json "summarise the second one"
```

## When something is off

**The model says it has no tools.** The profile has no `:tools` key, or the
config file is invalid (Xi prints why on startup). Both mean no tools, on
purpose.

**My coding extensions show up in the assistant.** Add `:extensions` to the
profile; without it, the top-level list loads.

## Reference

[Agent profiles in the CLI reference](../cli.md#agent-profiles).
