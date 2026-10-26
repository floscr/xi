(ns xi.ext.subagent.threads
  "Question threads on blocks, and the node addresses that make a chat and
   its sub-agents one graph. Pure and browser-safe: the node ext registers
   the handlers, the web renders with the rest.

   A node is a block of the conversation:
     {:index i :kind k :call-id id :hint s}           entry i of the room history
     {:sub-id s :index i :kind k :call-id id :hint s} entry i of sub-agent s
     {:sub-id s :hint label}                          sub-agent s itself
   :kind, :call-id (tool calls) and :hint (the start of its text) find the
   block again when the history is rebuilt from the transcript, which drops
   thinking blocks and shifts indices (`locate`). Agents name nodes the way
   parent_history numbers entries: #12, sa-x#4, sa-x (`address`).

   A thread is the ask sub-agents sharing a :thread id (the first one's id),
   in spawn order; each carries the :node and its :question. They are
   ordinary children of the room (xi.ext.subagent.handlers), so they mirror,
   save and link like any other; the web renders them under their node
   instead of in the sub-agent posts (h/inline-sub?)."
  (:require [clojure.string :as str]
            [xi.core.state :as state]
            [xi.ext.subagent.handlers :as h]))

;; ── Nodes ────────────────────────────────────────────────────────────────────

;; Built in xi.ext.subagent.handlers, whose spawn records one (:after).
(def entry-node h/entry-node)

(defn agent-node
  "The node of sub-agent `sub-id` itself; its `label` is the hint a thread
   shows."
  ([sub-id] (agent-node sub-id nil))
  ([sub-id label]
   (cond-> {:sub-id sub-id}
     (seq label) (assoc :hint label))))

(defn entry-node?
  "A node naming one entry (of the room's or a sub-agent's history), not a
   whole sub-agent."
  [node]
  (number? (:index node)))

(defn- matches?
  "Same kind and hint; a hint taken while the entry still streamed is a
   prefix of its final one."
  [node e]
  (and (= (:kind node) (:kind e))
       (or (nil? (:hint node))
           (some-> (h/hint e) (str/starts-with? (:hint node))))))

(defn- call-index
  "Index of the tool call with id `cid` in `history`, or nil."
  [history cid]
  (when cid
    (first (keep-indexed (fn [j e] (when (and (= :tool-call (:kind e)) (= cid (:id e))) j))
                         history))))

(defn locate
  "Index of `node`'s entry in `history`: its tool call by id, else the entry
   at its :index when kind and hint still match, else the nearest one that
   does, else its :index clamped. nil for an empty history or a node without
   an index."
  [history node]
  (let [history (vec history)
        n       (count history)
        i       (:index node)]
    (when (and (pos? n) (number? i))
      (or (call-index history (:call-id node))
          (when (and (< i n) (matches? node (nth history i))) i)
          (->> (range n)
               (filter #(matches? node (nth history %)))
               (sort-by #(js/Math.abs (- % i)))
               first)
          (min i (dec n))))))

(defn address
  "How agents and the UI name `node`: #12, sa-x#4, sa-x."
  [{:keys [sub-id index]}]
  (str sub-id (when (number? index) (str "#" index))))

;; ── Where a child's posts go in a (possibly rebuilt) history ─────────────────

(defn anchor-of
  "Where the web timeline posts `child`'s card in `history`: after its
   spawn_subagent call when that is found, else after the entry it spawned
   after (:after), else at its :anchor, clamped. The history the web shows
   may be rebuilt from the transcript, shorter than the live one :anchor
   counted."
  [history child]
  (let [n (count history)]
    (if-let [idx (or (call-index history (:call-id child))
                     (locate history (:after child)))]
      (inc idx)
      (min n (or (:anchor child) n)))))

(defn ended-anchor-of
  "Where `child`'s result goes: its :ended-anchor moved with its card
   (`anchor-of`), never before the card, clamped. nil while it runs."
  [history child]
  (when-let [end (:ended-anchor child)]
    (let [a     (anchor-of history child)
          shift (- a (or (:anchor child) a))]
      (min (count history) (max a (+ end shift))))))

;; ── Node text ────────────────────────────────────────────────────────────────

(def ^:private node-clips {:msg-chars 8000 :arg-chars 3000 :result-chars 4000})

(defn- child-text [{:keys [id label task status result]}]
  (str "[sub-agent " id "] " (or label task) "\n"
       "status: " (name (or status :running)) "\n"
       (when (seq task) (str "task:\n" (h/clip task 3000) "\n"))
       (when (seq result) (str "result:\n" (h/clip result 8000)))))

(defn- entry-block-text [e]
  (if (= :thinking (:kind e))
    (str "[thinking]\n" (h/clip (:text e) (:msg-chars node-clips)))
    (h/entry-text e node-clips)))

(defn resolve-node
  "`node` in room `room-id` of `st`: {:node <with its current :index>
   :text <the block as transcript text>}, or nil when it is gone."
  [st room-id {:keys [sub-id] :as node}]
  (let [child (when sub-id (h/find-child st room-id sub-id))]
    (cond
      (and sub-id (nil? child)) nil

      (not (entry-node? node))
      {:node (agent-node sub-id (:label child)) :text (child-text child)}

      :else
      (let [history (vec (if child
                           (:history child)
                           (:history (state/get-room st room-id))))]
        (when-let [i (locate history node)]
          (when-let [text (entry-block-text (nth history i))]
            {:node (entry-node sub-id history i) :text text}))))))

;; ── Threads ──────────────────────────────────────────────────────────────────

(defn thread-turns
  "The children of `thread` in `agents`, in spawn order."
  [agents thread]
  (filterv #(= thread (:thread %)) agents))

(defn threads
  "`agents`' open threads as [{:id :node :turns}], in the order they were
   started."
  [agents]
  (->> agents
       (filter :thread)
       (remove :closed?)
       (group-by :thread)
       (map (fn [[id turns]] {:id id :node (:node (first turns)) :turns turns}))
       (sort-by #(:started (first (:turns %))))
       vec))

(defn suggestions
  "One-tap questions for a block of `kind` (a node's :kind, nil for a whole
   sub-agent, :selection for text selected in a block)."
  [kind]
  (case kind
    :user      ["How was this handled?" "What came of this?"]
    :text      ["Explain this more simply" "Why?" "What are the alternatives?"]
    :tool-call ["What did this do?" "Why was this run?" "What could this break?"]
    :thinking  ["Summarize this reasoning" "Is this reasoning sound?"]
    :error     ["What went wrong?" "How do I fix this?"]
    :selection ["Explain this" "Why?" "Is this right?"]
    ["Summarize the result" "How did it get there?"]))

(defn ask-prompt
  "The prompt of a question about the block at `addr` (`text`, its
   transcript text), or about `selection`, text the user selected in it.
   `earlier` ([{:question :answer}]) is the thread so far, so a follow-up
   reads in context."
  [{:keys [question addr text selection earlier sub-id]}]
  (str "The user is reading this conversation and asks about "
       (if selection "text they selected in one block of it, " "one block of it, ")
       addr ".\n\n"
       "## The block (" addr ")\n```\n" text "\n```\n\n"
       (when selection
         (str "## The selected text\n```\n" selection "\n```\n\n"))
       (when (seq earlier)
         (str "## Earlier in this thread\n"
              (str/join "\n\n" (map (fn [{q :question a :answer}]
                                      (str "**Q:** " q "\n\n**A:** " a))
                                    earlier))
              "\n\n"))
       "## Question\n" question "\n\n"
       "Answer the question directly, in concise markdown. `"
       h/parent-history-tool "` reads the conversation"
       (if sub-id
         (str " of sub-agent " sub-id " with `agent: \"" sub-id "\"`")
         (str " (the block is entry " addr ")"))
       "; page around the block with `before` when it doesn't say enough. "
       "You may read files in the working directory. " h/ask-edit-policy))

;; ── Handlers (node ext; the web forwards the user's events) ──────────────────

(def ^:private selection-chars 4000)

(defn ask
  "Ask `question` about `node` (or about `selection`, text the user selected
   in it: quoted in the prompt and kept on the child), or follow up in
   `thread` (whose first turn's node and selection win). Pure and a no-op on
   state, like h/explain-call: the spawn goes out as an :app/dispatch so it
   is broadcast and mirrored like any sub-agent; it carries the asking
   `client-id`, so that client's browser can show the thread at once. nil
   without a question or when the block is gone."
  [st {:keys [room-id node question thread selection client-id]}]
  (let [q      (some-> question str/trim not-empty)
        agents (h/agents st room-id)
        turns  (when thread (thread-turns agents thread))
        node   (or (:node (first turns)) node)
        sel    (or (:selection (first turns))
                   (some-> selection str/trim not-empty (h/clip selection-chars)))
        found  (when (and q node) (resolve-node st room-id node))]
    (when found
      (let [sub-id  (h/ask-sub-id)
            node    (:node found)
            earlier (keep (fn [c] (when-let [a (:result c)]
                                    {:question (:question c) :answer a}))
                          turns)]
        {:effects [[:app/dispatch
                    (cond-> {:type     :subagent/spawn
                             :room-id  room-id
                             :sub-id   sub-id
                             :label    (h/default-label q)
                             :task     (str "Ask about " (address node) ": " q)
                             :node     node
                             :thread   (if (seq turns) thread sub-id)
                             :question q
                             :prompt   (ask-prompt {:question  q
                                                    :addr      (address node)
                                                    :text      (:text found)
                                                    :selection sel
                                                    :earlier   earlier
                                                    :sub-id    (:sub-id node)})}
                      sel       (assoc :selection sel)
                      client-id (assoc :client-id client-id))]]}))))

(defn close
  "Close `thread`: its turns hide (:closed?, still saved and linked) and
   running ones are stopped."
  [st {:keys [room-id thread]}]
  (let [turns (thread-turns (h/agents st room-id) thread)]
    (when (seq turns)
      {:state   (update-in st [:rooms room-id :ext h/ext-id :agents]
                           (fn [as] (mapv #(cond-> % (= thread (:thread %)) (assoc :closed? true))
                                          as)))
       :effects (vec (for [c turns :when (= :running (:status c))]
                       [:app/dispatch {:type :subagent/abort :room-id room-id
                                       :sub-id (:id c)}]))})))
