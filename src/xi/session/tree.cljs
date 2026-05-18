(ns xi.session.tree
  "Append-only session tree.

   Each session is a sequence of entries with id/parentId forming a tree.
   The 'leaf' pointer tracks the current position. Appending creates a child
   of the current leaf. Branching moves the leaf to an earlier entry, allowing
   new branches without modifying history.

   Provider-agnostic — this is Xi's own record of the conversation."
  (:require ["node:fs" :as fs]
            ["node:path" :as node-path]
            ["node:crypto" :as crypto]))

;; ── ID Generation ─────────────────────────────────────────────────────────────

(defn gen-id
  "Generate a short random hex ID (8 chars)."
  []
  (.toString (crypto/randomBytes 4) "hex"))

;; ── Tree State ────────────────────────────────────────────────────────────────

(defn create
  "Create a new session tree. Returns a mutable tree state atom.
   opts:
     :session-id  — session UUID
     :cwd         — working directory
     :filepath    — path to .tree.jsonl file (nil = in-memory only)"
  [{:keys [session-id cwd filepath]}]
  (let [header {:type "session"
                :id session-id
                :version 1
                :cwd cwd
                :timestamp (.toISOString (js/Date.))}]
    (atom {:header header
           :entries []          ;; vec of entry maps
           :by-id {}            ;; id → entry (for O(1) lookup)
           :leaf-id nil         ;; current position in tree
           :filepath filepath})))

;; ── Persistence ───────────────────────────────────────────────────────────────

(defn- entry->jsonl [entry]
  (str (js/JSON.stringify (clj->js entry)) "\n"))

