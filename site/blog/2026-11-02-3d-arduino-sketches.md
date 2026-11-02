# 3D Arduino sketches with the Xi Breadboard MCP

How I built a Toniebox replica with the help of Xi.

<!-- tag: MCP server -->
<!-- cover: /blog/3d-arduino-sketches/breadboard-3d.jpg -->

The Breadboard MCP draws a breadboard, perfboard or stripboard in 3D inside the chat: the board, the parts on it and every wire, hole by hole. The agent describes the circuit as data, and the view renders it. It's an MCP server in [xi-extensions](https://github.com/floscr/xi-extensions/tree/main/mcp/breadboard), so it works in Xi and in any other host that shows MCP Apps views.

```breadboard title="The music box's breadboard, in the same view as in the chat. Drag to turn it, click a wire, step through the build with the arrow."
{"title": "Music box breadboard",
 "board": "half",
 "parts": [
    {"type":"module","id":"NANO","label":"Arduino Nano","color":"#1565c0","marks":[{"side":"left","text":"USB"}],"pins":[{"from":"h1","to":"h15","names":["D12","D11","D10","D9","D8","D7","D6","D5","D4","D3","D2","GND","RST","RX0","TX1"]},{"from":"d1","to":"d15","names":["D13","3V3","REF","A0","A1","A2","A3","A4","A5","A6","A7","5V","RST","GND","VIN"]}]},
    {"type":"module","id":"DF","label":"DFPlayer Mini","color":"#222222","marks":[{"side":"left","text":"SD"}],"pins":[{"from":"h18","to":"h25","names":["SPK2","GND","SPK1","DAC_L","DAC_R","TX","RX","VCC"]},{"from":"d18","to":"d25","names":["IO1","GND","IO2","ADK1","ADK2","USB+","USB-","BUSY"]}]},
    {"type":"resistor","id":"D4","label":"D4 · 1 kΩ","from":"i10","to":"i16","ohms":1000}],
 "wires": [
    {"id":"P1","from":"a12","to":"B+12","color":"#e53935","label":"Nano 5V → bottom + rail"},
    {"id":"P2","from":"a14","to":"B-14","color":"#212121","label":"Nano GND → bottom − rail"},
    {"id":"P3","from":"T+30","to":"B+30","color":"#e53935","label":"links the + rails"},
    {"id":"P4","from":"T-29","to":"B-29","color":"#212121","label":"links the − rails"},
    {"id":"R1","from":"j3","to":{"label":"RC522 SDA"},"color":"#8e24aa","label":"RC522 SDA → Nano D10"},
    {"id":"R2","from":"b1","to":{"label":"RC522 SCK","side":"top"},"color":"#00acc1","label":"RC522 SCK → Nano D13"},
    {"id":"R3","from":"j2","to":{"label":"RC522 MOSI"},"color":"#ec407a","label":"RC522 MOSI → Nano D11"},
    {"id":"R4","from":"j1","to":{"label":"RC522 MISO"},"color":"#00897b","label":"RC522 MISO → Nano D12"},
    {"id":"R6","from":"T-11","to":{"label":"RC522 GND"},"color":"#212121","label":"RC522 GND → top − rail"},
    {"id":"R7","from":"j4","to":{"label":"RC522 RST"},"color":"#6d4c41","label":"RC522 RST → Nano D9"},
    {"id":"R8","from":"c2","to":{"label":"RC522 3.3V","side":"top"},"color":"#f4511e","label":"RC522 3.3V → Nano 3V3, not the + rail"},
    {"id":"D1","from":"j25","to":"T+25","color":"#e53935","label":"DFPlayer VCC → top + rail"},
    {"id":"D2","from":"a19","to":"B-19","color":"#212121","label":"DFPlayer GND → bottom − rail"},
    {"id":"D3","from":"j23","to":"j11","color":"#43a047","label":"DFPlayer TX → Nano D2"},
    {"id":"D5","from":"j16","to":"j24","color":"#fdd835","label":"resistor → DFPlayer RX"},
    {"id":"D6","from":"b25","to":"j9","color":"#ffa000","label":"DFPlayer BUSY → Nano D4"},
    {"id":"D7","from":"j20","to":{"label":"Speaker"},"color":"#9e9e9e","label":"SPK1 → speaker"},
    {"id":"D8","from":"j18","to":{"label":"Speaker"},"color":"#616161","label":"SPK2 → speaker"},
    {"id":"B1","from":"a4","to":{"label":"Play/Pause"},"color":"#5e35b1","label":"Nano A0 → Play/Pause"},
    {"id":"B2","from":"a5","to":{"label":"Up"},"color":"#3949ab","label":"Nano A1 → Up"},
    {"id":"B3","from":"a6","to":{"label":"Down"},"color":"#1e88e5","label":"Nano A2 → Down"},
    {"id":"B4","from":"B-27","to":{"label":"Buttons, other legs"},"color":"#212121","label":"other leg of all 3 buttons → − rail"}],
 "steps": [
    {"title":"Nano and power","text":"Nano in columns 1–15, USB on the left. P1/P2 take 5V and GND to the bottom rails, P3/P4 link them to the top rails.","add":["NANO","P1","P2","P3","P4"]},
    {"title":"RC522 card reader","text":"Seven wires, female end on the reader. R8 goes to the Nano's 3V3, never the + rail. Then Test 1: toniebox log, hold the key fob on it.","add":["R1","R2","R3","R4","R6","R7","R8"],"focus":["NANO"]},
    {"title":"DFPlayer and resistor","text":"microSD in first, SD slot pointing left. The 1 kΩ (D4) sits between Nano D3 and the DFPlayer's RX (D5).","add":["DF","D4","D1","D2","D3","D5","D6"]},
    {"title":"Speaker","text":"SPK1 and SPK2 only, never GND or a rail. Then Test 2: the startup chime.","add":["D7","D8"],"focus":["DF"]},
    {"title":"Buttons (or the ears)","text":"One leg to A0/A1/A2, the other legs together to the − rail. With the Toniebox ears: right ear on A1, left ear on A2.","add":["B1","B2","B3","B4"]}]}
```

