(ns xi.web.timeline-posts
  "Posts a web extension places in the chat timeline between history entries:
   an extension's `:timeline-posts` is (fn [state dispatch! room entries
   sub-id] → [{:pos p :key str :node hiccup :hides i?}]). The chat view
   (xi.web.views/chat-view) draws each node before history entry `p` (`p` =
   the history length puts it at the end), outside any folded tool group; a
   `p` above the rendered window pins it at the window's top. `:hides i`
   leaves history entry `i` out, unless it holds a permission ask or answer.
   A sub-agent's view (xi.web.views/subagent-view) asks with its `sub-id`
   and its own history as `entries`; the chat with nil.
   Keys must be unique per room. The agent blocks (xi.ext.blocks.web) post
   this way, in place of the tool call that made them.

   The registry holds fns, so it lives outside the app state (like
   xi.web.buffer-views); built-in web halves register at init.")

(defonce ^:private registry (atom {}))

(defn register!
  "Add extension `ext-id`'s `:timeline-posts` fn."
  [ext-id f]
  (swap! registry assoc ext-id f))

(defn items
  "Every registered extension's posts for `room`, whose history is `entries`
   (or, with `sub-id`, for that sub-agent of the room, whose history it is)."
  ([state dispatch! room entries] (items state dispatch! room entries nil))
  ([state dispatch! room entries sub-id]
   (when room
     (into [] (mapcat (fn [f] (f state dispatch! room entries sub-id))) (vals @registry)))))
