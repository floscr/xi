(ns xi.tui.command-palette
  "Command palette — manages custom commands and recency sorting.
   Persists to ~/.config/xi/command-palette.json."
  (:require [xi.ext.core :as ext]
            ["node:fs" :as fs]
            ["node:path" :as node-path]))

(def ^:private HOME (aget js/process.env "HOME"))
(def ^:private CONFIG_PATH (.join node-path HOME ".config" "xi" "command-palette.json"))

(def ^:private BUILTIN_COMMANDS
  [{:label "/help"     :command "/help"     :description "Show available commands"}
   {:label "/sessions" :command "/sessions" :description "List recent sessions"}
   {:label "/resume"   :command "/resume"   :description "Resume a previous session"}
   {:label "/new"      :command "/new"      :description "Start a new session"}
   {:label "/clear"    :command "/clear"    :description "Clear current session"}
   {:label "/model"    :command "/model"    :description "Show or set model"}
   {:label "/prompt"   :command "/prompt"   :description "Show system prompt"}
   {:label "/buffers"  :command "/buffers"  :description "Switch buffer view"}
   {:label "/debug"    :command "/debug"    :description "Copy debug info to clipboard"}
   {:label "/quit"     :command "/quit"     :description "Exit Xi"}])

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
  "Build palette items from built-in, extension, and custom commands.
   Sorted by most recently used."
  []
  (let [config (load-config)
        recent (:recent config)
        recency-map (into {} (map-indexed (fn [i cmd] [cmd i]) recent))

        ext-cmds (mapv (fn [{:keys [name desc]}]
                         {:label (str "/" name)
                          :command (str "/" name)
                          :description desc})
                       (ext/list-commands))

        customs (mapv (fn [{:keys [command]}]
                        {:label command
                         :command command
                         :description "custom"})
                      (:custom config))

        seen (atom #{})
        all (reduce (fn [acc item]
                      (if (@seen (:command item))
                        acc
                        (do (swap! seen conj (:command item))
                            (conj acc item))))
                    []
                    (concat BUILTIN_COMMANDS ext-cmds customs))

        sorted (sort-by (fn [item]
                          [(get recency-map (:command item) 999)
                           (:label item)])
                        all)]
    (mapv (fn [item]
            {:label (:label item)
             :description (:description item)
             :value (:command item)})
          sorted)))
