(ns xi.ext.file-view.core
  "File viewer extension (node half) — opens the full contents of a file that a
   Write/Edit tool touched into a :file buffer of the room, so the web client
   can view it in a tab instead of the truncated tool-block preview.

   The web client dispatches :file/open (with the file path) which forwards to
   the server. This node half reads the file from disk relative to the room's
   cwd (the server owns the I/O, so this works for both Write and Edit), then
   installs it via the generic :ui/buffer-set / :ui/buffer-switch core handlers,
   which broadcast + mirror to clients: every client gets the buffer, only the
   one that asked switches to it (the switch carries its :client-id).
   :file/open is not broadcast (it's a client→server request, not room state)."
  (:require ["node:fs" :as fs]
            [xi.buffers :as buffers]
            [xi.core.state :as state]
            [xi.tools.fs :as tfs]))

(defn- file-open
  "A client asked to view a file — defer the read to the :file/load effect."
  [_st {:keys [room-id path client-id]}]
  (when (and room-id path)
    {:effects [[:file/load (cond-> {:room-id room-id :path path}
                             client-id (assoc :client-id client-id))]]}))

(defn- file-load-fx
  "Read the file relative to the room's cwd and install it as a :file buffer
   (one per path, xi.buffers/file-id), then show it on the asking client. On
   failure the buffer's text carries the error: the web client has no status
   line, so a :ui/status would fail silently there."
  [{:keys [dispatch! state]} {:keys [room-id path client-id]}]
  (let [room     (state/get-room state room-id)
        cwd      (or (:cwd room) (.cwd js/process))
        resolved (tfs/resolve-path path cwd)
        text     (try
                   (if (fs/existsSync resolved)
                     (fs/readFileSync resolved "utf8")
                     (str "File not found:\n" resolved))
                   (catch :default e
                     (str "Could not read " resolved ":\n" (.-message e))))
        id       (buffers/file-id path)]
    (dispatch! {:type :ui/buffer-set :room-id room-id :buffer-id id
                :buffer {:kind :file :title path :path path :text text}})
    (dispatch! (cond-> {:type :ui/buffer-switch :room-id room-id :buffer-id id}
                 client-id (assoc :client-id client-id)))))

(def extension
  {:id           :file-view
   :handlers     {:file/open file-open}
   :fx           {:file/load file-load-fx}
   :no-broadcast #{:file/open}})
