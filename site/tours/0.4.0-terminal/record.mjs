// The client's side of the 0.4.0 terminal slide: /term in a new acme-web
// chat, then attach to the terminal at the size the replay's xterm.js has on
// the site (COLS × ROWS, measured in the 1280×800 tour frame) and type a
// few commands, a key at a time, so the shell's echo replays as typing.
const COLS = Number(process.env.TOUR_TERM_COLS || 126);
const ROWS = Number(process.env.TOUR_TERM_ROWS || 45);

export default async ({ send, until, sleep, cwd }) => {
  const web = cwd("acme-web");

  send({ type: ":room/leave" });
  send({ type: ":user-ext/web-sources" });
  send({ type: ":projects/web-list" });
  await until('"~:projects/web-list-result"');
  await sleep(800);

  // New chat in acme-web: `/term` waits for the room, then goes out as a
  // submit (a virtual new chat's pending submit, xi.web.core)
  send({ type: ":cwd/agents-files", cwd: web });
  await until('"~:cwd/agents-files-result"');
  await sleep(800);
  send({ type: ":room/join", target: "new", "join-token": crypto.randomUUID(), cwd: web });
  const joined = await until('"~:room/joined"');
  const roomId = joined.match(/"~:room-id","([^"]+)"/)[1];
  send({ type: ":input/submit", "room-id": roomId, text: "/term" });
  const buffer = await until('"~:ui/buffer-set"', '"~:terminal"');
  const termId = buffer.match(/"~:term-id","([^"]+)"/)[1];

  send({ type: ":terminal/attach", "term-id": termId, cols: COLS, rows: ROWS });
  await sleep(1500);

  const type = async (text) => {
    for (const ch of text) {
      send({ type: ":terminal/input", "term-id": termId, text: ch });
      await sleep(45 + Math.floor(Math.random() * 70));
    }
    await sleep(250);
    send({ type: ":terminal/input", "term-id": termId, text: "\r" });
  };
  await type("ls");
  await sleep(1200);
  await type("git log --oneline");
  await sleep(1500);
  await type("head -9 src/upload.ts");
  await sleep(3000);
};
