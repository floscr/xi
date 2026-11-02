# 3D Arduino sketches with the Xi Breadboard MCP

How I built a Toniebox replica with the help of Xi.

<!-- tag: MCP server -->
<!-- cover: /blog/3d-arduino-sketches/breadboard-3d.jpg -->

![The breadboard view: the music box's layout in 3D, with a sidebar of parts and wires](/blog/3d-arduino-sketches/breadboard-3d.jpg)

Ask Xi to wire a circuit and it draws the breadboard in 3D, right in the chat: the board, the Arduino, the modules on their pin headers, every wire from hole to hole. You can orbit it, look from the top, or flip it to the side you solder. Click a hole and everything connected to it lights up. And the agent can point at things while it explains them: "this wire, from here to there."

That's the Breadboard MCP, a small server now in [xi-extensions](https://github.com/floscr/xi-extensions/tree/main/mcp/breadboard). It came out of a weekend project: a music box for my kid, built into an old Toniebox case.

## What it draws

The agent writes the layout as data: the board, the parts with their pins, the wires. The view draws it, and checks it with the same code, so a wire into a hole that doesn't exist comes back as an error instead of quietly going missing.

- **Boards:** full, half and mini breadboards, perfboards and stripboards. Holes have their usual names: `e12`, `T+5` for a rail.
- **Parts:** modules on pin headers (an Arduino Nano, an MP3 player, a card reader), chips, resistors with their colour bands, LEDs, diodes, capacitors, buttons, headers.
- **Wires:** between holes, to a part's pin by name (`NANO.D10`), or off the board to a labelled lead.

![The underside view: the metal strips that connect the holes, and every soldered pin named](/blog/3d-arduino-sketches/underside-3d.jpg)

Then:

- **Click a hole, pin or wire** and everything connected to it lights up.
- **The underside** is one button: the board flips toward you, with every pin dotted and named.
- **A sidebar** shows and hides parts and wires one by one.
- **Build steps** put parts and wires on the board one group at a time, with Prev/Next.
- **The agent points at things** in an open view: it lights up the wire it's talking about, or flies the camera there. For anything else it can run ClojureScript inside the view and get the value back.
- **The same board goes into an HTML document**, where a row of a wire table can light up its wire.

## Where it came from: a Toniebox replica

![The lid of an old Toniebox, opened: the green board around the card spot and the two ear switches](/blog/3d-arduino-sketches/toniebox-lid.jpg)

The idea: put a card on the box and it plays that card's songs; lift it and the music pauses. The case and the ears come from a Toniebox. Inside there's new hardware:

- an **Arduino Nano**, which reads the card and tells the player what to play;
- an **RC522** RFID reader, which identifies cards by their UID, so nothing is written to them;
- a **DFPlayer Mini**: microSD reader, MP3 decoder and a 3 W amplifier in one;
- the Toniebox speaker, and its two ears as volume buttons.

On the first day Xi wrote the firmware with me, a `toniebox` command-line tool to build, flash and read the box's serial log, and a wiring guide as a single HTML page: drawings of the breadboard, a table of every wire, a checklist to build and test in order. A small web app followed, to scan cards, link them to songs and flash the SD card.

![The first prototype on a breadboard: the Nano, the RC522 reader with its antenna, jumper wires, a power bank](/blog/3d-arduino-sketches/prototype.jpg)

The agent was part of testing, not just of writing code. When I plugged the board in, it read the box's log itself, with a time limit, while I placed a card:

```text
16:54:51 card 49e77512 3     ← reader detects card 3
16:54:51 play 3 1/1          ← plays folder 03, no error
16:54:55 gone                ← lifted, paused
16:54:56 card 49e77512 3
16:54:56 resume              ← same card back, picks up where it stopped
```

I never pasted anything. It reflashed a new SD card through the box's own USB port, and when the reader stopped answering, it pointed at the loose jumper.

![USB-C breakout boards with 5.1 kΩ resistors on the CC pins](/blog/3d-arduino-sketches/usb-c.jpg)

Over the next days the box got USB-C power, the SD card over USB, a sleep mode so a power bank switches itself off, and a perfboard to solder it all onto.

## When the drawings stopped helping

The wiring guide's drawings were flat SVG: correct, but fixed. I soldered the MP3 player the other way round from the drawing, so I asked for a second drawing with the chip turned 180°. Then I turned the board over to solder it, and nothing matched what I held anymore: *"it gets really confusing for me that this row is closer to me."* A third drawing, mirrored.

![The flat drawing of the breadboard from the wiring guide](/blog/3d-arduino-sketches/flat-drawing.jpg)

Three drawings of one board, and each new question would need another. What I wanted was the board itself: something to turn around, to flip, to click a hole and see where it goes. So I wrote a note: an interactive 3D breadboard view like [BBRouter](https://www.bbrouter.com/), without the simulation, in the chat and in documents like the wiring guide. Then I gave the note to Xi.

It took a few rounds:

- **A user extension first.** The agent wrote it as plain JavaScript inside a Clojure string. It worked, but that's not how I want code written, so I said so.
- **Squint ClojureScript.** The rewrite has a small library for three.js, written after [threeagent](https://github.com/DougHamil/threeagent): a scene is hiccup, and a re-render reuses the existing objects.
- **An MCP server.** A user extension can't compile squint when it loads, so the first version needed a build step. I asked whether it should be an MCP server instead. Now it's a babashka server that compiles its views on demand, and it works in any host that shows MCP Apps views, not just Xi.

The wiring guide got the same boards, five in total. The perfboard section, a side view and a top view before, is now one board in three steps: header and socket, then the wires underneath, then the cable plugging on.

![The perfboard at its second step: the wires on the underside, from the reader's header to the Nano's socket](/blog/3d-arduino-sketches/perfboard-steps.jpg)

## Try it

The server needs [babashka](https://babashka.org). Clone [xi-extensions](https://github.com/floscr/xi-extensions) and add it to `~/.config/xi/mcp.edn`:

```clojure
{:breadboard {:command "bb"
              :args ["--config" "/path/to/xi-extensions/mcp/breadboard/bb.edn" "serve"]
              :code-paths ["/path/to/xi-extensions/mcp/breadboard/bb"
                           "/path/to/xi-extensions/mcp/breadboard/src"]}}
```

Run `/mcp refresh breadboard`, then ask for a circuit. For "an LED with a 220 Ω resistor on a mini breadboard", the agent writes something like:

```json
{"board": "mini",
 "parts": [{"type": "led", "id": "LED1", "anode": "e5", "cathode": "e6", "color": "#e53935", "lit": true},
           {"type": "resistor", "id": "R1", "label": "220 Ω", "from": "a6", "to": "a10", "ohms": 220}],
 "wires": [{"id": "W1", "from": "c5", "to": {"label": "5V"}, "color": "#e53935"},
           {"id": "W2", "from": "c10", "to": {"label": "GND"}, "color": "#212121"}]}
```

![The LED example in the breadboard view](/blog/3d-arduino-sketches/led-example.jpg)

## What I took away

- **Let the agent see the hardware.** A log it can read with a time limit beats pasting output into the chat.
- **Ask for the drawing you need, from where you stand.** "The other way round" and "from below" were five-minute changes. When a format runs out of answers, build the tool.
- **Say what you want early.** The views are squint because I said so after the first version. Moving to an MCP server was one question.

The Breadboard MCP is on the [extensions page](/extensions/), with the rest of what I publish for Xi.
