(ns xi.workspace
  "Workspaces: named sets of project directories that scope the web client's
   session lists and project pages, picked in the tab bar across the top
   (xi.web.views/workspace-bar). The first tab, All (`all-id`), is the root
   workspace and shows everything.

   Two sources, shown as one tab row:

     config  `:workspaces` in config.edn (`parse-config`): dynamic — a dir may
             contain a `*` (one path segment), so a checkout joins by name.
             Server-global; sent in the lobby under [:lobby :workspaces] with
             `~` expanded (xi.user-config/workspaces).
     user    per-user state (xi.user-state :workspaces), edited in the UI:
             exact project directories, kept at :workspace/user.

   Membership is by directory: a session belongs to a workspace when its cwd
   is one of the dirs or below one. The selected id lives at
   :workspace/selected (xi.user-state :workspace); `scope` filters the lobby
   mirror by it, and is what the session-list choke points
   (xi.session.sidebar, xi.palette, xi.web.views) call."
  (:require [clojure.string :as str]))

(def all-id "all")
(def max-workspaces 32)
(def max-dirs 64)
(def max-label 40)

(defn- non-blank-string? [x]
  (and (string? x) (not (str/blank? x))))

(defn- id-string [id]
  (cond (keyword? id) (name id)
        (string? id)  id))

;; ── Config: dynamic workspaces ───────────────────────────────────────────────

(def ^:private shape
  "[{:id :penpot :label \"Penpot\" :dirs [\"~/Code/penpot\" \"~/Code/penpot-*\"]}]")

