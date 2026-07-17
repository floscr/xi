(ns xi.fx
  "Effect handlers for sessions, images and model listing — the impure
   counterparts to xi.commands. Effect handlers receive {:dispatch! :state}
   and a payload; they report completion by dispatching events, never by
   touching state.

   Room session shape: the on-disk session map (xi.session) plus
   :provider-session-id mirroring :cli-session-id in memory. The mirror key
   is stripped before writes so the on-disk format stays unchanged."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as node-path]
            [xi.core.log :as log]
            [xi.image :as image]
            [xi.session :as session]
            [xi.system-prompt :as system-prompt]
            [xi.util :as util]))

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
  (case (:source s) :claude " [claude]" ""))

(def ^:private edit-tool-names
  "Stripped, lower-cased tool names that mutate files on disk. Includes the
   structural-edit (clj-surgeon) tools so MCP-driven edits still register."
  #{"edit" "write" "multiedit" "notebookedit"
    "clj_replace" "clj_extract" "clj_fix_declares"
    "clj_mv" "clj_fix_parens" "clj_rename_ns"})

(defn- edit-tool-call?
  "True when a history entry is a file-mutating tool call. Tolerant of an
   un-stripped mcp__ prefix and of casing so edits register regardless of how
   the provider recorded the tool name."
  [{:keys [kind tool]}]
  (and (= :tool-call kind)
       (boolean (edit-tool-names (some-> tool util/strip-mcp-prefix str/lower-case)))))