(defn- persist-entry!
  "Append a single entry to the JSONL file."
  [tree entry]
  (when-let [fp (:filepath @tree)]
    (let [dir (.dirname node-path fp)]
      (when-not (fs/existsSync dir)
        (fs/mkdirSync dir #js {:recursive true}))
      (fs/appendFileSync fp (entry->jsonl entry) "utf8"))))

(defn- persist-header!
  "Write the session header as the first line of the JSONL file."
  [tree]
  (when-let [fp (:filepath @tree)]
    (let [dir (.dirname node-path fp)]
      (when-not (fs/existsSync dir)
        (fs/mkdirSync dir #js {:recursive true}))
      (fs/writeFileSync fp (entry->jsonl (:header @tree)) "utf8"))))

;; ── Core Operations ───────────────────────────────────────────────────────────

(defn append!
  "Append an entry as child of current leaf, advance leaf. Returns entry id.
   entry-data should be a map with :type and any type-specific fields.
   :id, :parentId, :timestamp are auto-added."
  [tree entry-data]
  (let [id (gen-id)
        entry (assoc entry-data
                     :id id
                     :parentId (:leaf-id @tree)
                     :timestamp (.toISOString (js/Date.)))]
    (swap! tree (fn [state]
                  (-> state
                      (update :entries conj entry)
                      (update :by-id assoc id entry)
                      (assoc :leaf-id id))))
    (persist-entry! tree entry)
    id))

(defn branch!
  "Move the leaf pointer to an earlier entry. The next append! will create
   a child of that entry, forming a new branch. Existing entries are not
   modified or deleted."
  [tree entry-id]
  (let [state @tree]
    (when-not (get-in state [:by-id entry-id])
      (throw (js/Error. (str "Entry " entry-id " not found"))))
    (swap! tree assoc :leaf-id entry-id)))

(defn reset-leaf!
  "Reset the leaf pointer to nil (before any entries).
   The next append! will create a new root entry (parentId = nil)."
  [tree]
  (swap! tree assoc :leaf-id nil))

(defn get-leaf-id
  "Return the current leaf entry id, or nil."
  [tree]
  (:leaf-id @tree))

(defn get-entry
  "Look up an entry by id."
  [tree id]
  (get-in @tree [:by-id id]))

(defn get-entries
  "Return all entries (shallow copy)."
  [tree]
  (:entries @tree))

(defn get-children
  "Return direct children of an entry."
  [tree parent-id]
  (filterv #(= parent-id (:parentId %)) (:entries @tree)))

;; ── Tree Traversal ────────────────────────────────────────────────────────────

(defn get-branch
  "Walk from an entry (default: current leaf) to root, returning entries
   in root→leaf order."
  ([tree] (get-branch tree (:leaf-id @tree)))
  ([tree from-id]
   (let [{:keys [by-id]} @tree]
     (loop [id from-id
            path []]
       (if-let [entry (get by-id id)]
         (recur (:parentId entry) (conj path entry))
         (vec (reverse path)))))))

(defn- build-tree-nodes
  "Build tree visualization nodes from entries.
   Returns vec of {:entry ... :children [...]}."
  [entries]
  (let [node-map (atom {})
        roots (atom [])]
    ;; Create nodes
    (doseq [entry entries]
      (swap! node-map assoc (:id entry) {:entry entry :children []}))
    ;; Build tree
    (doseq [entry entries]
      (let [pid (:parentId entry)]
        (if (nil? pid)
          (swap! roots conj (:id entry))
          (when (get @node-map pid)
            (swap! node-map update-in [pid :children] conj (:id entry))))))
    ;; Resolve IDs to nodes recursively
    (letfn [(resolve-node [id]
              (let [node (get @node-map id)]
                (assoc node :children
                       (mapv resolve-node (:children node)))))]
      (mapv resolve-node @roots))))

(defn get-tree
  "Build the full tree structure for visualization.
   Returns vec of tree nodes: {:entry {...} :children [...]}"
  [tree]
  (build-tree-nodes (:entries @tree)))

;; ── Build Context ─────────────────────────────────────────────────────────────

(defn build-message-context
  "Walk root→leaf on the current branch, collecting user/assistant messages
   suitable for sending to an LLM. Returns a vec of message maps.
   Skips non-message entries (turn-end, model-change, etc.)."
  ([tree] (build-message-context tree (:leaf-id @tree)))
  ([tree leaf-id]
   (let [branch (get-branch tree leaf-id)]
     (->> branch
          (filter #(#{"user-message" "assistant-text"} (:type %)))
          (mapv (fn [entry]
                  (case (:type entry)
                    "user-message"    {:role "user" :content (:text entry)}
                    "assistant-text"  {:role "assistant" :content (:text entry)})))))))

;; ── Load / Save ───────────────────────────────────────────────────────────────

(defn save!
  "Write full tree to JSONL (overwrites). Used for initial save."
  [tree]
  (when-let [fp (:filepath @tree)]
    (let [dir (.dirname node-path fp)]
      (when-not (fs/existsSync dir)
        (fs/mkdirSync dir #js {:recursive true}))
      (let [lines (str (entry->jsonl (:header @tree))
                       (apply str (map entry->jsonl (:entries @tree))))]
        (fs/writeFileSync fp lines "utf8")))))

(defn load-tree
  "Load a session tree from a JSONL file. Returns a tree atom."
  [filepath]
  (when (fs/existsSync filepath)
    (let [content (.toString (fs/readFileSync filepath "utf8"))
          lines (->> (.split content "\n")
                     (filter #(pos? (count (.trim %))))
                     (mapv #(js->clj (js/JSON.parse %) :keywordize-keys true)))
          header (first lines)
          entries (vec (rest lines))
          by-id (into {} (map (fn [e] [(:id e) e])) entries)
          ;; Find leaf: the last entry on the longest branch (deepest entry without children)
          child-set (set (map :parentId entries))
          leaves (filterv #(not (child-set (:id %))) entries)
          ;; Pick the most recent leaf (last by timestamp)
          leaf (last (sort-by :timestamp leaves))]
      (atom {:header header
             :entries entries
             :by-id by-id
             :leaf-id (:id leaf)
             :filepath filepath}))))

;; ── Fork Helpers ──────────────────────────────────────────────────────────────

(defn entries-up-to
  "Return entries on the branch from root to target-id (inclusive).
   Useful for forking: gives the subset of entries needed for the new branch."
  [tree target-id]
  (get-branch tree target-id))

(defn user-messages
  "Get all user messages from the tree. Returns [{:entry-id id :text text} ...]
   Useful for fork selector."
  [tree]
  (->> (:entries @tree)
       (filter #(= "user-message" (:type %)))
       (mapv (fn [e] {:entry-id (:id e) :text (:text e)}))))
