(ns xi.ext.blocks.web
  "Agent blocks, browser half: draws each block of the room's mirrored
   `:blocks` slice as its own timeline post after the tool call that made it
   (`:timeline-posts`, xi.web.timeline-posts), inside a frame the block's
   content can't cover or restyle: a header naming it an agent block, its
   kind and source, and a ⋯ menu (view data, copy).

   Controls only ever produce block actions (`:ext.blocks/act`, forwarded to
   the server, which records them on the block). A choice's Send and a
   control marked to send also post a message for the user (`:input/submit`
   with xi.blocks/action-message): user-initiated, never on mount or hover.
   The raw-data view is this browser's own ([:web/blocks …])."
  (:require [clojure.string :as str]
            [ui.alert :as alert]
            [ui.badge :as badge]
            [ui.button :as button]
            [ui.chip :as chip]
            [ui.form :as form]
            [ui.icon :as icon]
            [ui.popover :as popover]
            [ui.progress :as progress]
            [xi.blocks :as b]
            [xi.blocks.hiccup :as bh]
            [xi.core.state :as state]
            [xi.ext.blocks.frame :as frame]
            [xi.ext.blocks.handlers :as h]
            [xi.web.block-kinds :as block-kinds]
            [xi.web.block-nav :as block-nav]
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
   state makes, as the user."
  [st {:keys [room-id block-id action target value]}]
  (when-let [record (h/find-block st room-id block-id)]
    (let [ev {:type :ext.blocks/act :room-id room-id :block-id block-id
              :action action :target target :value value}]
      (when-let [{r :record send? :send?} (b/act record ev (state/own-user st) (js/Date.now))]
        {:effects (cond-> [[:ws/send ev]]
                    send? (conj [:ws/send {:type :input/submit :room-id room-id
                                           :text (b/action-message r action)}]))}))))

(defn- on-raw [st {:keys [block-id]}]
  {:state (update-in st (ui-path block-id :raw?) not)})

;; app views (xi.ext.blocks.frame)

(defn- forward-local
  "A request for the server about an app view: sent, answered to this client."
  [_st ev]
  (when-not (:remote? ev)
    {:effects [[:ws/send ev]]}))

(defn- on-app-html [st {:keys [block-id] :as ev}]
  (frame/loaded! ev)
  {:state (assoc-in st (ui-path block-id :html-at) (js/Date.now))})

(defn- on-app-reply [_st ev]
  (frame/reply! ev)
  nil)

(defn- on-app-display [st {:keys [block-id fullscreen?]}]
  {:state (assoc-in st (ui-path block-id :fullscreen?) (boolean fullscreen?))})

(defn- on-app-message
  "A view's ui/message: into the message box for the user to send, or not."
  [st {:keys [text]}]
  {:effects [[:app/dispatch {:type :compose/prefill :text text :draft-key (views/draft-key st)}]]})

;; ── Small pieces ─────────────────────────────────────────────────────────────


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

(defn- choice-text [{:keys [label description]}]
  [:span {:class ["ab-choice-text"]}
   [:span {:class ["ab-choice-label"]} label]
   (when description [:span {:class ["ab-note"]} description])])

(defn- choice-options
  "The options as cards: radios for one pick, checkboxes for `:multi`."
  [dispatch! room-id {:keys [id spec]} sel]
  (let [select! #(act! dispatch! room-id id "select" nil %)]
    (if (:multi spec)
      [:div {:class ["ab-choice-options"]}
       (for [o (:options spec)
             :let [on? (contains? sel (:id o))]]
         (form/form-checkbox
          {:label     (choice-text o)
           :checked   on?
           :attrs     {:replicant/key (:id o)}
           :on-change (fn [_] (select! (if on? (vec (disj sel (:id o))) (conj (vec sel) (:id o)))))}))]
      (let [by-value (into {} (map (juxt (comp str :id) :id)) (:options spec))]
        (form/form-radio-group
         {:class       "ab-choice-options"
          :radio-name  (str "ab-choice-" id)
          :radio-value (some-> (first sel) str)
          :options     (mapv (fn [o] {:value (str (:id o)) :label (choice-text o)}) (:options spec))
          :on-change   (fn [e] (select! [(by-value (.. e -target -value))]))})))))

(defn- choice-view [dispatch! room-id {:keys [id spec state] :as block}]
  (let [sel (set (:selected state))]
    [:div {:class ["ab-choice"]}
     (choice-options dispatch! room-id block sel)
     [:div {:class ["ab-actions"]}
      (button/button {:variant :primary :size :sm :disabled (empty? sel)
                      :on-click (fn [_] (submit! dispatch! room-id id "submit"))}
                     (:submit spec))
      (when (:submitted? state) [:span {:class ["ab-note"]} "Sent"])]]))

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
  (let [tree (some-> (block-kinds/render kind spec state) bh/normalize :tree)]
   (cond
    tree
    (tree-view dispatch! room-id id tree (set (:send rules)))

    (:text-form record)
    [:pre {:class ["ab-raw"]} (b/body-text record)]

    ;; a kind Xi no longer has (the static presets, form, cards), in an older chat
    :else
    [:div
     [:p {:class ["ab-note"]} (str "Blocks of kind " kind " aren't drawn any more. Their data:")]
     [:pre {:class ["ab-raw"]} (js/JSON.stringify (clj->js spec) nil 2)]])))

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
    "choice"    (choice-view dispatch! room-id record)
    "hiccup"    (hiccup-view dispatch! room-id record)
    "app"       (frame/view dispatch! room-id record ui)
    (ext-kind-view dispatch! room-id record)))

