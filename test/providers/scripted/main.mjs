// A provider written against docs/provider-protocol.md by hand: a turn takes
// a lock, reads AGENTS.md through Xi's `read` tool and answers with what it
// read and its `greeting` option; `usage` returns one card. Its accounts live
// in <dataDir>/accounts.json; a sign-in wants the code "ok" back.

import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

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

// ── Accounts ─────────────────────────────────────────────────────────────────

const file = (req) => join(req.dataDir, "accounts.json");
const load = (req) =>
  existsSync(file(req)) ? JSON.parse(readFileSync(file(req), "utf8")) : [{ id: "1", email: "one@x.io", active: true }];
const save = (req, accounts) => {
  mkdirSync(req.dataDir, { recursive: true });
  writeFileSync(file(req), JSON.stringify(accounts));
};
const fail = (message) => {
  send({ type: "error", error: { type: "error", message } });
  send({ type: "done", failed: true });
};

async function accounts(req) {
  let accts = load(req);
  const one = () => accts.find((a) => a.id === req.account);
  switch (req.type) {
    case "accounts":
      return send({ type: "done", accounts: accts });
    case "switch":
      if (!one()) return fail("no account " + req.account);
      accts = accts.map((a) => ({ ...a, active: a.id === req.account }));
      save(req, accts);
      return send({ type: "done", account: one() });
    case "alias":
      accts = accts.map((a) => (a.id === req.account ? { ...a, alias: req.alias ?? undefined } : a));
      save(req, accts);
      return send({ type: "done", account: one() });
    case "logout":
      if (one()?.active) return fail("that is the live account");
      save(req, accts.filter((a) => a.id !== req.account));
      return send({ type: "done" });
    case "login": {
      send({ type: "login-prompt", url: "https://scripted.example/sign-in", needsCode: true });
      const { code } = await waitFor("login-code:undefined");
      if (code !== "ok") return fail("wrong code");
      const account = { id: String(accts.length + 1), email: "new@x.io", active: true, ...(req.alias ? { alias: req.alias } : {}) };
      save(req, [...accts.map((a) => ({ ...a, active: false })), account]);
      return send({ type: "done", email: account.email, account });
    }
  }
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
      const run = request.type === "turn" ? turn : request.type === "usage" ? usage : accounts;
      // exit once stdout drained, so `done` arrives
      Promise.resolve(run(request)).then(() => process.stdout.write("", () => process.exit(0)));
    } else {
      const key = frame.type === "locked" ? `locked:${frame.name}` : `${frame.type}:${frame.id}`;
      waiting.get(key)?.(frame);
    }
  }
});
