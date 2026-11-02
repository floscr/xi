# 3D Arduino sketches with the Xi Breadboard MCP

How I built a Toniebox replica with the help of Xi.

<!-- tag: MCP server -->
<!-- cover: /blog/3d-arduino-sketches/breadboard-3d.jpg -->

The Breadboard MCP draws a breadboard, perfboard or stripboard in 3D inside the chat: the board, the parts on it and every wire, hole by hole. The agent describes the circuit as data, and the view renders it. It's an MCP server in [xi-extensions](https://github.com/floscr/xi-extensions/tree/main/mcp/breadboard), so it works in Xi and in any other host that shows MCP Apps views.

![The music box's breadboard in the 3D view, with the sidebar of parts and wires](/blog/3d-arduino-sketches/breadboard-3d.jpg)

## Why

I built a card-operated music box into an old Toniebox case: an Arduino Nano, an RC522 card reader and a DFPlayer Mini MP3 player. Xi wrote the firmware and a wiring guide with breadboard drawings as SVG.

The SVGs didn't hold up once I started building. I soldered the MP3 player rotated by 180°, so I needed a second drawing. Soldering happens on the underside, where everything is mirrored, so I needed a third. Every new question meant another fixed drawing of the same board. A 3D board answers all of them: turn it, flip it, click a hole.

![One of the flat SVG drawings from the wiring guide](/blog/3d-arduino-sketches/flat-drawing.jpg)

## The layout

A layout lists the board, the parts and the wires. Holes use breadboard names: rows `a`–`j`, columns from 1, rails `T+`, `T-`, `B+`, `B-`. For an LED with a 220 Ω resistor:

```json
{"board": "mini",
 "parts": [{"type": "led", "id": "LED1", "anode": "e5", "cathode": "e6", "color": "#e53935", "lit": true},
           {"type": "resistor", "id": "R1", "label": "220 Ω", "from": "a6", "to": "a10", "ohms": 220}],
 "wires": [{"id": "W1", "from": "c5", "to": {"label": "5V"}, "color": "#e53935"},
           {"id": "W2", "from": "c10", "to": {"label": "GND"}, "color": "#212121"}]}
```

![The LED example in the view](/blog/3d-arduino-sketches/led-example.jpg)

- **Boards:** full, half and mini breadboards, perfboards, stripboards.
- **Parts:** modules on pin headers with named pins (an Arduino, an MP3 player), DIP chips, resistors (colour bands from `ohms`), LEDs, diodes, capacitors, buttons, headers.
- **Wires:** between holes, to a part's pin (`NANO.D10`), or off the board to a labelled lead (`{"label": "Speaker"}`).
- **Steps:** an optional list that adds parts and wires in groups, for build instructions.

The server checks a layout with the same model the view draws with. A wire into a hole that doesn't exist comes back to the agent as an error.

## The view

- Click a hole, pin or wire: every hole on that connection is marked, and the info line names the pins on it.
- **Top** and **Underside**: the underside flips the board toward you and names every soldered pin.
- A sidebar shows and hides each part and wire.
- With steps, **Prev**/**Next** walks through the build.

![The underside view: the strips that connect the holes, and the soldered pins with their names](/blog/3d-arduino-sketches/underside-3d.jpg)

## Tools

| Tool | Does |
| --- | --- |
| `breadboard_show` | Shows a layout as a view in the chat |
| `breadboard_html` | Writes the view into an HTML file, at a `breadboard:NAME` marker comment, and previews it in the chat |
| `breadboard_target` | Points at things in an open view: highlight, fly the camera to, show only, switch the view, go to a step |
| `breadboard_eval` | Runs ClojureScript inside an open view and returns the value |
| `scene_show` | A general 3D scene of simple shapes |

`breadboard_target` lets the agent walk you through a circuit: "this wire, from here to there", with the wire lit up in the view. Commands reach a view that is already open through a long poll. A view opened later starts from the last state.

## In documents

`breadboard_html` puts the same view into a page. Elements in the page can drive it with attributes: a table row with `data-bb-highlight="D3"` lights up wire D3 on hover, `data-bb-focus` flies the camera to it on click. The music box's wiring guide has five boards this way, one per section. The perfboard section is a three-step build.

![The perfboard board at its second step: the wires on the underside](/blog/3d-arduino-sketches/perfboard-steps.jpg)

## How it's built

- **Views in squint.** The browser code is [squint](https://github.com/squint-cljs/squint) ClojureScript on three.js. A small library, after [threeagent](https://github.com/DougHamil/threeagent), renders hiccup to three.js objects and reuses them on re-render. Showing, hiding and highlighting are just new data.
- **Compiled on demand.** The server runs on babashka and compiles the views with squint and esbuild when they're requested. There is no build step and no generated file. A view's URI contains a hash of its bundle, so a code change gives a new view.
- **One model.** The board model is a `.cljc` file that both the server (for checking layouts) and the view (for drawing) use.
- **Live eval.** `breadboard_eval` compiles ClojureScript in the view with squint's in-browser compiler and runs it as an inline module script. A view's content security policy allows inline scripts but not `eval`.

## Install

You need [babashka](https://babashka.org). Clone [xi-extensions](https://github.com/floscr/xi-extensions) and add the server to `~/.config/xi/mcp.edn`:

```clojure
{:breadboard {:command "bb"
              :args ["--config" "/path/to/xi-extensions/mcp/breadboard/bb.edn" "serve"]
              :code-paths ["/path/to/xi-extensions/mcp/breadboard/bb"
                           "/path/to/xi-extensions/mcp/breadboard/src"]}}
```

Then run `/mcp refresh breadboard`. `bb dev` in `mcp/breadboard` starts a dev page with live reload and a ClojureScript REPL into the view.
