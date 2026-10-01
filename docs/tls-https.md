# HTTPS / TLS (and iOS PWA setup)

The web client's per-device **client key** lives in `localStorage`
(`xi-client-key`, see [client-auth.md](client-auth.md)). On iOS this is a
problem over plain HTTP: Safari treats an insecure origin's storage as
disposable and **evicts the localStorage bucket** for a backgrounded
home-screen PWA. The key vanishes, so the device shows up as a brand-new
client and has to be re-paired on almost every launch.

The fix is to serve the web client over **HTTPS** (a secure origin), which
makes `localStorage` durable. This page documents how the server does TLS and
how to trust the certificate on a new device — **iOS in particular**, which
has the most finicky flow.

> If you're on a Tailscale (SaaS) tailnet, prefer `tailscale serve` /
> `tailscale cert` — you get a real, already-trusted cert and can skip the
> local-CA dance entirely. The steps below are for setups where that isn't
> available (e.g. a self-hosted **Headscale** control server on a `*.ts.local`
> domain, which does not issue certs).

## How the server does TLS

`xi.server.ws` runs **two listeners** because Bun binds one protocol per port:

| Port | Protocol | For |
|---|---|---|
| `7474` (`XI_PORT`) | `ws://` / `http://` | the local TUI + any existing clients — always plain, never changes |
| `7443` (`XI_TLS_PORT`) | `wss://` / `https://` | browsers / the iOS PWA — only exists when a cert is configured |

- The plain port is left untouched so the TUI (`ws://localhost:7474`) and
  already-paired clients keep working with **no re-pairing**.
- TLS **auto-enables** when a cert + key are found; otherwise only the plain
  listener runs. `resolve-tls` looks for, in order:
  1. `XI_TLS_CERT` / `XI_TLS_KEY` env paths, else
  2. the default `~/.config/xi/tls/xi.crt` + `~/.config/xi/tls/xi.key`.
- Override the TLS port with `XI_TLS_PORT` (default `7443`).
- The web client picks the scheme/port from the page it was loaded from
  (`xi.web.core/ws-url`): open the page over `https://host:7443` and it dials
  `wss://host:7443` automatically. No client config needed.

On start the server logs which listeners came up:

```
[ws] Listening on ws://localhost:7474 + wss://localhost:7443
```

## One-time server setup (on the machine running `bb serve`)

