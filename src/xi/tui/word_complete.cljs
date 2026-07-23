(ns xi.tui.word-complete
  "Word tab-completion from the chat buffer. Given a partial word token and
   the room's history (oldest→newest), collects every word that appears in the
   conversation text and ranks the ones sharing the token's prefix.

   Ranking blends *frequency* and *closeness to the prompt*. Each occurrence of
   a word contributes a diminishing weight (most-recent 1, then ½, ¼, …), so a
   word you mention often — and recently — outranks an incidental word that
   just happens to appear last in the buffer. Equal scores break on recency
   (latest occurrence), then shorter completions, then alphabetically.

   Returns the same shape as `xi.tui.path-complete/complete` so the TUI can
   reuse one insert/menu handler:
     {:action :insert :text <suffix>}                — a single match, inline
     {:action :menu   :items [{:label :insert} ...]} — ranked candidate menu"
  (:require [clojure.string :as str]))

(def ^:private word-re
  "A completable word: starts on a letter/digit/underscore, then word chars or
   hyphens. Excludes punctuation, whitespace and path separators (paths are
   handled by xi.tui.path-complete)."
  #"[A-Za-z0-9_][A-Za-z0-9_-]*")

(defn- entry-text
  "The plain conversation text carried by a history entry, or nil for entries
   (tool calls, errors, …) that don't contribute vocabulary."
  [entry]
  (case (:kind entry)
    (:user :text :thinking :status) (:text entry)
    nil))

(defn- collect-words
  "Scan history (oldest→newest) with a global word counter (oldest word = 0)
   into a map of lower-cased-word → {:word <latest casing> :positions [idx …]}.
   Positions are the global indices at which the word occurs, so both how often
   and how recently it appears are available to the ranker."
  [history]
  (loop [texts (seq (keep entry-text history))
         idx   0
         acc   {}]
    (if-not texts
      acc
      (let [[idx' acc']
            (reduce (fn [[i m] w]
                      (let [k (str/lower-case w)
                            e (get m k {:word w :positions []})]
                        [(inc i)
                         (assoc m k (-> e
                                        (assoc :word w)
                                        (update :positions conj i)))]))
                    [idx acc]
                    (re-seq word-re (first texts)))]
        (recur (next texts) idx' acc')))))

(defn- freq-recency-score
  "Frequency weighted by recency: sum 0.5^i over occurrences ordered
   most-recent-first. One recent hit ≈ 1.0; repeated hits add diminishing
   returns up to ~2.0, so frequency lifts a word above a lone recent match."
  [positions]
  (->> (sort > positions)
       (map-indexed (fn [i _] (js/Math.pow 0.5 i)))
       (reduce + 0)))

(defn candidates
  "Ranked vector of full words from `history` that share `token`'s prefix
   (case-insensitive), excluding the token itself. Words nearer the prompt
   (more recent entries) rank first; ties break on shorter then alphabetical.
   Empty when the token is blank or nothing matches."
  [token history]
  (if-not (and (string? token) (>= (count token) 1))
    []
    (let [tl (str/lower-case token)]
      (->> (collect-words history)
           vals
           (filter (fn [{:keys [word]}]
                     (let [wl (str/lower-case word)]
                       (and (str/starts-with? wl tl)
                            (not= wl tl)))))
           (sort-by (fn [{:keys [word positions]}]
                      [(- (freq-recency-score positions)) ;; frequency×recency, desc
                       (- (apply max positions))          ;; latest occurrence, desc
                       (count word)                        ;; shorter first
                       (str/lower-case word)]))            ;; alphabetical
           (mapv :word)))))

(defn complete
  "Compute a word completion for `token` against a room's `history`.
   Matching is case-insensitive prefix; the token itself is never offered.
   Returns nil when nothing matches (see ns docstring for the result shape)."
  [token history]
  (let [cands (candidates token history)]
    (cond
      (empty? cands) nil

      (= 1 (count cands))
      {:action :insert :text (subs (first cands) (count token))}

      :else
      {:action :menu
       :items (mapv (fn [word]
                      {:label word :insert (subs word (count token))})
                    cands)})))
