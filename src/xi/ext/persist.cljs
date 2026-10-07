(ns xi.ext.persist
  "Keeps the room state extensions mark `:persist-room` (see xi.ext.core)
   across `bb serve:restart` and reaped rooms.

   The state lives with the chat, in `<session-id>.ext.edn` beside its
   metadata. `install!` taps the app's event stream: whenever a room's
   persisted slices change they are written out; when a chat is resumed into a
   room they are read back and installed with :ext.persist/hydrate (a
   handler composed by xi.ext.core, so a client mirror replays it too).

   Runs where the room is owned — the server and standalone — never in a
   client mirror, which has no tap installed."
  (:require [cljs.tools.reader :as tr]
            [xi.ext.core :as ext]
            [xi.ext.manager :as manager]
            [xi.session :as session]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(defn- read-saved
  "The slices saved at `fp` ({ext-id slice}), or nil when there are none or the
   file is unreadable."
  [fp]
  (when (fs/existsSync fp)
    (try
      (let [saved (binding [tr/*default-data-reader-fn* (fn [_tag v] v)]
                    (tr/read-string (str (fs/readFileSync fp "utf8"))))]
        (when (map? saved) saved))
      (catch :default e
        (js/console.error "[persist] ignoring unreadable" fp (.-message e))
        nil))))

(defn- write-saved!
  "Save `data` ({ext-id slice}) at `fp`; nothing left to keep removes the file."
  [fp data]
  (try
    (if (seq data)
      (do (fs/mkdirSync (path/dirname fp) #js {:recursive true})
          (fs/writeFileSync fp (pr-str data) "utf8"))
      (when (fs/existsSync fp) (fs/unlinkSync fp)))
    (catch :default e
      (js/console.error "[persist] saving" fp "failed:" (.-message e)))))

(defn- on-event!
  "The tap body. `seen` is {room-id data}: what each room was last known to
   hold. A room first seen here only gets a baseline — writing then would
   replace the saved state with a fresh room's empty one before the chat's
   :session/resumed has put it back."
  [seen mgr dispatch! {:keys [type room-id]} state]
  (let [specs (:persist-room (manager/composed mgr))
        room  (get-in state [:rooms room-id])
        sess  (:session room)]
    (when (= :room/close type) (swap! seen dissoc room-id))
    (when (and room-id room (seq specs))
      (if (= :session/resumed type)
        (when (:id sess)
          (when-let [saved (read-saved (session/ext-state-sidecar-path sess))]
            (dispatch! {:type :ext.persist/hydrate :room-id room-id :slices saved})))
        (let [data (ext/persisted-room-state specs room)]
          (when (and (contains? @seen room-id)
                     (not= data (get @seen room-id))
                     (:id sess))
            (write-saved! (session/ext-state-sidecar-path sess) data))
          (swap! seen assoc room-id data))))))

(defn install!
  "Tap `app` ({:dispatch! :add-tap!}) so extensions' `:persist-room` state is
   saved with its chat and restored on resume. `mgr` is the extension manager,
   read live so enabling / disabling an extension is followed."
  [{:keys [dispatch! add-tap!]} mgr]
  (let [seen (atom {})]
    (add-tap! (fn [event state] (on-event! seen mgr dispatch! event state)))))
