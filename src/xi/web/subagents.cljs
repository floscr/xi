(ns xi.web.subagents
  "Pure reads behind the chat's sub-agent rows, the composer's Agents
   popover and the sidebar's batch view (xi.web.views): who waits on a
   permission ask, a row's one-line summary, a batch's shared label, and the
   agents grouped by the prompt that spawned them. A batch is the children
   spawned at one anchor (xi.ext.subagent.threads/anchor-of)."
  (:require [clojure.string :as str]
            [xi.ext.subagent.threads :as sa-threads]))

;; ── Batch views ──────────────────────────────────────────────────────────────

(def ^:private group-prefix "sa-group:")

(defn group-view
  "The layout view (xi.web.layout) of the batch whose first child is `sub-id`."
  [sub-id]
  (str group-prefix sub-id))

(defn group-view-id
  "The first child's id when `view` names a batch view, else nil."
  [view]
  (when (and (string? view) (str/starts-with? view group-prefix))
    (subs view (count group-prefix))))

(defn batch-of
  "The children spawned at the same anchor as `sub-id` in `history`, in spawn
   order."
  [history children sub-id]
  (when-let [c (some #(when (= sub-id (:id %)) %) children)]
    (let [a (sa-threads/anchor-of history c)]
      (filterv #(= a (sa-threads/anchor-of history %)) children))))

;; ── Asks and state ───────────────────────────────────────────────────────────

(defn asks-by-sub
  "sub-id → the room's pending dialogs of that sub-agent, in queue order."
  [dialogs]
  (group-by :sub-id (filter :sub-id dialogs)))

(defn state-of
  "What a row shows for `child`: :ask while one of its permission asks waits,
   else its status."
  [asks {:keys [id status]}]
  (if (seq (get asks id)) :ask (or status :running)))

(defn counts
  "{:ask :running :finished :error :total} over `children`; an asking child
   counts only as :ask, a failed one as :finished and :error."
  [asks children]
  (let [states (map #(state-of asks %) children)]
    {:ask      (count (filter #{:ask} states))
     :running  (count (filter #{:running} states))
     :finished (count (remove #{:ask :running} states))
     :error    (count (filter #{:error} states))
     :total    (count children)}))

(defn batch-state
  "One state for a batch's mark: :ask while any child asks, :running while
   any runs, :error when one failed, else :done."
  [{:keys [ask running error]}]
  (cond (pos? ask) :ask (pos? running) :running (pos? error) :error :else :done))

;; ── Text ─────────────────────────────────────────────────────────────────────

(defn- first-line [s]
  (some->> (str/split-lines (str s))
           (map str/trim)
           (remove str/blank?)
           first))

(defn- plain
  "A markdown line as plain text: no heading / list markers, emphasis or code
   ticks."
  [line]
  (some-> line
          (str/replace #"^(#+|[-*+]|\d+\.)\s+" "")
          (str/replace #"\*\*|__|`" "")))

(defn label
  "A child's name: its label, else its task's first line."
  [{label' :label :keys [task]}]
  (or (not-empty label') (first-line task) "sub-agent"))

(defn ask-text
  "A sub-agent's ask without the “Sub-agent “x”: ” prefix xi.subagent adds
   for the main chat."
  [{:keys [message text]}]
  (str/replace (str (or message text)) #"^Sub-agent “[^”]*”: " ""))

(defn summary
  "One line for `child`'s row: its ask, the newest tool while it runs, else
   the answer's first line."
  [asks {:keys [id status history result]}]
  (if-let [ask (first (get asks id))]
    (first-line (ask-text ask))
    (case (or status :running)
      :running (if-let [t (some #(when (= :tool-call (:kind %)) %) (rseq (vec history)))]
                 (str (:tool t) "…")
                 "starting…")
      :error   (or (plain (first-line result)) "failed")
      :stopped "stopped"
      (or (plain (first-line result)) "no answer"))))

(defn- common-prefix [a b]
  (subs a 0 (count (take-while true? (map = a b)))))

(defn shared-label
  "What a batch's labels share, cut at the last \": \" (\"Commit plan: /a\",
   \"Commit plan: /b\" → \"Commit plan\"), else at the last space; nil when
   that leaves under three characters or there is one label."
  [labels]
  (when (< 1 (count labels))
    (let [p   (reduce common-prefix labels)
          cut (or (str/last-index-of p ": ") (str/last-index-of p " "))
          s   (when cut (str/trim (subs p 0 cut)))]
      (when (and s (<= 3 (count s))) s))))

(defn child-title
  "`label` without the batch's `prefix` (shared-label) and the \": \" after it."
  [prefix label']
  (if (and prefix (str/starts-with? label' prefix))
    (or (not-empty (str/replace (subs label' (count prefix)) #"^[\s:]+" "")) label')
    label'))

;; ── By prompt ────────────────────────────────────────────────────────────────

(defn by-turn
  "`children` grouped by the user prompt before their anchor in `history`:
   [{:index :prompt :batches [[child …] …]}] in history order, :index -1 (no
   :prompt) for ones spawned before any. A batch shares one anchor."
  [history children]
  (let [history (vec history)
        prompt-before (fn [a]
                        (or (some #(when (= :user (:kind (nth history %))) %)
                                  (range (dec (min a (count history))) -1 -1))
                            -1))
        anchored (map (fn [c] (let [a (sa-threads/anchor-of history c)]
                                {:child c :anchor a :turn (prompt-before a)}))
                      children)]
    (->> (group-by :turn anchored)
         (sort-by key)
         (mapv (fn [[i xs]]
                 {:index   i
                  :prompt  (when (<= 0 i) (first-line (:text (nth history i))))
                  :batches (->> (group-by :anchor xs)
                                (sort-by key)
                                (mapv (fn [[_ ys]] (mapv :child ys))))})))))
