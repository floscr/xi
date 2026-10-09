// The client's side of the desk tour, sent the way the web client sends it
// (see steps.json for the same actions as the visitor sees them). Run by
// scripts/tour-record.mjs against `bb tour desk`.
export const prompt =
  "Uploads on a flaky connection wait minutes between retries. Cap the backoff in src/upload.ts and add a test.";

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

  // Allow `bun test`
  const ask = await until('"~:ui/dialog-open"', "allow running `bun`");
  const dialogId = ask.match(/"(dlg-[^"]+)"/)[1];
  await sleep(1500);
  send({ type: ":ui/dialog-response", "room-id": roomId, "dialog-id": dialogId, value: true });
  send({ type: ":ui/dialog-close", "room-id": roomId, "dialog-id": dialogId });

  await until('"~:agent/turn-end"');
  send({ type: ":projects/web-list" });
};
