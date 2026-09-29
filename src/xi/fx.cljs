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
  ;; Skip display-only entries carried over from before a /truncate — the
  ;; session title must come from this session's own conversation.
  (some #(when (and (= :user (:kind %)) (not (:no-llm? %))) (:text %))
        (:history room)))

(defn- shorten-home [path]
  (let [home (aget js/process.env "HOME")]
    (if (and path home (str/starts-with? path home))
      (str "~" (subs path (count home)))
      path)))

(defn ->disk-session
  "Normalize a room's in-memory session map for an on-disk write: fold the
   in-memory :provider-session-id mirror back into :cli-session-id and drop the
   keys that must never hit disk (the mirror itself and the in-flight marker)."
  [sess]
  (-> sess
      (assoc :cli-session-id (or (:provider-session-id sess)
                                 (:cli-session-id sess)))
      (dissoc :provider-session-id
              ;; Never carry the in-flight marker into a normal save/touch —
              ;; a completed sync is precisely what clears it (only a hard
              ;; process kill mid-turn leaves it behind).
              :interrupted-at)))

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

(def ^:private commit-summary-re
  "Matches git's commit-summary line `[<branch> <sha>] subject` and captures
   the abbreviated sha. Covers the root-commit and detached-HEAD variants
   (`[master (root-commit) abc1234]`, `[detached HEAD abc1234]`)."
  #"\[[^\]]*?([0-9a-f]{7,40})\]")

(def ^:private clj-commit-re
  "Matches a commit made from the clj sandbox: `(git \"commit\" …)` via the
   pre-approved git helper, or the escalated `(sh \"git\" \"commit\" …)` form."
  #"\(\s*(?:git\s+\"commit\"|sh\s+\"git\"\s+\"commit\")")

(defn- commit-block?
  "True when a history entry is a commit action: the git_commit tool, a bash
   command that ran `git commit` (amend/fixup included), or a clj sandbox call
   whose code ran `(git \"commit\" …)` / `(sh \"git\" \"commit\" …)`. Tolerant
   of the mcp__ prefix and casing."
  [{:keys [kind tool arguments]}]
  (and (= :tool-call kind)
       (let [t (some-> tool util/strip-mcp-prefix str/lower-case)]
         (or (= t "git_commit")
             (and (= t "bash")
                  (some-> (:command arguments) str/lower-case (str/includes? "git commit")))
             (and (= t "clj")
                  (some->> (:code arguments) (re-find clj-commit-re)))))))

(defn- result->text
  "Display text of a tool-result content (a string or a vector of blocks)."
  [content]
  (cond
    (string? content)     content
    (sequential? content) (->> content
                               (keep #(cond (string? %)          %
                                            (= "text" (:type %)) (:text %)))
                               (str/join "\n"))
    :else nil))

(defn session-commit-refs
  "Abbreviated shas of the commits created during the session, in the order
   they were made. Scans the room history for commit blocks (the git_commit
   tool, shell `git commit`s, and clj-sandbox `(git \"commit\" …)` calls
   alike) and pulls each result's `[branch <sha>]`
   summary line. Stays pure: dedup and dead-commit (amended/rebased-away)
   pruning happen in the git layer against the live repo."
  [room]
  (->> (:history room)
       (filter commit-block?)
       (mapcat (fn [{:keys [result]}]
                 (->> (re-seq commit-summary-re (or (result->text result) ""))
                      (map second))))
       vec))

(defn- list-room-sessions [room scope]
  (let [pa? (get-in room [:agent :personal-agent?])]
    (cond
      pa?           (session/list-personal-agent-sessions
                     (get-in room [:session :agent]))
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
  ["claude-fable-5-1" "claude-opus-5" "claude-opus-4-8" "claude-fable-5"
   "claude-sonnet-5" "claude-opus-4-6" "claude-sonnet-4-6"
   "claude-haiku-4-5-20251001"])

(defn- fetch-ollama-ids
  "Promise of Ollama model-name vector (empty on error)."
  []
  (-> (js/fetch "http://localhost:11434/api/tags")
      (.then (fn [res] (.json res)))
      (.then (fn [^js data]
               (mapv :name (js->clj (.-models data) :keywordize-keys true))))
      (.catch (fn [_err] []))))

(defn- fetch-zen-ids
  "Promise of OpenCode Zen model ids, each prefixed with `opencode/` so the
   picker routes them to the Zen provider. Empty on error (best-effort)."
  []
  (-> (js/fetch "https://opencode.ai/zen/v1/models"
                #js {:signal (js/AbortSignal.timeout 4000)})
      (.then (fn [res] (.json res)))
      (.then (fn [^js data]
               (->> (js->clj (.-data data) :keywordize-keys true)
                    (keep :id)
                    (mapv (fn [id] (str "opencode/" id))))))
      (.catch (fn [_err] []))))

(defn- fetch-all-model-ids
  "Fetch Ollama + Zen model names, combine with Claude IDs, call cb.
   Each source degrades to empty independently, so a failure never blocks the
   others; falls back to Claude-only if all remote sources fail."
  [cb]
  (-> (js/Promise.all #js [(fetch-ollama-ids) (fetch-zen-ids)])
      (.then (fn [^js results]
               (let [[ollama zen] (js->clj results)]
                 (cb (into (into claude-model-ids ollama) zen)))))
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
   (fn [{:keys [dispatch! state]} {:keys [room-id save-current? after-prompt truncated-from keep-history?]}]
     (let [room (room-of state room-id)
           current (:session room)
           pa? (get-in room [:agent :personal-agent?])]
       (when (and save-current? (:provider-session-id current))
         (try (session/save-session! (->disk-session current))
              (catch :default e
                (js/console.error "[fx] session save failed:" e))))
       (dispatch! {:type :session/created
                   :room-id room-id
                   :session (cond-> (session/create-session
                                     (or (:cwd room) (.cwd js/process))
                                     (when pa? {:personal-agent? true
                                                :agent (:agent current)}))
                              ;; Lineage link from /truncate — persisted with the
                              ;; session so a later resume can render the prior
                              ;; conversation above the truncation divider.
                              truncated-from (assoc :truncated-from truncated-from))
                   :keep-history? keep-history?
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
                             (when pa? {:personal-agent? true
                                        :agent (:agent current)}))})))

   :session/sync
   (fn [{:keys [dispatch! state]} {:keys [room-id]}]
     (let [room (room-of state room-id)
           sess (:session room)]
       ;; Skip persisting a session the user deleted while its room was still
       ;; keep-alive (see room-manager/session-delete) — re-writing the file on
       ;; turn-end would resurrect the card the delete just removed.
       (when (and (:provider-session-id sess) (not (:deleted? sess)))
         (let [title (util/session-title (first-user-text room))
               model (get-in room [:agent :model])
               ;; The turn that triggered this sync ended aborted when the
               ;; room's last history entry is the {:kind :aborted} stub
               ;; turn-end appends. Effects run against post-reduce state, so
               ;; the stub is already present. Persist that as :aborted-at (so
               ;; the HTTP status endpoint can report `error` after the room is
               ;; reaped); a later clean turn's sync drops it.
               aborted? (= :aborted (:kind (last (:history room))))
               named (cond-> sess
                       (and (nil? (:name sess)) title) (assoc :name title)
                       model (assoc :model model)
                       aborted? (assoc :aborted-at (.toISOString (js/Date.)))
                       (not aborted?) (dissoc :aborted-at))
               touched (session/touch-session! (->disk-session named))]
           (dispatch! {:type :session/updated :room-id room-id
                       :session (-> touched
                                    (assoc :provider-session-id (:cli-session-id touched)))})))))

   ;; Persist an :interrupted-at marker as soon as a resumable session id
   ;; exists (turn in flight / spinner shown). A completed turn's :session/sync
   ;; rewrites the file without the marker, so it only survives a hard process
   ;; kill mid-turn — the signal used to auto-resume the agent on reconnect.
   :session/mark-interrupted
   (fn [{:keys [state]} {:keys [room-id]}]
     (let [room (room-of state room-id)
           sess (:session room)]
       (when (and (:provider-session-id sess) (not (:deleted? sess)))
         (let [title (util/session-title (first-user-text room))
               model (get-in room [:agent :model])
               named (cond-> sess
                       (and (nil? (:name sess)) title) (assoc :name title)
                       model (assoc :model model))]
           (try (session/mark-interrupted! (->disk-session named))
                (catch :default e
                  (js/console.error "[fx] session mark-interrupted failed:" e)))))))

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
         (dispatch! {:type :ui/menu-push :room-id room-id
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
         (dispatch! {:type :ui/menu-push :room-id room-id
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
   (fn [{:keys [dispatch! state]} {:keys [room-id scope index session-id]}]
     (let [room (room-of state room-id)
           sessions (list-room-sessions room scope)
           summary (if session-id
                     (or (some #(when (= session-id (:session-id %)) %) sessions)
                         ;; Promoted sub-agent sessions are hidden from the
                         ;; listings — resolve an explicit id directly.
                         (session/find-session-by-id session-id))
                     (when (and (<= 1 index) (<= index (count sessions)))
                       (nth sessions (dec index))))]
       (if summary
         ;; A resumed session may live in a sibling git worktree; landing the
         ;; room in that worktree (or the repo root when it's gone) is the
         ;; worktree extension's job, chained onto :session/resumed.
         (dispatch! {:type :session/resumed
                     :room-id room-id
                     :session (session/load-session summary)
                     :summary summary
                     :messages (session/read-session-messages summary)})
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
     ;; Persist each attachment to disk so the agent's file tools and any
     ;; spawned subagents can reach it by path (they only see the inline base64
     ;; blocks otherwise). The path rides on each attachment map (:path) and is
     ;; surfaced to the provider prompt at turn time — the visible user bubble
     ;; keeps the plain text. Images are additionally resized to fit the API
     ;; pixel limits; other file types (PDF, zip, text, …) are stored verbatim
     ;; and reach the model only via their on-disk path.
     (let [processed
           (->> images
                (mapv (fn [{:keys [media-type] :as att}]
                        (if (str/starts-with? (or media-type "") "image/")
                          (let [img (image/ensure-within-limits att)]
                            (if-let [p (image/persist-image! img)]
                              (assoc img :path p)
                              img))
                          (if-let [p (image/persist-file! att)]
                            (assoc att :path p)
                            att)))))]
       (dispatch! {:type :prompt/submit :room-id room-id :text text
                   :images processed})))

   :models/fetch
   (fn [{:keys [dispatch!]} {:keys [room-id]}]
     ;; Show a spinner frame immediately (drills onto the palette if it's
     ;; open), then fill it in once the model list arrives.
     (dispatch! {:type :ui/menu-push :room-id room-id
                 :menu {:id :model :prompt "model> " :loading? true}})
     (fetch-all-model-ids
      (fn [ids]
        (let [items (mapv (fn [id]
                            {:label id
                             :event {:type :command/run :room-id room-id
                                     :name "model" :args id}})
                          ids)]
          (dispatch! {:type :ui/menu-populate :room-id room-id :id :model
                      :menu {:prompt "model> " :items items}})))))

   :model/persist-preferred
   (fn [_ctx {:keys [model]}]
     (when (seq model)
       (session/save-preferred-model! model)))

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
