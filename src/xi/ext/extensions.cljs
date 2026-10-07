(ns xi.ext.extensions
  "Control surface for the live extension manager (xi.ext.manager):
   `/ext list | enable <id> | disable <id>` toggles extensions at runtime,
   and `/ext reload` / the agent-callable `ext_reload` tool re-read the user
   extensions.

   Only use-time surfaces hot-swap (tool definitions + registry — see the manager
   docstring); toggling an extension that contributes reducer handlers,
   commands, keybindings, or a system prompt needs a restart to fully take
   effect. This is a factory: it returns nil unless a :manager is in ctx
   (so the client mirror, which has no manager, gets nothing)."
  (:require [clojure.string :as str]
            [xi.ext.manager :as manager]
            [xi.ext.user :as user-ext]))

(defn- render-list
  [mgr]
  (let [rows (manager/ext-list mgr)]
    (if (empty? rows)
      "No extensions registered."
      (str "Extensions (" (count (filter :enabled? rows)) "/" (count rows) " enabled):\n"
           (str/join "\n"
                     (map (fn [{:keys [id enabled?]}]
                            (str "  " (if enabled? "[x]" "[ ]") " " (name id)))
                          rows))))))

(defn- status!
  [dispatch! room-id text]
  (dispatch! {:type :history/append :room-id room-id
              :entry {:kind :status :text text}}))

(defn- ext-command
  "/ext [list | enable <id> | disable <id>] — defers to fx (reads the live
   manager, which is impure runtime state, not app state)."
  [_st {:keys [room-id args]}]
  (let [[sub id] (str/split (str/trim (or args "")) #"\s+" 2)]
    (case sub
      "enable"  {:effects [[:ext/toggle {:room-id room-id :action :enable  :id id}]]}
      "disable" {:effects [[:ext/toggle {:room-id room-id :action :disable :id id}]]}
      "reload"  {:effects [[:ext/reload {:room-id room-id}]]}
      {:effects [[:ext/list {:room-id room-id}]]})))

(defn- list-fx
  [mgr {:keys [dispatch!]} {:keys [room-id]}]
  (status! dispatch! room-id (render-list mgr)))

(defn- toggle-fx
  [mgr {:keys [dispatch!]} {:keys [room-id action id]}]
  (if (str/blank? id)
    (status! dispatch! room-id
             (str "Usage: /ext " (name action) " <id>\nUse /ext list to see extensions."))
    (let [kw (keyword (str/trim id))]
      (cond
        (not (manager/known? mgr kw))
        (status! dispatch! room-id
                 (str "Unknown extension '" (str/trim id) "'. Use /ext list."))

        :else
        (let [changed (if (= action :enable)
                        (manager/enable! mgr kw)
                        (manager/disable! mgr kw))]
          (status! dispatch! room-id
                   (if changed
                     (str (name kw) " " (if (= action :enable) "enabled." "disabled.")
                          " Takes effect on the next turn.")
                     (str (name kw) " already "
                          (if (= action :enable) "enabled." "disabled.")))))))))

(defn- reload-report
  "Re-evaluate ~/.config/xi/extensions and re-register (xi.ext.user) →
   {:text :rejected?}, the report shared by `/ext reload` and ext_reload."
  [reload!]
  (let [{:keys [loaded rejected skipped]} (reload!)]
    {:rejected? (boolean (seq rejected))
     :text
     (str "Reloaded user extensions."
          (when (seq loaded)
            (str "\n  loaded: " (str/join ", " (map name loaded))))
          (when (seq rejected)
            (str "\n  rejected: "
                 (str/join "; " (map #(str (:file %) " — " (:error %)) rejected))))
          (when (seq skipped)
            (str "\n  not enabled (list under :extensions in config.edn): "
                 (str/join ", " skipped)))
          "\nLive: handlers, commands, fx, state init (existing room state is kept); "
          "tool definitions from the next turn; a web half on the next page load. "
          "Keybindings need a restart.")}))

(defn- reload-fx
  [reload! {:keys [dispatch!]} {:keys [room-id]}]
  (status! dispatch! room-id (:text (reload-report reload!))))

(def ^:private reload-tool-def
  {:name "ext_reload"
   :description (str "Re-evaluate the enabled user extensions in ~/.config/xi/extensions "
                     "and the bundled demos enabled under :demo-extensions "
                     "(the same as `/ext reload`) and report which loaded or were rejected, "
                     "with the eval error. Call it after editing an extension file to check "
                     "that it still evaluates and to pick up its changes — no restart. "
                     "Each extension is unmounted (its :on-unmount runs; spawned processes "
                     "and its browser are stopped) and the new code mounted. Live at once: "
                     "handlers, commands, fx; tool definitions from the next turn; the "
                     "browser half on the next page load. Room state is kept. Not live: "
                     "keybindings. Files not listed under :extensions in config.edn are "
                     "never loaded.")
   :input_schema {:type "object" :properties {} :required []}})

(defn- reload-tool
  "ext_reload tool body. A rejected file is an error result, so the agent
   sees its own eval failure."
  [reload! _args _ctx]
  (let [{:keys [text rejected?]} (reload-report reload!)]
    {:content [{:type "text" :text text}] :is-error rejected?}))

(defn create
  "Factory — returns the control extension, or nil when no manager is in ctx.
   ctx :reload! (0-arg → user-ext/reload! report) defaults to reloading the
   manager's user extensions; tests inject a stub."
  [{:keys [manager] :as ctx}]
  (let [reload! (or (:reload! ctx) #(user-ext/reload! manager))]
    (when manager
      {:id       :extensions
       :commands [{:name "ext"
                   :description "List or toggle runtime extensions"
                   :handler ext-command
                   :subcommands [{:name "list" :description "List registered extensions"}
                                 {:name "enable" :description "Enable an extension by id"}
                                 {:name "disable" :description "Disable an extension by id"}
                                 {:name "reload" :description "Reload user extensions from ~/.config/xi/extensions"}]}]
       :tool-definitions [reload-tool-def]
       :tool-registry    {"ext_reload" (partial reload-tool reload!)}
       :fx       {:ext/list   (partial list-fx manager)
                  :ext/toggle (partial toggle-fx manager)
                  :ext/reload (partial reload-fx reload!)}})))
