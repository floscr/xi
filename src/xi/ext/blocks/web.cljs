(ns xi.ext.blocks.web
  "Agent blocks, browser half: draws each block of the room's mirrored
   `:blocks` slice as its own timeline post after the tool call that made it
   (`:timeline-posts`, xi.web.timeline-posts), inside a frame the block's
   content can't cover or restyle: a header naming it an agent block, its
   kind and source, and a ⋯ menu (view data, copy).

   Controls only ever produce block actions (`:ext.blocks/act`, forwarded to
   the server, which records them on the block). A form's Submit, a choice's
   Send and a control marked to send also post a message for the user
   (`:input/submit` with xi.blocks/action-message): user-initiated, never on
   mount or hover. Table sort, the raw-data view and unsent form values are
   this browser's own ([:web/blocks …])."
  (:require [clojure.string :as str]
            [ui.alert :as alert]
            [ui.badge :as badge]
            [ui.button :as button]
            [ui.card :as card]
            [ui.chip :as chip]
            [ui.form :as form]
            [ui.icon :as icon]
            [ui.popover :as popover]
            [ui.progress :as progress]
            [ui.table :as table]
            [xi.blocks :as b]
            [xi.blocks.hiccup :as bh]
            [xi.core.state :as state]
            [xi.ext.blocks.handlers :as h]
            [xi.markdown.hiccup :as md]
            [xi.web.block-kinds :as block-kinds]
            [xi.web.views :as views]))


;; ── Handlers ─────────────────────────────────────────────────────────────────

(defn- ui-path [block-id & ks] (into [:web/blocks block-id] ks))

(defn- on-act
  "A control's action: forward it; the server's echo updates every mirror."
  [st {:keys [remote?] :as ev}]
  (if remote?
    (h/act st ev)
    {:effects [[:ws/send ev]]}))

(defn- on-submit
  "Submit / send: record the action and post the message the block's new
   state makes, as the user. Clears this browser's unsent form values."
  [st {:keys [room-id block-id action target value]}]
  (when-let [record (h/find-block st room-id block-id)]
    (let [ev {:type :ext.blocks/act :room-id room-id :block-id block-id
              :action action :target target :value value}]
      (when-let [{r :record send? :send?} (b/act record ev (state/own-user st) (js/Date.now))]
        {:state   (update-in st [:web/blocks block-id] dissoc :draft)
         :effects (cond-> [[:ws/send ev]]
                    send? (conj [:ws/send {:type :input/submit :room-id room-id
                                           :text (b/action-message r action)}]))}))))

(defn- on-sort [st {:keys [block-id col]}]
  (let [{c :col dir :dir} (get-in st (ui-path block-id :sort))]
    {:state (assoc-in st (ui-path block-id :sort)
                      {:col col :dir (if (and (= c col) (= :asc dir)) :desc :asc)})}))

(defn- on-raw [st {:keys [block-id]}]
  {:state (update-in st (ui-path block-id :raw?) not)})

(defn- on-draft [st {:keys [block-id field value]}]
  {:state (assoc-in st (ui-path block-id :draft field) value)})

;; ── Small pieces ─────────────────────────────────────────────────────────────

(defn- markdown [s] (when (seq s) (md/render s)))

(defn- hide-popover! [id]
  (some-> (.getElementById js/document id) .hidePopover))

(defn- act! [dispatch! room-id block-id action & [target value]]
  (dispatch! (cond-> {:type :ext.blocks/act :room-id room-id :block-id block-id :action action}
               target      (assoc :target target)
               (some? value) (assoc :value value))))

(defn- submit! [dispatch! room-id block-id action & [target value]]
  (dispatch! (cond-> {:type :ext.blocks/submit :room-id room-id :block-id block-id :action action}
               target      (assoc :target target)
               (some? value) (assoc :value value))))

(def ^:private tone->variant
  {"info" :info "warn" :warning "success" :success "error" :danger})

;; ── Kinds ────────────────────────────────────────────────────────────────────

