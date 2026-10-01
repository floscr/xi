(ns xi.web.resubmit
  "Pure effect builder for the web chat's bubble resubmits (Retry / Edit-save).
   Kept out of xi.web.core so node-side tests can exercise it without pulling
   in the browser-only app shell.")

(defn fork-effects
  "Effects for a bubble resubmit: fork the conversation at `index` (truncate
   history to before it, like /tree edit), then resubmit `text` plus the
   message's `images` as a fresh prompt — straight into the joined room, or
   via :submit/pending when the viewed session has no room yet. `model` (the
   viewed room's current pick) rides along so the fork runs on it.

   Images must be re-sent explicitly: the fork truncates the history entry
   that held them, so a text-only resubmit would silently drop them."
  [{:keys [room-id sid index text images model]}]
  (let [submit (cond-> (if room-id
                         {:type :input/submit :room-id room-id :text text}
                         {:type :submit/pending :session-id sid :text text})
                 model        (assoc :model model)
                 (seq images) (assoc :images (vec images)))]
    [[:app/dispatch {:type :tree/navigate :room-id room-id :index index}]
     [:app/dispatch submit]]))
