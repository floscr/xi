(ns xi.blocks
  "Agent blocks: visual, interactive output an agent posts into the chat
   (docs/guide/agent-blocks.md). Pure and shared by every surface: the kind
   catalog, turning a model's payload into a validated spec, the size caps,
   the actions a block accepts, and each kind's text form (TUI, prompt mode,
   `ui_state`, Copy as Markdown).

   Blocks live at [:rooms rid :ext :blocks :blocks] (xi.ext.blocks owns the
   tools and reducers); the web half (xi.ext.blocks.web) draws them. A block
   record is plain data:

     {:id \"b-3f9a\" :kind \"checklist\" :title \"…\"
      :source {:type :tool :name \"ui_block\"}  ; or {:type :ext :id …}
      :call-id \"toolu_…\" :anchor 12            ; where the chat shows it
      :spec {…} :state {…}                      ; payload, interaction state
      :actions [{:action :target :value :user :at} …] :unread 0
      :at ms :updated-at ms}"
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [xi.blocks.hiccup :as bh]))

;; ── Limits ───────────────────────────────────────────────────────────────────

(def max-spec-chars
  "Largest spec a block may hold, as printed EDN."
  32768)

(def max-blocks
  "Most blocks one chat keeps."
  64)

(def max-table-cells 10000)
(def max-items 500)
(def max-series 12)
(def max-points 500)
(def max-value-chars 4000)

(def max-actions
  "Actions a block remembers (the newest)."
  50)

;; ── Coercion helpers ─────────────────────────────────────────────────────────

(defn- invalid! [msg]
  (throw (ex-info msg {::invalid true})))

(defn- scalar? [x]
  (or (nil? x) (string? x) (number? x) (boolean? x) (keyword? x)))

(defn ->str
  "Display string of a scalar cell; nil → \"\"."
  [x]
  (cond
    (nil? x)     ""
    (keyword? x) (name x)
    (string? x)  x
    :else        (str x)))

(defn- text-or-nil [x]
  (when (scalar? x) (some-> x ->str str/trim not-empty)))

(defn- parse-json-string
  "Models sometimes send `data` as a JSON string; read it back to data."
  [s]
  #?(:cljs (try (js->clj (js/JSON.parse s) :keywordize-keys true)
                (catch :default _ (invalid! "data is a string that is not valid JSON")))
     :clj  (invalid! (str "data must be an object, got a string: " (subs s 0 (min 40 (count s)))))))

(defn- coerce-data [data]
  (walk/keywordize-keys (if (string? data) (parse-json-string data) data)))

(defn- as-vec [x what]
  (cond
    (nil? x)        []
    (sequential? x) (vec x)
    :else           (invalid! (str what " must be an array"))))

(defn- capped [xs n what]
  (when (> (count xs) n)
    (invalid! (str "too many " what ": " (count xs) " (at most " n ")")))
  xs)

(defn- item-id
  "An item's id: its own (as a string) or its 1-based position."
  [i item]
  (or (when (map? item) (text-or-nil (:id item)))
      (str (inc i))))

(defn- unique-ids! [items what]
  (let [ids (map :id items)]
    (when (not= (count ids) (count (distinct ids)))
      (invalid! (str what " ids must be unique"))))
  items)

(defn- label-of [item]
  (if (map? item)
    (or (text-or-nil (:label item)) (text-or-nil (:title item))
        (text-or-nil (:text item)) (text-or-nil (:name item)) "")
    (->str item)))

(defn- one-of [x allowed default what]
  (let [v (some-> (text-or-nil x) str/lower-case)]
    (cond
      (nil? v)          default
      (contains? allowed v) v
      :else (invalid! (str what " must be one of " (str/join ", " (sort allowed))
                           ", got " (pr-str x))))))

