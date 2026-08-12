(ns xi.ext.subagent.web
  "Browser half of the sub-agents extension.

   The server broadcasts every room-scoped :subagent/* event to the room's
   clients; this ext registers the shared pure state handlers so those
   broadcasts build the [:ext :subagents] state on the client, which the
   Sub-agents panel (xi.web.views) renders live below the conversation.

   The turn itself only ever runs server-side, so the :subagent/start effect
   the spawn handler emits is a no-op here."
  (:require [xi.ext.subagent.handlers :as h]))

(def extension
  {:id       :subagents
   :init     {:room {:agents [] :collapsed? false}}
   :handlers h/handlers
   :fx       {:subagent/start (fn [_ _] nil)}})
