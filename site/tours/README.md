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
| `scripts/tour-record.mjs` | Headless recorder: connects as a web client, runs `record.mjs`, then joins each earlier session for the tape's `visits`, writes the raw frames to `/tmp`, bakes. |
| `scripts/tour-bake.mjs` | Raw frames → tape: `/tmp/xi-tour-home` becomes `/home/dev`, sends keep only their type, the `:join-token` becomes a slot the player fills. Refuses to bake if a frame still names the repo or your HOME. |

## Record

```sh
bb tour:record desk     # fresh server on :1419 + record + bake → site/public/tours/desk.json
bb tour:stop            # stop the recording server
```

`bb tour <tour>` starts the server alone (HOME `/tmp/xi-tour-home`, the
tour's fake LLM), for poking at it in a browser on `http://localhost:1419`.

Changed only `steps.json`? Re-bake the last recording:
`bun scripts/tour-bake.mjs /tmp/xi-tour-desk-raw.json desk`.

## On the site

The home page's stage (`xisite.pages/stage`) is an iframe on `/tour/?tape=desk`:
the web client from the release `:tour` build (`bb tour:build` →
`target/tour/main.js`, ~260 KB gzipped; `bb site:build` runs it first), the
web client's `style.css`, and `site/public/css/tour.css`, which shrinks the
app's rem-based type one size. The iframe renders at 1280×800; `site.js`
scales it to the frame with a CSS transform, loads it once the stage is 40%
in view, and shows Replay when the tour posts `{xiTour: "done"}`: once the
tape has played out and the autopilot's last step ran, so steps may click
through the result after the stream ends (expanding a tool block mid-stream
is too busy to follow). Until a
tour frame starts, `site.js` prefetches the tour's files once the page is
idle, and `/tour/` starts fetching the tape in its `<head>` (`window.xiTape`)
rather than after `main.js` runs. In an
iframe the client ignores programmatic focus until the visitor clicks into
it, so the tour never takes the page's keyboard focus or scroll.

### Theme

The site uses the web client's default theme (`xisite.theme`, the
framework's default tokens, same as `resources/public/theme.css`), and the
site and its previews share one stored theme: the light/dark choice
(`ui-theme`) and a custom theme's CSS properties (`xi/theme-vars`), both in
localStorage on the site's origin. A change anywhere reaches every other
document through the `storage` event (`site.js`, `xi.web.embed`), so the
client's own light/dark switch inside a preview switches the page too. The
theming section (`site/public/js/theme-demo.js`) sends a hue to the stage
after it; that client derives the properties (`xi.web.theme`) and stores
them for everyone. A tour never applies or stores its own custom theme.

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

The visitor can open sessions from the sidebar. A join of an earlier
session plays the tape's `visits` entry for it (what the server answered
when the recorder joined it); a join of the tour's own session re-sends the
client's mirror of its room, which kept following the tape meanwhile. Neither
counts as a gate, and the autopilot waits while the visitor is on an earlier
session.

## Adding a tour

1. `site/tours/<name>/fake-llm.edn`: the turn. Split text into several
   `{:text}` steps with `{:sleep}` between them so it streams.
2. `record.mjs`: the sends, using `until` to wait for the server.
3. `steps.json`: the same actions as clicks, typing and key presses
   (`{"key": "Ctrl+k"}`, `{"key": "g g"}`: space-separated chords, each
   shown as a keycap; the full step list is in `xi.web.tour`), and the gate
   types.
4. `bb tour:record <name>`, then replay it as above.

A tour with no model turn (the palette, navigate mode, buffers) keeps
`fake-llm.edn` as `{}`; its `record.mjs` only connects and sends what the
client would.

- Send what the web client sends: a slash command in an open chat is
  `:command/run`, but in a new chat it waits for the room and goes out as
  `:input/submit`.
- The autopilot pauses while the visitor is on an earlier session, and so
  does every step that waits for an element (`waitText`, `click`). A slide
  that opens earlier sessions on purpose (link hints) uses `wait` and `key`
  steps after it.
- The tour HOME binds `f` to link hints and gives zsh a plain prompt
  (`scripts/tour-seed.mjs`).
- A sub-agent the client spawns (the diff view's Ask) is matched by its
  `:sub-id`, filled in like the join token.
- Terminal tours: the player hands `:terminal/output` to xterm.js and gates
  on the client's `terminal/attach`, so the recorder attaches at the size
  the replay's terminal has (126×45 in the 1280×800 frame) and types a key
  at a time. In replay a new chat's own route change overrides the server's
  switch to the terminal, so the steps open it from the view menu
  (`.buffer-menu-trigger`). The site serves xterm.js at `/vendor/xterm/`
  (`xisite.build/tour-files`).

## Release videos

Under a release's heading on `/changelog/`, a slideshow plays a few short
tours in one stage, the most important features and UI fixes of that
release. `releases.edn` lists them per version:

```clojure
{"0.4.0" [{:tour "0.4.0-palette" :title "Switch themes from the palette"}
          {:tour "0.4.0-navigate" :title "Navigate mode: j/k, gg, SPC"}]}
```

Each slide is an ordinary tour (`site/tours/<tour>/`, recorded with
`bb tour:record <tour>`), named after its release. Keep one to ~20 seconds
showing one thing; the slideshow (`xisite.pages/slideshow`, `site.js`)
shows its title as it starts and moves to the next slide 2.5 s after the
tour is done, with Replay after the last. The chips below the stage jump to
a slide. The `xi-release` skill asks which changes go in when cutting a
release.

Tapes replay in the current web client, so a protocol change can break an
old release's slides: re-record them with `bb tour:record <tour>`.
