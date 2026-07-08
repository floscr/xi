(ns xi.auth
  "Client-key authentication store — the filesystem is the root of trust.

   Approved client keys live in ~/.config/xi/clients.edn (map of key →
   {:name :platform :approved-at :last-seen}). Pending pairing requests are
   mirrored to ~/.config/xi/pending-clients.edn so `bb serve:approve <code>`
   can approve a client by moving its entry across; the server polls the
   approved file while requests are pending (xi.server.ws).

   The local client key (~/.config/xi/client-key, used by the TUI) is
   implicitly trusted: anything that can read that file already runs as the
   user and needs no further gating. This scheme therefore protects against
   malicious webpages (WS is not subject to CORS), other users on the
   machine, sandboxed processes, and network peers — NOT against malware
   running as the same user, which could read the key files directly.

   Node-only (server + TUI client); the web client keeps its key in
   localStorage and never touches this namespace."
  (:require [cljs.reader :as reader]))

(def ^:private fs (js/require "node:fs"))
(def ^:private path (js/require "node:path"))
(def ^:private os (js/require "node:os"))
(def ^:private crypto (js/require "node:crypto"))

(def ^:private MODE-0600 384)

(defn- config-dir []
  (.join path (.homedir os) ".config" "xi"))

(defn clients-file [] (.join path (config-dir) "clients.edn"))
(defn pending-file [] (.join path (config-dir) "pending-clients.edn"))
(defn client-key-file [] (.join path (config-dir) "client-key"))

(defn- read-edn-file [file]
  (try
    (when (.existsSync fs file)
      (let [m (reader/read-string (.readFileSync fs file "utf8"))]
        (when (map? m) m)))
    (catch :default _ nil)))

(defn- write-edn-file! [file m]
  (.mkdirSync fs (config-dir) #js {:recursive true})
  (.writeFileSync fs file (str (pr-str m) "\n") #js {:mode MODE-0600}))

;; ── Approved clients ──────────────────────────────────────────────────────────

(defn approved-clients []
  (or (read-edn-file (clients-file)) {}))

(defn local-client-key
  "The key the local TUI identifies with, or nil. Implicitly trusted by the
   server (same filesystem, same user)."
  []
  (try
    (when (.existsSync fs (client-key-file))
      (let [k (.trim (.readFileSync fs (client-key-file) "utf8"))]
        (when (seq k) k)))
    (catch :default _ nil)))

(defn approved? [client-key]
  (boolean (and (string? client-key)
                (or (contains? (approved-clients) client-key)
                    (= client-key (local-client-key))))))

(defn approve! [client-key info]
  (write-edn-file! (clients-file)
                   (assoc (approved-clients) client-key
                          (merge info {:approved-at (js/Date.now)}))))

(defn touch!
  "Record :last-seen (and freshen name/platform) for an already-approved key.
   No-op for keys only trusted via the local key file."
  [client-key info]
  (let [clients (approved-clients)]
    (when (contains? clients client-key)
      (write-edn-file! (clients-file)
                       (update clients client-key merge info
                               {:last-seen (js/Date.now)})))))

;; ── Pending pairing requests (mirrored for `bb serve:approve`) ────────────────

(defn read-pending []
  (or (read-edn-file (pending-file)) {}))

(defn write-pending! [m]
  (write-edn-file! (pending-file) m))

(defn add-pending! [code entry]
  (write-pending! (assoc (read-pending) code entry)))

(defn remove-pending! [code]
  (write-pending! (dissoc (read-pending) code)))

(defn gen-code
  "Random 4-digit pairing code not in `taken` (a set of code strings)."
  [taken]
  (loop []
    (let [n    (.readUInt32BE (.randomBytes crypto 4) 0)
          code (str (+ 1000 (mod n 9000)))]
      (if (contains? taken code) (recur) code))))

;; ── Client-side key (TUI) ─────────────────────────────────────────────────────

(defn ensure-client-key!
  "Read ~/.config/xi/client-key, generating it (0600) on first run."
  []
  (or (local-client-key)
      (let [k (.toString (.randomBytes crypto 32) "hex")]
        (.mkdirSync fs (config-dir) #js {:recursive true})
        (.writeFileSync fs (client-key-file) (str k "\n") #js {:mode MODE-0600})
        k)))
