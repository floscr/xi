# Fix: Web agent crash when multiple photos are attached

## Root Cause

When multiple photos are attached in the web client, they're sent as a single
WebSocket message containing all images as base64 strings. Bun's WebSocket
`maxPayloadLength` defaults to 16MB. Multiple unresized photos easily exceed
this, causing Bun to silently close the connection. The server's close handler
then destroys the room (no clients + not busy), and the reconnecting client
finds no room → falls back to the home/listing page.

## Fix (two-pronged)

- [x] 1. **Server**: Increase `maxPayloadLength` to 100MB in Bun WS config (`src/xi/server/ws.cljs`)
- [x] 2. **Client**: Resize images in the browser before sending using Canvas API (`src/xi/web/views.cljs`)
      - Mirrors the server-side 1568px max from `src/xi/image.cljs`
      - Reduces payload from potentially 50MB+ to ~1-2MB total
      - Removed dead `read-file-as-base64` function
- [x] 3. **Build & verify**: `bb build` — both `:main` and `:web` compile cleanly (0 new warnings)