## Why

I built a card-operated music box into an old Toniebox case: an Arduino Nano, an RC522 card reader and a DFPlayer Mini MP3 player. Xi wrote the firmware and a wiring guide with breadboard drawings as SVG.

The SVGs didn't hold up once I started building. I soldered the MP3 player rotated by 180°, so I needed a second drawing. Soldering happens on the underside, where everything is mirrored, so I needed a third. Every new question meant another fixed drawing of the same board. A 3D board answers all of them: turn it, flip it, click a hole.

![One of the flat SVG drawings from the wiring guide](/blog/3d-arduino-sketches/flat-drawing.jpg)

## The layout

A layout lists the board, the parts and the wires. Holes use breadboard names: rows `a`–`j`, columns from 1, rails `T+`, `T-`, `B+`, `B-`. For an LED with a 220 Ω resistor:

```breadboard open
{"board": "mini",
 "parts": [{"type": "led", "id": "LED1", "anode": "e5", "cathode": "e6", "color": "#e53935", "lit": true},
           {"type": "resistor", "id": "R1", "label": "220 Ω", "from": "a6", "to": "a10", "ohms": 220}],
 "wires": [{"id": "W1", "from": "c5", "to": {"label": "5V"}, "color": "#e53935"},
           {"id": "W2", "from": "c10", "to": {"label": "GND"}, "color": "#212121"}]}
```

- **Boards:** full, half and mini breadboards, perfboards, stripboards.
- **Parts:** modules on pin headers with named pins (an Arduino, an MP3 player), DIP chips, resistors (colour bands from `ohms`), LEDs, diodes, capacitors, buttons, headers.
- **Wires:** between holes, to a part's pin (`NANO.D10`), or off the board to a labelled lead (`{"label": "Speaker"}`).
- **Steps:** an optional list that adds parts and wires in groups, for build instructions.

