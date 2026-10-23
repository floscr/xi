(ns xi.web.workspaces
  "The web client's workspace handlers (xi.workspace): selecting a tab, the
   user's own workspaces (create / rename / remove / a project in or out), the
   name dialog (:workspace/editor) and the palette's workspace page, the last
   two rendered by xi.web.views. The user's own workspaces are per-user state
   (xi.web.user-state :workspaces), so every edit is sent to the server.

   The selected tab (:workspace/selected) belongs to the browser window, never
   synced: two windows can show two workspaces. Each workspace remembers the
   chat focused in it (:workspace/chats, {workspace-id session-id}): leaving a
   tab records the chat on screen, entering one opens its chat again
   (`focus-nav`). Both live in the window's sessionStorage
   (:cache/window-workspace, xi.web.cache), so a reload keeps them."
  (:require [clojure.string :as str]
            [xi.web.user-state :as user-state]
            [xi.workspace :as workspace]))

(defn- current-chat
  "The session id of the chat on screen, or nil off the chat page."
  [st]
  (when (= :chat (get-in st [:web/route :page]))
    (get-in st [:web/route :session-id])))

(defn- session-cwd
  "The cwd of `sid` in the lobby, and whether the lobby knows it at all."
  [st sid]
  (some #(when (= sid (:session-id %)) {:cwd (:cwd %)})
        (concat (get-in st [:lobby :rooms]) (get-in st [:lobby :sessions]))))

(defn focus-nav
  "Where entering workspace `ws` (nil = All) goes, as a :route/navigate event
   or nil to stay: its remembered chat `target` while the lobby still lists
   it; else stay on the chat on screen when it belongs to `ws`, and leave a
   chat that does not for the dashboard. Off the chat page without a
   remembered chat nothing moves (the lists are filtered anyway)."
  [st ws target]
  (let [cur (current-chat st)]
    (cond
      (and target (= target cur))   nil
      (and target (session-cwd st target))
      {:type :route/navigate :page :chat :session-id target}
      (nil? cur)                    nil
      (or (nil? ws) (workspace/member? ws (:cwd (session-cwd st cur)))) nil
      :else                         {:type :route/navigate :page :home})))

