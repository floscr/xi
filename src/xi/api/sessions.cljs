(ns xi.api.sessions
  "Saved sessions for extensions: what a session id stands for.

     (summaries ctx ids) → [{:session-id :name :cwd :last-accessed} …]

   Synchronous and read-only, like xi.api.user: it reads the session
   metadata Xi keeps itself (~/.config/xi/sessions and the Claude CLI's
   transcripts) and exposes only these four fields, not a conversation. The
   extension behind `ctx` is proven by its token (xi.api.core/caller).

   An extension that keeps session ids (xi.api.user state, e.g. favorites)
   uses it to show them by name. To open one, dispatch `:session/resume` from
   a command (xi.ext.user.guard)."
  (:require [xi.api.core :as core]
            [xi.session :as session]))

(defn- summary [id]
  (or (session/find-session-by-id id)
      (session/find-personal-agent-session-by-id id)))

(defn summaries
  "→ the summaries of the sessions `ids` name, in the order of `ids`. An id no
   session has (deleted, or never saved) is left out."
  [ctx ids]
  (core/caller ctx)
  (->> ids
       (keep #(when (string? %) (summary %)))
       (mapv #(select-keys % [:session-id :name :cwd :last-accessed]))))