The server checks a layout with the same model the view draws with. A wire into a hole that doesn't exist comes back to the agent as an error.

## The view

- Click a hole, pin or wire: every hole on that connection is marked, and the info line names the pins on it.
- **Top** and **Underside**: the underside flips the board toward you and names every soldered pin.
- A sidebar shows and hides each part and wire.
- With steps, the arrows under the board walk through the build.

```breadboard title="The underside: the strips that connect the holes, and the soldered pins with their names."
{"title": "Underside, MP3 player rotated",
 "board": "half",
 "view": "bottom",
 "parts": [
    {"type":"module","id":"NANO","label":"Arduino Nano","color":"#1565c0","marks":[{"side":"left","text":"USB"}],"pins":[{"from":"h1","to":"h15","names":["D12","D11","D10","D9","D8","D7","D6","D5","D4","D3","D2","GND","RST","RX0","TX1"]},{"from":"d1","to":"d15","names":["D13","3V3","REF","A0","A1","A2","A3","A4","A5","A6","A7","5V","RST","GND","VIN"]}]},
    {"type":"module","id":"DF","label":"DFPlayer Mini","color":"#222222","marks":[{"side":"right","text":"SD"}],"pins":[{"from":"h18","to":"h25","names":["BUSY","USB-","USB+","ADK2","ADK1","IO2","GND","IO1"]},{"from":"d18","to":"d25","names":["VCC","RX","TX","DAC_R","DAC_L","SPK1","GND","SPK2"]}]},
    {"type":"resistor","id":"D4","label":"D4 · 1 kΩ","from":"i10","to":"i16","ohms":1000}],
 "wires": [
    {"id":"P1","from":"a12","to":"B+12","color":"#e53935","label":"Nano 5V → bottom + rail"},
    {"id":"P2","from":"a14","to":"B-14","color":"#212121","label":"Nano GND → bottom − rail"},
    {"id":"P3","from":"T+30","to":"B+30","color":"#e53935","label":"links the + rails"},
    {"id":"P4","from":"T-29","to":"B-29","color":"#212121","label":"links the − rails"},
    {"id":"R1","from":"j3","to":{"label":"RC522 SDA"},"color":"#8e24aa"},
    {"id":"R2","from":"b1","to":{"label":"RC522 SCK","side":"top"},"color":"#00acc1"},
    {"id":"R3","from":"j2","to":{"label":"RC522 MOSI"},"color":"#ec407a"},
    {"id":"R4","from":"j1","to":{"label":"RC522 MISO"},"color":"#00897b"},
    {"id":"R6","from":"T-11","to":{"label":"RC522 GND"},"color":"#212121"},
    {"id":"R7","from":"j4","to":{"label":"RC522 RST"},"color":"#6d4c41"},
    {"id":"R8","from":"c2","to":{"label":"RC522 3.3V","side":"top"},"color":"#f4511e","label":"3V3, not the + rail"},
    {"id":"D1","from":"a18","to":"B+18","color":"#e53935","label":"DFPlayer VCC → bottom + rail"},
    {"id":"D2","from":"a24","to":"B-24","color":"#212121","label":"DFPlayer GND"},
    {"id":"D3","from":"j11","to":"b20","color":"#43a047","label":"Nano D2 → DFPlayer TX"},
    {"id":"D5","from":"j16","to":"a19","color":"#fdd835","label":"resistor → DFPlayer RX"},
    {"id":"D6","from":"j18","to":"j9","color":"#ffa000","label":"DFPlayer BUSY → Nano D4"},
    {"id":"D7","from":"b23","to":{"label":"Speaker","side":"bottom"},"color":"#9e9e9e","label":"SPK1"},
    {"id":"D8","from":"a25","to":{"label":"Speaker","side":"bottom"},"color":"#616161","label":"SPK2"},
    {"id":"B1","from":"a4","to":{"label":"Play/Pause"},"color":"#5e35b1","label":"Nano A0"},
    {"id":"B2","from":"a5","to":{"label":"Up"},"color":"#3949ab","label":"Nano A1"},
    {"id":"B3","from":"a6","to":{"label":"Down"},"color":"#1e88e5","label":"Nano A2"},
    {"id":"B4","from":"B-27","to":{"label":"Buttons, other legs"},"color":"#212121"}]}
```

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

