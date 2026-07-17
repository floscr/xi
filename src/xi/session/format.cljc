(ns xi.session.format
  "Pure data helpers for session serialization.
   Shared between server (node:fs) and web client (localStorage).
   No I/O — just data shapes and key construction.")

;; ── Key construction ──────────────────────────────────────────────────────────
;; Mirrors filesystem path structure: base/collection/id

(defn session-key
  "Build a storage key for a session by id.
   Returns a path-like string: \"xi/sessions/{id}\""
  [session-id]
  (str "xi/sessions/" session-id))

(defn messages-key
  "Build a storage key for cached messages of a session.
   Returns: \"xi/messages/{session-id}\""
  [session-id]
  (str "xi/messages/" session-id))

(def sessions-list-key
  "Key for the cached sessions list."
  "xi/sessions-list")

(def pending-messages-key
  "Key for the pending messages queue."
  "xi/pending-messages")

;; ── Session shape ─────────────────────────────────────────────────────────────
;; Canonical session summary shape (same as server sends):
;; {:session-id  str
;;  :name        str|nil
;;  :timestamp   ISO-str|nil
;;  :last-accessed ISO-str|nil
;;  :user-messages int|nil
;;  :source      :xi|:claude}

(defn normalize-session
  "Ensure a session map has all expected keys with defaults."
  [s]
  (merge {:session-id nil
           :name nil
           :timestamp nil
           :last-accessed nil
           :user-messages nil
           :source :xi}
         s))

;; ── Pending message shape ─────────────────────────────────────────────────────
;; {:id          str         — client-generated unique id
;;  :session-id  str|nil     — which session this belongs to (nil = new)
;;  :room-id     str|nil     — room to send to
;;  :payload     map         — the raw command/prompt to send
;;  :timestamp   ISO-str     — when it was created
;;  :status      :pending|:sending|:failed}

(defn make-pending-message
  "Create a pending message record."
  [id session-id room-id payload]
  {:id id
   :session-id session-id
   :room-id room-id
   :payload payload
   :timestamp #?(:clj (.toString (java.time.Instant/now))
                 :cljs (.toISOString (js/Date.)))
   :status :pending})
