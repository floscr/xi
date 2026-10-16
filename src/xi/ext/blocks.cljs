(ns xi.ext.blocks
  "Agent blocks, server half: the `ui_block` / `ui_update` / `ui_state` tools
   (docs/guide/agent-blocks.md).

   A tool validates the model's payload (xi.blocks), builds the block record
   and dispatches it into the room's `:blocks` slice; the model gets a one-line
   summary back, never the payload, so its context stays small and the block
   survives resume (tool results are clipped there). The slice mirrors to
   every client and is `:persist-room`, so a reload or restart brings the
   blocks back. The web half (xi.ext.blocks.web) draws each one as its own
   post after the tool call that made it; the TUI shows its text under that
   call (xi.ext.blocks.handlers stamps it)."
  (:require [clojure.string :as str]
            [xi.blocks :as b]
            [xi.dialog :as dialog]
            [xi.ext.blocks.handlers :as h]))

(def ^:private hiccup-help
  (str "For kind hiccup, pass `hiccup` instead of data: JSON arrays like "
       "[\"div\", {\"layout\": \"row\"}, [\"strong\", \"Hi\"], [\"ui/badge\", {\"variant\": \"success\"}, \"ok\"]]. "
       "Tags: p span strong em code pre blockquote h1-h6 ul ol li dl dt dd table thead tbody tr th td "
       "div section header footer details summary figure figcaption img a hr br small mark kbd, "
       "svg with g path circle ellipse rect line polyline polygon text, "
       "controls button input select option textarea label, and ui/button ui/badge ui/progress ui/alert ui/chip. "
       "No class, id, style or script. Lay out with the attributes layout: row|grid|stack, gap: sm|md|lg, "
       "align: start|center|end; color with tone: info|warn|success|error|neutral, emphasis: strong|muted. "
       "A control with action: \"name\" records that action on the block (read it with ui_state); "
       "add send: true to also post it to you as a message when clicked."))

(defn- ui-block-def []
  {:name "ui_block"
   :description (str "Show a visual block in the chat instead of a wall of text: a checklist, table, "
                     "chart, form, choice, … The user sees it right after this call and can interact "
                     "with it; you get a short summary back, not the block. Read what the user did with "
                     "ui_state, change the block with ui_update. Use blocks for structured output the "
                     "user scans or acts on; keep prose answers as text.\n\nKinds and their data:\n"
                     (b/catalog) "\n\n" hiccup-help)
   :input_schema {:type "object"
                  :properties {:kind   {:type "string" :enum (b/kind-names)}
                               :title  {:type "string" :description "Short heading shown in the block's frame"}
                               :data   {:type "object" :description "The kind's payload (see the list above)"}
                               :hiccup {:description "For kind hiccup: the tree, as JSON arrays (or an EDN string)"}
                               :id     {:type "string" :description "Optional id to refer to the block later (generated if omitted)"}}
                  :required ["kind"]}})

(def ^:private ui-update-def
  {:name "ui_update"
   :description (str "Change a block you showed with ui_block: replace its data (or hiccup) and/or "
                     "its title. Use it for progress, steps that advance, tables that fill in. What the "
                     "user did on it (ticks, selections, field values) is kept where the ids still exist.")
   :input_schema {:type "object"
                  :properties {:id     {:type "string" :description "The block id ui_block returned"}
                               :data   {:type "object" :description "The new payload, same shape as for ui_block"}
                               :hiccup {:description "The new tree, for a hiccup block"}
                               :title  {:type "string" :description "A new title"}}
                  :required ["id"]}})

(def ^:private ui-state-def
  {:name "ui_state"
   :description (str "Read what the user did on a block: its current state (ticked items, chosen "
                     "options, field values) and the actions since you last read it. Without an id, "
                     "lists this chat's blocks and their unread actions.")
   :input_schema {:type "object"
                  :properties {:id {:type "string" :description "The block id; omit to list the blocks"}}}})

;; ── Tools ────────────────────────────────────────────────────────────────────

(defn- ok [text] {:content [{:type "text" :text text}]})
(defn- fail [text] {:content [{:type "text" :text text}] :is-error true})

(defn- gen-id []
  (str "b-" (.toString (js/Math.floor (* (js/Math.random) 0x7fffffff)) 36)))

(defn- own-call-id
  "The id of the running tool call `call` ({:name :arguments}) in the room's
   history, which the web shows the block after; nil when it isn't there (a
   sub-agent's call lives in the sub-agent's history)."
  [room call]
  (let [entries (vec (:history room))
        i       (when call (dialog/permission-tool-index {:call call} entries 0))]
    (when (and i (= :running (:status (nth entries i))))
      (:id (nth entries i)))))