(defn- open-buffer!
  "Show block `record` as a buffer of the room (`block:<id>`), full size."
  [dispatch! room-id {:keys [id kind title]}]
  (let [buffer-id (str "block:" id)]
    (dispatch! {:type :ui/buffer-set :room-id room-id :buffer-id buffer-id
                :buffer {:kind :block :block-id id :title (str "Block · " (or title kind))}})
    (dispatch! {:type :ui/buffer-switch :room-id room-id :buffer-id buffer-id})))

(defn- block-menu [dispatch! room-id {:keys [id] :as record} ui]
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
      (when room-id
        (item "buffer" :external-link "Open in buffer" #(open-buffer! dispatch! room-id record)))
      (item "raw" :code (if (:raw? ui) "Hide data" "View data")
            #(dispatch! {:type :ext.blocks/raw :block-id id}))
      (item "md" :copy "Copy as text" #(views/copy! dispatch! (b/text record)))
      (item "json" :copy "Copy as JSON"
            #(views/copy! dispatch! (js/JSON.stringify (clj->js (select-keys record [:id :kind :title :spec :state])) nil 2)))))))

(defn- block-post [dispatch! room-id {:keys [id kind title source unread] :as record} ui]
  [:div {:class ["post" "post--assistant" "post--agent-block"]}
   [:section {:class ["agent-block" (when (:fullscreen? ui) "agent-block--fullscreen")]
              :data-block-kind kind
              :aria-label (str "Agent block: " (or title kind))
              ;; :block/next can focus a block without controls; arrows rove
              :tabindex "-1"
              :on {:keydown block-nav/on-keydown}}
    [:header {:class ["agent-block-head"]}
     (icon/icon {:icon-name :layout-dashboard :size :sm})
     ;; an app view is someone else's page: say so, whatever it draws
     [:span {:class ["agent-block-badge"]} (if (= "app" kind) "External app" "Agent block")]
     [:span {:class ["agent-block-kind"]} kind]
     (when title [:span {:class ["agent-block-title"]} title])
     [:span {:class ["agent-block-spacer"]}]
     (when (pos? (or unread 0))
       [:span {:class ["agent-block-unread"]
               :title (str unread " action" (when (not= 1 unread) "s") " the agent hasn't read yet")}
        (str unread " new")])
     [:span {:class ["agent-block-source"] :title (str "Block " id)} (source-label source)]
     (when (:fullscreen? ui)
       (button/button {:variant :ghost :size :sm
                       :on-click (fn [_]
                                   (frame/notify-mode! id "inline")
                                   (dispatch! {:type :ext.blocks/app-display :block-id id :fullscreen? false}))}
                      "Exit full screen"))
     (block-menu dispatch! room-id record ui)]
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
  "Each block in place of its tool call (the call's row only says it showed
   the block), or at its :anchor when that call isn't in the history (a
   sub-agent's, or a rebuilt history without it)."
  [state dispatch! room entries]
  (let [blocks (b/room-blocks room)]
    (when (seq blocks)
      (let [calls (into {} (keep-indexed (fn [i e] (when (= :tool-call (:kind e)) [(:id e) i]))) entries)
            n     (count entries)]
        (for [{:keys [id call-id anchor] :as record} blocks
              :let [i (get calls call-id)]]
          (cond-> {:pos  (if i (inc i) (min n (or anchor n)))
                   :key  (str "block-" id)
                   :node (memo-post dispatch! (:id room) record (get-in state [:web/blocks id]))}
            i (assoc :hides i)))))))

(defn- buffer-view
  "A `block:<id>` buffer: the block at full size, outside the timeline."
  [state dispatch! room _id {:keys [block-id]}]
  (let [record (get-in room [:ext b/ext-id :blocks block-id])]
    [:div {:class ["ab-buffer"]}
     (if record
       (block-post dispatch! (:id room) record (get-in state [:web/blocks block-id]))
       [:p {:class ["ab-note"]} "This block is no longer in the chat."])]))

(def extension
  {:id             h/ext-id
   ;; so the web's :ext.persist/hydrate mirror installs restored blocks too
   :persist-room   [:blocks]
   :handlers       (merge h/handlers
                          {:ext.blocks/act    on-act
                           :ext.blocks/submit on-submit
                           :ext.blocks/app-load    forward-local
                           :ext.blocks/app-rpc     forward-local
                           :ext.blocks/app-html    on-app-html
                           :ext.blocks/app-reply   on-app-reply
                           :ext.blocks/app-display on-app-display
                           :ext.blocks/app-message on-app-message
                           :ext.blocks/raw    on-raw})
   :timeline-posts timeline-posts
   :buffer-views   {:block {:view buffer-view :icon :layout-dashboard}}})
