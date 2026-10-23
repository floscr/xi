(ns xi.ext.subagent.web
  "Browser half of the sub-agents extension.

   The server broadcasts every room-scoped :subagent/* event to the room's
   clients; this ext registers the shared pure state handlers so those
   broadcasts build the [:ext :subagents] state on the client, which the
   Sub-agents panel (xi.web.views) renders live below the conversation.

   The turn itself only ever runs server-side, so the :subagent/start effect
   the spawn handler emits is a no-op here."
  (:require [xi.ext.subagent.handlers :as h]
            [xi.ext.subagent.threads :as threads]
            [xi.web.appearance :as appearance]
            [xi.web.layout :as wlayout]))

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

(defn- forward
  "Send a user-initiated event to the server, applying nothing here."
  [_st ev]
  (when-not (:remote? ev)
    {:effects [[:ws/send ev]]}))

(defn- dismiss
  "Apply the shared dismiss; a sub-agent this client had open in place of the
   chat (xi.web.views/subagent-view) hands the view back to the chat. A click
   here is forwarded so a reload doesn't bring the panel back."
  [st {:keys [room-id] :as ev}]
  (when-let [res (h/dismiss st ev)]
    (let [view  (get-in st [:rooms room-id :ui :active-buffer])
          gone? (and (h/find-child st room-id view)
                     (not (h/find-child (:state res) room-id view)))]
      (cond-> res
        gone?              (assoc-in [:state :rooms room-id :ui :active-buffer] :chat)
        (not (:remote? ev)) (assoc :effects [[:ws/send ev]])))))

(defn- turn-end
  "The shared turn-end, plus: with answers set to open in a window
   (appearance :subagent-answers :pane), a finished background child of the
   chat on screen shows in the right sidebar (xi.web.layout) without taking
   focus; the chat keeps a marker row (xi.web.views/subagent-marker-post)."
  [st {:keys [room-id sub-id] :as ev}]
  (when-let [res ((:subagent/turn-end h/handlers) st ev)]
    (let [st' (or (:state res) st)]
      (cond-> res
        (and (= :pane (:subagent-answers (appearance/effective-in st')))
             (not (h/inline-sub? sub-id))
             (= room-id (:id (:room (wlayout/room-ctx st')))))
        (update :effects (fnil conj [])
                [:app/dispatch {:type :layout/open-view :view sub-id :where :right}])))))

(defn- reply
  "A reply to a finished sub-agent runs server-side: send the user's, apply
   the server's echo (which every client of the room gets)."
  [st ev]
  (if (:remote? ev)
    (h/reply st ev)
    {:effects [[:ws/send ev]]}))

(defn- thread-close
  "Close a thread here at once and on the server, which stops its running
   turns; its echo applies the same state."
  [st ev]
  (when-let [res (threads/close st ev)]
    (cond-> (select-keys res [:state])
      (not (:remote? ev)) (assoc :effects [[:ws/send ev]]))))

(def extension
  {:id       ext-id
   :init     {:room {:agents []}}
   ;; The hydrate handler is composed per half, so the mirror filters a
   ;; resumed chat's saved sub-agents the same way.
   :persist-room h/persisted
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
                    :subagent/dismiss dismiss
                    :subagent/turn-end turn-end
                    :subagent/reply reply
                    ;; A sidebar row's button (xi.web.views/session-buffer-rows):
                    ;; roomless, by session — the server finds the live room
                    ;; and re-dispatches the room event (xi.ext.subagent), which
                    ;; mirrors back if this client is in that room.
                    :session/subagent-stop    forward
                    :session/subagent-dismiss forward
                    :subagent/promote promote
                    :subagent/promoted promoted
                    ;; Explain button on a permission-gated tool block: the
                    ;; spawn runs server-side, so forward the click; the
                    ;; :subagent/spawn it causes comes back as a mirrored
                    ;; broadcast.
                    :subagent/explain-call forward
                    ;; A question about a block (xi.ext.subagent.threads):
                    ;; the spawn comes back as a mirrored broadcast.
                    :subagent/ask forward
                    :subagent/thread-close thread-close
                    ;; Make a saved child a listed session: disk work, so
                    ;; the server's; :subagent/upgraded comes back.
                    :subagent/upgrade forward
                    :session/subagent-upgrade forward
                    ;; The queue popover's "Run in a sub-agent": the queue
                    ;; and the spawn are server state, both come back as
                    ;; mirrored broadcasts.
                    :subagent/run-queued forward)
   :fx       {:subagent/start (fn [_ _] nil)
              :subagent/steer (fn [_ _] nil)}})
