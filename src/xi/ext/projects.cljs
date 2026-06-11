(ns xi.ext.projects
  "Project path completion — /project opens a fuzzy picker of project paths
   (from `project select --raw`). Selecting inserts the path into the editor.
   Tab drills into git-tracked files; Shift+Tab returns to the project list."
  (:require [clojure.string :as str]))

(defn- shorten-path
  "Replace $HOME prefix with ~."
  [path]
  (let [home (aget js/process.env "HOME")]
    (if (and home (str/starts-with? path home))
      (str "~" (subs path (count home)))
      path)))

(defn- open-picker
  "Open the project picker (defers to effect for I/O)."
  [_st {:keys [room-id]}]
  {:effects [[:project/open-picker {:room-id room-id}]]})

(defn- open-picker-fx
  "Fetch projects via the `project` CLI and open a menu of paths."
  [{:keys [dispatch!]} {:keys [room-id]}]
  (let [proc (js/Bun.spawn #js ["project" "select" "--raw"]
                            #js {:stdout "pipe" :stderr "pipe"})]
    (-> (.text (.-stdout proc))
        (.then (fn [stdout]
                 (let [lines (->> (str/split-lines (str/trim stdout))
                                  (remove empty?))]
                   (if (seq lines)
                     (let [items (mapv (fn [p]
                                         {:label (shorten-path p)
                                          :description p
                                          :event {:type :project/insert
                                                  :room-id room-id
                                                  :path p}})
                                       lines)]
                       (dispatch! {:type :ui/menu-open :room-id room-id
                                   :menu {:id :projects :prompt "project> " :items items
                                          :key-bindings [{:key "\t"
                                                          :selected? true
                                                          :event {:type :project/drill}}]}}))
                     (dispatch! {:type :history/append :room-id room-id
                                 :entry {:kind :status :text "No projects found."}})))))
        (.catch (fn [err]
                  (dispatch! {:type :history/append :room-id room-id
                              :entry {:kind :status
                                      :text (str "project picker failed: "
                                                 (.-message err))}}))))))

(defn- drill-handler
  "Tab on a project — drill into its git-tracked files."
  [_st {:keys [room-id selected]}]
  (when-let [project-path (:description selected)]
    {:effects [[:project/open-files {:room-id room-id :path project-path}]]}))

(defn- open-files-fx
  "Fetch git-tracked files for a project and open a file picker menu.
   Shift+Tab goes back to the project list."
  [{:keys [dispatch!]} {:keys [room-id path]}]
  (let [proc (js/Bun.spawn #js ["git" "-C" path "ls-files"]
                            #js {:stdout "pipe" :stderr "pipe"})]
    (-> (.text (.-stdout proc))
        (.then (fn [stdout]
                 (let [files (->> (str/split-lines (str/trim stdout))
                                  (remove empty?))]
                   (if (seq files)
                     (let [items (mapv (fn [f]
                                         {:label f
                                          :event {:type :project/insert
                                                  :room-id room-id
                                                  :path (str path "/" f)}})
                                       files)]
                       (dispatch! {:type :ui/menu-open :room-id room-id
                                   :menu {:id :project-files
                                          :prompt (str (shorten-path path) " > ")
                                          :items items
                                          :key-bindings [{:key (str (char 27) "[Z")
                                                          :event {:type :project/open}}]}}))
                     ;; No git files — just insert the project path
                     (dispatch! {:type :project/insert :room-id room-id :path path})))))
        (.catch (fn [_]
                  ;; Not a git repo or error — insert the project path
                  (dispatch! {:type :project/insert :room-id room-id :path path}))))))

(defn- insert-handler
  "Menu-selected a project path → insert it into the editor."
  [_st {:keys [room-id path]}]
  {:effects [[:editor/insert-text {:text path}]]})

(def extension
  {:id       :projects
   :commands [{:name "project"
               :description "Pick a project path"
               :handler open-picker}]
   :handlers {:project/insert insert-handler
              :project/open   open-picker
              :project/drill  drill-handler}
   :fx       {:project/open-picker open-picker-fx
              :project/open-files  open-files-fx}
   :keybindings [{:key "alt+p"
                  :event {:type :project/open}}]})
