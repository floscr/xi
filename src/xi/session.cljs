(ns xi.session
  "Pi-compatible JSONL session persistence (version 3).
   Sessions are stored in ~/.pi/agent/sessions/{cwd-encoded}/ as JSONL files.
   Each non-header line has an id (8-char hex) and parentId forming a DAG."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as node-path]
            ["node:crypto" :as crypto]))

;; ── Helpers ───────────────────────────────────────────────────────────────────

(def ^:private SESSIONS_DIR
  (.join node-path (aget js/process.env "HOME") ".pi" "agent" "sessions"))

(defn- gen-hex8
  "Generate an 8-char random hex string."
  []
  (.toString (crypto/randomBytes 4) "hex"))

(defn- gen-uuid-v7
  "Generate a UUIDv7 (time-ordered)."
  []
  (let [now (js/Date.now)
        ts-hex (.padStart (.toString now 16) 12 "0")
        rand-hex (.toString (crypto/randomBytes 10) "hex")
        ;; version nibble = 7
        ver-nibble (str "7" (subs rand-hex 0 3))
        ;; variant bits = 10xx
        var-byte (bit-or (bit-and (js/parseInt (subs rand-hex 3 5) 16) 0x3f) 0x80)
        var-hex (str (.padStart (.toString var-byte 16) 2 "0") (subs rand-hex 5 7))]
    (str (subs ts-hex 0 8) "-"
         (subs ts-hex 8 12) "-"
         ver-nibble "-"
         var-hex "-"
         (subs rand-hex 7 19))))

(defn- iso-now [] (.toISOString (js/Date.)))

(defn encode-cwd
  "Encode a CWD path to Pi's session directory format.
   /home/floscr/Code/Projects/xi → --home-floscr-Code-Projects-xi--"
  [cwd]
  (let [stripped (if (str/starts-with? cwd "/") (subs cwd 1) cwd)]
    (str "--" (str/replace stripped "/" "-") "--")))

(defn- session-dir
  "Get the session directory for a CWD."
  [cwd]
  (.join node-path SESSIONS_DIR (encode-cwd cwd)))

;; ── JSONL I/O ─────────────────────────────────────────────────────────────────

(defn- write-line
  "Append a single JSON line to a session file."
  [filepath obj]
  (let [json (js/JSON.stringify (clj->js obj))]
    (fs/appendFileSync filepath (str json "\n") "utf8")))

