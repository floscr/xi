(ns xi.ext.yolo.web
  "Browser half of yolo mode — composed by xi.web.core (never loaded by the
   node builds). /yolo runs server-side and broadcasts :ext.yolo/set; this
   half applies it with the shared reducer, so the flag and its status line
   reach every web client of the room. The chat header shows the flag
   (xi.web.views/yolo-chip)."
  (:require [xi.ext.yolo :as yolo]))

(def extension
  {:id           :yolo
   ;; so the web's :ext.persist/hydrate mirror installs a restored flag too
   :persist-room (:persist-room yolo/extension)
   :handlers     {:ext.yolo/set
                  (fn [st {:keys [remote?] :as ev}]
                    (when remote? (yolo/set-enabled st ev)))}})
