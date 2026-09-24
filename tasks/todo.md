# Allow TCP sockets in the clj sandbox

Agent tried `(import '[java.net Socket])` in the clj tool — it's SCI on Bun,
not the JVM, so no java.net. Add a synchronous `socket` SCI namespace so agents
can speak raw TCP to loopback services (nREPL, mpv, custom daemons).

## Design

- SCI evals are synchronous; JS sockets are async-only. Each
  `(socket/connect host port)` spawns a nested worker_threads Worker from the
  same bundle (`workerData :role "xi-socket-bridge"`, dispatched in
  xi.cli/main) that owns the async net.Socket.
- Received bytes flow through a SharedArrayBuffer ring buffer; the eval thread
  blocks with abortable Atomics.wait (same pattern as process/wait).
  Writes are postMessage'd to the bridge.
- Loopback-only (localhost / 127.x / ::1) — remote raw TCP stays blocked;
  curl covers http(s). Runtime check, so dynamic hosts can't bypass.
- API: `(socket/connect h p & [{:timeout-ms}])` → handle,
  `(socket/write s data)` (string or byte seq),
  `(socket/read s & [{:n | :until | :bytes? | :timeout-ms}])`,
  `(socket/close s)`, `(socket/open? s)`, `(socket/list)`.
- Sockets persist across evals (REPL state); die with the room worker or
  explicit close.

## Tasks

- [x] src/xi/ext/clj_socket.cljs — bridge (nested worker) + parent read/write
      + sci-namespace
- [x] xi.cli/main — workerData role dispatch (bridge vs clj eval worker)
- [x] xi.ext.clj/make-ctx — inject 'socket namespace; update tool-def
      description + SYSTEM_PROMPT
- [x] docs/clj-tool.md — socket section
- [x] tests for pure helpers (loopback-host?, ring math, delimiter search)
- [x] bb build + bb test
- [x] live verify after serve:restart (speak HTTP/1.0 to :7474 via socket)

## Review

All done. `bb test`: 661 tests / 2142 assertions, 0 failures (includes new
`xi.ext.clj-socket-test` covering loopback-host?, ring-used, index-of-subseq).

Live verification (from the agent's own clj tool after `bb serve:restart`):

- `(socket/connect "127.0.0.1" 7474)` → `{:id 1 :host … :port 7474}`
- `(socket/write s "GET /… HTTP/1.0\r\n\r\n")` + `(socket/read s {:until "\r\n\r\n"})`
  → returned the HTTP response headers (blocking delimiter read works;
  delimiter consumed, not returned)
- `(socket/read s {:n 15})` → exactly `"<!doctype html>"`;
  `{:n 4 :bytes? true}` → `[10 60 104 116]`
- `(socket/list)` → showed `:status :closed` after the server closed the
  HTTP/1.0 connection, with `:buffered 67145` still readable — buffered data
  survives remote close
- `(socket/close s)` → `open?` false, registry emptied
- Refusals: `example.com` / `192.168.1.10` → clear loopback-only error
  pointing at `(curl …)`; dead port 59999 → "connect ECONNREFUSED" error

No gate/scan changes were needed — loopback-only is enforced at runtime in
`connect!`, same trust tier as the pre-approved `(ports)` helper.
