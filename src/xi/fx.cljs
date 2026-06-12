(ns xi.fx
  "Effect handlers for sessions, images and model listing — the impure
   counterparts to xi.commands. Effect handlers receive {:dispatch! :state}
   and a payload; they report completion by dispatching events, never by
   touching state.

   Room session shape: the on-disk session map (xi.session) plus
   :provider-session-id mirroring :cli-session-id in memory. The mirror key
   is stripped before writes so the on-disk format stays unchanged."
  (:require [clojure.string :as str]
            [xi.core.log :as log]
            [xi.image :as image]
            [xi.session :as session]))

(defn- room-of [state room-id]
  (get-in state [:rooms room-id]))

(defn- first-user-text [room]
  (some #(when (= :user (:kind %)) (:text %)) (:history room)))

(defn- shorten-home [path]
  (let [home (aget js/process.env "HOME")]
    (if (and path home (str/starts-with? path home))
      (str "~" (subs path (count home)))
      path)))

(defn- ->disk-session [sess]
  (-> sess
      (assoc :cli-session-id (or (:provider-session-id sess)
                                 (:cli-session-id sess)))
      (dissoc :provider-session-id)))

(defn- source-suffix [s]
  (case (:source s) :claude " [claude]" :pi " [pi]" ""))

;; ── Git (for :diff/load) ─────────────────────────────────────────────────────────

(defn- git-out
  "Run git synchronously in cwd. Returns {:ok stdout} or {:err message}."
  [cwd args]
  (try
    (let [proc (js/Bun.spawnSync (into-array (cons "git" args)) #js {:cwd cwd})]
      (if (zero? (.-exitCode proc))
        {:ok (.toString (.-stdout proc) "utf-8")}
        {:err (str/trim (.toString (.-stderr proc) "utf-8"))}))
    (catch :default e {:err (str e)})))

(defn- untracked-diff
  "Synthesize a unified diff for untracked files via diff --no-index.
   stdout is read regardless of exit code — --no-index exits 1 on diffs."
  [cwd]
  (when-let [files (some->> (:ok (git-out cwd ["ls-files" "--others" "--exclude-standard"]))
                            str/trim str/split-lines (remove empty?) seq)]
    (->> files
         (map (fn [f]
                (let [p (js/Bun.spawnSync
                         #js ["git" "diff" "--no-index" "--" "/dev/null" f]
                         #js {:cwd cwd})]
                  (.toString (.-stdout p) "utf-8"))))
         (str/join "\n"))))

(defn- session-base-commit
  "The commit that was HEAD when the session started (works for resumed
   sessions too — derived from the session's :created timestamp)."
  [cwd created]
  (when created
    (some-> (:ok (git-out cwd ["rev-list" "-1" (str "--before=" created) "HEAD"]))
            str/trim not-empty)))

(defn- list-room-sessions [room scope]
  (let [pa? (get-in room [:agent :personal-agent?])]
    (cond
      pa?           (session/list-personal-agent-sessions)
      (= :all scope) (session/list-all-sessions)
      :else          (session/list-sessions (:cwd room)))))

(defn- session-item [room-id scope i s]
  {:label (or (:name s) "(unnamed)")
   :description (str (when (= scope :all)
                       (some-> (:cwd s) shorten-home (str " ")))
                     (:timestamp s)
                     (when (:user-messages s)
                       (str " (" (:user-messages s) " msgs)"))
                     (source-suffix s))
   :event {:type :command/run :room-id room-id :name "resume"
           :args (if (= scope :all) (str "all:" (inc i)) (str (inc i)))}})


(def ^:private claude-model-ids
  ["claude-opus-4-8" "claude-fable-5" "claude-opus-4-6"
   "claude-sonnet-4-6" "claude-haiku-4-5-20251001"])

(defn- fetch-all-model-ids
  "Fetch Ollama model names, combine with Claude IDs, call cb.
   Falls back to Claude-only on error."
  [cb]
  (-> (js/fetch "http://localhost:11434/api/tags")
      (.then (fn [res] (.json res)))
      (.then (fn [^js data]
               (let [models (js->clj (.-models data) :keywordize-keys true)]
                 (cb (into claude-model-ids (mapv :name models))))))
      (.catch (fn [_err] (cb claude-model-ids)))))

(defn web-model-list-reply-fx
  "Build the full model list and send it to the requesting client."
  [send-fn]
  (fetch-all-model-ids
   (fn [models] (send-fn {:type :models/web-list-result :models models}))))

(defn create-fx [ring]
  {:session/new
   (fn [{:keys [dispatch! state]} {:keys [room-id save-current? after-prompt]}]
     (let [room (room-of state room-id)
           current (:session room)
           pa? (get-in room [:agent :personal-agent?])]
       (when (and save-current? (:provider-session-id current))
         (try (session/save-session! (->disk-session current))
              (catch :default e
                (js/console.error "[fx] session save failed:" e))))
       (dispatch! {:type :session/created
                   :room-id room-id
                   :session (session/create-session
                             (or (:cwd room) (.cwd js/process))
                             (when pa? {:personal-agent? true}))
                   :after-prompt after-prompt})))

   :session/sync
   (fn [{:keys [dispatch! state]} {:keys [room-id]}]
     (let [room (room-of state room-id)
           sess (:session room)]
       (when (:provider-session-id sess)
         (let [title (when-let [t (first-user-text room)]
                       (subs t 0 (min 60 (count t))))
               named (cond-> sess
                       (and (nil? (:name sess)) title) (assoc :name title))
               touched (session/touch-session! (->disk-session named))]
           (dispatch! {:type :session/updated :room-id room-id
                       :session (-> touched
                                    (assoc :provider-session-id (:cli-session-id touched)))})))))

   :session/list
   (fn [{:keys [dispatch! state]} {:keys [room-id]}]
     (let [room (room-of state room-id)
           cwd-sessions (list-room-sessions room :cwd)
           all-sessions (list-room-sessions room :all)
           cwd-items (vec (map-indexed (partial session-item room-id :cwd) cwd-sessions))
           all-items (vec (map-indexed (partial session-item room-id :all) all-sessions))]
       (if (and (empty? cwd-items) (empty? all-items))
         (dispatch! {:type :ui/status :room-id room-id :text "(no previous sessions)"})
         (dispatch! {:type :ui/menu-open :room-id room-id
                     :menu {:id :resume
                            :prompt "resume> "
                            :items cwd-items
                            :alt-items all-items
                            :tab-labels ["Current Folder" "All"]}}))))

   :session/load
   (fn [{:keys [dispatch! state]} {:keys [room-id scope index]}]
     (let [room (room-of state room-id)
           sessions (list-room-sessions room scope)]
       (if (and (<= 1 index) (<= index (count sessions)))
         (let [summary (nth sessions (dec index))]
           (dispatch! {:type :session/resumed
                       :room-id room-id
                       :session (session/load-session summary)
                       :summary summary
                       :messages (session/read-session-messages summary)}))
         (dispatch! {:type :ui/status :room-id room-id :text "Session not found."}))))

   :diff/load
   (fn [{:keys [dispatch! state]} {:keys [room-id args]}]
     (let [room (room-of state room-id)
           cwd (or (:cwd room) (.cwd js/process))
           open! (fn [title text]
                   (if (str/blank? text)
                     (dispatch! {:type :ui/status :room-id room-id :text "No changes."})
                     (dispatch! {:type :ui/diff-open :room-id room-id
                                 :title title :text text})))
           run! (fn [title git-args]
                  (let [{:keys [ok err]} (git-out cwd git-args)]
                    (if err
                      (dispatch! {:type :ui/status :room-id room-id
                                  :text (str "git diff failed: " err)})
                      (open! title ok))))]
       (case args
         "git"
         (open! "All Git Changes"
                (->> [(:ok (git-out cwd ["diff"]))
                      (:ok (git-out cwd ["diff" "--staged"]))
                      (untracked-diff cwd)]
                     (remove str/blank?)
                     (str/join "\n")
                     str/trim))

         "staged"   (run! "Staged Changes" ["diff" "--staged"])
         "unstaged" (run! "Unstaged Changes" ["diff"])

         nil
         (let [base (session-base-commit cwd (get-in room [:session :created]))]
           (run! "Session Changes" (if base ["diff" base] ["diff"])))

         (run! (str "Diff: " args) (into ["diff"] (str/split args #"\s+"))))))

   :events/load
   (fn [{:keys [dispatch! state]} {:keys [room-id]}]
     (let [room (room-of state room-id)
           entries (when ring
                     (->> (log/entries ring)
                          (filter #(or (nil? (:room-id %)) (= room-id (:room-id %))))))
           text (if (seq entries)
                  (str/join "\n" (map log/format-entry-line entries))
                  "(no events)")]
       (dispatch! {:type :ui/buffer-open :room-id room-id
                   :buffer-id :events
                   :buffer {:title "Events" :text text}})))

   :image/process
   (fn [{:keys [dispatch!]} {:keys [room-id text images]}]
     (dispatch! {:type :prompt/submit :room-id room-id :text text
                 :images (image/process-images images)}))

   :models/fetch
   (fn [{:keys [dispatch!]} {:keys [room-id]}]
     (fetch-all-model-ids
      (fn [ids]
        (let [items (mapv (fn [id]
                            {:label id
                             :event {:type :command/run :room-id room-id
                                     :name "model" :args id}})
                          ids)]
          (dispatch! {:type :ui/menu-open :room-id room-id
                      :menu {:id :model :prompt "model> " :items items}})))))})

