(ns xi.tui.snippets
  "Snippet expansion for the editor. Maps trigger strings to expansions.")

(def ^:private snippets
  (atom {"c"   "continue"
         "rec" "in a recent change"}))

(defn expand
  "Given a trigger string, return the expansion or nil."
  [trigger]
  (get @snippets trigger))

(defn expand-at
  "Expand the snippet trigger that ends at `caret` in multi-line `text`. The
   word is delimited by whitespace, so it also works after a newline (the web
   composer). Returns {:text new-text :caret new-caret}, or nil when the word
   before the caret is not a snippet trigger."
  [text caret]
  (let [before     (subs text 0 caret)
        word-start (loop [i (dec (count before))]
                     (cond
                       (neg? i) 0
                       (re-matches #"\s" (.charAt before i)) (inc i)
                       :else (recur (dec i))))]
    (when-let [expansion (expand (subs before word-start))]
      {:text  (str (subs before 0 word-start) expansion (subs text caret))
       :caret (+ word-start (count expansion))})))

(defn add!
  "Register a snippet. Returns nil."
  [trigger expansion]
  (swap! snippets assoc trigger expansion)
  nil)

(defn remove!
  "Remove a snippet. Returns true if it existed."
  [trigger]
  (let [existed (contains? @snippets trigger)]
    (swap! snippets dissoc trigger)
    existed))

(defn list-all
  "Return all snippets as a sorted seq of [trigger expansion] pairs."
  []
  (sort-by first @snippets))