(defn- remember-chat
  "`chats` with workspace `id`'s focused chat set to `sid` (nil drops it),
   pruned to the workspaces that still exist."
  [st chats id sid]
  (let [ids (into #{workspace/all-id} (map :id) (workspace/workspaces st))]
    (-> (if sid (assoc chats id sid) (dissoc chats id))
        (select-keys ids))))

(defn- select [st id]
  (let [id   (if (workspace/find-workspace st id) id workspace/all-id)
        prev (workspace/selected-id st)]
    (if (= id prev)
      ;; the stored id may name a workspace that is gone: store the fallback
      (cond-> {:state (assoc st :workspace/selected id)}
        (not= id (:workspace/selected st))
        (assoc :effects [[:cache/window-workspace {:selected id
                                                   :chats (or (:workspace/chats st) {})}]]))
      (let [chats (remember-chat st (or (:workspace/chats st) {}) prev (current-chat st))
            nav   (focus-nav st (workspace/find-workspace st id) (get chats id))]
        {:state   (assoc st :workspace/selected id
                         :workspace/previous prev
                         :workspace/chats chats)
         :effects (cond-> [[:cache/window-workspace {:selected id :chats chats}]]
                    nav (conj [:app/dispatch nav]))}))))

(defn- save-user
  "Install the user's edited workspaces and tell the server."
  [st user]
  {:state   (assoc st :workspace/user user)
   :effects [(user-state/set-effect :workspaces user)]})

(defn- then
  "Run handler result `r`, then `(f state)` on its state, joining the effects."
  [r f]
  (let [r2 (f (:state r))]
    {:state (:state r2) :effects (into (vec (:effects r)) (:effects r2))}))

(defn- new-id []
  (str "w-" (.toString (js/Date.now) 36) (.toString (rand-int 1296) 36)))

(defn- create
  "Add a workspace and switch to it."
  [st {:keys [label dirs]}]
  (let [id   (new-id)
        user (workspace/add-workspace (:workspace/user st) {:id id :label label :dirs dirs})]
    (when (not= user (vec (:workspace/user st)))
      (then (save-user st user) #(select % id)))))

(defn- user-workspace?
  "Is `id` one of the user's own workspaces (not config.edn's)?"
  [st id]
  (boolean (some #(= id (:id %)) (:workspace/user st))))

(defn- remove-ws [st {:keys [id]}]
  (when (user-workspace? st id)
    (let [saved (save-user st (workspace/remove-workspace (:workspace/user st) id))]
      (if (= id (:workspace/selected st))
        (then saved #(select % workspace/all-id))
        saved))))

(defn- toggle-dir
  "`dir` in or out of workspace `id`; `:close-palette?` when picked from the
   palette's project list."
  [st {:keys [id dir close-palette?]}]
  (when (and id dir (user-workspace? st id))
    (let [saved (save-user st (workspace/toggle-dir (:workspace/user st) id dir))]
      (if close-palette?
        (-> saved
            (update :state dissoc :web/palette-page :web/palette-open? :web/palette-drilling?)
            (update :effects conj [:palette/close nil]))
        saved))))

;; ── The name dialog ──────────────────────────────────────────────────────────

(defn- edit-open
  "`:mode :create` (optionally with the `:dir` the new workspace starts with)
   or `:mode :rename` of the user's workspace `:id`."
  [st {:keys [mode id dir]}]
  (when (or (= mode :create) (user-workspace? st id))
    {:state (assoc st :workspace/editor {:mode  mode
                                         :id    id
                                         :dir   dir
                                         :label (or (:label (workspace/find-workspace st id)) "")})}))

(defn- edit-save [st _]
  (let [{:keys [mode id dir label]} (:workspace/editor st)
        st (dissoc st :workspace/editor)]
    (if (str/blank? label)
      {:state st}
      (or (case mode
            :create (create st {:label label :dirs (when dir [dir])})
            :rename (save-user st (workspace/rename-workspace (:workspace/user st) id label))
            nil)
          {:state st}))))

;; ── Palette page ─────────────────────────────────────────────────────────────

(defn- open-page
  "The palette's workspace page (xi.web.views/palette-workspace-page)."
  [st _]
  {:state   (assoc st :web/palette-page {:kind :workspaces}
                   :web/palette-open? true
                   :web/palette-drilling? true)
   :effects [[:palette/reopen nil]
             [:palette/reset-filter nil]]})

(defn- tab
  "Tab `n` (1 = All, :last = the final one); nothing past the end."
  [st {:keys [n]}]
  (when-let [id (workspace/nth-id st n)]
    (select st id)))

(def handlers
  {:workspace/select         (fn [st {:keys [id]}] (select st id))
   :workspace/next           (fn [st _] (select st (workspace/neighbor-id st 1)))
   :workspace/prev           (fn [st _] (select st (workspace/neighbor-id st -1)))
   :workspace/tab            tab
   ;; Doom's SPC TAB `: back to the tab selected before this one
   :workspace/other          (fn [st _]
                               (select st (or (:workspace/previous st) workspace/all-id)))
   :workspace/create         create
   :workspace/remove         remove-ws
   :workspace/remove-current (fn [st _] (remove-ws st {:id (:workspace/selected st)}))
   :workspace/rename-current (fn [st _] (edit-open st {:mode :rename :id (:workspace/selected st)}))
   :workspace/toggle-dir     toggle-dir
   :workspace/edit-open      edit-open
   :workspace/edit-set       (fn [st {:keys [label]}]
                               (when (:workspace/editor st)
                                 {:state (assoc-in st [:workspace/editor :label] label)}))
   :workspace/edit-close     (fn [st _] {:state (dissoc st :workspace/editor)})
   :workspace/edit-save      edit-save
   :palette/open-workspaces  open-page})
