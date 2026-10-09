(ns xi.ext.usage.web
  "The /usage page: one card per subscription or balance the server polls
   (xi.server.usage) — every window's meter, when it resets, the pace it is
   on, and a chart of the current period from the sample history.

   State lives at :web/usage {:readings :history :fetched-at :loading?}.
   Entering the page (and a lobby broadcast carrying a newer :usage-at while
   on it) sends the roomless :usage/fetch; the server answers :usage/state."
  (:require [clojure.string :as str]
            [ui.badge :as badge]
            [ui.button :as button]
            [ui.card :as card]
            [ui.empty-state :as empty-state]
            [ui.icon :as icon]
            [xi.usage :as usage]
            [xi.web.views :as views]))

;; ── Handlers ─────────────────────────────────────────────────────────────────

(defn- fetch [st _]
  {:state   (assoc-in st [:web/usage :loading?] true)
   :effects [[:ws/send {:type :usage/fetch}]]})

(defn- refresh
  "The Refresh button: a fresh poll, then the readings (the poll itself is
   asynchronous, so the fresh ones arrive with the next lobby broadcast)."
  [st _]
  {:state   (assoc-in st [:web/usage :loading?] true)
   :effects [[:ws/send {:type :usage/refresh}]
             [:ws/send {:type :usage/fetch}]]})

(defn- received [st {:keys [readings history fetched-at]}]
  {:state (assoc st :web/usage {:readings   (vec readings)
                                :history    (or history {})
                                :fetched-at fetched-at
                                :loading?   false})})

(defn- on-navigate
  "Chained after the router: entering the page fetches what it shows."
  [st {:keys [page]}]
  (when (= page :usage)
    {:state   (assoc-in st [:web/usage :loading?] true)
     :effects [[:ws/send {:type :usage/fetch}]]}))

(defn- showing-usage?
  "The /usage page or the dashboard (its usage card) is open."
  [{:keys [page dir]}]
  (or (= :usage page) (and (= :home page) (nil? dir))))

(defn- refetch-on-poll-tap
  "A lobby broadcast carries :usage-at, the server's last poll; a newer one
   while the page or the dashboard is open refetches the readings."
  [dispatch!]
  (fn [event state]
    (when (and (= :lobby/state (:type event))
               (showing-usage? (:web/route state))
               (number? (:usage-at event))
               (> (:usage-at event) (or (get-in state [:web/usage :fetched-at]) 0)))
      (dispatch! {:type :usage/fetch}))))

;; ── Formatting ───────────────────────────────────────────────────────────────

(defn- fmt
  [ms opts]
  (when (number? ms)
    (try (.toLocaleString (js/Date. ms) js/undefined (clj->js opts))
         (catch :default _ nil))))

(defn- fmt-time [ms] (fmt ms {:hour "2-digit" :minute "2-digit"}))
(defn- fmt-weekday [ms] (fmt ms {:weekday "short"}))
(defn- fmt-date [ms] (fmt ms {:month "short" :day "numeric"}))
(defn- fmt-date-time [ms] (fmt ms {:month "short" :day "numeric" :hour "2-digit" :minute "2-digit"}))

(defn- iso->ms [iso]
  (when (string? iso)
    (let [t (js/Date.parse iso)] (when-not (js/isNaN t) t))))

(defn- ago
  [ms now]
  (let [d (- now ms)]
    (cond (< d 60000) "just now"
          (< d usage/hour-ms) (str (js/Math.round (/ d 60000)) " min ago")
          :else (str (usage/format-duration d) " ago"))))

(defn resets-note
  "\"Resets in 2h 48m · 2h 11m of 5h\" / \"Resets Oct 15 · day 1 of 7\" for a
   window with a span; \"Resets 14:30\" without a period; nil without a time."
  [{:keys [resets-at period-ms]} stats]
  (let [end (iso->ms resets-at)]
    (cond
      (nil? end) nil
      (nil? stats) (str "Resets " (fmt-date-time end))
      (= :day (:unit stats))
      (str "Resets " (fmt-date end) " · day " (:day stats) " of " (:days stats))
      :else
      (str "Resets in " (usage/format-duration (:remaining-ms stats))
           " · " (usage/format-duration (:elapsed-ms stats))
           " of " (usage/format-duration period-ms)))))

(defn pace-note
  "\"≈6%/h · on pace for 32% at reset · last hour ≈4%\" from window stats,
   nil while the window is too young for a pace or nothing was used."
  [{:keys [rate projected recent unit]}]
  (when (and rate (pos? rate))
    (let [per (if (= unit :day) "day" "h")]
      (str "≈" (js/Math.round rate) "%/" per
           " · on pace for " projected "% at reset"
           (when recent
             (str " · " (if (= unit :day) "last 24h" "last hour") " ≈" recent "%"))))))

