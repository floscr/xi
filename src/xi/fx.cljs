(ns xi.fx
  "Effect handlers for sessions, images and model listing — the impure
   counterparts to xi.commands. Effect handlers receive {:dispatch! :state}
   and a payload; they report completion by dispatching events, never by
   touching state.

   Room session shape: the on-disk session map (xi.session) plus
   :provider-session-id mirroring :cli-session-id in memory. The mirror key
   is stripped before writes so the on-disk format stays unchanged."
  (:require [clojure.string :as str]
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

(defn create-fx []
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

   :image/process
   (fn [{:keys [dispatch!]} {:keys [room-id text images]}]
     (dispatch! {:type :prompt/submit :room-id room-id :text text
                 :images (image/process-images images)}))

   :models/fetch
   (fn [{:keys [dispatch!]} {:keys [room-id]}]
     (let [->item (fn [label description value]
                    {:label label
                     :description description
                     :event {:type :command/run :room-id room-id
                             :name "model" :args value}})
           claude-items [(->item "claude-fable-5"    "Most capable model" "claude-fable-5")
                         (->item "claude-opus-4-8"   "Latest Opus"        "claude-opus-4-8")
                         (->item "claude-opus-4-6"   "Opus 4.6"           "claude-opus-4-6")
                         (->item "claude-sonnet-4-6" "Fast + intelligent" "claude-sonnet-4-6")
                         (->item "claude-haiku-4-5"  "Fastest"            "claude-haiku-4-5-20251001")]
           open! (fn [items]
                   (dispatch! {:type :ui/menu-open :room-id room-id
                               :menu {:id :model :prompt "model> " :items items}}))]
       (-> (js/fetch "http://localhost:11434/api/tags")
           (.then (fn [res] (.json res)))
           (.then (fn [^js data]
                    (let [models (js->clj (.-models data) :keywordize-keys true)
                          ollama-items (mapv (fn [m]
                                               (->item (:name m)
                                                       (get-in m [:details :parameter_size])
                                                       (:name m)))
                                             models)]
                      (open! (into claude-items ollama-items)))))
           (.catch (fn [_err] (open! claude-items))))))})
