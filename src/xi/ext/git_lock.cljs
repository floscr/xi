(ns xi.ext.git-lock
  "Git staging lock extension — serializes index-mutating git ops across rooms
   that share a repo (see xi.git-lock for the lease itself).

   Enforcement:
     - :tool-gate (first in the gate chain, so a rules force-allow can't skip
       it) holds git_stage_hunks / git_commit / bash git calls until this
       room holds the lease, posting a status line while it waits.
     - The clj tool gates at runtime: its worker asks the main thread via
       request-for-room-key! before each index-mutating (git …) / (sh \"git\" …)
       and settles the lease itself after the op.
     - Broad adds (`add -A/./-u`, `commit -a`) are refused outright when they
       would sweep up files another room edited this session.

   Release: after a tracked git tool result and at turn end, the lease is
   dropped once the index is clean. Staged files keep it held across turns
   (the agent is waiting on the user) — /git-unlock force-releases.

   Waiters give up after XI_GIT_LOCK_WAIT_SECS (default 600) with an error
   naming the holder."
  (:require ["node:path" :as node-path]
            [clojure.string :as str]
            [xi.core.state :as state]
            [xi.fx :as fx]
            [xi.git-lock :as lock]))

(def ^:private DEFAULT_WAIT_SECS 600)

(defn- wait-ms []
  (let [v (js/parseInt (aget js/process.env "XI_GIT_LOCK_WAIT_SECS") 10)]
    (* 1000 (if (js/isNaN v) DEFAULT_WAIT_SECS v))))

(defn- room-key [room-id] (or (some-> room-id name) "default"))

(defn- room-label [room]
  (or (not-empty (get-in room [:session :name]))
      (some-> (:cwd room) node-path/basename)
      (some-> (:id room) name)))

(defn- owner [st room-id]
  {:pid   js/process.pid
   :room  (room-key room-id)
   :label (room-label (state/get-room st room-id))})

;; Gate ctx per room key, captured on every gated tool call (this gate runs
;; first, so every clj call passes through it) — the clj worker's runtime
;; git request only carries the room key.
(defonce ^:private room-ctxs (atom {}))

;; ── Broad-add guard ──────────────────────────────────────────────────────────

(defn- other-room-edits
  "[{:label :files [abs]}] for every other room in this process."
  [st room-id]
  (->> (:rooms st)
       (keep (fn [[rid room]]
               (when (and (not= rid room-id) (:cwd room))
                 {:label (room-label room)
                  :files (mapv #(node-path/resolve (:cwd room) %)
                               (fx/session-edited-files room (:cwd room)))})))))

(defn- broad-add-refusal
  "Error text when a broad add in `cwd` would stage files another room edited,
   else nil."
  [st room-id cwd ops]
  (when (some lock/broad-add? ops)
    (let [hits (lock/foreign-sweep (lock/dirty-files cwd) (other-room-edits st room-id))]
      (when (seq hits)
        (str "git lock: refusing a broad add/commit (-A, ., -u, commit -a) — it "
             "would stage files another room is working on:\n"
             (str/join "\n" (map (fn [{:keys [label files]}]
                                   (str "  \"" label "\": "
                                        (str/join ", " (map #(node-path/relative cwd %) files))))
                                 hits))
             "\nStage your own files explicitly (git add -- <paths>).")))))

;; ── Request (main thread) ────────────────────────────────────────────────────

(defn- status! [dispatch! room-id text]
  (when dispatch!
    (dispatch! {:type :ext.git-lock/status :room-id room-id :text text})))

(defn- secs [ms] (js/Math.round (/ ms 1000)))

(defn- staged-summary [staged]
  (when (seq staged)
    (str " (staged: " (str/join ", " (take 5 staged))
         (when (> (count staged) 5) (str ", +" (- (count staged) 5) " more"))
         ")")))

(defn- turn-over? [get-state room-id]
  (not (get-in (get-state) [:rooms room-id :agent :busy?])))

(defn request!
  "Gate git `argvs` (each without the leading \"git\") for a room. Waits for
   the lease when any op is index-mutating. ctx: {:room-id :cwd :get-state
   :dispatch!}. → Promise<nil (proceed) | error-text>."
  [{:keys [room-id cwd get-state dispatch!]} argvs]
  (let [ops     (map lock/parse-argv argvs)
        locking (filter lock/locking? ops)]
    (if-not (and (seq locking) get-state)
      (js/Promise.resolve nil)
      (let [st     (get-state)
            dir    (lock/op-cwd cwd (first locking))
            owner' (owner st room-id)]
        (if-let [refusal (broad-add-refusal st room-id dir locking)]
          (js/Promise.resolve refusal)
          (-> (lock/wait-acquire!
               {:cwd        dir
                :owner      owner'
                :timeout-ms (wait-ms)
                :cancelled? #(turn-over? get-state room-id)
                :on-wait    (fn [holder staged]
                              (status! dispatch! room-id
                                       (str "⏳ git is locked by "
                                            (lock/describe-holder holder)
                                            (staged-summary staged)
                                            " — waiting…")))})
              (.then
               (fn [{:keys [status waited-ms holder staged fresh?]}]
                 (case status
                   :acquired
                   (do (when (pos? (secs waited-ms))
                         (status! dispatch! room-id
                                  (str "🔓 git lock acquired after " (secs waited-ms) "s")))
                       (when (and fresh? (seq staged))
                         (status! dispatch! room-id
                                  (str "⚠ index already had staged files no room owns"
                                       (staged-summary staged)
                                       " — they will be part of this room's next commit")))
                       ;; Turn ended while we were polling: don't sit on a
                       ;; lease nobody will use.
                       (when (turn-over? get-state room-id)
                         (lock/settle! dir owner'))
                       nil)
                   :no-repo nil
                   :cancelled "git lock: stopped waiting for the git lock (turn ended)"
                   :timeout
                   (str "git lock: gave up after " (secs waited-ms) "s waiting for room "
                        (lock/describe-holder holder)
                        ", which holds the git index lock" (staged-summary staged) ". "
                        "Tell the user; they can commit/unstage there or run /git-unlock."))))))))))

(defn request-for-room-key!
  "clj worker entry: gate `argv` (no leading \"git\") run in `cwd` for the
   room identified by its worker room key. Proceeds when the room's gate ctx
   is unknown (no gated tool call seen yet)."
  [rk cwd argv]
  (if-let [ctx (get @room-ctxs rk)]
    (request! (assoc ctx :cwd cwd) [argv])
    (js/Promise.resolve nil)))

;; ── Tool gate ────────────────────────────────────────────────────────────────

(defn- tool-gate [tool-call {:keys [room-id] :as ctx}]
  (when room-id
    (swap! room-ctxs assoc (room-key room-id)
           (select-keys ctx [:room-id :cwd :get-state :dispatch!])))
  (let [argvs (lock/tool-call-argvs tool-call)]
    (if (empty? argvs)
      tool-call
      (-> (request! ctx argvs)
          (.then (fn [err]
                   (if err
                     {:intercepted true
                      :result {:content [{:type "text" :text err}] :is-error true}}
                     tool-call)))))))

;; ── Handlers (pure) + fx ─────────────────────────────────────────────────────

(defn- on-status [st {:keys [room-id text]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :history] conj {:kind :status :text text})}))

(defn- on-tool-result
  "After a git tool call finishes, drop the lease if the index is clean."
  [st {:keys [room-id id]}]
  (when-let [room (state/get-room st room-id)]
    (let [entry (some #(when (= id (:id %)) %) (rseq (vec (:history room))))]
      (when (some (comp lock/locking? lock/parse-argv)
                  (lock/tool-call-argvs {:name (:tool entry) :arguments (:arguments entry)}))
        {:effects [[:git-lock/settle {:room-id room-id :cwd (:cwd room)}]]}))))

(defn- on-turn-end [st {:keys [room-id]}]
  (when-let [room (state/get-room st room-id)]
    (when (:cwd room)
      {:effects [[:git-lock/settle {:room-id room-id :cwd (:cwd room)}]]})))

(defn- settle-fx [_ {:keys [room-id cwd]}]
  (try (lock/settle! cwd {:pid js/process.pid :room (room-key room-id)})
       (catch :default _ nil)))

(defn- unlock-command [st {:keys [room-id]}]
  (when-let [room (state/get-room st room-id)]
    {:effects [[:git-lock/unlock {:room-id room-id :cwd (:cwd room)}]]}))

(defn- unlock-fx [{:keys [dispatch!]} {:keys [room-id cwd]}]
  (let [dropped (lock/force-release! cwd)]
    (status! dispatch! room-id
             (if dropped
               (str "🔓 released the git lock held by " (lock/describe-holder dropped))
               "No git lock held for this repo."))))

(def extension
  {:id        :git-lock
   :handlers  {:ext.git-lock/status on-status
               :agent/tool-result   on-tool-result
               :agent/turn-end      on-turn-end}
   :fx        {:git-lock/settle settle-fx
               :git-lock/unlock unlock-fx}
   :commands  [{:name        "git-unlock"
                :description "Force-release the cross-room git staging lock for this repo"
                :handler     unlock-command}]
   :tool-gate tool-gate})
