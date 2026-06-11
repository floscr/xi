(ns xi.ext.projects
  "Project path completion — /project opens a fuzzy picker of project paths
   (from `project select --raw`). Selecting inserts the path into the editor.

   Note: the old drill-down-into-files (Tab) feature is not ported yet; it
   required callback-based menus that the new data-driven menu system
   doesn't support. The basic pick-and-insert workflow works."
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
  "Fetch projects via the `project` CLI and open a menu of paths. Each
   item carries a :project/insert event."
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
                                   :menu {:id :projects :prompt "project> " :items items}}))
                     (dispatch! {:type :history/append :room-id room-id
                                 :entry {:kind :status :text "No projects found."}})))))
        (.catch (fn [err]
                  (dispatch! {:type :history/append :room-id room-id
                              :entry {:kind :status
                                      :text (str "project picker failed: "
                                                 (.-message err))}}))))))

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
              :project/open   open-picker}
   :fx       {:project/open-picker open-picker-fx}
   :keybindings [{:key "alt+p"
                  :event {:type :project/open}}]})
