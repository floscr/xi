(ns xi.usage-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.usage :as usage]))

(def now (js/Date.parse "2026-10-09T10:30:00Z"))

;; Trimmed from a real /api/oauth/usage response.
(def claude-response
  {:five_hour {:utilization 81.0 :resets_at "2026-10-09T12:29:59.555872+00:00"}
   :seven_day {:utilization 33.0 :resets_at "2026-10-15T07:59:59.555892+00:00"}
   :extra_usage {:is_enabled false}
   :limits [{:kind "session" :group "session" :percent 81 :severity "warning"
             :resets_at "2026-10-09T12:29:59.555872+00:00" :scope nil :is_active true}
            {:kind "weekly_all" :group "weekly" :percent 33 :severity "normal"
             :resets_at "2026-10-15T07:59:59.555892+00:00" :scope nil :is_active false}
            {:kind "weekly_scoped" :group "weekly" :percent 62 :severity "normal"
             :resets_at "2026-10-15T07:59:59.556030+00:00"
             :scope {:model {:id nil :display_name "Fable"} :surface nil} :is_active false}]
   :seven_day_breakdown {:rows [{:key "claude_code" :display_name "Claude Code" :percent 100}
                                {:key "chat" :display_name "Chats" :percent 0}]}})

;; ── Claude ──

(deftest claude-reading-windows
  (let [r (usage/claude-reading {:response claude-response :email "me@example.com"
                                 :plan "max" :tier "default_claude_max_20x"
                                 :expires-at 1791560018620 :now now})]
    (is (= "claude/me@example.com" (:id r)))
    (is (= :claude (:provider r)))
    (is (= "Claude · Max 20x" (:subtitle r)))
    (is (= ["five-hour" "seven-day" "seven-day-fable"] (map :id (:windows r))))
    (is (= ["5-hour" "7-day" "7-day Fable"] (map :label (:windows r))))
    (is (= [81 33 62] (map :used (:windows r))))
    (testing "only the session window is active and carries its severity"
      (is (true? (:active? (first (:windows r)))))
      (is (nil? (:active? (second (:windows r)))))
      (is (= "warning" (:severity (first (:windows r))))))
    (testing "the weekly breakdown rides on the 7-day window"
      (is (= [{:label "Claude Code" :percent 100} {:label "Chats" :percent 0}]
             (:breakdown (second (:windows r))))))
    (is (= 1791560018620 (:renews-at r)))
    (is (nil? (:badges r)))
    (is (nil? (:balances r)))))

(deftest claude-reading-quota-used
  (let [resp (assoc-in claude-response [:limits 0 :percent] 100)
        r    (usage/claude-reading {:response resp :email "a@b" :now now})]
    (is (= [{:label "Quota used" :tone :danger}] (:badges r)))))

(deftest claude-reading-without-limits
  (let [r (usage/claude-reading {:response (dissoc claude-response :limits) :now now})]
    (is (= ["five-hour" "seven-day"] (map :id (:windows r))))
    (is (= [81 33] (map :used (:windows r))))
    (is (= "Claude login" (:title r)))))

(deftest claude-reading-empty
  (is (nil? (usage/claude-reading {:response {} :now now}))))

(deftest claude-plan-label-test
  (is (= "Max 20x" (usage/claude-plan-label "max" "default_claude_max_20x")))
  (is (= "Pro" (usage/claude-plan-label "pro" "default_claude_pro")))
  (is (nil? (usage/claude-plan-label nil "x"))))

(deftest claude-summary-test
  (let [r (usage/claude-reading {:response claude-response :now now})]
    (is (= {:session 81 :weekly 33 :severity "warning"
            :session-resets-at "2026-10-09T12:29:59.555872+00:00"
            :weekly-resets-at "2026-10-15T07:59:59.555892+00:00"}
           (usage/claude-summary r)))))

;; ── Codex ──

