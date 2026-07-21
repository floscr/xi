(ns xi.ext.diff.handlers
  "Pure handlers shared by the diff extension's node core and its browser
   half. The server delivers :ui/diff-open only to the client that ran /diff
   (originator-only), and both surfaces install the buffer with this same
   reducer, so the diff viewer is a client-local view."
  (:require [xi.core.state :as state]))

(defn diff-open
  "Diff text came back from :diff/load — install it as the :diff buffer and
   switch to it. :engine records the renderer; only :git diffs are unified and
   get :diff? true (the interactive viewer). :difft output is ANSI structural
   text shown as a plain buffer."
  [st {:keys [room-id title text engine]}]
  (when (state/get-room st room-id)
    (let [engine (or engine :git)]
      {:state (-> st
                  (assoc-in [:rooms room-id :ui :buffers :diff]
                            {:title title :text text :engine engine
                             :diff? (= engine :git)})
                  (assoc-in [:rooms room-id :ui :active-buffer] :diff))})))
