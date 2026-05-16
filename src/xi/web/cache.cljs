(ns xi.web.cache
  "LocalStorage-backed session cache for offline support.
   Mirrors the session filesystem pattern: paths → JSON blobs.
   Same data shapes as xi.session but backed by localStorage."
  (:require [xi.session.format :as fmt]))

;; ── Storage primitives ────────────────────────────────────────────────────────

(defn- store-get
  "Read a JSON value from localStorage by key. Returns nil if missing/corrupt."
  [k]
  (try
    (when-let [raw (.getItem js/localStorage k)]
      (js->clj (js/JSON.parse raw) :keywordize-keys true))
    (catch :default _ nil)))

(defn- store-set!
  "Write a value as JSON to localStorage."
  [k v]
  (try
    (.setItem js/localStorage k (js/JSON.stringify (clj->js v)))
    (catch :default e
      (js/console.warn "[cache] localStorage write failed:" e))))

(defn- store-remove!
  "Remove a key from localStorage."
  [k]
  (.removeItem js/localStorage k))

;; ── Sessions list ─────────────────────────────────────────────────────────────

(defn save-sessions!
  "Cache the home sessions list (from server handshake)."
  [sessions]
  (store-set! fmt/sessions-list-key (mapv fmt/normalize-session sessions)))

(defn load-sessions
  "Load cached sessions list. Returns [] if empty."
  []
  (or (store-get fmt/sessions-list-key) []))

;; ── Per-session messages ──────────────────────────────────────────────────────

(defn- keywordize-msg-type
  "Ensure :type is a keyword after JSON round-trip."
  [msg]
  (if (string? (:type msg))
    (update msg :type keyword)
    msg))

(defn save-messages!
  "Cache messages for a session (from server :history or accumulated events)."
  [session-id messages]
  (when session-id
    (store-set! (fmt/messages-key session-id) messages)))

(defn load-messages
  "Load cached messages for a session. Returns [] if not cached."
  [session-id]
  (if session-id
    (mapv keywordize-msg-type (or (store-get (fmt/messages-key session-id)) []))
    []))

(defn clear-messages!
  "Remove cached messages for a session."
  [session-id]
  (when session-id
    (store-remove! (fmt/messages-key session-id))))

;; ── Pending messages queue ────────────────────────────────────────────────────

(defn- keywordize-pending
  "Ensure :status and payload :type are keywords after JSON round-trip."
  [pm]
  (cond-> pm
    (string? (:status pm)) (update :status keyword)
    (string? (get-in pm [:payload :type])) (update-in [:payload :type] keyword)))

(defn load-pending
  "Load pending messages queue. Returns []."
  []
  (mapv keywordize-pending (or (store-get fmt/pending-messages-key) [])))

(defn save-pending!
  "Persist the pending messages queue."
  [pending]
  (if (seq pending)
    (store-set! fmt/pending-messages-key pending)
    (store-remove! fmt/pending-messages-key)))

(defn add-pending!
  "Add a message to the pending queue and persist."
  [pending-msg]
  (let [current (load-pending)]
    (save-pending! (conj current pending-msg))
    (conj current pending-msg)))

(defn remove-pending!
  "Remove a pending message by id and persist."
  [msg-id]
  (let [current (load-pending)
        updated (vec (remove #(= msg-id (:id %)) current))]
    (save-pending! updated)
    updated))

(defn clear-pending!
  "Clear all pending messages."
  []
  (store-remove! fmt/pending-messages-key))

;; ── Watched sessions (unread tracking) ────────────────────────────────────────

(def ^:private watched-key "xi/watched-sessions")

(defn load-watched-sessions
  "Load map of session-id → response-count-when-last-seen. Returns {}.
   Keys are strings (session IDs)."
  []
  (or (try
        (when-let [raw (.getItem js/localStorage watched-key)]
          (js->clj (js/JSON.parse raw)))
        (catch :default _ nil))
      {}))

(defn save-watched-sessions!
  "Persist the full watched sessions map."
  [watched]
  (store-set! watched-key watched))

(defn watch-session!
  "Mark a session as watched with its current response count."
  [session-id response-count]
  (when session-id
    (let [current (load-watched-sessions)]
      (save-watched-sessions! (assoc current session-id response-count)))))

(defn unwatch-session!
  "Remove a session from watched (user has seen it)."
  [session-id]
  (when session-id
    (let [current (load-watched-sessions)]
      (save-watched-sessions! (dissoc current session-id)))))

;; ── Room / active session tracking ───────────────────────────────────────────

(def ^:private last-room-key "xi/last-room")
(def ^:private last-session-key "xi/last-session-id")

(defn save-last-room!
  "Remember the last joined room-id + session-id for reconnect."
  [room-id session-id]
  (store-set! last-room-key {:room-id room-id :session-id session-id}))

(defn load-last-room
  "Load the last room info. Returns {:room-id :session-id} or nil."
  []
  (store-get last-room-key))

(defn clear-last-room! []
  (store-remove! last-room-key))
