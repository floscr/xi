// The client's side of the 0.4.0 link-hints slide: just connecting. The
// sessions it opens are earlier ones, answered from the tape's visits
// (scripts/tour-record.mjs).
export default async ({ send, until, sleep }) => {
  send({ type: ":room/leave" });
  send({ type: ":user-ext/web-sources" });
  send({ type: ":projects/web-list" });
  await until('"~:projects/web-list-result"');
  await sleep(1000);
};