(defn- checklist-view [dispatch! room-id {:keys [id spec state]}]
  [:ul {:class ["ab-checklist"]}
   (for [it (:items spec)
         :let [on? (b/checked? state it)]]
     [:li {:replicant/key (:id it) :class (when on? "ab-done")}
      (form/form-checkbox
       {:label     (:label it)
        :checked   on?
        :on-change (fn [e] (act! dispatch! room-id id "toggle" (:id it) (.. e -target -checked)))})
      (when-let [n (:note it)] [:span {:class ["ab-note"]} n])])])

(defn- sort-rows [rows {:keys [col dir]}]
  (if (nil? col)
    rows
    (let [num   (fn [s] (let [n (js/parseFloat (str/replace s #"[,_\s]" ""))]
                          (when-not (js/isNaN n) n)))
          k     (fn [r] (let [s (get r col "")] (if-let [n (num s)] [0 n ""] [1 0 (str/lower-case s)])))
          rows  (sort-by k rows)]
      (if (= :desc dir) (reverse rows) rows))))

(defn- table-view [dispatch! {:keys [id spec]} ui]
  (let [{:keys [columns rows]} spec
        srt (:sort ui)]
    (table/table
     {:variant :striped
      :class   "ab-table"
      :headers (map-indexed
                (fn [i {:keys [label align]}]
                  [:button {:class ["ab-sort" (str "ab-cell-" align)]
                            :title "Sort"
                            :on {:click (fn [_] (dispatch! {:type :ext.blocks/sort :block-id id :col i}))}}
                   label
                   (when (= i (:col srt))
                     (icon/icon {:icon-name (if (= :asc (:dir srt)) :chevron-up :chevron-down) :size :sm}))])
                columns)
      :rows    (for [r (sort-rows rows srt)]
                 (map-indexed (fn [i c] [:span {:class [(str "ab-cell-" (:align (nth columns i)))]} c]) r))})))

(defn- keyvalue-view [{:keys [spec]}]
  [:dl {:class ["ab-kv"]}
   (for [[i {:keys [key value]}] (map-indexed vector (:pairs spec))]
     (list [:dt {:replicant/key (str "k" i)} key]
           [:dd {:replicant/key (str "v" i)} value]))])

(defn- steps-view [{:keys [spec]}]
  [:ol {:class ["ab-steps"]}
   (for [it (:items spec)]
     [:li {:replicant/key (:id it) :class [(str "ab-step-" (:status it))]}
      [:span {:class ["ab-step-mark"] :title (:status it)} (b/step-marks (:status it))]
      [:span {:class ["ab-step-label"]} (:label it)]
      (when-let [n (:note it)] [:span {:class ["ab-note"]} n])])])

(defn- progress-view [{:keys [spec]}]
  (let [pct (b/percent spec)]
    [:div {:class ["ab-progress"]}
     [:div {:class ["ab-progress-head"]}
      [:span (or (:label spec) "")]
      [:span {:class ["ab-progress-pct"]} (str pct "%")]]
     (progress/progress {:value pct :variant (when (>= pct 100) :success)})
     (when-let [n (:note spec)] [:div {:class ["ab-note"]} n])]))

(defn- callout-view [{:keys [spec]}]
  (alert/alert {:variant (tone->variant (:tone spec))} (markdown (:body spec))))

;; chart (pure SVG; hover values through <title>)

(def ^:private chart-w 320)
(def ^:private chart-h 120)

(defn- fmt [n] (if (== n (js/Math.round n)) (str n) (.toFixed n 2)))

(defn- bar-chart [{:keys [labels series]}]
  (let [vals    (mapcat :values series)
        hi      (max 0 (reduce max 0 vals))
        lo      (min 0 (reduce min 0 vals))
        span    (let [s (- hi lo)] (if (zero? s) 1 s))
        n       (max 1 (count labels))
        k       (count series)
        slot    (/ chart-w n)
        bw      (max 1 (/ (* 0.8 slot) k))
        y-of    (fn [v] (* chart-h (/ (- hi v) span)))
        zero-y  (y-of 0)]
    [:svg {:class ["ab-chart"] :viewBox (str "0 0 " chart-w " " (+ chart-h 18)) :role "img"}
     [:line {:class ["ab-axis"] :x1 0 :x2 chart-w :y1 zero-y :y2 zero-y}]
     (for [[si s] (map-indexed vector series)
           [i v]  (map-indexed vector (:values s))
           :let [x (+ (* i slot) (* 0.1 slot) (* si bw))
                 y (min (y-of v) zero-y)
                 h (max 1 (js/Math.abs (- (y-of v) zero-y)))]]
       [:rect {:replicant/key (str si "-" i) :class [(str "ab-series-" (mod si 6))]
               :x x :y y :width (max 1 (- bw 1)) :height h :rx 1}
        [:title (str (when (> k 1) (str (:name s) " · ")) (get labels i "") ": " (fmt v))]])
     (when (<= n 24)
       (for [[i l] (map-indexed vector labels)]
         [:text {:replicant/key (str "l" i) :class ["ab-chart-label"]
                 :x (+ (* i slot) (/ slot 2)) :y (+ chart-h 14) :text-anchor "middle"}
          l]))]))

(defn- line-chart [{:keys [labels series type]}]
  (let [spark? (= "sparkline" type)
        w      (if spark? 120 chart-w)
        h      (if spark? 28 chart-h)
        vals   (mapcat :values series)
        hi     (reduce max 0 vals)
        lo     (reduce min (if (seq vals) (first vals) 0) vals)
        span   (let [s (- hi lo)] (if (zero? s) 1 s))
        n      (max 2 (reduce max 0 (map (comp count :values) series)))
        x-of   (fn [i] (* w (/ i (dec n))))
        y-of   (fn [v] (- h 2 (* (- h 4) (/ (- v lo) span))))]
    [:svg {:class ["ab-chart" (when spark? "ab-chart--spark")] :viewBox (str "0 0 " w " " h) :role "img"}
     (for [[si s] (map-indexed vector series)]
       [:g {:replicant/key si :class [(str "ab-series-" (mod si 6))]}
        [:polyline {:class ["ab-line"]
                    :points (str/join " " (map-indexed (fn [i v] (str (x-of i) "," (y-of v))) (:values s)))}]
        (when-not spark?
          (for [[i v] (map-indexed vector (:values s))]
            [:circle {:replicant/key i :class ["ab-point"] :cx (x-of i) :cy (y-of v) :r 3}
             [:title (str (when (> (count series) 1) (str (:name s) " · ")) (get labels i "") ": " (fmt v))]]))])]))

(defn- chart-view [{:keys [spec]}]
  [:div {:class ["ab-chart-wrap"]}
   (if (= "bar" (:type spec)) (bar-chart spec) (line-chart spec))
   (when (> (count (:series spec)) 1)
     [:div {:class ["ab-legend"]}
      (for [[si s] (map-indexed vector (:series spec))]
        [:span {:replicant/key si :class ["ab-legend-item"]}
         [:span {:class ["ab-swatch" (str "ab-series-" (mod si 6))]}] (:name s)])])])

;; interactive kinds

(defn- choice-view [dispatch! room-id {:keys [id spec state]}]
  (let [sel (set (:selected state))]
    [:div {:class ["ab-choice"]}
     [:div {:class ["ab-choice-options"]}
      (for [o (:options spec)
            :let [on? (contains? sel (:id o))]]
        [:div {:replicant/key (:id o) :class ["ab-choice-option"]}
         (chip/chip {:active on?
                     :on-click (fn [_]
                                 (act! dispatch! room-id id "select" nil
                                       (cond
                                         (and (:multi spec) on?) (vec (disj sel (:id o)))
                                         (:multi spec)           (conj (vec sel) (:id o))
                                         on?                     []
                                         :else                   [(:id o)])))}
                    (:label o))
         (when-let [d (:description o)] [:span {:class ["ab-note"]} d])])]
     [:div {:class ["ab-actions"]}
      (button/button {:variant :primary :size :sm :disabled (empty? sel)
                      :on-click (fn [_] (submit! dispatch! room-id id "submit"))}
                     (:submit spec))
      (when (:submitted? state) [:span {:class ["ab-note"]} "Sent"])]]))

(defn- form-view [dispatch! room-id {:keys [id spec state]} ui]
  (let [draft   (:draft ui)
        value   (fn [f] (get draft (:name f) (b/field-value state f)))
        put!    (fn [f v] (dispatch! {:type :ext.blocks/draft :block-id id :field (:name f) :value v}))
        values  (into {} (map (juxt :name value)) (:fields spec))
        missing (b/missing-fields spec {:values values})]
    [:div {:class ["ab-form"]}
     (for [f (:fields spec)]
       [:div {:replicant/key (:name f)}
        (if (= "checkbox" (:type f))
          (form/form-checkbox {:label (:label f) :checked (boolean (value f))
                               :on-change (fn [e] (put! f (.. e -target -checked)))})
          (form/form-field
           {:label (str (:label f) (when (:required f) " *")) :hint (:hint f)}
           (case (:type f)
             "textarea" (form/form-textarea {:value (value f) :placeholder (:placeholder f)
                                             :attrs {:rows 3 :on {:input (fn [e] (put! f (.. e -target -value)))}}})
             "select"   (form/form-select {:value (value f) :placeholder (or (:placeholder f) "Choose…")
                                           :options (mapv (fn [o] {:value (:id o) :label (:label o)}) (:options f))
                                           :on-change (fn [v] (put! f v))})
             (form/form-input {:type (if (= "number" (:type f)) :number :text)
                               :value (value f) :placeholder (:placeholder f)
                               :attrs {:on {:input (fn [e] (put! f (.. e -target -value)))}}}))))])
     [:div {:class ["ab-actions"]}
      (button/button {:variant :primary :size :sm :disabled (boolean (seq missing))
                      :on-click (fn [_] (submit! dispatch! room-id id "submit" nil values))}
                     (:submit spec))
      (when (:submitted? state) [:span {:class ["ab-note"]} "Submitted"])]]))

(defn- cards-view [dispatch! room-id {:keys [id spec state]}]
  [:div {:class ["ab-cards"]}
   (for [c (:cards spec)
         :let [picked (get-in state [:picked (:id c)])]]
     (card/card {:attrs {:replicant/key (:id c)} :class "ab-card"}
                (card/card-header {} [:strong (:title c)]
                                  (when-let [m (:meta c)] [:span {:class ["ab-note"]} m]))
                (when (:body c) (card/card-body {} (markdown (:body c))))
                (when (seq (:actions c))
                  (card/card-footer
                   {}
                   (for [a (:actions c)]
                     (button/button {:variant (if (= picked (:id a)) :primary :secondary) :size :sm
                                     :attrs {:replicant/key (:id a)}
                                     :on-click (fn [_]
                                                 (if (:send a)
                                                   (submit! dispatch! room-id id "card" (:id c) (:id a))
                                                   (act! dispatch! room-id id "card" (:id c) (:id a))))}
                                    (:label a)))))))])

;; hiccup (tier 2): the allowlisted tree from xi.blocks.hiccup

(def ^:private semantic-class
  {:tone "ab-tone-" :emphasis "ab-em-" :layout "ab-layout-" :gap "ab-gap-" :align "ab-align-"})

(defn- dom-attrs
  "The DOM attributes of an allowlisted element: semantic attributes become
   our ab-* classes; action wiring is the caller's."
  [tag attrs]
  (let [classes (keep (fn [[k p]] (when-let [v (get attrs k)] (str p v))) semantic-class)
        attrs   (apply dissoc attrs :action :send (keys semantic-class))]
    (cond-> attrs
      (seq classes) (assoc :class (vec classes))
      (= :a tag)    (assoc :target "_blank" :rel "noopener noreferrer nofollow")
      (= :img tag)  (assoc :loading "lazy" :referrerpolicy "no-referrer"))))

(defn- tree-view
  "An allowlisted tree (xi.blocks.hiccup) with its controls bound to block
   actions; `sends` are the actions that also post a message."
  [dispatch! room-id id tree sends]
  (let [fire! (fn [action value]
                (if (contains? sends action)
                  (submit! dispatch! room-id id action nil value)
                  (act! dispatch! room-id id action nil value)))
        render
        (fn render [node]
          (if-not (vector? node)
            node
            (let [[tag attrs & kids] node
                  kids   (map render kids)
                  action (:action attrs)
                  click  (when action (fn [_] (fire! action (:value attrs))))]
              (case tag
                :ui/button   (button/button {:variant (keyword (or (:variant attrs) "secondary"))
                                             :size (keyword (or (:size attrs) "sm"))
                                             :disabled (:disabled attrs) :on-click click}
                                            kids)
                :ui/badge    (badge/badge {:variant (some-> (:variant attrs) keyword)} kids)
                :ui/progress (progress/progress {:value (:value attrs)
                                                 :variant (some-> (:variant attrs) keyword)})
                :ui/alert    (alert/alert {:variant (some-> (:variant attrs) keyword)} kids)
                :ui/chip     (chip/chip {:active (:active attrs) :on-click click} kids)
                :button      (into [:button (cond-> (assoc (dom-attrs tag attrs) :type "button")
                                              click (assoc-in [:on :click] click))]
                                   kids)
                :input       [:input (cond-> (dom-attrs tag attrs)
                                       action (assoc-in [:on :change]
                                                        (fn [e]
                                                          (let [t (.-target e)]
                                                            (fire! action (if (#{"checkbox" "radio"} (.-type t))
                                                                            (.-checked t)
                                                                            (.-value t)))))))]
                :textarea    [:textarea (cond-> (dissoc (dom-attrs tag attrs) :value)
                                          action (assoc-in [:on :change] (fn [e] (fire! action (.. e -target -value)))))
                              (str (or (:value attrs) (apply str (filter string? kids))))]
                :select      (form/form-select
                              {:value (:value attrs)
                               :options (for [[t a & label] (filter vector? (drop 2 node)) :when (= :option t)]
                                          {:value (str (or (:value a) (apply str label)))
                                           :label (apply str (filter string? label))})
                               :on-change (fn [v] (when action (fire! action v)))})
                (into [tag (dom-attrs tag attrs)] kids)))))]
    [:div {:class ["ab-hiccup"]} (render tree)]))

(defn- hiccup-view [dispatch! room-id {:keys [id spec]}]
  (tree-view dispatch! room-id id (:tree spec) (bh/send-actions (:tree spec))))

(defn- ext-kind-view
  "A user extension's kind, drawn by its web half's renderer through the
   same allowlist; its text form when there is no renderer."
  [dispatch! room-id {:keys [id kind spec state rules] :as record}]
  (if-let [tree (some-> (block-kinds/render kind spec state) bh/normalize :tree)]
    (tree-view dispatch! room-id id tree (set (:send rules)))
    [:pre {:class ["ab-raw"]} (b/body-text record)]))

;; ── Frame ────────────────────────────────────────────────────────────────────

(defn- source-label [{:keys [type name id server tool]}]
  (case type
    :tool (or name "tool")
    :ext  (str "extension " (some-> id clojure.core/name))
    :mcp  (str "mcp " server (when tool (str " · " tool)))
    "agent"))

(defn- block-body [dispatch! room-id {:keys [kind] :as record} ui]
  (case kind
    "checklist" (checklist-view dispatch! room-id record)
    "table"     (table-view dispatch! record ui)
    "keyvalue"  (keyvalue-view record)
    "steps"     (steps-view record)
    "progress"  (progress-view record)
    "callout"   (callout-view record)
    "chart"     (chart-view record)
    "choice"    (choice-view dispatch! room-id record)
    "form"      (form-view dispatch! room-id record ui)
    "cards"     (cards-view dispatch! room-id record)
    "hiccup"    (hiccup-view dispatch! room-id record)
    (ext-kind-view dispatch! room-id record)))

(defn- block-menu [dispatch! {:keys [id] :as record} ui]
  (let [menu-id (str "ab-menu-" id)
        item    (fn [k icon-name label f]
                  [:button {:class ["sidebar-more-item"] :replicant/key k
                            :on {:click (fn [_] (hide-popover! menu-id) (f))}}
                   (icon/icon {:icon-name icon-name :size :sm})
                   [:span label]])]
    (list
     [:button (merge {:class ["icon-btn" "icon-btn--sm" "agent-block-menu"]
                      :title "Block menu" :replicant/key "trigger"}
                     (popover/trigger-attrs menu-id))
      (icon/icon {:icon-name :menu :size :sm})]
     (popover/popover-content
      {:id menu-id :side :bottom :align :end :class "sidebar-more-menu"
       :attrs {:replicant/key "menu"}}
      (item "raw" :code (if (:raw? ui) "Hide data" "View data")
            #(dispatch! {:type :ext.blocks/raw :block-id id}))
      (item "md" :copy "Copy as text" #(views/copy! dispatch! (b/text record)))
      (item "json" :copy "Copy as JSON"
            #(views/copy! dispatch! (js/JSON.stringify (clj->js (select-keys record [:id :kind :title :spec :state])) nil 2)))))))

(defn- block-post [dispatch! room-id {:keys [id kind title source unread] :as record} ui]
  [:div {:class ["post" "post--assistant" "post--agent-block"]}
   [:section {:class ["agent-block"] :data-block-kind kind
              :aria-label (str "Agent block: " (or title kind))}
    [:header {:class ["agent-block-head"]}
     (icon/icon {:icon-name :layout-dashboard :size :sm})
     [:span {:class ["agent-block-badge"]} "Agent block"]
     [:span {:class ["agent-block-kind"]} kind]
     (when title [:span {:class ["agent-block-title"]} title])
     [:span {:class ["agent-block-spacer"]}]
     (when (pos? (or unread 0))
       [:span {:class ["agent-block-unread"]
               :title (str unread " action" (when (not= 1 unread) "s") " the agent hasn't read yet")}
        (str unread " new")])
     [:span {:class ["agent-block-source"] :title (str "Block " id)} (source-label source)]
     (block-menu dispatch! record ui)]
    [:div {:class ["agent-block-body"]}
     (block-body dispatch! room-id record ui)]
    (when (:raw? ui)
      [:pre {:class ["agent-block-raw"]}
       (js/JSON.stringify (clj->js (select-keys record [:spec :state :actions])) nil 2)])]])

;; ── Timeline ─────────────────────────────────────────────────────────────────

(defonce ^:private memo
  ;; block id → [record ui room-id dispatch! renderers node]: a chat with
  ;; many blocks rebuilds only the ones whose record or browser state changed,
  ;; or all once a user web half registers its kinds
  (atom {}))

(defn- memo-post [dispatch! room-id record ui]
  (let [[r u rid d kinds node] (get @memo (:id record))
        kinds-now (block-kinds/version)]
    (if (and (identical? r record) (= u ui) (= rid room-id) (identical? d dispatch!)
             (identical? kinds kinds-now))
      node
      (let [node (block-post dispatch! room-id record ui)]
        (swap! memo assoc (:id record) [record ui room-id dispatch! kinds-now node])
        node))))

(defn- timeline-posts
  "Each block after its tool call, or at its :anchor when that call isn't in
   the history (a sub-agent's, or a rebuilt history without it)."
  [state dispatch! room entries]
  (let [blocks (b/room-blocks room)]
    (when (seq blocks)
      (let [calls (into {} (keep-indexed (fn [i e] (when (= :tool-call (:kind e)) [(:id e) i]))) entries)
            n     (count entries)]
        (for [{:keys [id call-id anchor] :as record} blocks]
          {:pos  (if-let [i (get calls call-id)] (inc i) (min n (or anchor n)))
           :key  (str "block-" id)
           :node (memo-post dispatch! (:id room) record (get-in state [:web/blocks id]))})))))

(def extension
  {:id             h/ext-id
   ;; so the web's :ext.persist/hydrate mirror installs restored blocks too
   :persist-room   [:blocks]
   :handlers       (merge h/handlers
                          {:ext.blocks/act    on-act
                           :ext.blocks/submit on-submit
                           :ext.blocks/sort   on-sort
                           :ext.blocks/raw    on-raw
                           :ext.blocks/draft  on-draft})
   :timeline-posts timeline-posts})
