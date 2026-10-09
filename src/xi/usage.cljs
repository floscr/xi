(ns xi.usage
  "Subscription usage readings, provider-agnostic and pure (no clock, no I/O):
   the shape the /usage page renders, the parsers that turn each provider's
   payload into it, the sample history behind the charts, and the pace maths.
   xi.server.usage fetches and stores; xi.web.usage draws.

   A reading is one card:
     {:id         \"claude/me@example.com\"   ; stable across fetches
      :provider   :claude                      ; :claude :codex :ollama :opencode …
      :title      \"me@example.com\"
      :subtitle   \"Claude · Max 20x\"
      :badges     [{:label \"Quota used\" :tone :danger}]        ; optional
      :windows    [{:id \"five-hour\" :label \"5-hour\" :used 81  ; percent
                    :resets-at \"2026-…Z\" :period-ms 18000000
                    :severity \"warning\" :active? true
                    :breakdown [{:label \"Claude Code\" :percent 100}]}]
      :balances   [{:label \"Included remaining\" :value \"$72.50 of $100.00\"
                    :used-pct 28 :resets-at \"2026-…Z\"}]        ; optional
      :notes      [\"Last 30 days: $0.02 · 15 requests\"]         ; optional
      :renews-at  1791560018620                                   ; optional, ms
      :fetched-at 1791541410145}

   History is {reading-id {window-id [[t used] …]}}, oldest first."
  (:require [clojure.string :as str]))

(def hour-ms (* 60 60 1000))
(def day-ms (* 24 hour-ms))
(def five-hours-ms (* 5 hour-ms))
(def week-ms (* 7 day-ms))

(defn- iso->ms
  [iso]
  (when (string? iso)
    (let [t (js/Date.parse iso)]
      (when-not (js/isNaN t) t))))

(defn- ms->iso
  [ms]
  (when (number? ms) (.toISOString (js/Date. ms))))

(defn pct
  "A percentage clamped to 0–100 and rounded, nil for a non-number."
  [n]
  (when (and (number? n) (not (js/isNaN n)))
    (-> n js/Math.round (max 0) (min 100))))

(defn severity
  "The severity class the sidebar ring uses, from a used percent."
  [used]
  (cond (nil? used) "normal"
        (>= used 100) "exceeded"
        (>= used 90) "critical"
        (>= used 75) "warning"
        :else "normal"))

(defn- slug
  [s]
  (-> (str s) str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" "")))

(defn- money
  [amount currency]
  (when (number? amount)
    (str (case currency ("USD" nil) "$" (str currency " ")) (.toFixed amount 2))))

;; ── Claude ────────────────────────────────────────────────────────────────────

(defn- claude-limit->window
  "One entry of the OAuth usage payload's `limits` as a window."
  [{:keys [kind percent severity resets_at scope is_active]}]
  (let [model (get-in scope [:model :display_name])]
    (cond-> (case kind
              "session"       {:id "five-hour" :label "5-hour" :period-ms five-hours-ms}
              "weekly_all"    {:id "seven-day" :label "7-day" :period-ms week-ms}
              "weekly_scoped" {:id (str "seven-day-" (slug (or model "model")))
                               :label (str "7-day " (or model "model")) :period-ms week-ms}
              {:id (slug kind) :label (str/replace (str kind) "_" " ")})
      true              (assoc :used (pct percent) :resets-at resets_at)
      severity          (assoc :severity severity)
      (true? is_active) (assoc :active? true))))

(defn- claude-fallback-windows
  "The two windows every payload carries, for one without a `limits` list."
  [{:keys [five_hour seven_day]}]
  (keep (fn [[id label period m]]
          (when (number? (:utilization m))
            {:id id :label label :period-ms period
             :used (pct (:utilization m)) :resets-at (:resets_at m)}))
        [["five-hour" "5-hour" five-hours-ms five_hour]
         ["seven-day" "7-day" week-ms seven_day]]))

(defn- claude-breakdown
  [{:keys [rows]}]
  (when (seq rows)
    (vec (for [{:keys [display_name percent]} rows
               :when (and display_name (number? percent))]
           {:label display_name :percent (pct percent)}))))

(defn- claude-extra-usage
  [{:keys [is_enabled utilization used_credits monthly_limit currency]}]
  (when (true? is_enabled)
    [{:label "Extra usage"
      :value (str (or (money used_credits currency) "$0.00")
                  (when-let [l (money monthly_limit currency)] (str " of " l)))
      :used-pct (pct utilization)}]))

(defn claude-plan-label
  "\"Max 20x\" from the credentials' subscriptionType and rateLimitTier."
  [plan tier]
  (let [mult (some->> tier (re-find #"_(\d+x)$") second)]
    (when plan
      (str (str/capitalize plan) (when mult (str " " mult))))))

(defn claude-reading
  "The reading of a Claude login from its /api/oauth/usage `response`
   (keywordized), nil when the payload carries no usage.
   `email`/`plan`/`tier` come from the login files, `expires-at` (ms) is the
   OAuth token's expiry, `now` the fetch time."
  [{:keys [response email plan tier expires-at now]}]
  (let [limits  (:limits response)
        windows (vec (if (seq limits)
                       (map claude-limit->window limits)
                       (claude-fallback-windows response)))
        windows (if-let [bd (claude-breakdown (:seven_day_breakdown response))]
                  (mapv #(cond-> % (= "seven-day" (:id %)) (assoc :breakdown bd)) windows)
                  windows)
        over?   (some #(and (:used %) (>= (:used %) 100)) windows)]
    (when (seq windows)
      (cond-> {:id         (str "claude/" (or email "login"))
               :provider   :claude
               :title      (or email "Claude login")
               :subtitle   (str "Claude" (when-let [p (claude-plan-label plan tier)] (str " · " p)))
               :windows    windows
               :fetched-at now}
        over?      (assoc :badges [{:label "Quota used" :tone :danger}])
        expires-at (assoc :renews-at expires-at)
        (claude-extra-usage (:extra_usage response))
        (assoc :balances (claude-extra-usage (:extra_usage response)))))))

(defn claude-summary
  "The sidebar ring's compact reading (`[:lobby :claude-usage]`) from a Claude
   reading: the 5-hour window as :session, the 7-day one as :weekly."
  [{:keys [windows]}]
  (let [by-id   (into {} (map (juxt :id identity)) windows)
        session (get by-id "five-hour")
        weekly  (get by-id "seven-day")]
    (when session
      {:session           (:used session)
       :weekly            (:used weekly)
       :severity          (or (:severity session) (severity (:used session)))
       :session-resets-at (:resets-at session)
       :weekly-resets-at  (:resets-at weekly)})))

;; ── Codex (ChatGPT subscription) ─────────────────────────────────────────────

(defn- codex-window
  [id label {:keys [used_percent reset_at reset_after_seconds limit_window_seconds]} now]
  (when (number? used_percent)
    (let [period (when (number? limit_window_seconds) (* 1000 limit_window_seconds))
          resets (cond (number? reset_at) (* 1000 (if (> reset_at 1e10) (/ reset_at 1000) reset_at))
                       (number? reset_after_seconds) (+ now (* 1000 reset_after_seconds)))
          label  (cond (nil? period) label
                       (>= period week-ms) "7-day"
                       (>= period (* 4 hour-ms)) "5-hour"
                       :else label)]
      (cond-> {:id id :label label :used (pct used_percent)}
        period (assoc :period-ms period)
        resets (assoc :resets-at (ms->iso resets))))))

(defn- codex-limit-windows
  [prefix label {:keys [primary_window secondary_window]} now]
  (keep identity
        [(codex-window (str prefix "-primary") label primary_window now)
         (codex-window (str prefix "-secondary") label secondary_window now)]))

(defn codex-reading
  "The reading of the Codex login from chatgpt.com's wham/usage `response`."
  [{:keys [response now]}]
  (let [{:keys [email plan_type rate_limit code_review_rate_limit additional_rate_limits credits]} response
        windows (-> []
                    (into (codex-limit-windows "codex" "Codex" rate_limit now))
                    (into (when code_review_rate_limit
                            (map #(update % :label (fn [l] (str "Code review " l)))
                                 (codex-limit-windows "code-review" "Code review" code_review_rate_limit now))))
                    (into (mapcat (fn [{:keys [limit_name metered_feature rate_limit]}]
                                    (let [n (or limit_name metered_feature "Additional")]
                                      (map #(update % :label (fn [l] (str n " " l)))
                                           (codex-limit-windows (slug n) n rate_limit now))))
                                  additional_rate_limits)))
        reached? (or (true? (:limit_reached rate_limit))
                     (some #(and (:used %) (>= (:used %) 100)) windows))
        balance  (:balance credits)]
    (when (seq windows)
      (cond-> {:id         (str "codex/" (or email "login"))
               :provider   :codex
               :title      (or email "ChatGPT login")
               :subtitle   (str "OpenAI" (when plan_type (str " · " (str/capitalize (str plan_type)))))
               :windows    windows
               :fetched-at now}
        reached? (assoc :badges [{:label "Limit reached" :tone :danger}])
        (and balance (not (true? (:unlimited credits))))
        (assoc :balances [{:label "Credits" :value (str balance)}])))))

;; ── Ollama Cloud ──────────────────────────────────────────────────────────────

(defn- ollama-legacy-windows
  [{:keys [session weekly]}]
  (keep (fn [[id label period m]]
          (when (number? (:remaining_percent m))
            {:id id :label label :period-ms period
             :used (pct (- 100 (:remaining_percent m))) :resets-at (:resets_at m)}))
        [["session" "Session" five-hours-ms session]
         ["weekly" "Weekly" week-ms weekly]]))

(defn ollama-reading
  "The reading of an Ollama Cloud key from ollama.com's /api/balance (and,
   when given, /api/usage?range=30d) responses."
  [{:keys [balance usage now]}]
  (let [{:keys [included purchased]} balance
        {:keys [balance_usd allowance_usd period]} included
        included-row (when (number? balance_usd)
                       (cond-> {:label "Included remaining"
                                :value (str (money balance_usd "USD")
                                            (when (number? allowance_usd)
                                              (str " of " (money allowance_usd "USD"))))}
                         (and (number? allowance_usd) (pos? allowance_usd))
                         (assoc :used-pct (pct (* 100 (/ (- allowance_usd balance_usd) allowance_usd))))
                         (:until period) (assoc :resets-at (:until period))))
        purchased-row (when (number? (:balance_usd purchased))
                        {:label "Purchased" :value (money (:balance_usd purchased) "USD")})
        totals (:totals usage)
        note   (when (number? (:request_count totals))
                 (str "Last 30 days: "
                      (or (money (:usage_usd totals) "USD") "–")
                      " · " (:request_count totals) " requests"))]
    (when (or included-row (seq (ollama-legacy-windows included)))
      (cond-> {:id        "ollama/cloud"
               :provider  :ollama
               :title     "Ollama Cloud"
               :subtitle  "ollama.com"
               :windows   (vec (ollama-legacy-windows included))
               :fetched-at now}
        (or included-row purchased-row)
        (assoc :balances (vec (keep identity [included-row purchased-row])))
        note (assoc :notes [note])))))

;; ── OpenCode Go ───────────────────────────────────────────────────────────────

(defn opencode-reading
  "The reading of an OpenCode Go key from opencode.ai's zen/go/v1/usage."
  [{:keys [response now]}]
  (let [usage   (:usage response)
        windows (keep (fn [[k id label period]]
                        (let [m (get usage k)]
                          (when (and (map? m) (number? (:percent m))
                                     (contains? #{nil "ok"} (:status m)))
                            {:id id :label label :period-ms period
                             :used (pct (:percent m)) :resets-at (:resetsAt m)})))
                      [[:rolling "rolling" "5-hour" five-hours-ms]
                       [:weekly "weekly" "7-day" week-ms]
                       [:monthly "monthly" "Monthly" (* 30 day-ms)]])]
    (when (seq windows)
      {:id        "opencode/go"
       :provider  :opencode
       :title     "OpenCode Go"
       :subtitle  "opencode.ai"
       :windows   (vec windows)
       :fetched-at now})))

;; ── History ───────────────────────────────────────────────────────────────────

(def sample-interval-ms
  "An unchanged value is re-sampled this often, so a flat line still has points."
  (* 15 60 1000))

(def history-max-age-ms (* 14 day-ms))

(def ^:private max-samples-per-window 3000)

(defn record
  "`history` with a sample per window of `readings` appended at `now`, unless
   the last sample is both recent and equal."
  [history readings now]
  (reduce (fn [h {:keys [id windows]}]
            (reduce (fn [h {wid :id used :used}]
                      (if (nil? used)
                        h
                        (let [samples (get-in h [id wid] [])
                              [t v]   (peek samples)]
                          (if (and t (= v used) (< (- now t) sample-interval-ms))
                            h
                            (assoc-in h [id wid]
                                      (let [s (conj samples [now used])]
                                        (if (> (count s) max-samples-per-window)
                                          (subvec s (- (count s) max-samples-per-window))
                                          s)))))))
                    h windows))
          (or history {}) readings))

(defn prune
  "`history` without samples older than `history-max-age-ms` and without the
   windows / readings that are left empty."
  [history now]
  (let [cutoff (- now history-max-age-ms)]
    (into {}
          (keep (fn [[id windows]]
                  (let [ws (into {}
                                 (keep (fn [[wid samples]]
                                         (let [s (filterv #(>= (first %) cutoff) samples)]
                                           (when (seq s) [wid s]))))
                                 windows)]
                    (when (seq ws) [id ws]))))
          history)))

;; ── Pace ──────────────────────────────────────────────────────────────────────

(defn window-span
  "{:start :end} in ms of a window that knows when it resets and how long it
   lasts, nil otherwise."
  [{:keys [resets-at period-ms]}]
  (when-let [end (iso->ms resets-at)]
    (when (number? period-ms)
      {:start (- end period-ms) :end end})))

(defn- value-at
  "The sampled value in effect at `t` (the last sample at or before it), nil
   when there is none."
  [samples t]
  (->> samples (take-while #(<= (first %) t)) last second))

(defn window-stats
  "Pace figures of `window` at `now`, from its `samples`:
     :elapsed-ms :fraction (0–1 of the period) :remaining-ms
     :unit (:hour for a period up to a day, else :day) :rate (percent per unit)
     :projected (percent at reset at this rate) :recent (percent used in the
     last unit) :day / :days (\"day 3 of 7\", periods over a day).
   nil when the window's span is unknown or over."
  [{:keys [used period-ms] :as window} samples now]
  (when-let [{:keys [start end]} (window-span window)]
    (when (and used (> end now))
      (let [elapsed  (max 0 (- now start))
            fraction (min 1 (/ elapsed period-ms))
            unit-ms  (if (> period-ms day-ms) day-ms hour-ms)
            units    (/ elapsed unit-ms)
            in-win   (filter #(>= (first %) start) samples)
            before   (value-at in-win (- now unit-ms))
            recent   (cond before (max 0 (- used before))
                           (< elapsed unit-ms) used)]
        (cond-> {:elapsed-ms   elapsed
                 :remaining-ms (- end now)
                 :fraction     fraction
                 :unit         (if (= unit-ms day-ms) :day :hour)}
          (>= fraction 0.02) (assoc :rate (/ used units)
                                    :projected (min 100 (js/Math.round (/ used fraction))))
          recent (assoc :recent recent)
          (> period-ms day-ms) (assoc :day (inc (js/Math.floor (/ elapsed day-ms)))
                                      :days (js/Math.round (/ period-ms day-ms))))))))

(defn format-duration
  "\"2h 48m\", \"5d 3h\", \"12m\", \"now\" from a span in ms."
  [ms]
  (let [m (max 0 (js/Math.round (/ ms 60000)))
        d (quot m 1440) h (quot (mod m 1440) 60) mm (mod m 60)]
    (cond (zero? m) "now"
          (pos? d)  (str d "d" (when (pos? h) (str " " h "h")))
          (pos? h)  (str h "h" (when (pos? mm) (str " " mm "m")))
          :else     (str mm "m"))))

;; ── Charts ────────────────────────────────────────────────────────────────────

(defn chart
  "The current period of `window` as a step line: :points [[x y] …] with x the
   fraction of the period (0–1) and y the percent, from the samples inside
   the span plus the live value at `now`; :projection [[x y] [1 projected]]
   when there is a pace; :ticks [{:x :at}] one per day (periods over a day)
   or per hour, `at` in ms for the view to label. nil without a span."
  [{:keys [used] :as window} samples now]
  (when-let [{:keys [start end]} (window-span window)]
    (let [period (- end start)
          x-of   (fn [t] (/ (- t start) period))
          in-win (filter #(let [t (first %)] (and (>= t start) (<= t now))) samples)
          points (cond-> (mapv (fn [[t v]] [(x-of t) v]) in-win)
                   (and used (<= now end)) (conj [(min 1 (x-of now)) used]))
          points (if (and (seq points) (pos? (ffirst points)))
                   (into [[0 (or (value-at samples start) 0)]] points)
                   points)
          stats  (window-stats window samples now)
          step   (if (> period day-ms) day-ms hour-ms)
          ticks  (vec (for [t (range start (inc end) step)]
                        {:x (x-of t) :at t}))]
      (cond-> {:points points :ticks ticks}
        (:projected stats) (assoc :projection [(peek points) [1 (:projected stats)]])))))

(defn past-windows
  "Peak percent of each earlier period of `window`, oldest first, aligned to
   its reset time: [{:start :end :peak}] for those of the last `n` periods
   that have samples. The 5-hour strip (\"last 14 days · 12 windows\")."
  [window samples n]
  (when-let [{:keys [end]} (window-span window)]
    (let [period (:period-ms window)]
      (->> (range 1 (inc n))
           (keep (fn [k]
                   (let [e (- end (* k period)) s (- e period)
                         in (filter #(let [t (first %)] (and (>= t s) (< t e))) samples)]
                     (when (seq in)
                       {:start s :end e :peak (apply max (map second in))}))))
           reverse
           vec))))
