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

(def max-items 500)

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

(defn- norm-app
  "An MCP Apps view (tier 3): built by the host from a tool with a UI, never
   by the model. The HTML stays on the server (xi.ext.blocks); a structured
   result too large to keep is dropped from the spec."
  [{:keys [resource-uri tool-input tool-result csp prefers-border tool] :as data}]
  (when-not (and (string? resource-uri) (str/starts-with? resource-uri "ui://"))
    (invalid! "an app needs a ui:// resource-uri"))
  (let [spec (cond-> {:resource-uri resource-uri
                      :tool-input   (if (map? tool-input) tool-input {})
                      :tool-result  (if (map? tool-result) tool-result {})}
               (map? csp)             (assoc :csp csp)
               (boolean? prefers-border) (assoc :prefers-border prefers-border)
               (map? tool)            (assoc :tool tool)
               (string? (:title data)) (assoc :title (:title data)))]
    (if (> (count (pr-str spec)) max-spec-chars)
      (update spec :tool-result #(-> % (dissoc :structured-content) (assoc :truncated true)))
      spec)))

(defn- text-app [{:keys [resource-uri tool-result]} _]
  (str "App view " resource-uri
       (when-let [t (->> (:content tool-result) (keep :text) (str/join "\n") not-empty)]
         (str "\n" t))))

(def kinds
  "kind name → {:describe :normalize :text …}. The catalog the `ui_block`
   tool lists, in this order; `:internal?` kinds are built by the host only."
  (array-map
   "checklist" {:describe "{items: [{id?, label, done?, note?}]} — a list the user can tick off; read the ticks with ui_state"
                :normalize norm-checklist :text text-checklist
                :carry carry-checklist :act act-checklist}
   "choice"    {:describe "{options: [{id?, label, description?}], multi?, submit?: button label} — the user picks and presses Send, which posts the choice to you as a message"
                :normalize norm-choice :text text-choice
                :init-state (fn [_] {:selected []}) :carry carry-choice :act act-choice}
   "hiccup"    {:describe "pass `hiccup` instead of data: a tree of JSON arrays like [\"div\", {\"layout\": \"row\"}, [\"strong\", \"Hi\"]]; see the tool description for the allowed tags"
                :normalize norm-hiccup :text (fn [spec _] (bh/text (:tree spec)))
                :act act-hiccup}
   "app"       {:internal? true :normalize norm-app :text text-app}))

(defn kind-names
  "The built-in kind names, then `extra`'s (extension kinds)."
  ([] (kind-names nil))
  ([extra] (into (vec (keep (fn [[k d]] (when-not (:internal? d) k)) kinds)) (keys extra))))

(defn interactive?
  "Does `record` take user actions?"
  [{:keys [kind rules]}]
  (boolean (or (get-in kinds [kind :act]) (seq (:actions rules)) (seq (:send rules)))))

;; ── Extension kinds ──────────────────────────────────────────────────────────
;; A user extension declares kinds (`:block-kinds`, namespaced <ext-id>/<name>
;; by xi.ext.user.guard): {:describe str :actions [str] :send [str]
;; :normalize (fn [data] → spec) :text (fn [spec state] → str)}. Its record
;; carries what it accepts as :rules and its text as :text-form, so reducers
;; and clients, which don't have the extension's fns, need no registry.

(defn- plain-data? [x]
  (letfn [(ok? [x depth]
            (and (< depth 30)
                 (cond
                   (scalar? x) true
                   (map? x)    (every? (fn [[k v]] (and (scalar? k) (ok? v (inc depth)))) x)
                   (or (sequential? x) (set? x)) (every? #(ok? % (inc depth)) x)
                   :else false)))]
    (ok? x 0)))

(defn- ext-normalize [{f :normalize} data]
  (let [spec (if f (f data) data)]
    (when-not (plain-data? spec)
      (invalid! "the data must be plain data: maps, vectors, strings, numbers, booleans"))
    spec))

(defn- ext-rules [{:keys [actions send]}]
  (let [r (cond-> {}
            (seq actions) (assoc :actions (vec (distinct (map ->str actions))))
            (seq send)    (assoc :send (vec (distinct (map ->str send)))))]
    (not-empty r)))

(defn- act-ext [{:keys [rules] :as block} {:keys [action target value]}]
  (let [send (set (:send rules))]
    (when (and (contains? (into send (:actions rules)) action)
               (or (scalar? value) (and (map? value) (every? scalar? (vals value)))))
      {:block (assoc-in block [:state :last] (cond-> {:action action}
                                               target        (assoc :target target)
                                               (some? value) (assoc :value value)))
       :send? (contains? send action)})))

;; ── Specs and records ────────────────────────────────────────────────────────

(defn normalize
  "Validate and normalize a model's `data` for `kind`: {:spec spec} or
   {:error message} (worded for the model, so it can fix the call)."
  ([kind data] (normalize kind data nil))
  ([kind data extra]
  (if-let [normalize (or (get-in kinds [kind :normalize])
                         (when-let [d (get extra kind)] (partial ext-normalize d)))]
    (try
      (let [data (if (= "hiccup" kind) data (coerce-data data))]
        (when-not (or (map? data) (= "hiccup" kind) (contains? extra kind))
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
    {:error (str "unknown kind " (pr-str kind) "; one of " (str/join ", " (kind-names extra)))})))

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

(defn- with-ext-form
  "An extension kind's record gets its :rules and current :text-form."
  [record extra]
  (if-let [d (get extra (:kind record))]
    (let [t (when-let [f (:text d)] (f (:spec record) (:state record)))]
      (cond-> (dissoc record :text-form)
        (ext-rules d) (assoc :rules (ext-rules d))
        (string? t)   (assoc :text-form t)))
    record))

(defn make-record
  "A new block record, or {:error …}. `opts`: :id (already safe) :kind :title
   :data :source :call-id :anchor :at, and :extra (extension kinds)."
  [{:keys [id kind title data source call-id anchor at extra]}]
  (let [{:keys [spec error]} (normalize kind data extra)]
    (if error
      {:error error}
      {:record (-> (cond-> {:id id :kind kind :spec spec
                            :state (initial-state kind spec)
                            :source source :actions [] :unread 0
                            :at at :updated-at at}
                     (text-or-nil title) (assoc :title (text-or-nil title))
                     call-id             (assoc :call-id call-id)
                     (number? anchor)    (assoc :anchor anchor))
                   (with-ext-form extra))})))

(defn update-record
  "`record` with a new spec from `data` and/or a new `title`, or {:error …}."
  [record {:keys [data title at extra]}]
  (let [kind (:kind record)
        {:keys [spec error]} (if (some? data) (normalize kind data extra) {:spec (:spec record)})]
    (if error
      {:error error}
      {:record (-> (cond-> (assoc record :spec spec :updated-at at
                                  :state (if (some? data)
                                           (carry-state kind (:spec record) (:state record) spec)
                                           (:state record)))
                     (text-or-nil title) (assoc :title (text-or-nil title)))
                   (with-ext-form extra))})))

;; ── Actions ──────────────────────────────────────────────────────────────────

(defn act
  "Apply a user's block action `{:action :target :value}` (by `user` at `at`)
   to `record`: {:record r :send? bool} or nil when the kind doesn't take it.
   A block action only ever changes the block itself."
  [record {:keys [action target value]} user at]
  (when-let [f (or (get-in kinds [(:kind record) :act]) (when (:rules record) act-ext))]
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

(defn note
  "`record` with an action the host recorded itself (an app view's tool
   call or context update, xi.ext.blocks), not a user's control: logged and
   unread like one. A \"context\" note keeps its value as the view's
   :model-context."
  [record {:keys [action target value]} at]
  (-> (cond-> record
        (= "context" action) (assoc-in [:state :model-context] value))
      (update :actions (fn [as] (vec (take-last max-actions
                                                (conj (vec as) (cond-> {:action action :at at :user "app"}
                                                                 target        (assoc :target target)
                                                                 (some? value) (assoc :value value)))))))
      (update :unread (fnil inc 0))
      (assoc :updated-at at)))

;; ── Text ─────────────────────────────────────────────────────────────────────

(defn body-text
  "A block's body as plain text."
  [{:keys [kind spec state text-form]}]
  (if-let [f (get-in kinds [kind :text])]
    (f spec state)
    (or text-form (let [s (pr-str spec)] (subs s 0 (min 2000 (count s)))))))

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
       "\n</block-action>"))

(defn parse-action-message
  "The parts of a message `action-message` wrote: {:id :kind :action :body},
   or nil for any other text. The web draws such a message as a card."
  [text]
  (when (string? text)
    (when-let [[_ id kind action body]
               (re-matches #"(?s)\s*<block-action id=\"([^\"]*)\" kind=\"([^\"]*)\" action=\"([^\"]*)\">\n?(.*?)\n?</block-action>\s*" text)]
      {:id id :kind kind :action action :body body})))

(defn catalog
  "The kind catalog as tool-description lines; `extra` adds kinds from
   extensions ({name describe})."
  ([] (catalog nil))
  ([extra]
   (str/join "\n" (concat (for [[k {:keys [describe internal?]}] kinds :when (not internal?)]
                            (str "- " k ": " describe))
                          (for [[k d] extra] (str "- " k ": " (if (map? d) (:describe d) d)))))))

;; ── Room slice ───────────────────────────────────────────────────────────────

(def ext-id :blocks)

(defn room-blocks
  "The blocks of `room` (the room map), oldest first."
  [room]
  (sort-by (juxt :at :id) (vals (get-in room [:ext ext-id :blocks]))))