Uses [mkcert](https://github.com/FiloSottile/mkcert) to create a local CA and
a leaf cert. On NixOS, pull it in ephemerally with `nix-shell -p mkcert`.

1. **Create the local CA** (once per machine). This writes the root to
   `~/.local/share/mkcert/rootCA.pem`:

   ```bash
   nix-shell -p mkcert --run 'mkcert -install'
   mkcert -CAROOT   # prints ~/.local/share/mkcert
   ```

2. **Issue a cert** into `~/.config/xi/tls/`, covering **every** hostname/IP a
   device might use to reach the server (tailnet name, tailnet IP, localhost):

   ```bash
   mkdir -p ~/.config/xi/tls && cd ~/.config/xi/tls
   nix-shell -p mkcert --run \
     'mkcert -cert-file xi.crt -key-file xi.key \
        desktop.ts.local desktop 192.0.2.10 localhost 127.0.0.1'
   ```

   Replace `desktop.ts.local` / `192.0.2.10` with your own tailnet hostname and
   IP. Names/IPs not in the cert's SANs will fail with "not private".

3. **Restart the server** so it picks up the cert:

   ```bash
   bb serve:restart
   ```

   Confirm the log shows `... + wss://localhost:7443`. Quick check from the host:

   ```bash
   curl --cacert ~/.local/share/mkcert/rootCA.pem https://<host>:7443/ -o /dev/null -w '%{http_code}\n'
   ```

4. **Make the root CA downloadable** for device onboarding. Bun serves `.crt`
   with `Content-Type: application/x-x509-ca-cert`, which is exactly what
   triggers iOS's profile-install prompt — so drop the root into the served
   public dir:

   ```bash
   cp ~/.local/share/mkcert/rootCA.pem resources/public/mkcert-rootCA.crt
   ```

   It's the **public** root cert (no private key), served over the plain port,
   so this is safe. Leave it in place if you onboard devices regularly; it is
   git-ignored and not part of the app. Delete it if you'd rather not serve it.

## iOS device setup (per device)

Do this **in real Safari** — not Chrome, not an in-app browser (Slack,
Tailscale, etc.). The profile-download flow only works in Safari.

1. Open the root cert:
   **`http://<host>:7474/mkcert-rootCA.crt`** (plain port — the device isn't
   trusted yet, so it can't use https).
2. Safari: *"This website is trying to download a configuration profile"* →
   **Allow**. Download it **once**, then leave Safari — re-tapping the link can
   invalidate the pending profile.
3. **Install the profile:** Settings → General → **VPN & Device Management** →
   tap **"mkcert development CA"** → **Install** (enter passcode) → Install.
4. **Enable full trust** — this is the step everyone misses, and without it you
   still get "not private": Settings → General → About → **Certificate Trust
   Settings** → toggle **ON** the `mkcert …` root.
   - **Not showing up there?** iOS often won't surface a freshly-installed root
     until a **reboot**. Restart the phone, then recheck. First remove any
     duplicate/stale `mkcert` profiles from *VPN & Device Management* so only
     one remains.
5. Open **`https://<host>:7443`** in Safari → it should load with no warning.
6. **Add to Home Screen** from that `https` page (delete any old icon that
   still points at the `http` URL first).
7. **Pair once:** the pairing code prints in the server log and the web banner;
   approve with `bb serve:approve <code>` (see [client-auth.md](client-auth.md)).
   Because the origin is now secure, the key persists — no more re-pairing.

## Other devices (brief)

- **macOS / Linux / desktop browsers on the host:** `mkcert -install` already
  added the root to the OS/browser trust store — just open `https://<host>:7443`.
- **Other machines:** copy `~/.local/share/mkcert/rootCA.pem` over and import it
  into the OS/browser trust store, then use the `https://<host>:7443` URL.
- **Android (Chrome):** Settings → Security → Encryption & credentials →
  Install a certificate → **CA certificate**, then pick `rootCA.pem`.

## Troubleshooting

- **The hostname doesn't resolve at all (`pi.home` / every `.home` domain fails,
  not just Xi)** — this is a DNS problem, not a cert problem; the cert only
  matters *after* the name resolves. If you reach the server fine by its
  Tailscale IP (e.g. `http://100.x.y.z:7474`) but the name never loads, the
  device isn't using the tailnet's DNS. Fixes, in order:
  1. **iOS/iPadOS:** open the **Tailscale app → Settings → turn ON "Use
     Tailscale DNS settings"**. iOS otherwise keeps its own resolver, which has
     never heard of `pi.home`. (This is the one that's easy to miss — installing
     the cert and being "connected" to the tailnet is not enough on its own.)
  2. In the Tailscale **admin console → DNS**, add the pi as a **nameserver**
     and set up **Split DNS** so the `home` domain is routed to it.
  3. Confirm the pi actually runs a DNS server (dnsmasq / Pi-hole / CoreDNS)
     that answers `pi.home` → its IP.
- **"This connection is not private"** — the CA isn't trusted. Confirm the
  `mkcert` root is toggled **on** in *Certificate Trust Settings* (iOS). If it
  won't appear there, reboot. Also verify the hostname/IP you're visiting is in
  the cert's SANs (re-issue in step 2 to add it).
- **Cert installs but never appears in Certificate Trust Settings** — reboot the
  device; remove duplicate `mkcert` profiles first. (This was the final blocker
  during initial setup.)
- **TUI stuck reconnecting** — it must use the **plain** port
  (`ws://…:7474`), not the TLS port. TLS is deliberately kept off `7474` so the
  TUI never has to deal with certs.
- **PWA still forgets the key** — make sure you opened the **`https://…:7443`**
  URL (not `http`) and re-added the home-screen icon from that page; an old icon
  keeps launching the insecure origin whose storage still gets evicted.
- **Root cert 404s on download** — you didn't copy it into `resources/public`
  (step 4), or you're hitting the wrong port (use the plain `:7474`).
