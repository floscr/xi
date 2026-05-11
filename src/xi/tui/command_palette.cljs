(ns xi.tui.command-palette
  "Command palette — manages custom commands and recency sorting.
   Reads available commands from the central command-registry.
   Persists custom commands and recency data to ~/.config/xi/command-palette.json."
  (:require [xi.command-registry :as registry]
            ["node:fs" :as fs]
            ["node:path" :as node-path]))

(def ^:private HOME (aget js/process.env "HOME"))
(def ^:private CONFIG_PATH (.join node-path HOME ".config" "xi" "command-palette.json"))

(defn- ensure-dir! [dir]
  (when-not (.existsSync fs dir)
    (.mkdirSync fs dir #js {:recursive true})))

(defn- load-config []
  (try
    (let [raw (js->clj (js/JSON.parse (.readFileSync fs CONFIG_PATH "utf8")) :keywordize-keys true)]
      {:custom (vec (or (:custom raw) []))
       :recent (vec (or (:recent raw) []))})
    (catch :default _
      {:custom [] :recent []})))

(defn- save-config! [config]
  (ensure-dir! (.dirname node-path CONFIG_PATH))
  (.writeFileSync fs CONFIG_PATH (js/JSON.stringify (clj->js config) nil 2) "utf8"))

(defn add-custom!
  "Add a custom command. Returns true if added, false if duplicate."
  [command]
  (let [config (load-config)
        existing (set (map :command (:custom config)))]
    (if (contains? existing command)
      false
      (do (save-config! (update config :custom conj {:command command}))
          true))))

(defn remove-custom!
  "Remove a custom command. Returns true if found and removed."
  [command]
  (let [config (load-config)
        new-custom (vec (remove #(= (:command %) command) (:custom config)))]
    (if (= (count new-custom) (count (:custom config)))
      false
      (do (save-config! (assoc config :custom new-custom))
          true))))

(defn list-custom
  "Return all custom commands."
  []
  (:custom (load-config)))

(defn record-use!
  "Record command usage for recency sorting."
  [command]
  (let [config (load-config)
        recent (->> (:recent config)
                    (remove #(= % command))
                    (cons command)
                    (take 50)
                    vec)]
    (save-config! (assoc config :recent recent))))

(defn build-items
  "Build palette items from the central registry and custom commands.
   Sorted by most recently used."
  []
  (let [config (load-config)
        recent (:recent config)
        recency-map (into {} (map-indexed (fn [i cmd] [cmd i]) recent))

        ;; All registered commands (built-in + extension + client)
        reg-cmds (mapv (fn [cmd]
                         {:label (str "/" (:name cmd))
                          :command (str "/" (:name cmd))
                          :description (:description cmd)})
                       (registry/list-commands))

        ;; User's custom commands (ad-hoc palette entries)
        customs (mapv (fn [{:keys [command]}]
                        {:label command
                         :command command
                         :description "custom"})
                      (:custom config))

        ;; Deduplicate, preserving order (registry first, then customs)
        seen (atom #{})
        all (reduce (fn [acc item]
                      (if (@seen (:command item))
                        acc
                        (do (swap! seen conj (:command item))
                            (conj acc item))))
                    []
                    (concat reg-cmds customs))

        sorted (sort-by (fn [item]
                          [(get recency-map (:command item) 999)
                           (:label item)])
                        all)]
    (mapv (fn [item]
            {:label (:label item)
             :description (:description item)
             :value (:command item)})
          sorted)))