(defn- read-lines
  "Read all JSON lines from a session file. Returns vec of maps."
  [filepath]
  (let [content (fs/readFileSync filepath "utf8")
        lines (str/split content #"\n")]
    (into []
          (comp (filter seq)
                (map (fn [line]
                       (js->clj (js/JSON.parse line) :keywordize-keys true))))
          lines)))

;; ── Session Creation ──────────────────────────────────────────────────────────

(defn create-session
  "Create a new session file. Returns session state map."
  [cwd]
  (let [dir (session-dir cwd)
        session-id (gen-uuid-v7)
        timestamp (iso-now)
        filename (str (str/replace timestamp #"[:.]" "-") "_" session-id ".jsonl")
        filepath (.join node-path dir filename)
        header {:type "session"
                :version 3
                :id session-id
                :timestamp timestamp
                :cwd cwd}]
    ;; Ensure directory exists
    (when-not (fs/existsSync dir)
      (fs/mkdirSync dir #js {:recursive true}))
    ;; Write header
    (write-line filepath header)
    ;; Return session state
    {:filepath filepath
     :session-id session-id
     :cwd cwd
     :head-id nil
     :lines [header]}))

;; ── Append Entries ────────────────────────────────────────────────────────────

(defn append-model-change
  "Append a model_change entry. Returns new head-id."
  [session provider model-id]
  (let [id (gen-hex8)
        entry {:type "model_change"
               :id id
               :parentId (:head-id session)
               :timestamp (iso-now)
               :provider provider
               :modelId model-id}]
    (write-line (:filepath session) entry)
    (-> session
        (assoc :head-id id)
        (update :lines conj entry))))

(defn append-message
  "Append a message entry (user, assistant, or toolResult). Returns updated session."
  [session msg]
  (let [id (gen-hex8)
        entry {:type "message"
               :id id
               :parentId (:head-id session)
               :timestamp (iso-now)
               :message (assoc msg :timestamp (js/Date.now))}]
    (write-line (:filepath session) entry)
    (-> session
        (assoc :head-id id)
        (update :lines conj entry))))

(defn append-custom
  "Append a custom event entry. Returns updated session."
  [session custom-type data]
  (let [id (gen-hex8)
        entry {:type "custom"
               :customType custom-type
               :data data
               :id id
               :parentId (:head-id session)
               :timestamp (iso-now)}]
    (write-line (:filepath session) entry)
    (-> session
        (assoc :head-id id)
        (update :lines conj entry))))

(defn append-session-info
  "Append a session_info (name) entry. Returns updated session."
  [session name]
  (let [id (gen-hex8)
        entry {:type "session_info"
               :id id
               :parentId (:head-id session)
               :timestamp (iso-now)
               :name name}]
    (write-line (:filepath session) entry)
    (-> session
        (assoc :head-id id)
        (update :lines conj entry))))

;; ── Session Loading ───────────────────────────────────────────────────────────

(defn- find-head
  "Find the head node (last node in the chain) given all lines.
   The head is the node whose id is never used as a parentId by any other node."
  [lines]
  (let [all-ids (set (keep :id lines))
        parent-ids (set (keep :parentId lines))
        leaf-ids (set/difference all-ids parent-ids)]
    ;; Pick the most recent leaf (by timestamp)
    (->> lines
         (filter #(contains? leaf-ids (:id %)))
         (sort-by :timestamp)
         last
         :id)))

(defn load-session
  "Load a session from a JSONL file. Returns session state map."
  [filepath]
  (let [lines (read-lines filepath)
        header (first lines)
        head-id (find-head lines)]
    {:filepath filepath
     :session-id (:id header)
     :cwd (:cwd header)
     :head-id head-id
     :lines lines}))

(defn- reconstruct-chain
  "Reconstruct the message chain from head back to root.
   Returns messages in chronological order (root first)."
  [lines head-id]
  (let [by-id (into {} (map (fn [l] [(:id l) l]) lines))]
    (loop [id head-id
           chain []]
      (if-let [node (get by-id id)]
        (recur (:parentId node) (conj chain node))
        (vec (reverse chain))))))

(defn get-messages
  "Get the message chain for the current head. Returns vec of message maps
   (user, assistant, toolResult) in chronological order."
  [session]
  (let [chain (reconstruct-chain (:lines session) (:head-id session))]
    (->> chain
         (filter #(= "message" (:type %)))
         (mapv :message))))

;; ── Rewind / Branching ────────────────────────────────────────────────────────────

(defn rewind-to
  "Move the session head to a specific node id. Next append will branch from there."
  [session target-id]
  (let [valid-ids (set (keep :id (:lines session)))]
    (when-not (contains? valid-ids target-id)
      (throw (js/Error. (str "Node not found: " target-id))))
    (assoc session :head-id target-id)))

(defn build-tree
  "Build a tree structure from session lines for visualization.
   Returns {:roots [...] :children {parent-id → [child-nodes]}}."
  [session]
  (let [lines (:lines session)
        children (group-by :parentId lines)
        roots (get children nil [])]
    {:roots roots
     :children children
     :head-id (:head-id session)}))

(defn format-tree
  "Format the session tree as a string for display."
  [session]
  (let [{:keys [children head-id]} (build-tree session)
        sb (atom [])]
    (letfn [(walk [node-id depth prefix]
              (let [nodes (get children node-id [])
                    ;; Skip session header
                    nodes (if (zero? depth)
                            (filter #(not= "session" (:type %)) nodes)
                            nodes)]
                (doseq [[i node] (map-indexed vector nodes)]
                  (let [last? (= i (dec (count nodes)))
                        connector (if last? "└─" "├─")
                        is-head (= (:id node) head-id)
                        label (case (:type node)
                                "message" (let [role (get-in node [:message :role])
                                                text (case role
                                                       "user" (let [t (get-in node [:message :content 0 :text] "")]
                                                                (subs t 0 (min 50 (count t))))
                                                       "assistant" "[assistant]"
                                                       "toolResult" (str "[" (get-in node [:message :toolName]) "]")
                                                       (str "[" role "]"))]
                                            (str role ": " text))
                                "model_change" (str "model: " (:modelId node))
                                "session_info" (str "name: " (:name node))
                                "custom" (str "custom: " (:customType node))
                                (str (:type node)))
                        marker (if is-head " ◀" "")]
                    (swap! sb conj
                           (str prefix connector " "
                                (:id node) " " label marker))
                    (walk (:id node) (inc depth)
                          (str prefix (if last? "  " "│ ")))))))]
      (walk nil 0 ""))
    (str/join "\n" @sb)))

;; ── Session Listing ───────────────────────────────────────────────────────────

(defn- get-session-name
  "Extract session name from lines, if any session_info entry exists."
  [lines]
  (->> lines
       (filter #(= "session_info" (:type %)))
       last
       :name))

(defn- session-summary
  "Quick summary of a session file without loading all lines."
  [filepath]
  (try
    (let [lines (read-lines filepath)
          header (first lines)
          name (get-session-name lines)
          msg-count (count (filter #(and (= "message" (:type %))
                                        (= "user" (get-in % [:message :role])))
                                  lines))]
      {:filepath filepath
       :session-id (:id header)
       :cwd (:cwd header)
       :timestamp (:timestamp header)
       :name name
       :user-messages msg-count})
    (catch :default _e nil)))

(defn list-sessions
  "List all sessions for a CWD. Returns vec of session summaries, newest first."
  [cwd]
  (let [dir (session-dir cwd)]
    (if-not (fs/existsSync dir)
      []
      (let [files (->> (fs/readdirSync dir)
                       (filter #(str/ends-with? % ".jsonl"))
                       (map #(.join node-path dir %))
                       (sort)
                       reverse)]
        (into [] (keep session-summary) files)))))
