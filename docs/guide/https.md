# HTTPS

Serve the web client over HTTPS so a phone keeps its pairing, and trust the
certificate on each device. Needed on iPhone; optional elsewhere.

## Why

The web client's device key lives in the browser's storage. Over plain HTTP,
iOS treats that storage as disposable for a home-screen app and clears it,
so the phone asks to pair on almost every launch. A secure origin makes the
storage durable.

If your devices share a Tailscale network, `tailscale serve` and
`tailscale cert` give you a trusted certificate without any of the steps
below. The rest of this page is for networks where that is not available.

## How the server does it

The server keeps the plain port and adds a second listener when it finds a
certificate:

| Port | Protocol | Used by |
| --- | --- | --- |
| `7474` (`XI_PORT`) | `http://` and `ws://` | The terminal client and anything already paired |
| `7443` (`XI_TLS_PORT`) | `https://` and `wss://` | Browsers and the phone |

It looks for `XI_TLS_CERT` and `XI_TLS_KEY`, else
`~/.config/xi/tls/xi.crt` and `xi.key`. The web client connects with the
scheme and port of the page it was loaded from, so there is nothing to
configure there. The startup line lists both listeners when TLS is on.

## Set up the server

With [mkcert](https://github.com/FiloSottile/mkcert):

```sh
mkcert -install        # a local certificate authority, once per machine
mkcert -CAROOT         # where its root certificate is

mkdir -p ~/.config/xi/tls && cd ~/.config/xi/tls
mkcert -cert-file xi.crt -key-file xi.key \
  my-desktop my-desktop.local 192.0.2.10 localhost 127.0.0.1
```

List every name and address a device might use. A name that is not in the
certificate fails with "not private". Restart the server and check:

```sh
curl --cacert "$(mkcert -CAROOT)/rootCA.pem" https://my-desktop:7443/ -o /dev/null -w '%{http_code}\n'
```

## Trust the certificate on a device

The root certificate (`rootCA.pem` in the directory `mkcert -CAROOT`
prints) has to be trusted once per device. It is public; the private key
stays on the server.

**Desktop browsers on the server's machine**: `mkcert -install` already did
it.

**Other computers**: copy `rootCA.pem` over and import it into the system or
browser trust store.

**Android (Chrome)**: Settings → Security → Encryption & credentials →
Install a certificate → CA certificate, then pick the file.

**iPhone**, in Safari (not Chrome, not an in-app browser):

1. Put the root certificate somewhere the phone can download it over plain
   HTTP, for example copy it as `rootCA.crt` into the directory the server
   serves. The `.crt` extension makes iOS offer to install it as a profile.
2. Open it in Safari, allow the download, and leave Safari.
3. Settings → General → VPN & Device Management → the mkcert profile →
   Install.
4. Settings → General → About → Certificate Trust Settings → turn the mkcert
   root on. This step is easy to miss; without it you still get "not
   private". If the root is not listed, restart the phone.
5. Open `https://my-desktop:7443` in Safari. It should load with no warning.
6. Add it to the home screen from that page, and pair once.
