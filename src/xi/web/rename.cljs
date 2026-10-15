(ns xi.web.rename
  "The rename dialog's state (:web/rename) and handlers. Opening it asks the
   server for name suggestions right away (xi.naming :session/suggest-name),
   so they arrive while you type; replies are kept per session in
   :web/name-suggestions, so reopening costs nothing. Rendered by
   xi.web.views/rename-dialog."
  (:require [clojure.string :as str]))

(defn- request
  "Ask for suggestions, skipping every name this dialog has shown."
  [st]
  (let [{:keys [session-id seen]} (:web/rename st)]
    {:state   (update st :web/rename assoc :status :loading :selected nil)
     :effects [[:ws/send {:type :session/suggest-name :session-id session-id
                          :exclude (vec seen)}]]}))

(defn- open [st {:keys [session-id name]}]
  (let [{:keys [names stats]} (get-in st [:web/name-suggestions session-id])
        st (assoc st :web/rename {:session-id session-id
                                  :name       (or name "")
                                  :names      (or names [])
                                  :seen       (set (map :name names))
                                  :stats      stats
                                  :status     (when (seq names) :ready)})]
    (if (seq names) {:state st} (request st))))

(defn- pick
  "Fill the field with suggestion `idx`."
  [st idx]
  (when-let [n (get-in st [:web/rename :names idx :name])]
    {:state (update st :web/rename assoc :name n :selected idx)}))

(defn next-index
  "The suggestion Tab (dir 1) or Shift+Tab (dir -1) moves to from `selected`
   (nil: none yet) among `n`."
  [selected n dir]
  (cond
    (zero? n)       nil
    (nil? selected) (if (neg? dir) (dec n) 0)
    :else           (mod (+ selected dir) n)))

(defn- suggestion [st {:keys [session-id names stats]}]
  (let [names (vec names)
        st    (cond-> st
                (seq names) (assoc-in [:web/name-suggestions session-id]
                                      {:names names :stats stats}))]
    {:state (if (= session-id (get-in st [:web/rename :session-id]))
              (update st :web/rename
                      (fn [r]
                        (if (seq names)
                          (-> r
                              (assoc :names names :stats stats :status :ready)
                              (update :seen into (map :name names)))
                          (assoc r :status :failed))))
              st)}))

(defn- save [st _]
  (let [{:keys [session-id name]} (:web/rename st)
        name (str/trim (or name ""))]
    {:state   (dissoc st :web/rename)
     :effects (when (seq name)
                [[:ws/send {:type :session/rename :session-id session-id :name name}]])}))

(def handlers
  {:rename/open             open
   :rename/set              (fn [st {:keys [name]}]
                              (when (:web/rename st)
                                {:state (update st :web/rename assoc :name name :selected nil)}))
   :rename/pick             (fn [st {:keys [idx]}] (pick st idx))
   :rename/tab              (fn [st {:keys [dir]}]
                              (let [{:keys [selected names]} (:web/rename st)]
                                (some->> (next-index selected (count names) (or dir 1))
                                         (pick st))))
   :rename/more             (fn [st _]
                              (when (and (:web/rename st)
                                         (not= :loading (get-in st [:web/rename :status])))
                                (request st)))
   :session/name-suggestion suggestion
   :rename/save             save
   :rename/close            (fn [st _] {:state (dissoc st :web/rename)})})

;; ── Status line ──────────────────────────────────────────────────────────────

(defn model-label
  "\"claude-haiku-4-5-20251001\" → \"Haiku 4.5\"."
  [model]
  (if-let [[_ fam major minor] (re-find #"claude-([a-z]+)-(\d+)-(\d+)" (str model))]
    (str (str/capitalize fam) " " major "." minor)
    model))

(defn- compact [n]
  (if (>= n 1000) (str (.toFixed (/ n 1000) 1) "k") (str n)))

(defn stats-line
  "One line on what a suggestion turn cost: model, tokens, cost, time."
  [{:keys [model input-tokens output-tokens cost ms]}]
  (str/join " · "
            (remove nil?
                    [(model-label model)
                     (str (compact (or input-tokens 0)) " in, "
                          (compact (or output-tokens 0)) " out")
                     (when (number? cost)
                       (let [cents (* cost 100)]
                         (if (< cents 0.01) "<0.01¢" (str (.toFixed cents 2) "¢"))))
                     (when (number? ms) (str (.toFixed (/ ms 1000) 1) "s"))])))