(deftest codex-reading-test
  (let [resp {:email "o@example.com" :plan_type "pro"
              :rate_limit {:allowed true :limit_reached false
                           :primary_window {:used_percent 5 :limit_window_seconds 18000
                                            :reset_at 1791545400}
                           :secondary_window {:used_percent 3 :limit_window_seconds 604800
                                              :reset_after_seconds 500000}}
              :code_review_rate_limit {:primary_window {:used_percent 0 :limit_window_seconds 604800}}
              :additional_rate_limits [{:limit_name "GPT-5.3-Codex-Spark"
                                        :rate_limit {:primary_window {:used_percent 12 :limit_window_seconds 18000}}}]
              :credits {:has_credits true :unlimited false :balance 553}}
        r (usage/codex-reading {:response resp :now now})]
    (is (= "codex/o@example.com" (:id r)))
    (is (= "OpenAI · Pro" (:subtitle r)))
    (is (= ["5-hour" "7-day" "Code review 7-day" "GPT-5.3-Codex-Spark 5-hour"]
           (map :label (:windows r))))
    (is (= [5 3 0 12] (map :used (:windows r))))
    (is (= "2026-10-09T11:30:00.000Z" (:resets-at (first (:windows r)))))
    (is (= (.toISOString (js/Date. (+ now 500000000))) (:resets-at (second (:windows r)))))
    (is (= 18000000 (:period-ms (first (:windows r)))))
    (is (= [{:label "Credits" :value "553"}] (:balances r)))
    (is (nil? (:badges r)))))

(deftest codex-reading-limit-reached
  (let [r (usage/codex-reading {:response {:rate_limit {:limit_reached true
                                                        :primary_window {:used_percent 100}}}
                                :now now})]
    (is (= [{:label "Limit reached" :tone :danger}] (:badges r)))
    (is (= "ChatGPT login" (:title r)))))

;; ── Ollama ──

(deftest ollama-reading-test
  (let [r (usage/ollama-reading
           {:balance {:included {:balance_usd 72.5 :allowance_usd 100
                                 :period {:from "2026-09-15T09:30:00Z" :until "2026-10-15T09:30:00Z"}}
                      :purchased {:balance_usd 25}}
            :usage {:totals {:request_count 15 :usage_usd 0.01718}}
            :now now})]
    (is (= "ollama/cloud" (:id r)))
    (is (= [] (:windows r)))
    (is (= [{:label "Included remaining" :value "$72.50 of $100.00" :used-pct 28
             :resets-at "2026-10-15T09:30:00Z"}
            {:label "Purchased" :value "$25.00"}]
           (:balances r)))
    (is (= ["Last 30 days: $0.02 · 15 requests"] (:notes r)))))

(deftest ollama-reading-legacy
  (let [r (usage/ollama-reading
           {:balance {:included {:session {:remaining_percent 75 :resets_at "2026-10-01T07:00:00Z"}
                                 :weekly {:remaining_percent 40 :resets_at "2026-10-05T00:00:00Z"}}
                      :purchased {:balance_usd 0}}
            :now now})]
    (is (= [25 60] (map :used (:windows r))))
    (is (= [{:label "Purchased" :value "$0.00"}] (:balances r)))))

(deftest ollama-reading-empty
  (is (nil? (usage/ollama-reading {:balance {} :now now}))))

;; ── OpenCode ──

(deftest opencode-reading-test
  (let [r (usage/opencode-reading
           {:response {:usage {:rolling {:percent 10 :resetsAt "2026-10-09T12:00:00Z"}
                               :weekly {:percent 40 :resetsAt "2026-10-12T00:00:00Z" :status "ok"}
                               :monthly {:percent 70 :status "disabled"}}}
            :now now})]
    (is (= ["5-hour" "7-day"] (map :label (:windows r))))
    (is (= [10 40] (map :used (:windows r)))))
  (is (nil? (usage/opencode-reading {:response {} :now now}))))

;; ── History ──

(def reading {:id "r" :windows [{:id "w" :used 10} {:id "v" :used nil}]})

(deftest record-appends-and-dedupes
  (let [h1 (usage/record {} [reading] now)]
    (is (= {"r" {"w" [[now 10]]}} h1) "a window without a value is skipped")
    (testing "same value shortly after: no new sample"
      (is (= h1 (usage/record h1 [reading] (+ now 60000)))))
    (testing "same value after the interval: sampled again"
      (is (= [[now 10] [(+ now usage/sample-interval-ms) 10]]
             (get-in (usage/record h1 [reading] (+ now usage/sample-interval-ms)) ["r" "w"]))))
    (testing "a changed value is sampled at once"
      (is (= [[now 10] [(+ now 1000) 11]]
             (get-in (usage/record h1 [(assoc-in reading [:windows 0 :used] 11)] (+ now 1000))
                     ["r" "w"]))))))

