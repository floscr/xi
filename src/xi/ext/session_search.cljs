(ns xi.ext.session-search
  "Agent tool for searching previous sessions (past conversations) by title
   and conversation content. Reads session summaries via `xi.session`; the
   search is scoped to a project directory — the room's cwd by default, an
   explicit `cwd` arg, or every project when `all` is true. Pure data + a
   read-only tool exec fn (the filesystem read is contained in xi.session)."
  (:require [clojure.string :as str]
            [xi.session :as session]))

(def ^:private MAX_RESULTS 20)

(def ^:private tool-defs
  [{:name "session_search"
    :description
    (str "Search your previous Xi sessions (past conversations) by title and "
         "conversation content, case-insensitive. Returns matching sessions "
         "with their title, project directory, date, session id and a short "
         "excerpt around the match. Use it to recall earlier work, decisions "
         "or context from prior sessions.")
    :input_schema
    {:type "object"
     :properties
     {:query {:type "string"
              :description "Text to look for in session titles and conversation content."}
      :cwd   {:type "string"
              :description (str "Absolute path of the project to search within. "
                                "Defaults to the current session's working "
                                "directory. Ignored when `all` is true.")}
      :all   {:type "boolean"
              :description "Search across every project instead of a single directory."}}
     :required ["query"]}}])

(defn- fmt-date [ts]
  (if (seq ts)
    (try (subs (.toISOString (js/Date. ts)) 0 10)
         (catch :default _ (str ts)))
    "unknown"))

(defn- fmt-result [s]
  (str "• " (or (:name s) "(untitled)") "\n"
       "  id: " (:session-id s)
       "  · " (fmt-date (or (:last-accessed s) (:timestamp s)))
       (when (seq (:cwd s)) (str "  · " (:cwd s)))
       (when (:snippet s) (str "\n  " (:snippet s)))))

(defn- session-search [{:keys [query all cwd]} ctx]
  (let [scope-cwd (cond
                    all       nil
                    (seq cwd) cwd
                    :else     (:cwd ctx))
        results   (session/search-sessions scope-cwd query)
        n         (count results)]
    {:content
     [{:type "text"
       :text (if (zero? n)
               (str "No sessions found matching " (pr-str query)
                    (cond
                      all       " across all projects."
                      (seq cwd) (str " in " cwd ".")
                      :else     " in the current project."))
               (str "Found " n " session" (when (> n 1) "s")
                    (when (> n MAX_RESULTS) (str " (showing first " MAX_RESULTS ")"))
                    ":\n\n"
                    (str/join "\n\n" (map fmt-result (take MAX_RESULTS results)))))}]}))

(def extension
  {:id               :session-search
   :tool-definitions tool-defs
   :tool-registry    {"session_search" session-search}})