(defn- parse-entry
  "A config entry normalized to {:id str :label str :dirs [str]}, or nil."
  [entry]
  (when (and (map? entry)
             (every? #{:id :label :dirs} (keys entry))
             (or (keyword? (:id entry)) (non-blank-string? (:id entry)))
             (or (nil? (:label entry)) (non-blank-string? (:label entry)))
             (sequential? (:dirs entry))
             (seq (:dirs entry))
             (every? non-blank-string? (:dirs entry)))
    (let [id (id-string (:id entry))]
      {:id id :label (or (:label entry) id) :dirs (vec (:dirs entry))})))

(defn parse-config
  "Validate config.edn's `:workspaces` (nil = absent) → `[{:id :label :dirs}]`
   or `{:error msg}`."
  [data]
  (cond
    (nil? data)
    []

    (not (sequential? data))
    {:error (str ":workspaces must be a vector " shape)}

    (some nil? (map parse-entry data))
    {:error (str ":workspaces entries must be {:id kw :label str :dirs [dir …]} "
                 "with at least one dir — " shape)}

    :else
    (let [ws  (mapv parse-entry data)
          ids (map :id ws)]
      (cond
        (and (seq ids) (not (apply distinct? ids)))
        {:error ":workspaces ids must be distinct"}

        (some #{all-id} ids)
        {:error (str ":workspaces may not use the id " (pr-str all-id)
                     ", that is the root workspace")}

        :else ws))))

;; ── Membership ───────────────────────────────────────────────────────────────

(defn- strip-slash [d]
  (if (and (> (count d) 1) (str/ends-with? d "/"))
    (subs d 0 (dec (count d)))
    d))

(defn- quote-re [s]
  (str/replace s #"[.*+?^${}()|\[\]\\]" (fn [m] (str "\\" m))))

(defn- glob->re
  "`*` matches within one path segment, so \"~/Code/penpot-*\" takes the
   siblings but \"~/Code/*\" not the grandchildren."
  [pattern]
  (re-pattern (str "^" (str/join "[^/]*" (map quote-re (str/split pattern #"\*" -1))) "$")))

(defn- ancestors-of
  "cwd, then each directory above it, down to (not including) the root."
  [cwd]
  (->> (iterate (fn [p] (let [i (str/last-index-of p "/")]
                          (when (and i (pos? i)) (subs p 0 i))))
                cwd)
       (take-while some?)))

(defn dir-matches?
  "Is `cwd` the directory `dir` names, or below it? A `dir` with `*` matches
   when the cwd or one of its ancestors matches the glob."
  [dir cwd]
  (let [dir (strip-slash dir)
        cwd (strip-slash cwd)]
    (if (str/includes? dir "*")
      (let [re (glob->re dir)]
        (boolean (some #(re-matches re %) (ancestors-of cwd))))
      (or (= dir cwd)
          (str/starts-with? cwd (str dir "/"))))))

(defn member?
  "Does a session with `cwd` belong to the workspace?"
  [{:keys [dirs]} cwd]
  (boolean (and (string? cwd)
                (some #(dir-matches? % cwd) dirs))))

;; ── Reading state ────────────────────────────────────────────────────────────

(defn workspaces
  "Every workspace in tab order, config ones first, each with `:source`
   (:config / :user). Without the root All."
  [state]
  (-> []
      (into (map #(assoc % :source :config)) (get-in state [:lobby :workspaces]))
      (into (map #(assoc % :source :user)) (:workspace/user state))))

(defn find-workspace [state id]
  (some #(when (= id (:id %)) %) (workspaces state)))

(defn selected
  "The selected workspace, or nil for All (also when the selected id no
   longer exists)."
  [state]
  (let [id (:workspace/selected state)]
    (when (and id (not= id all-id))
      (find-workspace state id))))

(defn selected-id [state]
  (or (:id (selected state)) all-id))

(defn- tab-ids [state]
  (into [all-id] (map :id) (workspaces state)))

(defn neighbor-id
  "The id `step` tabs (+1 / -1) from the selected one, wrapping; All counts
   as the first tab."
  [state step]
  (let [ids (tab-ids state)
        i   (.indexOf ids (selected-id state))
        n   (count ids)]
    (nth ids (mod (+ (max i 0) step) n))))

(defn nth-id
  "The id of tab `n`, counted from 1 (All), like Doom's workspace numbers;
   :last for the final tab. nil past the end."
  [state n]
  (let [ids (tab-ids state)]
    (if (= n :last)
      (peek ids)
      (get ids (dec n)))))

(defn filter-sessions
  "`sessions` (maps with :cwd) in the workspace; all of them for nil (All)."
  [ws sessions]
  (if ws (filterv #(member? ws (:cwd %)) sessions) sessions))

(defn filter-dirs
  "Project directories in the workspace; all of them for nil (All)."
  [ws dirs]
  (if ws (filterv #(member? ws %) dirs) dirs))

(defn scope
  "`state` with the lobby's sessions and rooms narrowed to the selected
   workspace; unchanged on All. The session-list choke points call this."
  [state]
  (if-let [ws (selected state)]
    (update state :lobby
            (fn [lobby]
              (-> lobby
                  (update :sessions #(filter-sessions ws %))
                  (update :rooms #(filter-sessions ws %)))))
    state))

(defn unscoped
  "`state` as All sees it (the badges count across workspaces)."
  [state]
  (dissoc state :workspace/selected))

;; ── User-defined workspaces (the :workspace/user vector) ─────────────────────

(defn valid-id? [v]
  (and (string? v) (<= 1 (count v) 64)))

(defn valid-user-workspaces?
  "The shape xi.user-state stores: `[{:id str :label str :dirs [str …]}]`,
   bounded."
  [v]
  (and (sequential? v)
       (<= (count v) max-workspaces)
       (every? (fn [w]
                 (and (map? w)
                      (every? #{:id :label :dirs} (keys w))
                      (valid-id? (:id w))
                      (string? (:label w)) (<= 1 (count (:label w)) max-label)
                      (sequential? (:dirs w))
                      (<= (count (:dirs w)) max-dirs)
                      (every? #(and (string? %) (<= 1 (count %) 512)) (:dirs w))))
               v)
       (or (empty? v) (apply distinct? (map :id v)))))

(defn clean-label [label]
  (let [l (str/trim (or label ""))]
    (subs l 0 (min (count l) max-label))))

(defn add-workspace
  "`user` with a new workspace appended; a blank label or an id already taken
   leaves it unchanged."
  [user {:keys [id label dirs]}]
  (let [label (clean-label label)
        user  (vec user)]
    (if (or (str/blank? label) (some #(= id (:id %)) user) (>= (count user) max-workspaces))
      user
      (conj user {:id id :label label :dirs (vec (distinct (filter string? dirs)))}))))

(defn rename-workspace [user id label]
  (let [label (clean-label label)]
    (if (str/blank? label)
      (vec user)
      (mapv #(if (= id (:id %)) (assoc % :label label) %) user))))

(defn remove-workspace [user id]
  (filterv #(not= id (:id %)) user))

(defn toggle-dir
  "Add `dir` to the workspace `id`, or remove it when already there."
  [user id dir]
  (mapv (fn [w]
          (if (= id (:id w))
            (update w :dirs (fn [dirs]
                              (if (some #{dir} dirs)
                                (vec (remove #{dir} dirs))
                                (vec (take max-dirs (conj (vec dirs) dir))))))
            w))
        user))
