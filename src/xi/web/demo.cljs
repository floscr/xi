(ns xi.web.demo
  "Fabricated data for README screenshots.

   When the web client is loaded with a `?demo=<view>` query param,
   xi.web.core renders a static, fully-populated view backed by the data
   here instead of connecting to a server. This keeps the screenshots free
   of real sessions and reproducible.

   Views:
     ?demo=sessions    → the session list
     ?demo=chat        → a chat room mid-conversation (the configured appearance)
     ?demo=chat-viewer → the same room forced into viewer mode (grouped,
                         collapsed tool + thinking rows)
     ?demo=chat-open   → the same room with every block expanded, ungrouped
     ?demo=usage       → the /usage page: Claude logins, Codex, Ollama Cloud"
  (:require [xi.core.state :as state]
            [xi.usage :as usage]))

(def ^:private now (js/Date.now))

(defn- mins-ago [m] (- now (* m 60000)))

;; ── Session list ─────────────────────────────────────────────────────────────

(def ^:private demo-sessions
  [{:session-id "demo-dark-mode"
    :name "Add dark mode toggle"
    :cwd "/home/dev/acme-web"
    :last-accessed (mins-ago 2)
    :favorite? true}
   {:session-id "demo-auth-tests"
    :name "Fix flaky auth integration tests"
    :cwd "/home/dev/acme-api"
    :last-accessed (mins-ago 47)}
   {:session-id "demo-perf"
    :name "Profile slow dashboard query"
    :cwd "/home/dev/acme-api"
    :last-accessed (mins-ago 180)}
   {:session-id "demo-webhooks"
    :name "Add Stripe webhook handler"
    :cwd "/home/dev/acme-billing"
    :last-accessed (mins-ago 1440)
    :favorite? true}
   {:session-id "demo-refactor"
    :name "Refactor onboarding flow"
    :cwd "/home/dev/acme-web"
    :last-accessed (mins-ago 2880)}
   {:session-id "demo-cli"
    :name "Ship `acme deploy` subcommand"
    :cwd "/home/dev/acme-cli"
    :last-accessed (mins-ago 5760)}])

;; ── Chat history ─────────────────────────────────────────────────────────────

(def ^:private read-result
  "export function Settings({ user }: SettingsProps) {
  const { theme } = useTheme();
  return (
    <section className=\"settings\">
      <h1>Settings</h1>
      <ProfileForm user={user} />
      <NotificationPrefs />
    </section>
  );
}")

