(ns xi.ext.diff.web
  "Browser half of the diff extension — composed by xi.web.core (never loaded
   by the node builds). /diff runs server-side; this half applies the
   :ui/diff-open event (delivered only to the client that ran /diff) with the
   same pure reducer so the room's :diff buffer installs and the Diff tab opens
   in that web client."
  (:require [xi.ext.diff.handlers :as handlers]))

(def extension
  {:id       :diff
   :handlers {:ui/diff-open handlers/diff-open}})
