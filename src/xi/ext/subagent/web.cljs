(ns xi.ext.subagent.web
  "Browser half of the sub-agents extension.

   The server broadcasts every room-scoped :subagent/* event to the room's
   clients; this ext registers the shared pure state handlers so those
   broadcasts build the [:ext :subagents] state on the client, which the
   Sub-agents panel (xi.web.views) renders live below the conversation.

   The turn itself only ever runs server-side, so the :subagent/start effect
   the spawn handler emits is a no-op here."
  (:require [xi.ext.subagent.handlers :as h]))

(def ^:private ext-id h/ext-id)

(defn- promote
  "Open-as-chat click: promotion runs server-side (the transcript lives in
   the server's throwaway config dir), so forward the request and remember
   which child we want to navigate to once :subagent/promoted comes back."
  [st {:keys [room-id sub-id] :as ev}]
  (when-not (:remote? ev)
    {:state   (assoc-in st [:rooms room-id :ext ext-id :pending-open] sub-id)
     :effects [[:ws/send ev]]}))

(defn- promoted
  "Apply the shared promoted handler; when this client initiated the open,
   navigate to the promoted session's chat page."
  [st {:keys [room-id sub-id session-id] :as ev}]
  (let [{st' :state :as res} (h/promoted st ev)
        st'     (or st' st)
        pending (get-in st' [:rooms room-id :ext ext-id :pending-open])]
    (if (and res (= pending sub-id))
      {:state   (update-in st' [:rooms room-id :ext ext-id] dissoc :pending-open)
       :effects [[:app/dispatch {:type :route/navigate :page :chat
                                 :session-id session-id}]]}
      res)))

(def extension
  {:id       ext-id
   :init     {:room {:agents [] :collapsed? false}}
   :handlers (assoc h/handlers
                    ;; Stop button: the abort must run server-side (the turn
                    ;; handle lives in the server's registry), so forward the
                    ;; user's click. Extension handlers install unwrapped on
                    ;; the web (local apply, no auto-forward), hence the
                    ;; explicit :ws/send; the server echo comes back tagged
                    ;; :remote? and is ignored here.
                    :subagent/abort
                    (fn [_st ev]
                      (when-not (:remote? ev)
                        {:effects [[:ws/send ev]]}))
                    ;; Dismiss mutates server state too (so a reload doesn't
                    ;; bring the panel back): apply locally, forward the click.
                    :subagent/dismiss
                    (fn [st ev]
                      (when-let [res (h/dismiss st ev)]
                        (cond-> res
                          (not (:remote? ev)) (assoc :effects [[:ws/send ev]]))))
                    :subagent/promote promote
                    :subagent/promoted promoted)
   :fx       {:subagent/start    (fn [_ _] nil)
              :subagent/promote! (fn [_ _] nil)}})
