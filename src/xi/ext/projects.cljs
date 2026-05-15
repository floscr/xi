(ns xi.ext.projects
  "Project completion extension.
   /project opens a fuzzy completion menu of projects (from `project select --raw`).
   Enter inserts the project path. Tab drills into git-tracked files."
  (:require [clojure.string :as str]
            [xi.ext.core :as ext]))

;; ── Shell Helpers ─────────────────────────────────────────────────────────────

(defn- run-cmd
  "Run a command, return promise of stdout string."
  [args]
  (js/Promise.
   (fn [resolve _reject]
     (let [proc (js/Bun.spawn
                 (clj->js args)
                 #js {:stdout "pipe" :stderr "pipe"})]
       (-> (.text (.-stdout proc))
           (.then (fn [stdout]
                    (resolve (str/trim stdout)))))))))

(defn- parse-lines [text]
  (when (seq text)
    (str/split-lines text)))

;; ── Data ──────────────────────────────────────────────────────────────────────

(defn- fetch-projects []
  (-> (run-cmd ["project" "select" "--raw"])
      (.then parse-lines)))

(defn- fetch-git-files [project-path]
  (-> (run-cmd ["git" "-C" project-path "ls-files"])
      (.then parse-lines)
      (.catch (fn [_] []))))

;; ── Completion ────────────────────────────────────────────────────────────────

(defn- shorten-path
  "Replace $HOME prefix with ~."
  [path]
  (let [home (aget js/process.env "HOME")]
    (if (and home (str/starts-with? path home))
      (str "~" (subs path (count home)))
      path)))

;; Forward declarations for mutual recursion
(declare show-project-menu!)

(defn- show-file-menu!
  "Show git files for a project. Enter inserts project/file path."
  [project-path]
  (-> (fetch-git-files project-path)
      (.then
       (fn [files]
         (if (seq files)
           (let [items (mapv (fn [f] {:label f :value f}) files)]
             (ext/show-completion!
              {:items items
               :prompt (str (shorten-path project-path) " > ")
               :on-select (fn [item]
                            (ext/insert-text! (str project-path "/" (:value item))))
               :key-bindings
               [{:key-fn (fn [data] (= data (str (char 27) "[Z")))  ;; Shift+Tab
                 :handler (fn [_state-atom _update-items!]
                            (show-project-menu!))}]}))
           ;; No git files — just insert the project path
           (ext/insert-text! project-path))))))

(defn show-project-menu! []
  (-> (fetch-projects)
      (.then
       (fn [projects]
         (when (seq projects)
           (let [items (mapv (fn [p]
                               {:label (shorten-path p)
                                :value p})
                             projects)]
             (ext/show-completion!
              {:items items
               :prompt "project> "
               :on-select (fn [item]
                            (ext/insert-text! (:value item)))
               :key-bindings
               [{:key-fn (fn [data] (= data "\t"))
                 :handler (fn [state-atom _update-items!]
                            (let [{:keys [filtered selected]} @state-atom]
                              (when (seq filtered)
                                (let [project (:value (nth filtered selected))]
                                  (show-file-menu! project)))))}]})))))))

;; ── Extension ─────────────────────────────────────────────────────────────────

(def extension
  {:name "projects"
   :commands [{:name "project"
               :description "Complete project paths and files"
               :handler (fn [_ctx]
                          (show-project-menu!)
                          nil)}]})
