(ns xi.web.sidebar-nav
  "Keyboard walk through the sidebar (:session/next / :session/prev and their
   :sibling twins). The rows are the rendered DOM: every navigable sidebar
   row carries `data-nav` (its key, e.g. \"s:<session-id>\", see
   xi.web.views/recent-sidebar*), so a collapsed group, whose rows aren't
   built, is skipped for free. A step clicks the target row, reusing its own
   handler. The sibling walk stays inside the row's context: its sidebar
   section, or the buffer list it hangs in.

   Pure helpers (`route-keys`, `locate`, `step`) are tested in
   xi.web.sidebar-nav-test."
  (:require [clojure.string :as str]
            [xi.core.state :as state]))

;; ── Pure ─────────────────────────────────────────────────────────────────────

(defn route-keys
  "The row keys the current route stands on, best first: a chat on a buffer
   is its buffer row, else its session row (buffers folded away)."
  [st]
  (let [{:keys [page session-id dir]} (:web/route st)
        room (state/active-room st)
        buf  (when (= session-id (get-in room [:session :id]))
               (get-in room [:ui :active-buffer]))]
    (case page
      :chat (when session-id
              (cond-> []
                (and buf (not= :chat buf)) (conj (str "b:" session-id ":" buf))
                true                       (conj (str "s:" session-id))))
      :home [(cond (string? dir)        (str "dir:" dir)
                   (#{:projects :all} dir) "projects"
                   :else                "home")]
      (vec (for [{:keys [menu label event]} (:web/nav-items st)
                 :when (and (= :sidebar menu)
                            (= :route/navigate (:type event))
                            (= page (:page event)))]
             (str "nav:" label))))))

(defn- index-of
  [ks k]
  (first (keep-indexed (fn [i x] (when (= x k) i)) ks)))

(defn- buffer-row-of?
  "Is `k` a buffer row of session row `session-key`?"
  [session-key k]
  (and (str/starts-with? session-key "s:")
       (str/starts-with? (str k) (str "b:" (subs session-key 2) ":"))))

(defn locate
  "Where the walk stands in `ks` (row keys in sidebar order) → {:idx :exact?}
   or nil. The first of `currents` (route-keys) that has a row wins; the
   `cursor` ({:key :idx} of the last step) breaks ties for a chat listed in
   two groups, keeps a sub-agent row (it reveals the card without changing
   the route) and stands in when the route has no row (a \"more\" row's
   page). A cursor row that vanished (an opened draft) leaves `:exact?`
   false: its successor now sits at `:idx`."
  [ks currents {:keys [key idx] :as cursor}]
  (let [at? (fn [k] (and (some? idx) (= k (get ks idx))))
        cur (some #(when (index-of ks %) %) currents)]
    (if (and cur (not (and (at? key) (buffer-row-of? cur key))))
      {:idx (if (at? cur) idx (index-of ks cur)) :exact? true}
      (when cursor
        (cond
          (at? key)          {:idx idx :exact? true}
          (index-of ks key)  {:idx (index-of ks key) :exact? true}
          (seq ks)           {:idx (min idx (dec (count ks))) :exact? false})))))

(defn step
  "Target index among `n` rows from `pos` (locate) in `dir` (:next / :prev),
   clamped to the ends. No position: the first row, or the last going up."
  [n {:keys [idx exact?]} dir]
  (when (pos? n)
    (let [t (cond
              (nil? idx)    (if (= dir :next) 0 (dec n))
              (= dir :next) (if exact? (inc idx) idx)
              :else         (dec idx))]
      (max 0 (min (dec n) t)))))

(defn- row-session
  "The session a row key belongs to: its session row or one of its buffer rows."
  [k]
  (cond
    (str/starts-with? (str k) "s:") (subs k 2)
    (str/starts-with? (str k) "b:") (first (str/split (subs k 2) #":" 2))))

(defn enter-at
  "Target index `t` in `ks`, unless it is a buffer row of a session the row at
   `from` (nil: none) isn't part of: the walk enters a chat on its session
   row, which opens it on its last view (xi.web.router/restore-view)."
  [ks from t]
  (let [k (get ks t)]
    (if (and (str/starts-with? (str k) "b:")
             (not= (row-session k) (some-> (get ks from) row-session)))
      (or (some #(when (= (get ks %) (str "s:" (row-session k))) %) (range t -1 -1))
          t)
      t)))

;; ── DOM ──────────────────────────────────────────────────────────────────────

;; {:key :idx} of the row the last step landed on (see locate).
(defonce ^:private cursor (atom nil))

(defn- rows []
  (vec (array-seq (.querySelectorAll js/document ".sidebar [data-nav]"))))

(defn- context-of
  [^js el]
  (or (.closest el ".session-buffers") (.closest el ".sidebar-section")))

(defn step!
  "Click the next / previous visible sidebar row (`dir` :next / :prev); with
   `sibling?` only rows sharing the current row's context. Returns false when
   the sidebar isn't rendered (a closed mobile drawer), so the caller can fall
   back to the plain session order."
  [st dir sibling?]
  (let [els (rows)
        ks  (mapv #(.. ^js % -dataset -nav) els)]
    (if (empty? els)
      false
      (let [pos   (locate ks (route-keys st) @cursor)
            ;; Indexes into els the walk may land on, and pos within them.
            scope (if (and sibling? (:exact? pos))
                    (let [ctx (context-of (nth els (:idx pos)))]
                      (filterv #(identical? ctx (context-of (nth els %))) (range (count els))))
                    (vec (range (count els))))
            spos  (when pos
                    (if (:exact? pos)
                      {:idx (index-of scope (:idx pos)) :exact? true}
                      pos))
            t     (step (count scope) spos dir)]
        (when (and t (not (and (:exact? spos) (= t (:idx spos)))))
          (let [i       (enter-at ks (when (:exact? pos) (:idx pos)) (nth scope t))
                ^js el  (nth els i)]
            (reset! cursor {:key (nth ks i) :idx i})
            (.scrollIntoView el #js {:block "nearest"})
            (.click el)))
        true))))
