// Tries the npm package on a clean machine: a Docker container with only Bun,
// the packed tarball installed globally the way `bun install -g xi-agent` does
// it, and `xi server --headless` running as a non-root user.
//
//   bun scripts/try-package-docker.mjs [up] [--port N] [--no-build] [--fresh]
//   bun scripts/try-package-docker.mjs approve <code>     pair a browser
//   bun scripts/try-package-docker.mjs login              sign in to Claude again
//   bun scripts/try-package-docker.mjs down [--fresh]     remove the container
//                                                          (--fresh: and its home)
//                                              (or: bb package:docker …)
//
//   --port N     host port for the web client (default 7478)
//   --no-build   reuse the tarball already in dist/ instead of rebuilding it
//   --fresh      also drop the home volume: first-run experience, logged out
//
// `up` (the default) is one shot: build, recreate the container, wait for the
// server, sign in to Claude if the container is not logged in yet. The home
// directory is a named volume, so the Claude login and the paired browsers
// survive re-running `up`. The container runs as `bun`, not root: the Claude
// CLI refuses --dangerously-skip-permissions as root.

import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const dist = join(root, "dist");
const name = "xi-package-try";
const volume = `${name}-home`;
const inContainerPath = "export PATH=/home/bun/.bun/bin:$PATH;";
// The SDK ships a glibc and a musl CLI next to each other; sign in with the
// glibc one (the runner picks the same one on this image).
const claudeBin =
  "$(ls -d /home/bun/.bun/install/global/node_modules/@anthropic-ai/claude-agent-sdk-linux-*/claude | grep -v musl | head -1)";

const args = process.argv.slice(2);
const sub = args[0] && !args[0].startsWith("--") ? args[0] : "up";
const flag = (f) => args.includes(f);
const portAt = args.indexOf("--port");
const port = portAt >= 0 ? args[portAt + 1] : "7478";
if (!/^\d+$/.test(port ?? "")) {
  console.error("--port needs a number");
  process.exit(2);
}

const docker = (argv, opts = {}) => spawnSync("docker", argv, { stdio: "inherit", ...opts });
const dockerOut = (argv) => spawnSync("docker", argv, { encoding: "utf8" });
const must = (r, what) => {
  if (r.error || r.status !== 0) {
    console.error(`\n${what} failed`);
    process.exit(r.status ?? 1);
  }
};

const running = () => dockerOut(["inspect", "-f", "{{.State.Running}}", name]).stdout.trim() === "true";
const requireRunning = () => {
  if (!running()) {
    console.error(`${name} is not running — start it with: bb package:docker`);
    process.exit(1);
  }
};
// Commands run as the same user and HOME as the server, or they would look at
// another pairing store.
const inContainer = (sh, { tty = false } = {}) =>
  docker(["exec", ...(tty ? ["-it"] : []), "-u", "bun", "-e", "HOME=/home/bun", name, "sh", "-c", `${inContainerPath} ${sh}`]);

const loggedIn = () => {
  const r = dockerOut(["exec", "-u", "bun", "-e", "HOME=/home/bun", name, "sh", "-c", `${claudeBin} auth status`]);
  return /"loggedIn":\s*true/.test(r.stdout ?? "");
};
const login = () => must(inContainer(`${claudeBin} auth login`, { tty: true }), "claude auth login");

if (sub === "approve") {
  const code = args[1];
  if (!/^\d+$/.test(code ?? "")) {
    console.error("usage: approve <code>   (the four-digit code the browser shows)");
    process.exit(2);
  }
  requireRunning();
  process.exit(inContainer(`xi clients approve ${code}`).status ?? 1);
}

if (sub === "login") {
  requireRunning();
  login();
  process.exit(0);
}

if (sub === "down") {
  docker(["rm", "-f", name], { stdio: "ignore" });
  if (flag("--fresh")) docker(["volume", "rm", "-f", volume], { stdio: "ignore" });
  console.log(`removed ${name}${flag("--fresh") ? ` and ${volume}` : ""}`);
  process.exit(0);
}

if (sub !== "up") {
  console.error(`unknown command ${sub} — up | approve <code> | login | down`);
  process.exit(2);
}

if (!flag("--no-build")) must(spawnSync("bun", [join(root, "scripts/build-package.mjs")], { cwd: root, stdio: "inherit" }), "bb package");

const pkgFile = join(dist, "pkg", "package.json");
if (!existsSync(pkgFile)) {
  console.error("dist/pkg is missing — run without --no-build first");
  process.exit(1);
}
const pkg = JSON.parse(readFileSync(pkgFile, "utf8"));
const tarball = join(dist, `${pkg.name}-${pkg.version}.tgz`);
if (!existsSync(tarball)) {
  console.error(`${tarball} is missing — run without --no-build first`);
  process.exit(1);
}

docker(["rm", "-f", name], { stdio: "ignore" });
if (flag("--fresh")) docker(["volume", "rm", "-f", volume], { stdio: "ignore" });

// The image's BUN_INSTALL_BIN is /usr/local/bin, which `bun` cannot write to;
// root only does the setup (git for the agent), then hands over to `bun`.
const start =
  "apt-get update -qq && apt-get install -y -qq git >/dev/null && " +
  "exec runuser -u bun -- env HOME=/home/bun BUN_INSTALL_BIN=/home/bun/.bun/bin PATH=/home/bun/.bun/bin:$PATH " +
  "sh -c 'bun install -g /pkg.tgz && xi server --headless'";
must(
  docker([
    "run", "-d", "--name", name,
    "-p", `${port}:7474`,
    "-v", `${volume}:/home/bun`,
    "-v", `${tarball}:/pkg.tgz:ro`,
    ...(process.env.ANTHROPIC_API_KEY ? ["-e", "ANTHROPIC_API_KEY"] : []),
    "oven/bun:1", "sh", "-c", start,
  ], { stdio: ["inherit", "ignore", "inherit"] }),
  "docker run",
);

process.stdout.write(`starting ${pkg.name}@${pkg.version} in ${name} `);
const deadline = Date.now() + 180_000;
let ready = false;
while (Date.now() < deadline) {
  if (!running()) break;
  const logs = dockerOut(["logs", name]);
  if (/Headless server on/.test(`${logs.stdout}${logs.stderr}`)) {
    ready = true;
    break;
  }
  process.stdout.write(".");
  spawnSync("sleep", ["2"]);
}
console.log();
if (!ready) {
  console.error("the server did not come up — docker logs " + name);
  docker(["logs", "--tail", "30", name]);
  process.exit(1);
}

if (!process.env.ANTHROPIC_API_KEY && !loggedIn()) {
  console.log("\nNot signed in to Claude yet — opening the login:\n");
  login();
}

console.log(`
xi ${pkg.version} is running as ${name}
  web:      http://localhost:${port}
  pair:     the browser shows a four-digit code, then
            bb package:docker approve <code>
  logs:     docker logs -f ${name}
  TUI:      docker exec -it -u bun -e HOME=/home/bun ${name} sh -c 'PATH=/home/bun/.bun/bin:$PATH xi'
  stop:     bb package:docker down   (--fresh also forgets the login and pairings)`);
