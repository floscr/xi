(ns xi.ext.diff.web
  "Browser half of the diff extension — composed by xi.web.core (never loaded
   by the node builds). /diff runs server-side; this half applies the
   mirrored :ui/diff-open broadcast with the same pure reducer so the room's
   :diff buffer installs and the Diff tab opens in the web client."
  (:require [xi.ext.diff.handlers :as handlers]))

(def extension
  {:id       :diff
   :handlers {:ui/diff-open handlers/diff-open}})
