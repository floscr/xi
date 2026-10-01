(ns xi.ext.extensions
  "Control surface for the live extension manager (xi.ext.manager):
   `/ext list | enable <id> | disable <id>` toggles extensions at runtime.

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

(defn- reload-fx
  "Re-evaluate ~/.config/xi/extensions and re-register (xi.ext.user)."
  [mgr {:keys [dispatch!]} {:keys [room-id]}]
  (let [{:keys [loaded rejected skipped]} (user-ext/reload! mgr)]
    (status! dispatch! room-id
             (str "Reloaded user extensions."
                  (when (seq loaded)
                    (str "\n  loaded: " (str/join ", " (map name loaded))))
                  (when (seq rejected)
                    (str "\n  rejected: "
                         (str/join "; " (map #(str (:file %) " — " (:error %)) rejected))))
                  (when (seq skipped)
                    (str "\n  not enabled (list under :extensions in config.edn): "
                         (str/join ", " skipped)))
                  "\nTool changes apply next turn; handler/command changes need a restart."))))

(defn create
  "Factory — returns the control extension, or nil when no manager is in ctx."
  [{:keys [manager]}]
  (when manager
    {:id       :extensions
     :commands [{:name "ext"
                 :description "List or toggle runtime extensions"
                 :handler ext-command
                 :subcommands [{:name "list" :description "List registered extensions"}
                               {:name "enable" :description "Enable an extension by id"}
                               {:name "disable" :description "Disable an extension by id"}
                               {:name "reload" :description "Reload user extensions from ~/.config/xi/extensions"}]}]
     :fx       {:ext/list   (partial list-fx manager)
                :ext/toggle (partial toggle-fx manager)
                :ext/reload (partial reload-fx manager)}}))
