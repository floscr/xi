(ns xi.error-info
  "Turn a raw agent error (the `:error` payload of a timeline entry) into a
   user-facing description: what happened in plain words, when it is back, and
   the usage windows involved. Pure — no rendering, no clock; callers pass `now`
   where relative time is needed. Unrecognised errors return nil so the caller
   falls back to the raw message.

   Shape: {:kind :title :subtitle :resets-at :windows [{:label :pct}] :raw}
   :resets-at is epoch seconds; :raw is the original error as a string.")

(def ^:private window-labels
  {"five_hour" "5-hour window"
   "seven_day" "Weekly"})

(def ^:private limit-titles
  {"five_hour" "Session limit reached"
   "seven_day" "Weekly limit reached"})

(defn- windows
  "`:unifiedWindows` ({:five_hour {:utilization 0-1}}) → ordered
   [{:label :pct}], 5-hour first."
  [unified]
  (->> ["five_hour" "seven_day"]
       (keep (fn [id]
               (when-let [u (get-in unified [(keyword id) :utilization])]
                 {:label (window-labels id)
                  :pct   (-> u (* 100) Math/round)})))
       vec))

(defn- rate-limit [error]
  (let [info (:info error)
        kind (:rateLimitType info)]
    {:kind      :rate-limit
     :title     (get limit-titles kind "Usage limit reached")
     :subtitle  "Claude is paused"
     :resets-at (:resetsAt info)
     :windows   (windows (:unifiedWindows info))}))

(def ^:private message-patterns
  "[regex kind title subtitle], first match wins. Matched against the error
   message; only for failures a user can act on."
  [[#"(?i)session limit|usage limit|hit your .*limit"
    :rate-limit "Usage limit reached" "Claude is paused"]
   [#"(?i)authentication failed|login has expired|not allowed to use the OAuth"
    :auth "You're signed out"
    "Run `claude /login` on the host, or set ANTHROPIC_API_KEY."]
   [#"(?i)requires usage credits"
    :billing "Usage credits required"
    "This model needs usage credits. Switch to another model, or add credits."]
   [#"(?i)billing"
    :billing "Billing problem" "Check the account's plan or credit balance."]
   [#"(?i)overloaded|\b529\b"
    :overloaded "Claude is overloaded" "Try again in a moment."]
   [#"(?i)ECONNRESET|ENOTFOUND|ETIMEDOUT|fetch failed|network"
    :network "Connection lost" "Your message may not have been sent."]])

(defn- from-message [msg]
  (some (fn [[re kind title subtitle]]
          (when (re-find re msg)
            {:kind kind :title title :subtitle subtitle}))
        message-patterns))

(defn describe
  "Friendly description of `error` (a map with :type/:message, or anything
   `pr-str`able), or nil when it isn't a recognised failure."
  [error]
  (let [raw (or (:message error) (pr-str error))
        d   (if (= "rate_limit" (:type error))
              (rate-limit error)
              (from-message (str raw)))]
    (when d (assoc d :raw (str raw)))))

(defn minutes-until
  "Whole minutes (rounded up) from `now-ms` until `resets-at` (epoch seconds);
   nil when unknown or already past."
  [resets-at now-ms]
  (when resets-at
    (let [ms (- (* 1000 resets-at) now-ms)]
      (when (pos? ms)
        (long (Math/ceil (/ ms 60000)))))))

(defn format-minutes
  "54 → \"54 min\", 125 → \"2 h 5 min\", 120 → \"2 h\"."
  [n]
  (if (< n 60)
    (str n " min")
    (let [h (quot n 60) m (rem n 60)]
      (str h " h" (when (pos? m) (str " " m " min"))))))
