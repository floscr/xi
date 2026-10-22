// A provider written against docs/provider-protocol.md by hand: a turn takes
// a lock, reads AGENTS.md through Xi's `read` tool and answers with what it
// read and its `greeting` option; `usage` returns one card.

const send = (frame) => process.stdout.write(JSON.stringify(frame) + "\n");

const waiting = new Map(); // frame type + id/name → resolve
const waitFor = (key) => new Promise((resolve) => waiting.set(key, resolve));

async function turn(req) {
  send({ type: "lock", name: "scripted/turn" });
  await waitFor("locked:scripted/turn");
  send({ type: "unlock", name: "scripted/turn" });

  const call = { id: "call_1", name: "read", arguments: { path: "AGENTS.md" } };
  send({ type: "tool-start", ...call });
  send({ type: "tool-call", id: "rt1", name: call.name, arguments: call.arguments });
  const result = await waitFor("tool-result:rt1");
  send({ type: "tool-done", id: call.id, content: result.content, isError: result.isError });

  const read = result.content.filter((b) => b.type === "text").map((b) => b.text).join("");
  const text = `${req.options.greeting}! read: ${read.includes("E2E-PROJECT-INSTRUCTIONS") ? "instructions" : read}`;
  send({ type: "text", text });
  send({ type: "done", stopReason: "stop", resultText: text, usage: { input_tokens: 1, output_tokens: 1 } });
}

function usage() {
  send({
    type: "done",
    readings: [{ id: "scripted/me", provider: "scripted", title: "Scripted", windows: [{ id: "w", label: "Day", used: 7, periodMs: 86400000 }] }],
  });
}

let buf = "";
let request = null;
process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => {
  buf += chunk;
  let nl;
  while ((nl = buf.indexOf("\n")) >= 0) {
    const frame = JSON.parse(buf.slice(0, nl));
    buf = buf.slice(nl + 1);
    if (!request) {
      request = frame;
      // exit once stdout drained, so `done` arrives
      Promise.resolve(request.type === "turn" ? turn(request) : usage()).then(() =>
        process.stdout.write("", () => process.exit(0)),
      );
    } else {
      const key = frame.type === "locked" ? `locked:${frame.name}` : `${frame.type}:${frame.id}`;
      waiting.get(key)?.(frame);
    }
  }
});
