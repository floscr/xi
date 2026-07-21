(ns xi.ext.file-view.web
  "Browser half of the file-view extension — composed by xi.web.core (never
   loaded by the node builds). Clicking the view-file button on a Write/Edit
   tool block dispatches :file/open with the file path; this handler injects the
   active room-id and forwards it to the server, which reads the file and
   installs the :file buffer via the generic :ui/buffer-set broadcast. The
   buffer install + switch are handled by the core handlers, so no extra web
   handler is needed here.")

(def extension
  {:id       :file-view
   :handlers {:file/open
              (fn [st {:keys [remote?] :as ev}]
                (when-not remote?
                  {:effects [[:ws/send (-> ev
                                           (assoc :room-id (:active-room st))
                                           (dissoc :event/id :event/ts))]]}))}})
