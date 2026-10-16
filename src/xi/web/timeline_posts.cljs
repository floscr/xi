(ns xi.web.timeline-posts
  "Posts a web extension places in the chat timeline between history entries:
   an extension's `:timeline-posts` is (fn [state dispatch! room entries] →
   [{:pos p :key str :node hiccup}]). The chat view (xi.web.views/chat-view)
   draws each node before history entry `p` (`p` = the history length puts it
   at the end), outside any folded tool group; a `p` above the rendered window
   pins it at the window's top. Keys must be unique per room. The agent blocks
   (xi.ext.blocks.web) post this way, after the tool call that made them.

   The registry holds fns, so it lives outside the app state (like
   xi.web.buffer-views); built-in web halves register at init.")

(defonce ^:private registry (atom {}))

(defn register!
  "Add extension `ext-id`'s `:timeline-posts` fn."
  [ext-id f]
  (swap! registry assoc ext-id f))

(defn items
  "Every registered extension's posts for `room`, whose history is `entries`."
  [state dispatch! room entries]
  (when room
    (into [] (mapcat (fn [f] (f state dispatch! room entries))) (vals @registry))))
