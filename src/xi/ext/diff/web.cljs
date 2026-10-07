(ns xi.ext.diff.web
  "Browser half of the diff extension — composed by xi.web.core (never loaded
   by the node builds). /diff runs server-side and broadcasts :ui/diff-open;
   this half applies it with the same pure reducer, so the diff buffer
   installs in every web client of the room and the one that ran /diff
   switches to it. A :ui/diff-open raised in this browser (the code-block
   menu's View diff, built from the tool's own diff text — no git run) is
   forwarded to the server instead of applied here, so that buffer too is
   shared with the room, listed in the sidebar and parked with the session;
   the server stamps our :client-id, so only we switch to it."
  (:require [xi.ext.diff.handlers :as handlers]))

(def extension
  {:id       :diff
   :handlers {:ui/diff-open
              (fn [st {:keys [remote?] :as ev}]
                (if remote?
                  (handlers/diff-open st ev)
                  {:effects [[:ws/send (dissoc ev :event/id :event/ts)]]}))}})
