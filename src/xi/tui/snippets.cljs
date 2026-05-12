(ns xi.tui.snippets
  "Snippet expansion for the editor. Maps trigger strings to expansions.")

(def ^:private snippets
  (atom {"c"   "continue"
         "rec" "in a recent change"}))

(defn expand
  "Given a trigger string, return the expansion or nil."
  [trigger]
  (get @snippets trigger))

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