(defn- unread-note
  "A line about other blocks with actions the agent hasn't read, or nil."
  [st room-id except-id]
  (let [unread (->> (vals (h/blocks st room-id))
                    (filter #(and (pos? (or (:unread %) 0)) (not= except-id (:id %))))
                    (sort-by :at))]
    (when (seq unread)
      (str "\nUnread actions on: "
           (str/join ", " (map #(str (:id %) " (" (:unread %) ")") unread))
           " — read them with ui_state."))))

(defn- dropped-note [record]
  (when-let [ds (seq (get-in record [:spec :dropped]))]
    (str "\nDropped from the hiccup (not allowed): " (str/join "; " ds) ".")))

(defn- payload [{:keys [kind data hiccup]}]
  (if (= "hiccup" kind) (or hiccup data) data))

(defn- show-block
  [args {:keys [dispatch! get-state room-id call]}]
  (let [st     (get-state)
        room   (get-in st [:rooms room-id])
        kind   (some-> (:kind args) str str/trim str/lower-case)
        id     (or (b/safe-id (:id args)) (gen-id))
        now    (js/Date.now)]
    (cond
      (>= (count (h/blocks st room-id)) b/max-blocks)
      (fail (str "This chat has " b/max-blocks " blocks, the most it keeps. Update an existing one with ui_update."))

      (h/find-block st room-id id)
      (fail (str "A block with id " id " exists already. Change it with ui_update, or pick another id."))

      (nil? (payload (assoc args :kind kind)))
      (fail (if (= "hiccup" kind) "Pass the tree as `hiccup`." (str "Pass the " kind " payload as `data`.")))

      :else
      (let [{:keys [record error]}
            (b/make-record {:id id :kind kind :title (:title args)
                            :data (payload (assoc args :kind kind))
                            :source {:type :tool :name "ui_block"}
                            :call-id (own-call-id room call)
                            :anchor (count (:history room))
                            :at now})]
        (if error
          (fail (str "Block not shown: " error))
          (do (dispatch! {:type :ext.blocks/add :room-id room-id :block record})
              (ok (str "Showed " kind (when-let [t (:title record)] (str " \"" t "\""))
                       " as block " id "."
                       (when (b/interactive? kind) " The user can interact with it; read what they did with ui_state.")
                       (dropped-note record)
                       (unread-note st room-id id)))))))))

(defn- update-block
  [args {:keys [dispatch! get-state room-id]}]
  (let [st (get-state)
        id (some-> (:id args) str str/trim)]
    (if-let [record (h/find-block st room-id id)]
      (let [data (payload (assoc args :kind (:kind record)))]
        (if (and (nil? data) (str/blank? (:title args)))
          (fail "Nothing to change: pass data (or hiccup) and/or title.")
          (let [{r :record error :error} (b/update-record record {:data data :title (:title args)
                                                                  :at (js/Date.now)})]
            (if error
              (fail (str "Block not updated: " error))
              (do (dispatch! {:type :ext.blocks/update :room-id room-id :block r})
                  (ok (str "Updated block " id "." (dropped-note r) (unread-note st room-id nil))))))))
      (fail (str "No block " (pr-str id) " in this chat."
                 (when-let [ids (seq (keys (h/blocks st room-id)))]
                   (str " Blocks: " (str/join ", " (sort ids)) ".")))))))

(defn- block-state
  [args {:keys [dispatch! get-state room-id]}]
  (let [st (get-state)
        id (some-> (:id args) str str/trim not-empty)]
    (cond
      (nil? id)
      (let [bs (sort-by :at (vals (h/blocks st room-id)))]
        (ok (if (seq bs)
              (str/join "\n" (for [{:keys [id kind title unread]} bs]
                               (str id "  " kind (when title (str "  \"" title "\""))
                                    (when (pos? (or unread 0)) (str "  " unread " unread")))))
              "No blocks in this chat.")))

      (h/find-block st room-id id)
      (let [record (h/find-block st room-id id)]
        (when (pos? (or (:unread record) 0))
          (dispatch! {:type :ext.blocks/read :room-id room-id :block-id id}))
        (ok (b/state-text record)))

      :else
      (fail (str "No block " (pr-str id) " in this chat.")))))

(defn- with-room [f]
  (fn [args {:keys [dispatch! get-state] :as ctx}]
    (if (and dispatch! get-state)
      (f args ctx)
      (fail "blocks: no chat to show the block in"))))

(def ^:private tool-registry
  {"ui_block"  (with-room show-block)
   "ui_update" (with-room update-block)
   "ui_state"  (with-room block-state)})

;; ── Extension ────────────────────────────────────────────────────────────────

(def extension
  {:id               h/ext-id
   :init             {:room {:blocks {}}}
   :persist-room     [:blocks]
   :handlers         h/handlers
   :tool-definitions [(ui-block-def) ui-update-def ui-state-def]
   :tool-registry    tool-registry})
