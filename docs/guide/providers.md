# Providers

A provider connects Xi to models. Each one is a program Xi starts for every
message; Xi keeps the tools, the rules and the chat. Xi ships Claude,
Ollama, OpenAI Codex and OpenCode ([Models](models.md)). Which ones run is
`:providers` in [`config.edn`](configuration.md#providers).

## Add one

A provider is a directory, a published npm package, or one Xi bundles:

```clojure
:providers [:anthropic
            {:dir "~/code/my-provider"}
            {:npm "xi-provider-example@1.2.0"}]
```

The order is the order of the model picker. Restart Xi after changing the
list. An edit to a provider's program applies on the next message.

## Write one

A provider directory has a manifest and a program. With Bun and the
provider kit, `package.json` holds both the dependencies and the manifest:

```json title="~/code/my-provider/package.json"
{
  "name": "my-provider",
  "type": "module",
  "dependencies": { "@xi-agent/provider-kit": "0.1.0" },
  "xi": {
    "id": "local",
    "label": "My models",
    "command": ["bun", "main.ts"],
    "routes": { "prefixes": ["local/"] },
    "models": ["local/small"]
  }
}
```

```ts title="~/code/my-provider/main.ts"
import { serve } from "@xi-agent/provider-kit";
import { chatCompletionsTurn } from "@xi-agent/provider-kit/openai-compat";

serve({
  turn: (req, io) =>
    chatCompletionsTurn(req, io, {
      baseUrl: (req.options.baseUrl as string) ?? "http://localhost:8080",
      modelId: (m) => m.replace(/^local\//, ""),
      label: "My models",
    }),
});
```

Run `bun install` in the directory, add `{:dir "~/code/my-provider"}` to
`:providers` and restart. `/model local/small` now runs on it, with every
tool the chat has. Keys besides `:dir` reach the program as `req.options`
(`:base-url` becomes `baseUrl`).

`routes` says which model ids are this provider's: `prefixes`, exact `ids`,
or `"default": true` for every id no other provider claims.

The kit also has loops for the OpenAI Responses API and the Anthropic
Messages API. The bundled providers in
[xi-providers](https://github.com/floscr/xi-providers) use all three.

## In another language

Any program that speaks the protocol works: it reads the request as one
JSON line on stdin and writes JSON lines to stdout, ending with `done`.
Outside npm the manifest is a `provider.edn` with the same keys:

```clojure title="~/code/bb-provider/provider.edn"
{:id      :mine
 :command ["bb" "main.clj"]
 :routes  {:prefixes ["mine/"]}
 :models  ["mine/echo"]}
```

Every frame and manifest key is in [the provider protocol](../provider-protocol.md).
