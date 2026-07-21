(ns xi.ext.file-view.core
  "File viewer extension (node half) — opens the full contents of a file that a
   Write/Edit tool touched into the room's :file buffer, so the web client can
   view it in a tab instead of the truncated tool-block preview.

   The web client dispatches :file/open (with the file path) which forwards to
   the server. This node half reads the file from disk relative to the room's
   cwd (the server owns the I/O, so this works for both Write and Edit), then
   installs it via the generic :ui/buffer-set / :ui/buffer-switch core handlers,
   which broadcast + mirror to clients. :file/open is not broadcast (it's a
   client→server request, not room state)."
  (:require ["node:fs" :as fs]
            [xi.core.state :as state]
            [xi.tools.fs :as tfs]))

(defn- file-open
  "A client asked to view a file — defer the read to the :file/load effect."
  [_st {:keys [room-id path]}]
  (when (and room-id path)
    {:effects [[:file/load {:room-id room-id :path path}]]}))

(defn- file-load-fx
  "Read the file relative to the room's cwd and install it as the :file buffer,
   then switch to it. On failure, surface a status line instead."
  [{:keys [dispatch! state]} {:keys [room-id path]}]
  (let [room     (state/get-room state room-id)
        cwd      (or (:cwd room) (.cwd js/process))
        resolved (tfs/resolve-path path cwd)]
    (if-not (fs/existsSync resolved)
      (dispatch! {:type :ui/status :room-id room-id
                  :text (str "File not found: " path)})
      (try
        (let [text (fs/readFileSync resolved "utf8")]
          (dispatch! {:type :ui/buffer-set :room-id room-id :buffer-id :file
                      :buffer {:title path :path path :text text}})
          (dispatch! {:type :ui/buffer-switch :room-id room-id :buffer-id :file}))
        (catch :default e
          (dispatch! {:type :ui/status :room-id room-id
                      :text (str "Could not read " path ": " (.-message e))}))))))

(def extension
  {:id           :file-view
   :handlers     {:file/open file-open}
   :fx           {:file/load file-load-fx}
   :no-broadcast #{:file/open}})