;; ── Pieces ───────────────────────────────────────────────────────────────────

(defn- severity-class [{:keys [severity used]}]
  (str "claude-usage--" (or severity (usage/severity used))))

(defn- meter [{:keys [used] :as window}]
  [:div {:class ["usage-meter" (severity-class window)]}
   [:div {:class ["claude-usage-track"]}
    [:div {:class ["claude-usage-fill"] :style {:width (str (or used 0) "%")}}]]])

(defn- line-chart
  "The current period as an SVG line (solid: this period; dotted: at this
   pace) over day / hour ticks, labelled below."
  [window samples now]
  (when-let [{:keys [points projection ticks]} (usage/chart window samples now)]
    (let [coord (fn [[x y]] (str (* 100 x) "," (- 100 y)))
          daily? (> (:period-ms window) usage/day-ms)]
      [:div {:class ["usage-chart"]}
       [:svg {:class ["usage-chart-svg"] :viewBox "0 0 100 100"
              :preserveAspectRatio "none" :aria-hidden "true"}
        (for [{:keys [x]} ticks]
          [:line {:class ["usage-chart-grid"] :x1 (* 100 x) :x2 (* 100 x) :y1 0 :y2 100}])
        (when (seq points)
          [:polyline {:class ["usage-chart-line"] :points (str/join " " (map coord points))}])
        (when projection
          [:polyline {:class ["usage-chart-projection"]
                      :points (str/join " " (map coord projection))}])]
       [:div {:class ["usage-chart-ticks"]}
        (for [[i {:keys [x at]}] (map-indexed vector ticks)
              :when (or daily? (even? i))]
          [:span {:class ["usage-chart-tick"] :style {:left (str (* 100 x) "%")}}
           (if daily? (fmt-weekday at) (fmt-time at))])]
       [:div {:class ["usage-chart-legend"]}
        [:span {:class ["usage-legend-line"]} (if daily? "this week" "this window")]
        (when projection [:span {:class ["usage-legend-dotted"]} "at this pace"])]])))

(defn- past-strip
  "Peak use of each earlier period, as bars."
  [window samples]
  (let [bars (usage/past-windows window samples 60)]
    (when (seq bars)
      [:div {:class ["usage-strip"]}
       [:div {:class ["usage-strip-bars"]}
        (for [{:keys [start end peak]} bars]
          [:div {:class ["usage-strip-bar" (str "claude-usage--" (usage/severity peak))]
                 :title (str (fmt-date-time start) " – " (fmt-time end) ": " peak "%")}
           [:div {:class ["usage-strip-fill"] :style {:height (str peak "%")}}]])]
       [:div {:class ["usage-chart-legend"]}
        (str "Last " (count bars) " windows · peak use")]])))

(defn- window-section
  [reading-id history now {:keys [id label used active? breakdown period-ms] :as window}]
  (let [samples (get-in history [reading-id id] [])
        stats   (usage/window-stats window samples now)
        over?   (and (iso->ms (:resets-at window)) (<= (iso->ms (:resets-at window)) now))]
    [:section {:class ["usage-window"] :replicant/key id}
     [:div {:class ["usage-window-head"]}
      [:span {:class ["usage-window-label"]} label]
      (when active? (badge/badge {:variant :outline :size :sm} "Active"))
      [:span {:class ["usage-window-pct"]}
       (if over? "stale" (str (or used 0) "% used"))]]
     (meter (cond-> window over? (assoc :severity "unknown")))
     (when-let [n (resets-note window stats)]
       [:div {:class ["usage-note"]} n])
     (when-let [p (pace-note stats)]
       [:div {:class ["usage-note" "usage-note--pace"]} p])
     (when (seq breakdown)
       [:div {:class ["usage-note"]}
        (str/join " · " (for [{:keys [label percent]} breakdown] (str label " " percent "%")))])
     (if (and period-ms (> period-ms usage/day-ms))
       (line-chart window samples now)
       (past-strip window samples))]))

(defn- balance-row [{:keys [label value used-pct resets-at]}]
  [:div {:class ["usage-balance"] :replicant/key label}
   [:div {:class ["usage-balance-row"]}
    [:span {:class ["usage-window-label"]} label]
    [:span {:class ["usage-balance-value"]} value]]
   (when used-pct
     (meter {:used used-pct}))
   (when-let [end (iso->ms resets-at)]
     [:div {:class ["usage-note"]} (str "Resets " (fmt-date end))])])

(defn- tone-variant [tone]
  (case tone :danger :danger :warning :warning :success :success :outline))

