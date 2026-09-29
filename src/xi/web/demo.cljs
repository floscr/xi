(ns xi.web.demo
  "Fabricated data for README screenshots.

   When the web client is loaded with a `?demo=<view>` query param,
   xi.web.core renders a static, fully-populated view backed by the data
   here instead of connecting to a server. This keeps the screenshots free
   of real sessions and reproducible.

   Views:
     ?demo=sessions    → the session list
     ?demo=chat        → a chat room mid-conversation
     ?demo=chat-viewer → the same room in viewer mode (grouped tool rows)"
  (:require [xi.core.state :as state]))

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
         :lobby {:personal-agent? false
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

(defn demo-state
  "Build the seeded app state for a `?demo=<view>` value."
  [view]
  (case view
    "chat" (chat-state)
    "chat-viewer" (assoc (chat-state) :web/viewer-mode? true)
    (sessions-state)))
