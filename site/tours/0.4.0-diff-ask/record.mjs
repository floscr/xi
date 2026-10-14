// The client's side of the 0.4.0 diff-ask slide (steps.json has the same
// actions as the visitor sees them). Run by scripts/tour-record.mjs against
// `bb tour 0.4.0-diff-ask`.
export const prompt = "Cap the upload retry backoff at 30 s";

export default async ({ send, until, sleep, cwd }) => {
  const web = cwd("acme-web");

  // the web client's own requests after connecting
  send({ type: ":room/leave" });
  send({ type: ":user-ext/web-sources" });
  send({ type: ":projects/web-list" });
  await until('"~:projects/web-list-result"');
  await sleep(800);

  // New chat in acme-web, then the prompt
  send({ type: ":cwd/agents-files", cwd: web });
  await until('"~:cwd/agents-files-result"');
  await sleep(800);
  send({ type: ":room/join", target: "new", "join-token": crypto.randomUUID(), cwd: web });
  const joined = await until('"~:room/joined"');
  const roomId = joined.match(/"~:room-id","([^"]+)"/)[1];
  send({ type: ":input/submit", "room-id": roomId, text: prompt });
  await until('"~:agent/turn-end"');
  await sleep(1200);

  // /diff: the session's edits
  send({ type: ":command/run", "room-id": roomId, name: "diff" });
  await until('"~:ui/diff-open"');
  await sleep(2500);

  // Ask about the new Math.min line (its suggestion chip)
  const question = "Why was this added?";
  send({
    type: ":subagent/spawn",
    "room-id": roomId,
    "sub-id": "ask-tour0",
    task: "Ask: " + question,
    prompt:
      "The user is reading the diff of this session's changes and asks about the selected lines.\n\n" +
      "```diff\nsrc/upload.ts\n+  return Math.min(maxMs, baseMs * 2 ** attempt);\n```\n\n" +
      "## Question\n" + question,
  });
  await until('"~:subagent/turn-end"');
  await sleep(1500);
};
