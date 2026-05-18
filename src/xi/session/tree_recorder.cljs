(ns xi.session.tree-recorder
  "Records runtime events into a session tree.
   Subscribes to the event bus and maps events to tree entries."
  (:require [xi.session.tree :as tree]))

(defn create
  "Create a tree recorder. Returns a map with:
     :tree       — the session tree atom
     :on-event   — (fn [event]) to feed runtime events
     :reset!     — (fn [new-tree-atom]) replace the underlying tree"
  [session-tree]
  (let [;; Mutable ref to the active tree (allows reset on session-cleared)
        tree-ref (atom session-tree)
        ;; Accumulate text deltas within a turn
        turn-text (atom "")
        ;; Track whether we're in a turn (for text accumulation)
        in-turn? (atom false)]

    {:tree-ref tree-ref

     :reset!
     (fn [new-tree]
       (reset! tree-ref new-tree)
       (reset! turn-text "")
       (reset! in-turn? false))

     :on-event
     (fn [event]
       (case (:type event)
         :user-message
         (tree/append! @tree-ref
                       (cond-> {:type "user-message"
                                :text (:text event)}
                         (seq (:images event)) (assoc :image-count (count (:images event)))))

         :turn-start
         (do (reset! turn-text "")
             (reset! in-turn? true))

         :text-delta
         (swap! turn-text str (:text event))

         :tool-start
         (do
           ;; Flush accumulated assistant text before tool calls
           (when (and @in-turn? (pos? (count @turn-text)))
             (tree/append! @tree-ref
                           {:type "assistant-text"
                            :text @turn-text})
             (reset! turn-text ""))
           (tree/append! @tree-ref
                         {:type "tool-use"
                          :name (:name event)
                          :tool-call-id (:id event)
                          :arguments (:arguments event)}))

         :tool-result
         (tree/append! @tree-ref
                       {:type "tool-result"
                        :tool-name (:id event)
                        :content (let [c (:content event)]
                                   ;; Truncate large tool results for the tree
                                   ;; Full content is in Claude's own JSONL
                                   (if (> (count (str c)) 2000)
                                     (str (subs (str c) 0 2000) "…")
                                     (str c)))
                        :is-error (:is-error event)})

         :turn-end
         (do
           ;; Flush any remaining assistant text
           (when (and @in-turn? (pos? (count @turn-text)))
             (tree/append! @tree-ref
                           {:type "assistant-text"
                            :text @turn-text}))
           (reset! turn-text "")
           (reset! in-turn? false)
           (tree/append! @tree-ref
                         {:type "turn-end"
                          :usage (:usage event)
                          :cost (:cost event)}))

         :model-changed
         (tree/append! @tree-ref
                       {:type "model-change"
                        :model (:model event)})

         :session-compacted
         (tree/append! @tree-ref
                       {:type "compaction"
                        :summary (:summary event)})

         ;; Ignore all other event types (busy-changed, thinking, tool-args, etc.)
         nil))}))
