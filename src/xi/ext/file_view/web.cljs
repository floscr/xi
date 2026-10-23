(ns xi.ext.file-view.web
  "Browser half of the file-view extension — composed by xi.web.core (never
   loaded by the node builds). Clicking the view-file button on a Write/Edit
   tool block dispatches :file/open with the file path; this handler injects the
   active room-id and forwards it to the server, which reads the file and
   installs the :file buffer via the generic :ui/buffer-set broadcast. The
   buffer install + switch are handled by the core handlers, so no extra web
   handler is needed here. An :anchor (a link's #fragment) stays here: the
   buffer scrolls to that heading once it shows."
  (:require [xi.web.heading-anchor :as heading-anchor]))

(def extension
  {:id       :file-view
   :handlers {:file/open
              (fn [st {:keys [remote? path anchor] :as ev}]
                (when-not remote?
                  {:effects (cond-> [[:ws/send (-> ev
                                                   (assoc :room-id (:active-room st))
                                                   (dissoc :event/id :event/ts :anchor))]]
                              anchor (conj [:file/reveal-anchor {:path path :anchor anchor}]))}))}
   :fx       {:file/reveal-anchor
              (fn [_ {:keys [path anchor]}] (heading-anchor/reveal-in-file! path anchor))}})