(def ^:private edit-result
  "  export function Settings({ user }: SettingsProps) {
-   const { theme } = useTheme();
+   const { theme, toggleTheme } = useTheme();
    return (
      <section className=\"settings\">
        <h1>Settings</h1>
+       <ThemeToggle checked={theme === \"dark\"} onChange={toggleTheme} />
        <ProfileForm user={user} />
        <NotificationPrefs />
      </section>
    );
  }")

(def ^:private test-result
  " PASS  src/components/ThemeToggle.test.tsx
  ThemeToggle
    ✓ renders in the light state (18 ms)
    ✓ toggles to dark on click (7 ms)
    ✓ persists the choice to localStorage (5 ms)

Test Suites: 1 passed, 1 total
Tests:       3 passed, 3 total
Time:        1.284 s")

(def ^:private clj-vector-result
  "=> [\"cli.cljs\" \"auth.cljs\" \"agent.cljs\"]")

(def ^:private clj-stdout-result
  "scanning src…\n=> 63")

(def ^:private clj-error-result
  "Error: clj: reading credential paths is blocked: /home/dev/.config/xi/clients.edn (line 2:4)")

(def ^:private clj-shell-result
  "=> On branch master\nYour branch is up to date with 'origin/master'.\n\nnothing to commit, working tree clean")

(def ^:private final-text
  "Done — the settings page now has a dark mode toggle.

**What changed**

- `useTheme()` now also returns `toggleTheme`, wired to the existing `ThemeContext`.
- Added a `<ThemeToggle>` control to the settings section.
- The choice persists to `localStorage` so it survives reloads.

All three tests pass. Want me to add a keyboard shortcut (`⌘⇧D`) for it too?")

(def ^:private demo-history
  [{:kind :user
    :text "Add a dark mode toggle to the settings page. It should persist the user's choice."}
   {:kind :thinking
    :text "The app already has a ThemeContext, so the toggle just needs to flip the mode and persist it. Let me look at how the settings page and the theme hook are wired before changing anything."
    :done? true}
   {:kind :text
    :text "I'll start by reading the current settings page to see how theming is set up."
    :done? true}
   {:kind :tool-call
    :id "t1"
    :tool "Read"
    :arguments {:file_path "src/components/Settings.tsx"}
    :result read-result
    :is-error false
    :status :done}
   {:kind :text
    :text "The page pulls `theme` from `useTheme()` but never lets the user change it. I'll expose `toggleTheme` from the hook and drop a `ThemeToggle` into the settings section."
    :done? true}
   {:kind :tool-call
    :id "t2"
    :tool "Edit"
    :arguments {:file_path "src/components/Settings.tsx"}
    :result edit-result
    :is-error false
    :status :done}
   {:kind :thinking
    :text "The edit landed cleanly. Run the ThemeToggle tests to make sure the persistence path works."
    :done? true}
   {:kind :tool-call
    :id "t3"
    :tool "Bash"
    :arguments {:command "npm test -- ThemeToggle"}
    :result test-result
    :is-error false
    :status :done}
   {:kind :tool-call
    :id "t4"
    :tool "clj"
    :arguments {:code "(->> (glob \"src/**/*.cljs\")\n     (map basename)\n     (take 3))"}
    :result clj-vector-result
    :is-error false
    :status :done}
   {:kind :tool-call
    :id "t5"
    :tool "clj"
    :arguments {:code "(do (println \"scanning src…\")\n    (count (glob \"src/**/*.cljs\")))"}
    :result clj-stdout-result
    :is-error false
    :status :done}
   {:kind :tool-call
    :id "t6"
    :tool "clj"
    :arguments {:code "(let [f (str (env \"HOME\") \"/.config/xi/clients.edn\")]\n  [(stat f) (when (stat f) (cat f))])"}
    :result clj-error-result
    :is-error true
    :status :done}
   {:kind :tool-call
    :id "t7"
    :tool "clj"
    :arguments {:code "(sh \"git\" \"status\")"}
    :result clj-shell-result
    :is-error false
    :status :done}
   {:kind :text
    :text final-text
    :done? true}])

;; ── State assembly ───────────────────────────────────────────────────────────

(defn- base
  "A connected client state with the demo lobby, routed to `route`."
  [route]
  (assoc (state/initial-state {:mode :client})
         :web/connected? true
         :web/route route
         :lobby {:agent-id nil
                 :rooms []
                 :sessions demo-sessions}))

(defn- sessions-state []
  (assoc (base {:page :home :dir :all})
         :web/selected-project-dir :all))

(defn- chat-state []
  (let [rid  "demo-room"
        room (assoc (state/make-room rid {:provider :anthropic
                                          :model "claude-opus-4"
                                          :cwd "/home/dev/acme-web"
                                          :session {:id "demo-dark-mode"
                                                    :name "Add dark mode toggle"}})
                    :history demo-history)]
    (assoc (base {:page :chat :session-id "demo-dark-mode"})
           :rooms {rid room}
           :active-room rid)))

;; ── Usage page ──

(defn- iso-in [ms] (.toISOString (js/Date. (+ now ms))))

(defn- claude-payload
  "An /api/oauth/usage payload: `session` % with `session-left` ms to go,
   `weekly` % with `week-left` ms, `fable` % on the per-model window."
  [session session-left weekly week-left fable]
  {:five_hour {:utilization session :resets_at (iso-in session-left)}
   :seven_day {:utilization weekly :resets_at (iso-in week-left)}
   :limits [{:kind "session" :percent session :resets_at (iso-in session-left)
             :severity (usage/severity session) :is_active (< weekly 100)}
            {:kind "weekly_all" :percent weekly :resets_at (iso-in week-left)
             :severity (usage/severity weekly) :is_active (>= weekly 100)}
            {:kind "weekly_scoped" :percent fable :resets_at (iso-in week-left)
             :scope {:model {:display_name "Fable"}}}]
   :seven_day_breakdown {:rows [{:display_name "Claude Code" :percent 92}
                                {:display_name "Chats" :percent 8}]}})

(defn- ramp
  "Samples every 30 min from `from` ms ago to now, climbing to `to` %."
  [from to]
  (let [n (js/Math.floor (/ from 1800000))]
    (vec (for [i (range (inc n))]
           [(- now (* (- n i) 1800000)) (js/Math.round (* to (/ i (max n 1))))]))))

(defn- usage-state []
  (let [live   (usage/claude-reading
                {:response (claude-payload 14 (* 168 60000) 15 (+ (* 1 usage/day-ms) (* 3 usage/hour-ms)) 0)
                 :email "dev@example.com" :plan "max" :tier "default_claude_max_20x"
                 :expires-at (+ now (* 4 usage/hour-ms)) :now now})
        stored (-> (usage/claude-reading
                    {:response (claude-payload 0 (* 4 usage/hour-ms) 100 (* 21 usage/hour-ms) 0)
                     :email "team@example.com" :plan "max" :tier "default_claude_max_20x" :now now})
                   (update :badges conj {:label "Stored · team" :tone :outline}))
        codex  (usage/codex-reading
                {:response {:email "OpenAI" :plan_type "prolite"
                            :rate_limit {:primary_window {:used_percent 11 :limit_window_seconds 604800
                                                          :reset_after_seconds (* 6 86400)}}}
                 :now now})
        ollama (usage/ollama-reading
                {:balance {:included {:balance_usd 295.18 :allowance_usd 300
                                      :period {:until (iso-in (* 27 usage/day-ms))}}
                           :purchased {:balance_usd 0}}
                 :usage {:totals {:request_count 412 :usage_usd 4.82}}
                 :now now})
        past5h (vec (for [k (range 1 13)
                          :let [t (- now (* 168 60000) (* k usage/five-hours-ms))]]
                      [t (mod (* k 23) 90)]))]
    (assoc (base {:page :usage})
           :web/usage {:readings   [live stored codex ollama]
                       :fetched-at (- now 90000)
                       :history    {(:id live)   {"seven-day" (ramp (* 5 usage/day-ms) 15)
                                                  "five-hour" (conj past5h [(- now 3600000) 9])}
                                    (:id stored) {"seven-day" (ramp (* 6 usage/day-ms) 100)}
                                    (:id codex)  {"codex-primary" (ramp (* 20 usage/hour-ms) 11)}}})))

(defn demo-state
  "Build the seeded app state for a `?demo=<view>` value."
  [view]
  (case view
    "usage" (usage-state)
    "chat" (chat-state)
    "chat-viewer" (assoc (chat-state) :web/appearance {:super-collapsed? false
                                                       :tool-blocks :collapsed
                                                       :thinking-blocks :collapsed})
    "chat-super" (assoc (chat-state) :web/appearance {:super-collapsed? true
                                                       :tool-blocks :collapsed
                                                       :thinking-blocks :collapsed})
    "chat-open" (assoc (chat-state) :web/appearance {:super-collapsed? false
                                                     :tool-blocks :open
                                                     :thinking-blocks :open})
    (sessions-state)))
