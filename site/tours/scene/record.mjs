// The client's side of the scene tour, sent the way the web client sends it
// (see steps.json for the same actions as the visitor sees them). Run by
// scripts/tour-record.mjs against `bb tour scene`.
export const prompt = "Which of our modules are the biggest? Show me in 3D.";
// what the view's "Ask about it" puts in the message box (scene/view.cljs)
export const followUp = "Tell me about the charts.ts in the scene.";
export const doIt = "Do it.";
// the two lines the visitor selects in /diff, as xi.diff/selected-snippet has them
const snippet =
  "src/charts.ts\n" +
  '+const compactFormat = new Intl.NumberFormat("en", { notation: "compact", maximumFractionDigits: 1 });\n' +
  "+  return compactFormat.format(n);";

export default async ({ send, until, sleep, cwd }) => {
  const web = cwd("acme-web");

  // the web client's own requests after connecting
  send({ type: ":room/leave" });
  send({ type: ":user-ext/web-sources" });
  send({ type: ":projects/web-list" });
  await until('"~:projects/web-list-result"');
  await sleep(1500);

  // New chat in acme-web
  send({ type: ":cwd/agents-files", cwd: web });
  await until('"~:cwd/agents-files-result"');
  await sleep(1000);

  // Send the prompt: the room is created on the first submit
  send({ type: ":room/join", target: "new", "join-token": crypto.randomUUID(), cwd: web });
  const joined = await until('"~:room/joined"');
  const roomId = joined.match(/"~:room-id","([^"]+)"/)[1];
  send({ type: ":input/submit", "room-id": roomId, text: prompt });

  // Allow `bun run size`
  const ask = await until('"~:ui/dialog-open"', "allow running `bun`");
  const dialogId = ask.match(/"(dlg-[^"]+)"/)[1];
  await sleep(1500);
  send({ type: ":ui/dialog-response", "room-id": roomId, "dialog-id": dialogId, value: true });
  send({ type: ":ui/dialog-close", "room-id": roomId, "dialog-id": dialogId });

  // The scene block: its frame asks for the view's HTML once it mounts
  const added = await until('"~:ext.blocks/add"');
  // transit caches repeated keys ("~:id" may be "^:"): match the id itself
  const blockId = added.match(/"(app-[a-z0-9]+)"/)[1];
  send({ type: ":ext.blocks/app-load", "room-id": roomId, "block-id": blockId });
  await until('"~:ext.blocks/app-html"');

  await until('"~:agent/turn-end"');
  // the visitor turns the scene and picks a box meanwhile
  await sleep(3000);

  // "Ask about it" on charts.ts, sent
  send({ type: ":input/submit", "room-id": roomId, text: followUp });
  await until('"~:agent/turn-end"', roomId);
  await sleep(1200);

  // The suggested cut
  send({ type: ":input/submit", "room-id": roomId, text: doIt });
  await until('"~:agent/turn-end"', roomId);
  await sleep(1000);

  // /diff, then Explain on the two new lines
  send({ type: ":command/run", "room-id": roomId, name: "diff" });
  await until('"~:ui/diff-open"');
  await sleep(2500);
  send({
    type: ":subagent/spawn",
    "room-id": roomId,
    "sub-id": "ask-tour0",
    task: "Explain: the selected lines",
    prompt:
      "The user is reading the diff of this session's changes and wants the selected lines explained.\n\n" +
      "```diff\n" + snippet + "\n```\n\n" +
      "Explain in plain language, in concise markdown, what these changes do and why.",
  });
  await until('"~:subagent/turn-end"');
  await sleep(1500);
  send({ type: ":projects/web-list" });
};
