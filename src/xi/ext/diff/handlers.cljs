(ns xi.ext.diff.handlers
  "Pure handlers shared by the diff extension's node core and its browser
   half. :ui/diff-open is broadcast to the room like any buffer install, so
   every client (and a later joiner, via the room snapshot) has the diff in
   its buffer list; only the client that ran /diff switches to it
   (xi.buffers/switch-here?)."
  (:require [xi.buffers :as buffers]
            [xi.core.state :as state]))

(defn diff-open
  "Diff text came back from :diff/load — install it as a :diff buffer and
   show it on the originating client. One buffer per :source (the /diff
   argument: nil = the session diff, `git`, `staged`, `commit:<sha>`, …), so
   re-running a source — or re-rendering it with the other engine — replaces
   that buffer in place while other sources stay open. :engine records the
   renderer; only :git diffs are unified and get :diff? true (the interactive
   viewer). :difft output is ANSI structural text shown as a plain buffer."
  [st {:keys [room-id title text engine commit source] :as ev}]
  (when (state/get-room st room-id)
    (let [engine (or engine :git)
          id     (buffers/diff-id source)]
      {:state (-> st
                  (update-in [:rooms room-id] buffers/install id
                             {:kind :diff :title title :text text :engine engine
                              :source source
                              :diff? (= engine :git)
                              ;; commit metadata for single-commit diffs; nil
                              ;; for every other source.
                              :commit commit}
                             (:event/ts ev))
                  (cond-> (buffers/switch-here? st ev)
                    (assoc-in [:rooms room-id :ui :active-buffer] id)))})))