(defn session-edited-files
  "Paths (relative to cwd) of files touched via edit/write tool calls in the
   room's history. Used to scope the session diff to files the agent changed,
   rather than every dirty file in the working tree."
  [room cwd]
  (->> (:history room)
       (filter edit-tool-call?)
       (keep (fn [{:keys [arguments]}]
               (or (:path arguments) (:file_path arguments) (:file arguments))))
       (map #(.relative node-path cwd (.resolve node-path cwd %)))
       (remove #(or (str/starts-with? % "..") (.isAbsolute node-path %)))
       distinct
       vec))

(defn- list-room-sessions [room scope]
  (let [pa? (get-in room [:agent :personal-agent?])]
    (cond
      pa?           (session/list-personal-agent-sessions)
      (= :all scope) (session/list-all-sessions)
      :else          (session/list-sessions (:cwd room)))))

(defn- session-item [room-id scope i s]
  {:label (str (when (:favorite? s) "★ ") (or (:name s) "(unnamed)"))
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

(defn create-fx
  "Build effect handlers. opts:
     :system-prompt-fn  (fn [cwd] → {:system str :system-parts [{:source :text}]})
                        — called on /cd to rebuild the system prompt."
  [ring & [{:keys [system-prompt-fn]}]]
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

   :session/fork
   (fn [{:keys [dispatch! state]} {:keys [room-id]}]
     (let [room (room-of state room-id)
           current (:session room)
           pa? (get-in room [:agent :personal-agent?])]
       ;; Persist the original branch on disk before diverging so the two
       ;; sessions don't share a provider session id.
       (when (:provider-session-id current)
         (try (session/save-session! (->disk-session current))
              (catch :default e
                (js/console.error "[fx] session save failed:" e))))
       (dispatch! {:type :session/forked
                   :room-id room-id
                   :session (session/create-session
                             (or (:cwd room) (.cwd js/process))
                             (when pa? {:personal-agent? true}))})))

   :session/sync
   (fn [{:keys [dispatch! state]} {:keys [room-id]}]
     (let [room (room-of state room-id)
           sess (:session room)]
       (when (:provider-session-id sess)
         (let [title (when-let [t (first-user-text room)]
                       (subs t 0 (min 60 (count t))))
               model (get-in room [:agent :model])
               named (cond-> sess
                       (and (nil? (:name sess)) title) (assoc :name title)
                       model (assoc :model model))
               touched (session/touch-session! (->disk-session named))]
           (dispatch! {:type :session/updated :room-id room-id
                       :session (-> touched
                                    (assoc :provider-session-id (:cli-session-id touched)))})))))

   :session/list
   (fn [{:keys [dispatch! state]} {:keys [room-id]}]
     (let [room (room-of state room-id)
           cwd-sessions (list-room-sessions room :cwd)
           all-sessions (list-room-sessions room :all)
           tag-summary (fn [items summaries]
                         (mapv (fn [item s] (assoc item :summary s))
                               items summaries))
           cwd-items (tag-summary (vec (map-indexed (partial session-item room-id :cwd) cwd-sessions))
                                  cwd-sessions)
           all-items (tag-summary (vec (map-indexed (partial session-item room-id :all) all-sessions))
                                  all-sessions)]
       (if (and (empty? cwd-items) (empty? all-items))
         (dispatch! {:type :ui/status :room-id room-id :text "(no previous sessions)"})
         (dispatch! {:type :ui/menu-open :room-id room-id
                     :menu {:id :resume
                            :prompt "resume> "
                            :items cwd-items
                            :alt-items all-items
                            :tab-labels ["Current Folder" "All"]
                            :search-field :search-text
                            :key-bindings [{:key "*" :selected? true
                                            :event {:type :session/toggle-favorite
                                                    :room-id room-id :reopen "sessions"}}]}}))))

   :session/list-favorites
   (fn [{:keys [dispatch! state]} {:keys [room-id]}]
     (let [room (room-of state room-id)
           all  (list-room-sessions room :all)
           ;; Resume works by index into the full :all list, so keep each
           ;; favorite's original index even after filtering.
           items (->> (map-indexed vector all)
                      (filter (fn [[_ s]] (:favorite? s)))
                      (mapv (fn [[i s]]
                              (assoc (session-item room-id :all i s) :summary s))))]
       (if (empty? items)
         (dispatch! {:type :ui/status :room-id room-id
                     :text "(no favorites yet — press * on a session in /resume)"})
         (dispatch! {:type :ui/menu-open :room-id room-id
                     :menu {:id :favorites
                            :prompt "favorite> "
                            :items items
                            :search-field :search-text
                            :key-bindings [{:key "*" :selected? true
                                            :event {:type :session/toggle-favorite
                                                    :room-id room-id :reopen "favorites"}}]}}))))

   :session/favorite-toggle
   (fn [{:keys [dispatch!]} {:keys [room-id session-id reopen]}]
     (when session-id
       (let [fav? (session/toggle-favorite! session-id)]
         (dispatch! {:type :ui/status :room-id room-id
                     :text (if fav? "★ Added to favorites" "☆ Removed from favorites")})
         ;; Lobby-relevant: refresh any attached web clients' session lists.
         (dispatch! {:type :favorites/changed})
         ;; From a picker keybinding, reopen it so the star flips live; the
         ;; /favorite command passes no :reopen and stays where it is.
         (when reopen
           (dispatch! {:type :command/run :room-id room-id :name reopen})))))

   :session/load
   (fn [{:keys [dispatch! state]} {:keys [room-id scope index]}]
     (let [room (room-of state room-id)
           sessions (list-room-sessions room scope)]
       (if (and (<= 1 index) (<= index (count sessions)))
         (let [summary (nth sessions (dec index))]
           ;; A resumed session may live in a sibling git worktree; landing the
           ;; room in that worktree (or the repo root when it's gone) is the
           ;; worktree extension's job, chained onto :session/resumed.
           (dispatch! {:type :session/resumed
                       :room-id room-id
                       :session (session/load-session summary)
                       :summary summary
                       :messages (session/read-session-messages summary)}))
         (dispatch! {:type :ui/status :room-id room-id :text "Session not found."}))))

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
                      :menu {:id :model :prompt "model> " :items items}})))))

   :cwd/change
   (fn [{:keys [dispatch! state]} {:keys [room-id path]}]
     (let [room     (room-of state room-id)
           cur-cwd  (or (:cwd room) (.cwd js/process))
           resolved (.resolve node-path cur-cwd path)]
       (if-not (.existsSync fs resolved)
         (dispatch! {:type :ui/status :room-id room-id
                     :text (str "Directory not found: " resolved)})
         (if-not (.isDirectory (.statSync fs resolved))
           (dispatch! {:type :ui/status :room-id room-id
                       :text (str "Not a directory: " resolved)})
           (let [pa?    (get-in room [:agent :personal-agent?])
                 result (when (and system-prompt-fn (not pa?))
                          (system-prompt-fn resolved))
                 agents (when (and system-prompt-fn (not pa?))
                          (system-prompt/find-agents-md resolved))]
             (dispatch! (cond-> {:type :cwd/changed :room-id room-id :cwd resolved}
                          (:system result) (assoc :system (:system result))
                          (:system-parts result) (assoc :system-parts (:system-parts result))
                          agents (assoc :agents-files agents))))))))}
)
