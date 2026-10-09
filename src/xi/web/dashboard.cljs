(ns xi.web.dashboard
  "The home dashboard's cards: the `:dashboard-cards` web-extension key (built-in
   halves at init, user halves once loaded, see xi.web.user-ext), which cards
   show, and the frame and grid they render in. The page around them — topbar,
   composer — is xi.web.views/dashboard-view.

   A card is
     {:id     :<ext>/name                 ; namespaced, the hide key
      :title  str   :icon kw   :description str   ; description: Customize list
      :order  n                            ; ascending, default 100
      :size   :normal | :wide              ; :wide takes the whole row
      :when   (fn [state] → bool)          ; optional, hidden while false
      :load   {event}                      ; dispatched on entering the dashboard
      :render (fn [state dispatch!] → hiccup)   ; the body only
      :more   {:label str :event {…}}}     ; a header link

   The registry lives outside the app state because cards hold fns (the state
   is persisted and synced). The user's hidden cards are the :dashboard-hidden
   user-state key (xi.web.user-state)."
  (:require [ui.card :as card]
            [ui.icon :as icon]
            [ui.switch :as switch]
            [xi.web.user-state :as user-state]))

(defonce ^:private registry (atom []))

(defn register!
  "Add `cards` after the registered ones; a card with an id already registered
   replaces it in place."
  [cards]
  (swap! registry
         (fn [reg]
           (reduce (fn [reg c]
                     (if-let [i (first (keep-indexed (fn [i x] (when (= (:id x) (:id c)) i)) reg))]
                       (assoc reg i c)
                       (conj reg c)))
                   reg cards))))

(defn cards [] @registry)

;; ── Pure ─────────────────────────────────────────────────────────────────────

(defn dashboard-route?
  "The bare home route: not a project's sessions, the project list or all sessions."
  [{:keys [page dir]}]
  (and (= :home page) (nil? dir)))

(defn- shown? [{when-fn :when} state]
  (or (nil? when-fn)
      (try (boolean (when-fn state)) (catch :default _ false))))

(defn visible-cards
  "The cards to draw, in order: not hidden by the user, :when holding, sorted
   by :order and then registration order."
  [cards state hidden]
  (->> cards
       (map-indexed vector)
       (remove (fn [[_ c]] (contains? hidden (:id c))))
       (filter (fn [[_ c]] (shown? c state)))
       (sort-by (fn [[i c]] [(or (:order c) 100) i]))
       (mapv second)))

(defn load-events
  "The :load events of every card the user hasn't hidden. :when is not
   consulted: a card is often hidden until its first load fills it."
  [cards hidden]
  (into [] (comp (remove #(contains? hidden (:id %))) (keep :load)) cards))

(defn toggle-hidden
  [hidden id]
  (if (contains? hidden id) (disj hidden id) (conj (or hidden #{}) id)))

;; ── Handlers ─────────────────────────────────────────────────────────────────

(defn- load-cards [st _]
  (when-let [evs (seq (load-events (cards) (or (:web/dashboard-hidden st) #{})))]
    {:effects (mapv (fn [ev] [:app/dispatch ev]) evs)}))

(defn- toggle-card [st {:keys [id]}]
  (let [hidden (toggle-hidden (or (:web/dashboard-hidden st) #{}) id)]
    {:state   (assoc st :web/dashboard-hidden hidden)
     :effects [(user-state/set-effect :dashboard-hidden hidden)]}))

(defn- toggle-customize [st _]
  {:state (update st :web/dashboard-customize? not)})

(def handlers
  {:dashboard/load             load-cards
   :dashboard/toggle-card      toggle-card
   :dashboard/toggle-customize toggle-customize})

(defn load-tap
  "App tap: loads the cards on entering the dashboard, and again on a
   (re)connect while on it — the first load of a page opened at / runs before
   the socket is up."
  [dispatch!]
  (fn [event state]
    (when (and (dashboard-route? (:web/route state))
               (case (:type event)
                 :route/navigate     true
                 :connection/status  (:connected? event)
                 false))
      (dispatch! {:type :dashboard/load}))))

;; ── View ─────────────────────────────────────────────────────────────────────

(defn- card-body [state dispatch! {:keys [id render]}]
  (try (render state dispatch!)
       (catch :default e
         (js/console.error "[dashboard] card" (str id) "failed to render" e)
         [:p {:class ["dash-card-error"]} "This card failed to render."])))

(defn- card-attrs
  "ui.card's :class takes one class name (replicant wants no spaces), so the
   full list goes in :attrs."
  [classes attrs]
  {:attrs (assoc attrs :class (into (card/card-class-list {}) classes))})

(defn- card-frame [state dispatch! {:keys [id title icon size more] :as c}]
  (card/card (card-attrs (cond-> ["dash-card"] (= :wide size) (conj "dash-card--wide"))
                         {:replicant/key (str id) :data-card (str id)})
    (card/card-header {:class "dash-card-head"}
      (when icon (icon/icon {:icon-name icon :size :sm :class "dash-card-icon"}))
      [:span {:class ["dash-card-title"]} title]
      (when more
        [:button {:class ["dash-card-more"]
                  :on {:click (fn [_] (dispatch! (:event more)))}}
         (:label more)
         (icon/icon {:icon-name :chevron-right :size :sm})]))
    (card/card-body {:class "dash-card-body"}
      (card-body state dispatch! c))))

(defn- customize-panel
  [dispatch! all hidden]
  (card/card (card-attrs ["dash-card" "dash-card--wide" "dash-customize"]
                         {:replicant/key "dash-customize"})
    (card/card-header {:class "dash-card-head"}
      (icon/icon {:icon-name :settings :size :sm :class "dash-card-icon"})
      [:span {:class ["dash-card-title"]} "Cards"]
      [:button {:class ["dash-card-more"]
                :on {:click (fn [_] (dispatch! {:type :dashboard/toggle-customize}))}}
       "Done"])
    (card/card-body {:class "dash-card-body"}
      (for [{:keys [id title description]} all]
        [:div {:class ["dash-customize-row"] :replicant/key (str id)}
         [:div {:class ["dash-customize-text"]}
          [:span {:class ["dash-customize-title"]} title]
          (when description
            [:span {:class ["dash-customize-desc"]} description])]
         (switch/switch-toggle
          {:checked   (not (contains? hidden id))
           :on-change (fn [_] (dispatch! {:type :dashboard/toggle-card :id id}))
           :attrs     {:aria-label (str "Show " title)}})]))))

(defn grid
  "The card grid, preceded by the Customize panel while it is open."
  [state dispatch!]
  (let [all    (cards)
        hidden (or (:web/dashboard-hidden state) #{})]
    [:div {:class ["dash-grid"]}
     (when (:web/dashboard-customize? state)
       (customize-panel dispatch! all hidden))
     (for [c (visible-cards all state hidden)]
       (card-frame state dispatch! c))]))
