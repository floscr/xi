(ns xi.buffers
  "Room buffers — the views a room keeps next to its chat: diffs, files, the
   system prompt, the event log, the shortcut list. Pure helpers over the
   room's `[:ui :buffers id → buffer]` map and `[:ui :active-buffer]`.

   A buffer is `{:kind :diff|:file|:prompt|:events|:keys :title … :opened-at
   ms …}` plus its kind's payload (`:text`, `:path`, `:engine`, …). Several
   buffers of one kind coexist, so ids are strings built from what they show
   (`file-id`, `diff-id`); the singletons keep a keyword id equal to their kind
   (`:prompt`, `:events`, `:keys`), which `kind` falls back to.

   The buffer list is room state (it mirrors to every client and survives the
   room's close — the server parks it per session, see
   xi.server.room-manager); which buffer a client looks at is that client's
   own (`switch-here?`)."
  (:require [clojure.string :as str]))

(def chat
  "The pseudo buffer id of the chat itself."
  :chat)

(defn kind
  "A buffer's kind: its `:kind`, else its keyword id (the singletons)."
  [id buf]
  (or (:kind buf) (when (keyword? id) id)))

(defn file-id
  "The id of the buffer showing the file at `path` (one per path)."
  [path]
  (str "file:" path))

(defn diff-id
  "The id of the buffer showing the diff of `source` — the `/diff` argument
   (`git`, `staged`, `commit:<sha>`, …); nil is the default session diff.
   Re-running the same source (or the same source through another renderer)
   replaces that buffer in place."
  [source]
  (str "diff:" (if (str/blank? source) "session-edits" source)))

(defn label
  "What a buffer is called in lists: its title, else its id."
  [id buf]
  (or (not-empty (:title buf))
      (if (keyword? id) (name id) (str id))))

(defn ordered
  "The `[id buffer]` pairs of a buffers map in opening order (ties by label)."
  [buffers]
  (sort-by (fn [[id buf]] [(or (:opened-at buf) 0) (label id buf)])
           buffers))

(defn summaries
  "The lobby-sized view of a buffers map: `[{:id :kind :title} …]` in opening
   order. What the sidebar lists for a session whose room is closed."
  [buffers]
  (mapv (fn [[id buf]] {:id id :kind (kind id buf) :title (label id buf)})
        (ordered buffers)))

(defn install
  "Install `buf` under `id` in `room`, stamped `:opened-at ts` unless a buffer
   with that id is already open (a refresh keeps its place in the order)."
  [room id buf ts]
  (let [existing (get-in room [:ui :buffers id])]
    (assoc-in room [:ui :buffers id]
              (assoc buf :opened-at (or (:opened-at existing) ts 0)))))

(defn close
  "Drop buffer `id` from `room`; a client looking at it lands back on the
   chat."
  [room id]
  (cond-> (update-in room [:ui :buffers] dissoc id)
    (= id (get-in room [:ui :active-buffer]))
    (assoc-in [:ui :active-buffer] chat)))

(defn close-all
  "Drop every buffer of `room` and show the chat."
  [room]
  (-> room
      (assoc-in [:ui :buffers] {})
      (assoc-in [:ui :active-buffer] chat)))

(defn viewers
  "Buffer presence of a room: `{buffer-id [user …]}` — who is looking at
   which view, from the room's `:members` (each `{:user :buffer}`; a member
   without a `:buffer` is on the chat). Users are distinct per view."
  [room]
  (reduce (fn [m {:keys [user buffer]}]
            (let [k (or buffer chat)]
              (if (some #{user} (get m k))
                m
                (update m k (fnil conj []) user))))
          {}
          (vals (:members room))))

(defn switch-here?
  "Should this process follow the view switch an event carries? A buffer is
   shared, the view of it is not: an auto-open (a `/diff` reply, a file read)
   switches only the client that asked. True when the event names no
   originating client, when this process has none of its own (standalone, the
   server — nothing renders there), or when the originator is this client
   (`[:connection :client-id]`, from `:auth/ok`)."
  [state ev]
  (let [own  (get-in state [:connection :client-id])
        from (:client-id ev)]
    (or (nil? from) (nil? own) (= from own))))
