(ns xi.e2e.profiles
  "The ~/.config/xi setups the end-to-end scenarios start xi with. Each
   profile is a map of path (relative to HOME) → file content, seeded into a
   throwaway HOME by xi.e2e.harness/create-env!. Written as data rather than
   a fixture tree so a scenario can see at a glance what the user configured;
   the team profile is read out of the roles tutorial, so the page is tested
   as written."
  (:require ["node:fs" :as fs]))

(defn- config [& {:as m}]
  {".config/xi/config.edn" (pr-str (merge {:type :xi/config :version 1} m))})

(defn- rules [rs]
  {".config/xi/rules.edn" (pr-str {:type :xi/rules :version 1 :rules rs})})

(def ^:private roles-tutorial
  (delay (str (fs/readFileSync "docs/guide/extension-tutorial-roles.md" "utf8"))))

(defn- tutorial-block
  "The fenced clojure block of the roles tutorial whose first line is `first-line`."
  [first-line]
  (second (re-find (re-pattern (str "(?s)```clojure\n" first-line "\n(.*?)```"))
                   @roles-tutorial)))

(def bare
  "A first-run user: no ~/.config/xi at all."
  {})

(def minimal
  "A config and an empty rules file — the built-in defaults decide."
  (merge (config) (rules [])))

(def broken-extension-source
  "An extension file that doesn't read (unbalanced)."
  "(ns broken)\n(def extension {:id :broken\n")

(def extended
  "A shipped demo extension (notes), the tutorial's board extension and one
   broken extension."
  (merge (config :extensions ["board.cljs" "broken.cljs"]
                 :demo-extensions ["notes.cljs"])
         (rules [])
         {".config/xi/extensions/board.cljs"
          (tutorial-block ";; ~/.config/xi/extensions/board.cljs")
          ".config/xi/extensions/broken.cljs" broken-extension-source}))

(defn team
  "The roles tutorial's server: alice is an admin, bob a guest, rules per
   role, the board extension."
  []
  {".config/xi/config.edn"           (tutorial-block ";; ~/.config/xi/config.edn")
   ".config/xi/rules.edn"            (tutorial-block ";; ~/.config/xi/rules.edn")
   ".config/xi/extensions/board.cljs" (tutorial-block ";; ~/.config/xi/extensions/board.cljs")})

(def locked-down
  "No shell at all; every edit asks."
  (merge (config)
         (rules [{:match {:tool #{:bash :sh}} :action {:type :deny :message "No programs here."}}
                 {:match {:tool :edit} :action {:type :ask}}])))

(def agent
  "An agent profile with a fixed prompt and two tools."
  (merge (config :agents {"helper" {:system-prompt "You are the e2e helper."
                                    :tools ["read" "ls"]}})
         (rules [])))

(def broken
  "Neither config.edn nor rules.edn reads."
  {".config/xi/config.edn" "{:type :xi/config :version 1 :extensions ["
   ".config/xi/rules.edn"  "{:type :xi/rules"})