(defn- number-or [x default what]
  (cond
    (nil? x)    default
    (number? x) x
    (and (string? x) (re-matches #"\s*-?\d+(\.\d+)?\s*" x))
    #?(:cljs (js/parseFloat x) :clj (Double/parseDouble (str/trim x)))
    :else (invalid! (str what " must be a number, got " (pr-str x)))))

(defn- round [x]
  #?(:cljs (js/Math.round x) :clj (Math/round (double x))))

(defn- truthy? [x]
  (boolean (if (string? x) (#{"true" "yes" "1" "x" "done"} (str/lower-case x)) x)))

;; ── Kinds ────────────────────────────────────────────────────────────────────
;; Each kind: :describe (the tool catalog line), :normalize (data → spec, throws
;; via invalid!), :text (spec state → body text), and optionally :init-state,
;; :carry (old-spec old-state new-spec → state for an updated spec) and :act
;; (block action → block', see `act`).

(defn- norm-checklist [{:keys [items]}]
  (let [items (-> (as-vec items "items") (capped max-items "items"))]
    {:items (unique-ids!
             (vec (map-indexed (fn [i it]
                                 (cond-> {:id (item-id i it) :label (label-of it)
                                          :done (boolean (and (map? it) (truthy? (:done it))))}
                                   (and (map? it) (text-or-nil (:note it)))
                                   (assoc :note (text-or-nil (:note it)))))
                               items))
             "item")}))

(defn checked?
  "Is checklist item `item` checked: the user's toggle, else the spec's :done."
  [state item]
  (get-in state [:checked (:id item)] (:done item)))

(defn- carry-checklist
  "Keep the user's toggles for items still there whose :done the agent left
   alone; an item the agent re-marked takes the agent's value."
  [old-spec old-state new-spec]
  (let [old-done (into {} (map (juxt :id :done)) (:items old-spec))
        keep?    (fn [{:keys [id done]}]
                   (and (contains? (:checked old-state) id)
                        (= done (get old-done id))))]
    {:checked (into {} (keep (fn [it] (when (keep? it)
                                         [(:id it) (get-in old-state [:checked (:id it)])])))
                    (:items new-spec))}))

(defn- text-checklist [{:keys [items]} state]
  (str/join "\n" (for [it items]
                   (str "- [" (if (checked? state it) "x" " ") "] " (:label it)
                        (when-let [n (:note it)] (str " — " n))))))

(defn- act-checklist [{:keys [spec] :as block} {:keys [action target value]}]
  (when (and (= "toggle" action)
             (some #(= target (:id %)) (:items spec))
             (boolean? value))
    {:block (assoc-in block [:state :checked target] value)}))

;; table

(defn- norm-columns [columns rows]
  (let [cols (as-vec columns "columns")
        cols (if (and (empty? cols) (map? (first rows)))
               (mapv name (keys (first rows)))
               cols)]
    (mapv (fn [i c]
            (if (map? c)
              {:key   (or (some-> (:key c) ->str) (str i))
               :label (or (text-or-nil (:label c)) (some-> (:key c) ->str) "")
               :align (one-of (:align c) #{"left" "right" "center"} "left" "align")}
              {:key (->str c) :label (->str c) :align "left"}))
          (range) cols)))

(defn- norm-table [{:keys [columns rows] :as data}]
  (let [rows (as-vec (or rows (:data data)) "rows")
        cols (norm-columns columns rows)
        cells (mapv (fn [r]
                      (cond
                        (map? r)        (mapv (fn [{:keys [key]}]
                                                (->str (get r (keyword key) (get r key))))
                                              cols)
                        (sequential? r) (mapv ->str r)
                        :else           (invalid! "each row must be an array or an object")))
                    rows)
        width (max (count cols) (reduce max 0 (map count cells)))]
    (when (> (* width (max 1 (count cells))) max-table-cells)
      (invalid! (str "table too large: " (* width (count cells)) " cells (at most "
                     max-table-cells ")")))
    {:columns (into cols (for [i (range (count cols) width)]
                           {:key (str i) :label "" :align "left"}))
     :rows    (mapv #(into % (repeat (- width (count %)) "")) cells)}))

(defn- pad [s w align]
  (let [n (- w (count s))]
    (if (pos? n)
      (case align
        "right"  (str (apply str (repeat n " ")) s)
        "center" (let [l (quot n 2)]
                   (str (apply str (repeat l " ")) s (apply str (repeat (- n l) " "))))
        (str s (apply str (repeat n " "))))
      s)))

(defn aligned-rows
  "Rows of cells (strings) as space-aligned lines, no box drawing.
   `aligns` is one of left/right/center per column (default left)."
  ([rows] (aligned-rows rows nil))
  ([rows aligns]
   (let [one-line (fn [s] (str/replace (->str s) #"\s*\n\s*" " "))
         rows     (mapv #(mapv one-line %) rows)
         widths   (reduce (fn [ws r]
                            (mapv #(max (get ws % 0) (count (get r % "")))
                                  (range (max (count ws) (count r)))))
                          [] rows)]
     (mapv (fn [r]
             (str/trimr (str/join "  " (map-indexed (fn [i w] (pad (get r i "") w (get aligns i "left")))
                                                    widths))))
           rows))))

(defn- text-table [{:keys [columns rows]} _]
  (let [aligns (mapv :align columns)
        head?  (some (comp seq :label) columns)
        lines  (aligned-rows (cond->> rows head? (cons (mapv :label columns))) aligns)]
    (str/join "\n" (if head?
                     (let [[h & body] lines]
                       (concat [h (apply str (repeat (count h) "-"))] body))
                     lines))))

;; keyvalue

(defn- norm-keyvalue [data]
  (let [pairs (or (:pairs data) (:items data))
        pairs (cond
                (map? pairs) (map (fn [[k v]] {:key (->str k) :value v}) pairs)
                (nil? pairs) (map (fn [[k v]] {:key (->str k) :value v}) (dissoc data :pairs :items))
                :else (as-vec pairs "pairs"))]
    {:pairs (-> (mapv (fn [p]
                        (cond
                          (map? p)        {:key (->str (or (:key p) (:label p) (:name p)))
                                           :value (->str (:value p))}
                          (sequential? p) {:key (->str (first p)) :value (->str (second p))}
                          :else           (invalid! "each pair must be {key, value} or [key, value]")))
                      pairs)
                (capped max-items "pairs"))}))

(defn- text-keyvalue [{:keys [pairs]} _]
  (str/join "\n" (aligned-rows (mapv (juxt :key :value) pairs))))

;; steps

(def ^:private step-statuses #{"todo" "running" "done" "failed" "skipped"})

(defn- norm-steps [{:keys [items steps]}]
  (let [items (-> (as-vec (or items steps) "items") (capped max-items "items"))]
    {:items (unique-ids!
             (vec (map-indexed (fn [i it]
                                 (cond-> {:id (item-id i it) :label (label-of it)
                                          :status (if (map? it)
                                                    (one-of (:status it) step-statuses "todo" "status")
                                                    "todo")}
                                   (and (map? it) (text-or-nil (:note it)))
                                   (assoc :note (text-or-nil (:note it)))))
                               items))
             "step")}))

(def step-marks {"todo" "○" "running" "◐" "done" "✓" "failed" "✗" "skipped" "–"})

(defn- text-steps [{:keys [items]} _]
  (str/join "\n" (for [it items]
                   (str (step-marks (:status it)) " " (:label it)
                        (when-let [n (:note it)] (str " — " n))))))

;; progress

(defn- norm-progress [{:keys [value max label note]}]
  (let [mx (number-or max 100 "max")
        v  (number-or value 0 "value")]
    (when-not (pos? mx) (invalid! "max must be positive"))
    (cond-> {:value (min mx (clojure.core/max 0 v)) :max mx}
      (text-or-nil label) (assoc :label (text-or-nil label))
      (text-or-nil note)  (assoc :note (text-or-nil note)))))

(defn percent [{:keys [value max]}]
  (round (* 100 (/ value max))))

(defn- bar [fraction width]
  (let [n (round (* fraction width))]
    (str (apply str (repeat n "█")) (apply str (repeat (- width n) "░")))))

(defn- text-progress [{:keys [value max label note] :as spec} _]
  (str (when label (str label "  "))
       (bar (/ value max) 20) " " (percent spec) "%"
       (when note (str "\n" note))))

;; callout

(def tones #{"info" "warn" "success" "error"})

(defn- norm-callout [{:keys [tone body text markdown]}]
  {:tone (one-of tone tones "info" "tone")
   :body (or (text-or-nil body) (text-or-nil text) (text-or-nil markdown)
             (invalid! "callout needs a body"))})

(defn- text-callout [{:keys [tone body]} _]
  (str "[" tone "] " body))

;; chart

(def chart-types #{"bar" "line" "sparkline"})

(defn- norm-series [{:keys [series values]}]
  (let [series (cond
                 (seq series) (as-vec series "series")
                 (some? values) [{:name "" :values values}]
                 :else (invalid! "chart needs series or values"))]
    (capped series max-series "series")
    (mapv (fn [i s]
            (let [s    (if (sequential? s) {:values s} s)
                  vals (-> (as-vec (:values s) "series values") (capped max-points "points"))]
              {:name   (or (text-or-nil (:name s)) (text-or-nil (:label s))
                           (if (= 1 (count series)) "" (str "Series " (inc i))))
               :values (mapv #(number-or % 0 "a value") vals)}))
          (range) series)))

(defn- norm-chart [{:keys [type labels] :as data}]
  (let [series (norm-series data)
        n      (reduce max 0 (map (comp count :values) series))
        labels (-> (as-vec labels "labels") (capped max-points "labels"))]
    {:type   (one-of type chart-types "bar" "type")
     :labels (mapv ->str (concat labels (map #(str (inc %)) (range (count labels) n))))
     :series series}))

(def ^:private spark-chars "▁▂▃▄▅▆▇█")

(defn sparkline
  "Unicode sparkline of `values`."
  [values]
  (if (empty? values)
    ""
    (let [lo (reduce min values) hi (reduce max values) span (- hi lo)]
      (apply str (for [v values]
                   (nth spark-chars (if (zero? span) 3 (round (* 7 (/ (- v lo) span))))))))))

(defn- fmt-num [n]
  (if (== n (round n)) (str (round n)) (str (/ (round (* n 100)) 100))))

(defn- text-chart [{:keys [type labels series]} _]
  (if (= "bar" type)
    (let [hi   (reduce max 1 (mapcat :values series))
          rows (for [s series
                     [l v] (map vector labels (:values s))]
                 [(if (> (count series) 1) (str (:name s) " · " l) l)
                  (str (apply str (repeat (round (* 24 (/ (max 0 v) hi))) "█")) " " (fmt-num v))])]
      (str/join "\n" (aligned-rows (vec rows))))
    (str/join "\n" (aligned-rows (mapv (fn [s] [(:name s) (sparkline (:values s))
                                                (str (fmt-num (reduce min (:values s))) "–"
                                                     (fmt-num (reduce max (:values s))))])
                                       series)))))

;; choice

(defn- norm-choice [{:keys [options multi submit]}]
  (let [options (-> (as-vec options "options") (capped max-items "options"))]
    (when (empty? options) (invalid! "choice needs options"))
    {:options (unique-ids!
               (vec (map-indexed (fn [i o]
                                   (cond-> {:id (item-id i o) :label (label-of o)}
                                     (and (map? o) (text-or-nil (:description o)))
                                     (assoc :description (text-or-nil (:description o)))))
                                 options))
               "option")
     :multi  (truthy? multi)
     :submit (or (text-or-nil submit) "Send")}))

(defn- carry-choice [_ old-state new-spec]
  (let [ids (set (map :id (:options new-spec)))]
    (cond-> {:selected (filterv ids (:selected old-state))}
      (:submitted? old-state) (assoc :submitted? true))))

(defn- text-choice [{:keys [options multi]} state]
  (let [sel (set (:selected state))]
    (str/join "\n" (for [o options]
                     (str (if multi
                            (str "[" (if (sel (:id o)) "x" " ") "] ")
                            (str "(" (if (sel (:id o)) "x" " ") ") "))
                          (:label o)
                          (when-let [d (:description o)] (str " — " d)))))))

(defn- act-choice [{:keys [spec state] :as block} {:keys [action value]}]
  (let [ids (set (map :id (:options spec)))]
    (case action
      "select"
      (when (and (sequential? value) (every? ids value)
                 (or (:multi spec) (<= (count value) 1)))
        {:block (assoc-in block [:state :selected] (vec (distinct value)))})

      "submit"
      (when (seq (:selected state))
        {:block (assoc-in block [:state :submitted?] true) :send? true})
      nil)))

;; form

(def field-types #{"text" "textarea" "select" "checkbox" "number"})

(defn- norm-field [i f]
  (let [f    (if (map? f) f {:name (->str f)})
        nm   (or (text-or-nil (:name f)) (text-or-nil (:id f)) (str "field" (inc i)))
        type (one-of (:type f) field-types "text" "field type")
        opts (when (= "select" type)
               (let [os (as-vec (:options f) "select options")]
                 (when (empty? os) (invalid! (str "select field " nm " needs options")))
                 (mapv (fn [j o] {:id (item-id j o) :label (label-of o)}) (range) os)))]
    (cond-> {:name nm :type type :label (or (text-or-nil (:label f)) nm)}
      opts                           (assoc :options opts)
      (truthy? (:required f))        (assoc :required true)
      (text-or-nil (:placeholder f)) (assoc :placeholder (text-or-nil (:placeholder f)))
      (text-or-nil (:hint f))        (assoc :hint (text-or-nil (:hint f)))
      (some? (:default f))           (assoc :default (if (= "checkbox" type)
                                                       (truthy? (:default f))
                                                       (->str (:default f)))))))

(defn- norm-form [{:keys [fields submit]}]
  (let [fields (-> (as-vec fields "fields") (capped 50 "fields"))]
    (when (empty? fields) (invalid! "form needs fields"))
    (let [fields (vec (map-indexed norm-field fields))]
      (when (not= (count fields) (count (distinct (map :name fields))))
        (invalid! "field names must be unique"))
      {:fields fields :submit (or (text-or-nil submit) "Submit")})))

(defn field-value
  "The current value of form field `f`: the user's, else its default."
  [state f]
  (get-in state [:values (:name f)]
          (if (= "checkbox" (:type f)) (boolean (:default f)) (:default f ""))))

(defn- carry-form [_ old-state new-spec]
  (let [names (set (map :name (:fields new-spec)))]
    (cond-> {:values (into {} (filter (comp names key)) (:values old-state))}
      (:submitted? old-state) (assoc :submitted? true))))

(defn- text-form [{:keys [fields]} state]
  (str/join "\n" (aligned-rows (mapv (fn [f]
                                       (let [v (field-value state f)]
                                         [(str (:label f) ":")
                                          (cond
                                            (= "checkbox" (:type f)) (if v "[x]" "[ ]")
                                            (= "select" (:type f))
                                            (or (some #(when (= v (:id %)) (:label %)) (:options f)) (->str v))
                                            :else (->str v))]))
                                     fields))))

(defn- field-value-ok? [f v]
  (case (:type f)
    "checkbox" (boolean? v)
    "select"   (or (= "" v) (some #(= v (:id %)) (:options f)))
    (and (string? v) (<= (count v) max-value-chars))))

(defn missing-fields
  "Required fields of `spec` without a value in `state`."
  [spec state]
  (filterv #(and (:required %)
                 (let [v (field-value state %)]
                   (or (nil? v) (= "" v) (false? v))))
           (:fields spec)))

(defn- act-form [{:keys [spec] :as block} {:keys [action target value]}]
  (let [by-name (into {} (map (juxt :name identity)) (:fields spec))]
    (case action
      "set"
      (when-let [f (by-name target)]
        (when (field-value-ok? f value)
          {:block (assoc-in block [:state :values target] value)}))

      "submit"
      (let [values (if (map? value) (update-keys value ->str) {})]
        (when (every? (fn [[k v]] (and (by-name k) (field-value-ok? (by-name k) v))) values)
          (let [block (update-in block [:state :values] merge values)]
            (when (empty? (missing-fields spec (:state block)))
              {:block (assoc-in block [:state :submitted?] true) :send? true}))))
      nil)))

;; cards

(defn- norm-cards [{:keys [cards items]}]
  (let [cards (-> (as-vec (or cards items) "cards") (capped 100 "cards"))]
    {:cards (unique-ids!
             (vec (map-indexed
                   (fn [i c]
                     (let [c (if (map? c) c {:title (->str c)})]
                       (cond-> {:id (item-id i c) :title (label-of c)}
                         (text-or-nil (:body c)) (assoc :body (text-or-nil (:body c)))
                         (text-or-nil (:meta c)) (assoc :meta (text-or-nil (:meta c)))
                         (seq (:actions c))
                         (assoc :actions
                                (unique-ids!
                                 (vec (map-indexed (fn [j a]
                                                     {:id (item-id j a) :label (label-of a)
                                                      :send (boolean (and (map? a) (truthy? (:send a))))})
                                                   (capped (as-vec (:actions c) "card actions") 6 "card actions")))
                                 "card action")))))
                   cards))
             "card")}))

(defn- text-cards [{:keys [cards]} state]
  (str/join "\n\n" (for [c cards]
                     (str "### " (:title c)
                          (when-let [m (:meta c)] (str "\n" m))
                          (when-let [b (:body c)] (str "\n" b))
                          (when (seq (:actions c))
                            (str "\n" (str/join " " (for [a (:actions c)]
                                                      (str "[" (:label a)
                                                           (when (= (:id a) (get-in state [:picked (:id c)])) " ✓")
                                                           "]")))))))))

(defn- carry-cards [_ old-state new-spec]
  (let [ids (set (map :id (:cards new-spec)))]
    {:picked (into {} (filter (comp ids key)) (:picked old-state))}))

(defn- act-cards [{:keys [spec] :as block} {:keys [action target value]}]
  (when (= "card" action)
    (when-let [c (some #(when (= target (:id %)) %) (:cards spec))]
      (when-let [a (some #(when (= value (:id %)) %) (:actions c))]
        {:block (assoc-in block [:state :picked target] value) :send? (:send a)}))))

;; hiccup (xi.blocks.hiccup has the allowlist)

(defn- norm-hiccup [data]
  (let [{:keys [tree dropped]} (bh/normalize (if (map? data) (or (:hiccup data) (:tree data)) data))]
    (when (nil? tree) (invalid! (str "hiccup has nothing left to render"
                                     (when (seq dropped) (str " (dropped: " (str/join ", " dropped) ")")))))
    (cond-> {:tree tree}
      (seq dropped) (assoc :dropped dropped))))

(defn- act-hiccup [{:keys [spec] :as block} {:keys [action value]}]
  (when (contains? (bh/actions (:tree spec)) action)
    (when (or (scalar? value) (and (map? value) (every? scalar? (vals value))))
      {:block (assoc-in block [:state :last] {:action action :value value})
       :send? (contains? (bh/send-actions (:tree spec)) action)})))

(def kinds
  "kind name → {:describe :normalize :text …}. The catalog the `ui_block`
   tool lists, in this order."
  (array-map
   "checklist" {:describe "{items: [{id?, label, done?, note?}]} — a list the user can tick off; read the ticks with ui_state"
                :normalize norm-checklist :text text-checklist
                :carry carry-checklist :act act-checklist}
   "table"     {:describe "{columns: [string | {key, label, align?}], rows: [[cell…] | {key: cell}]} — sortable table"
                :normalize norm-table :text text-table}
   "keyvalue"  {:describe "{pairs: [{key, value}]} or an object — a key/value card"
                :normalize norm-keyvalue :text text-keyvalue}
   "steps"     {:describe "{items: [{id?, label, status: todo|running|done|failed|skipped, note?}]} — progress through steps; update it with ui_update"
                :normalize norm-steps :text text-steps}
   "progress"  {:describe "{value, max? (100), label?, note?} — a progress bar"
                :normalize norm-progress :text text-progress}
   "callout"   {:describe "{tone: info|warn|success|error, body: markdown} — a highlighted note"
                :normalize norm-callout :text text-callout}
   "chart"     {:describe "{type: bar|line|sparkline, labels?: [string], series: [{name, values: [number]}]} or {values: [number]} — a small chart"
                :normalize norm-chart :text text-chart}
   "choice"    {:describe "{options: [{id?, label, description?}], multi?, submit?: button label} — the user picks and presses Send, which posts the choice to you as a message"
                :normalize norm-choice :text text-choice
                :init-state (fn [_] {:selected []}) :carry carry-choice :act act-choice}
   "form"      {:describe "{fields: [{name, label?, type: text|textarea|select|checkbox|number, options?, required?, placeholder?, default?}], submit?} — the user fills it in and submits, which posts the values to you as a message"
                :normalize norm-form :text text-form
                :carry carry-form :act act-form}
   "cards"     {:describe "{cards: [{id?, title, body?: markdown, meta?, actions?: [{id?, label, send?}]}]} — cards with buttons; an action with send: true posts the click to you as a message"
                :normalize norm-cards :text text-cards
                :carry carry-cards :act act-cards}
   "hiccup"    {:describe "pass `hiccup` instead of data: a tree of JSON arrays like [\"div\", {\"layout\": \"row\"}, [\"strong\", \"Hi\"]]; see the tool description for the allowed tags"
                :normalize norm-hiccup :text (fn [spec _] (bh/text (:tree spec)))
                :act act-hiccup}))

(defn kind-names [] (vec (keys kinds)))

(defn interactive?
  "Does a block of `kind` take user actions?"
  [kind]
  (some? (get-in kinds [kind :act])))

;; ── Specs and records ────────────────────────────────────────────────────────

(defn normalize
  "Validate and normalize a model's `data` for `kind`: {:spec spec} or
   {:error message} (worded for the model, so it can fix the call)."
  [kind data]
  (if-let [{:keys [normalize]} (get kinds kind)]
    (try
      (let [data (if (= "hiccup" kind) data (coerce-data data))]
        (when-not (or (map? data) (= "hiccup" kind))
          (invalid! "data must be an object"))
        (let [spec (normalize data)
              size (count (pr-str spec))]
          (if (> size max-spec-chars)
            {:error (str "block too large: " size " characters (at most " max-spec-chars ")")}
            {:spec spec})))
      (catch #?(:cljs :default :clj Exception) e
        (if (::invalid (ex-data e))
          {:error (ex-message e)}
          {:error (str "invalid " kind " data: " (ex-message e))})))
    {:error (str "unknown kind " (pr-str kind) "; one of " (str/join ", " (kind-names)))}))

(defn initial-state [kind spec]
  (if-let [f (get-in kinds [kind :init-state])] (f spec) {}))

(defn carry-state
  "Interaction state for `new-spec` replacing `old-spec`: what still applies."
  [kind old-spec old-state new-spec]
  (if-let [f (get-in kinds [kind :carry])]
    (f old-spec old-state new-spec)
    (initial-state kind new-spec)))

(defn safe-id
  "A model-supplied block id made safe, or nil."
  [s]
  (some-> s ->str str/trim str/lower-case
          (str/replace #"[^a-z0-9_-]+" "-")
          (str/replace #"^-+|-+$" "")
          not-empty
          (#(subs % 0 (min 40 (count %))))))

(defn make-record
  "A new block record, or {:error …}. `opts`: :id (already safe) :kind :title
   :data :source :call-id :anchor :at."
  [{:keys [id kind title data source call-id anchor at]}]
  (let [{:keys [spec error]} (normalize kind data)]
    (if error
      {:error error}
      {:record (cond-> {:id id :kind kind :spec spec
                        :state (initial-state kind spec)
                        :source source :actions [] :unread 0
                        :at at :updated-at at}
                 (text-or-nil title) (assoc :title (text-or-nil title))
                 call-id             (assoc :call-id call-id)
                 (number? anchor)    (assoc :anchor anchor))})))

(defn update-record
  "`record` with a new spec from `data` and/or a new `title`, or {:error …}."
  [record {:keys [data title at]}]
  (let [kind (:kind record)
        {:keys [spec error]} (if (some? data) (normalize kind data) {:spec (:spec record)})]
    (if error
      {:error error}
      {:record (cond-> (assoc record :spec spec :updated-at at
                              :state (if (some? data)
                                       (carry-state kind (:spec record) (:state record) spec)
                                       (:state record)))
                 (text-or-nil title) (assoc :title (text-or-nil title)))})))

;; ── Actions ──────────────────────────────────────────────────────────────────

(defn act
  "Apply a user's block action `{:action :target :value}` (by `user` at `at`)
   to `record`: {:record r :send? bool} or nil when the kind doesn't take it.
   A block action only ever changes the block itself."
  [record {:keys [action target value]} user at]
  (when-let [f (get-in kinds [(:kind record) :act])]
    (let [action (some-> action ->str)
          target (some-> target ->str)]
      (when-let [{:keys [block send?]} (f record {:action action :target target :value value})]
        {:record (-> block
                     (update :actions (fn [as] (vec (take-last max-actions
                                                               (conj (vec as) (cond-> {:action action :at at}
                                                                                target      (assoc :target target)
                                                                                (some? value) (assoc :value value)
                                                                                user        (assoc :user user)))))))
                     (update :unread (fnil inc 0))
                     (assoc :updated-at at))
         :send?  (boolean send?)}))))

;; ── Text ─────────────────────────────────────────────────────────────────────

(defn body-text
  "A block's body as plain text."
  [{:keys [kind spec state]}]
  (if-let [f (get-in kinds [kind :text])]
    (f spec state)
    ""))

(defn text
  "A block as plain text: a header line, then its body."
  [{:keys [id kind title] :as record}]
  (str "▣ " (or title kind) " (" kind " " id ")\n" (body-text record)))

(defn- value-text [v]
  (cond
    (map? v)        (str/join ", " (map (fn [[k x]] (str (->str k) "=" (value-text x))) v))
    (sequential? v) (str/join ", " (map value-text v))
    :else           (pr-str v)))

(defn- action-line [{:keys [action target value user]}]
  (str "- " (or user "someone") ": " action
       (when target (str " " target))
       (when (some? value) (str " → " (value-text value)))))

(defn state-text
  "What `ui_state` tells the agent: the block's current text form plus the
   actions it hasn't read yet (the last `unread` of :actions)."
  [{:keys [unread actions] :as record}]
  (let [fresh (take-last (or unread 0) actions)]
    (str (text record)
         (when-let [st (not-empty (dissoc (:state record) :checked))]
           (str "\n\nstate: " (value-text st)))
         "\n\n"
         (if (seq fresh)
           (str (count fresh) " new action" (when (not= 1 (count fresh)) "s") ":\n"
                (str/join "\n" (map action-line fresh)))
           "No new actions."))))

(defn action-message
  "The chat message a submit / send action posts for the user: the block's
   current state, tagged so the agent knows where it came from."
  [{:keys [id kind] :as record} action]
  (str "<block-action id=\"" id "\" kind=\"" kind "\" action=\"" action "\">\n"
       (body-text record)
       (when-let [last-action (last (:actions record))]
         (when (= "card" (:action last-action))
           (str "\npicked: " (:target last-action) " → " (value-text (:value last-action)))))
       "\n</block-action>"))

(defn catalog
  "The kind catalog as tool-description lines; `extra` adds kinds from
   extensions ({name describe})."
  ([] (catalog nil))
  ([extra]
   (str/join "\n" (concat (for [[k {:keys [describe]}] kinds]
                            (str "- " k ": " describe))
                          (for [[k d] extra] (str "- " k ": " d))))))

;; ── Room slice ───────────────────────────────────────────────────────────────

(def ext-id :blocks)

(defn room-blocks
  "The blocks of `room` (the room map), oldest first."
  [room]
  (sort-by (juxt :at :id) (vals (get-in room [:ext ext-id :blocks]))))