(defn- reading-card
  [history now {:keys [id title subtitle badges windows balances notes renews-at]}]
  (card/card {:class "usage-card" :attrs {:replicant/key id}}
    (card/card-header {:class "usage-card-head"}
      [:div {:class ["usage-card-titles"]}
       [:div {:class ["usage-card-title"]} title]
       (when subtitle [:div {:class ["usage-card-subtitle"]} subtitle])]
      (when (seq badges)
        [:div {:class ["usage-card-badges"]}
         (for [{:keys [label tone]} badges]
           (badge/badge {:variant (tone-variant tone) :size :sm
                         :attrs {:replicant/key label}}
                        label))]))
    (card/card-body {:class "usage-card-body"}
      (for [w windows] (window-section id history now w))
      (for [b balances] (balance-row b))
      (for [n notes] [:div {:class ["usage-note"] :replicant/key n} n])
      (when renews-at
        [:div {:class ["usage-note"]} (str "Sign-in renews by " (fmt-date-time renews-at))]))))

;; ── Page ─────────────────────────────────────────────────────────────────────

(defn- usage-page [state dispatch!]
  (let [{:keys [readings history fetched-at loading?]} (:web/usage state)
        now (js/Date.now)]
    [:div {:class ["container"] :replicant/key "usage"}
     [:div {:class ["topbar"]}
      (views/menu-button dispatch!)
      [:div {:class ["topbar-title"]} "Usage"]
      (when (and fetched-at (pos? fetched-at))
        [:span {:class ["usage-updated"]} (str "Updated " (ago fetched-at now))])
      [:button {:class ["icon-btn"]
                :title "Refresh"
                :disabled (when loading? true)
                :on {:click (fn [_] (dispatch! {:type :usage/refresh-page}))}}
       (icon/icon {:icon-name :refresh :size :md})]
      (views/overflow-menu dispatch! state)]
     [:div {:class ["home"]}
      (cond
        (and loading? (empty? readings))
        (empty-state/empty-state {} (views/spinner) [:p "Loading usage…"])

        (empty? readings)
        (empty-state/empty-state {}
          [:p "No usage to show."]
          [:p {:class ["usage-note"]}
           "Sign in to Claude Code, log in with the Codex CLI, or set OLLAMA_API_KEY / OPENCODE_API_KEY; extensions can add accounts too."])

        :else
        [:div {:class ["usage-grid"]}
         (for [r readings] (reading-card history now r))])]]))

;; ── Dashboard card ───────────────────────────────────────────────────────────

(defn- card-window
  [reading-id history now {:keys [id label used] :as window}]
  (let [stats (usage/window-stats window (get-in history [reading-id id] []) now)]
    [:div {:class ["usage-dash-window"] :replicant/key id}
     [:div {:class ["usage-window-head"]}
      [:span {:class ["usage-window-label"]} label]
      [:span {:class ["usage-window-pct"]} (str (or used 0) "%")]]
     (meter window)
     (when-let [n (resets-note window stats)]
       [:div {:class ["usage-note"]} n])]))

(defn- usage-card
  "Every reading's windows as meters; the /usage page has the charts."
  [state _dispatch!]
  (let [{:keys [readings history]} (:web/usage state)
        now (js/Date.now)]
    [:div {:class ["usage-dash"]}
     (for [{:keys [id title subtitle windows balances]} readings
           :when (or (seq windows) (seq balances))]
       [:div {:class ["usage-dash-reading"] :replicant/key id}
        [:div {:class ["usage-dash-title"]}
         [:span {:class ["usage-card-title"]} title]
         (when subtitle [:span {:class ["usage-card-subtitle"]} subtitle])]
        (for [w windows] (card-window id history now w))
        (for [b balances] (balance-row b))])]))

;; ── Extension ────────────────────────────────────────────────────────────────

(def extension
  {:id       :usage
   :handlers {:usage/fetch        fetch
              :usage/refresh-page refresh
              :usage/state        received
              :route/navigate     on-navigate}
   :routes   {"usage" {:parse (fn [_] {:page :usage})
                       :path  {:usage (fn [_] "/usage")}}}
   :pages    {:usage usage-page}
   :nav-items [{:menu :palette :label "Usage" :icon :zap
                :event {:type :route/navigate :page :usage}}
               {:menu :overflow :label "Usage" :icon :zap
                :event {:type :route/navigate :page :usage}}]
   :taps     [refetch-on-poll-tap]
   :dashboard-cards
   [{:id          :usage/subscriptions
     :title       "Usage"
     :icon        :zap
     :description "Subscription windows and balances"
     :order       40
     :load        {:type :usage/fetch}
     :when        (fn [st] (seq (get-in st [:web/usage :readings])))
     :render      usage-card
     :more        {:label "Details" :event {:type :route/navigate :page :usage}}}]})