```breadboard title="The perfboard section: three steps, the second on the underside."
{"title": "Perfboard: RC522 header → Nano socket",
 "board": {"type":"perfboard","cols":26,"rows":16,"color":"#d9c79a"},
 "parts": [
    {"type":"header","id":"J1","label":"RC522 header (male)","from":"n2","to":"n9","names":["SDA","SCK","MOSI","MISO","IRQ","GND","RST","3.3V"]},
    {"type":"module","id":"NANO","label":"Arduino Nano (in its socket)","color":"#1565c0","lift":3.3,"marks":[{"side":"left","text":"USB"}],"pins":[{"from":"h10","to":"h24","names":["D12","D11","D10","D9","D8","D7","D6","D5","D4","D3","D2","GND","RST","RX0","TX1"]},{"from":"b10","to":"b24","names":["D13","3V3","REF","A0","A1","A2","A3","A4","A5","A6","A7","5V","RST","GND","VIN"]}]}],
 "wires": [
    {"id":"W1","from":"J1.SDA","to":"NANO.D10","side":"bottom","color":"#8e24aa","label":"SDA → D10"},
    {"id":"W2","from":"J1.SCK","to":"NANO.D13","side":"bottom","color":"#00acc1","label":"SCK → D13"},
    {"id":"W3","from":"J1.MOSI","to":"NANO.D11","side":"bottom","color":"#ec407a","label":"MOSI → D11"},
    {"id":"W4","from":"J1.MISO","to":"NANO.D12","side":"bottom","color":"#00897b","label":"MISO → D12"},
    {"id":"W5","from":"J1.GND","to":"NANO.GND","side":"bottom","color":"#212121","label":"GND → GND"},
    {"id":"W6","from":"J1.RST","to":"NANO.D9","side":"bottom","color":"#6d4c41","label":"RST → D9"},
    {"id":"W7","from":"J1.3.3V","to":"NANO.3V3","side":"bottom","color":"#f4511e","label":"3.3V → 3V3, never 5V"},
    {"id":"C1","from":"n2","to":{"label":"RC522","side":"top"},"color":"#8e24aa","label":"cable SDA"},
    {"id":"C2","from":"n3","to":{"label":"RC522","side":"top"},"color":"#00acc1","label":"cable SCK"},
    {"id":"C3","from":"n4","to":{"label":"RC522","side":"top"},"color":"#ec407a","label":"cable MOSI"},
    {"id":"C4","from":"n5","to":{"label":"RC522","side":"top"},"color":"#00897b","label":"cable MISO"},
    {"id":"C6","from":"n7","to":{"label":"RC522","side":"top"},"color":"#212121","label":"cable GND"},
    {"id":"C7","from":"n8","to":{"label":"RC522","side":"top"},"color":"#6d4c41","label":"cable RST"},
    {"id":"C8","from":"n9","to":{"label":"RC522","side":"top"},"color":"#f4511e","label":"cable 3.3V"}],
 "steps": [
    {"title":"Header and socket","text":"Solder the 8-pin male header (pins up) and the Nano's two female sockets; the Nano plugs into them and pulls out again. USB to the left, like on the breadboard.","add":["J1","NANO"]},
    {"title":"Wires on the underside","text":"Turned over everything is mirrored front to back: the header row is now nearest you. Each wire runs from a header pin to a socket pin, soldered at both ends. IRQ stays free.","add":["W1","W2","W3","W4","W5","W6","W7"],"view":"bottom"},
    {"title":"The RC522 cable","text":"The female jumpers of the RC522 cable plug onto the header pins from above.","add":["C1","C2","C3","C4","C6","C7","C8"],"view":"3d"}]}
```

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
