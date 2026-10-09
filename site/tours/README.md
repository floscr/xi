# Site tours

Live examples of the web client for the home page. A tour is the real web
client replaying a recorded **tape** of server frames: real views, real
handlers, no server behind it. The visitor can take over at any point the
autopilot would act (click Allow before it does, for example).

## Files

| Path | What |
| --- | --- |
| `<tour>/fake-llm.edn` | What the model "says" and which tools it calls (format: `xi.providers.fake`). The tool calls run for real against the seeded project. |
| `<tour>/record.mjs` | The client's side while recording: the events the web client would send (new chat, submit, dialog answers). |
| `<tour>/steps.json` | `gates` (sends the replay waits for), `steps`, which the autopilot plays in the visitor's place (see `xi.web.tour`), and `variants`: other runs of the same tape, picked with `?variant=` (`{steps, pointer: "touch", mirror: true}`). |
| `../public/tours/<tour>.json` | The baked tape the site serves. |
| `scripts/tour-seed.mjs` | The throwaway HOME `/tmp/xi-tour-home`: projects `acme-web` and `acme-api`, earlier sessions, the trusted client key. |
| `scripts/tour-record.mjs` | Headless recorder: connects as a web client, runs `record.mjs`, writes the raw frames to `/tmp`, bakes. |
| `scripts/tour-bake.mjs` | Raw frames → tape: `/tmp/xi-tour-home` becomes `/home/dev`, sends keep only their type, the `:join-token` becomes a slot the player fills. Refuses to bake if a frame still names the repo or your HOME. |

## Record

```sh
bb tour:record desk     # fresh server on :7479 + record + bake → site/public/tours/desk.json
bb tour:stop            # stop the recording server
```

`bb tour <tour>` starts the server alone (HOME `/tmp/xi-tour-home`, the
tour's fake LLM), for poking at it in a browser on `http://localhost:7479`.

Changed only `steps.json`? Re-bake the last recording:
`bun scripts/tour-bake.mjs /tmp/xi-tour-desk-raw.json desk`.

## On the site

The home page's stage (`xisite.pages/stage`) is an iframe on `/tour/?tape=desk`:
the web client from the release `:tour` build (`bb tour:build` →
`target/tour/main.js`, ~260 KB gzipped; `bb site:build` runs it first), the
web client's `style.css`, and `site/public/css/tour.css`, which shrinks the
app's rem-based type one size. The iframe renders at 1280×800; `site.js`
scales it to the frame with a CSS transform, loads it once the stage is 40%
in view, and shows Replay when the tour posts `{xiTour: "done"}`. In an
iframe the client ignores programmatic focus until the visitor clicks into
it, so the tour never takes the page's keyboard focus or scroll.

### Phones

The web-client section's two phones (`xisite.pages/phone`) replay the same
tape at 390×844 in the app's mobile layout, at its own type size:

- `?variant=phone` taps through the chat with a touch dot instead of a
  pointer.
- `?variant=list` stays on the home dashboard and **mirrors**: it has no
  steps and passes its gates on the chat phone's sends. Each tour posts
  `{xiTour: "sent", type}`; `site.js` relays it to the other phones of the
  `.phones` group as `{xiTourSent: type}` (and replays missed ones when a
  frame posts `ready`). So the new session shows up in the list, turns
  "needs response" while the chat waits on Allow, and settles when it ends,
  in step with the chat.

The pair starts together once 40% in view and loops 5 s after both are done.

## Replay on the dev build

With the web watch and `bb site:dev` running:

```
http://localhost:8100/?tape=http://localhost:4322/tours/desk.json
```

`?tape=desk` (no slash) loads `/tours/desk.json` from the page's own origin,
which is what the site uses.

## How replay works

`xi.web.tour/create!` stands in for `xi.client.ws-transport/create!`. Incoming
frames play back with their recorded spacing (idle gaps capped at 1.5 s), and
absolute times are shifted so "25m ago" stays 25 minutes ago. At a recorded
send of a gate type the tape waits until the client sends the same type: the
autopilot's click, or the visitor's. Other recorded sends are skipped.

## Adding a tour

1. `site/tours/<name>/fake-llm.edn`: the turn. Split text into several
   `{:text}` steps with `{:sleep}` between them so it streams.
2. `record.mjs`: the sends, using `until` to wait for the server.
3. `steps.json`: the same actions as clicks and typing, and the gate types.
4. `bb tour:record <name>`, then replay it as above.