(deftest prune-drops-old-samples
  (let [old (- now usage/history-max-age-ms 1)
        h   {"r" {"w" [[old 5] [now 10]] "x" [[old 1]]}
             "gone" {"w" [[old 2]]}}]
    (is (= {"r" {"w" [[now 10]]}} (usage/prune h now)))))

;; ── Pace ──

(def five-hour
  {:id "five-hour" :label "5-hour" :used 14 :period-ms usage/five-hours-ms
   ;; 2h 11m into the window
   :resets-at (.toISOString (js/Date. (+ now (* 1000 60 (+ 48 120)))))})

(deftest window-span-test
  (is (= {:start (- (+ now (* 1000 60 168)) usage/five-hours-ms)
          :end (+ now (* 1000 60 168))}
         (usage/window-span five-hour)))
  (is (nil? (usage/window-span {:resets-at "nope" :period-ms 1})))
  (is (nil? (usage/window-span {:resets-at "2026-10-09T12:00:00Z"}))))

(deftest window-stats-five-hour
  (let [s (usage/window-stats five-hour [] now)]
    (is (= :hour (:unit s)))
    (is (= (* 1000 60 168) (:remaining-ms s)))
    (is (= 32 (:projected s)) "14% after 2h11m of 5h is on pace for 32%")
    (is (< 6.3 (:rate s) 6.5))
    (is (nil? (:recent s)) "no sample an hour back: unknown")
    (is (nil? (:day s)))))

(deftest window-stats-recent
  (let [samples [[(- now (* 2 usage/hour-ms)) 4] [(- now (* 50 60 1000)) 10]]
        s (usage/window-stats five-hour samples now)]
    (is (= 10 (:recent s)) "14 now minus 4 an hour ago")))

(deftest window-stats-weekly
  (let [w {:id "seven-day" :used 11 :period-ms usage/week-ms
           :resets-at (.toISOString (js/Date. (+ now (* 6 usage/day-ms) (* 1 usage/hour-ms))))}
        s (usage/window-stats w [] now)]
    (is (= :day (:unit s)))
    (is (= 1 (:day s)))
    (is (= 7 (:days s)))
    (is (= 80 (:projected s)) "11% after 23h of 7d")
    (is (= 11 (:recent s)) "less than a day in: everything used is recent")))

(deftest window-stats-over-or-unknown
  (is (nil? (usage/window-stats (assoc five-hour :resets-at (.toISOString (js/Date. (- now 1)))) [] now)))
  (is (nil? (usage/window-stats (dissoc five-hour :period-ms) [] now)))
  (testing "a window that just started has no pace yet"
    (let [w (assoc five-hour :resets-at (.toISOString (js/Date. (+ now usage/five-hours-ms -1000))))]
      (is (nil? (:projected (usage/window-stats w [] now)))))))

(deftest format-duration-test
  (is (= "now" (usage/format-duration 0)))
  (is (= "12m" (usage/format-duration (* 12 60 1000))))
  (is (= "2h 48m" (usage/format-duration (* 168 60 1000))))
  (is (= "3h" (usage/format-duration (* 3 usage/hour-ms))))
  (is (= "5d 3h" (usage/format-duration (+ (* 5 usage/day-ms) (* 3 usage/hour-ms))))))

;; ── Charts ──

(deftest chart-points-and-projection
  (let [{:keys [start]} (usage/window-span five-hour)
        samples [[(- start 1000) 90]      ; previous window, ignored
                 [(+ start usage/hour-ms) 5]
                 [(+ start (* 2 usage/hour-ms)) 12]]
        c (usage/chart five-hour samples now)]
    (is (= [[0 90] [0.2 5] [0.4 12]] (take 3 (:points c)))
        "starts at the value in effect at the window start, then the samples")
    (is (= [(/ (- now start) usage/five-hours-ms) 14] (peek (:points c))) "ends at the live value")
    (is (= 6 (count (:ticks c))) "one per hour, both ends included")
    (is (= [1 32] (second (:projection c))))))

(deftest chart-without-span
  (is (nil? (usage/chart {:used 3} [] now))))

(deftest past-windows-test
  (let [{:keys [start]} (usage/window-span five-hour)
        p usage/five-hours-ms
        samples [[(- start p 1000) 50] [(- start p 2000) 70]  ; two windows back
                 [(- start 1000) 20]]                          ; one window back
        bars (usage/past-windows five-hour samples 5)]
    (is (= [70 20] (map :peak bars)) "oldest first, peak per window")
    (is (= [(- start p) start] (map :end bars)))))
