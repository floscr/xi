(ns xi.ext.session-search
  "Agent tools over previous sessions (past conversations): `session_search`
   finds them by title and conversation content (a blank query lists the
   newest), `session_view` reads one. The search is scoped to a project
   directory — the room's cwd by default, an explicit `cwd` arg, or every
   project when `all` is true; an agent profile's room sees its own sessions
   only, both to search and to view. Pure data + read-only tool exec fns (the
   filesystem reads are contained in xi.session)."
  (:require [clojure.string :as str]
            [xi.session :as session]))

(def ^:private MAX_RESULTS 20)

(def ^:private VIEW_MAX_CHARS
  "Text one session_view page carries; longer sessions continue at an offset."
  30000)

(def ^:private tool-defs
  [{:name "session_search"
    :description
    (str "Search your previous Xi sessions (past conversations) by title and "
         "conversation content, case-insensitive. Returns matching sessions "
         "with their title, project directory, date, session id and a short "
         "excerpt around the match. An empty query lists the newest sessions. "
         "Use it to recall earlier work, decisions or context from prior "
         "sessions; read one with session_view.")
    :input_schema
    {:type "object"
     :properties
     {:query {:type "string"
              :description (str "Text to look for in session titles and conversation "
                                "content. Empty: the newest sessions.")}
      :cwd   {:type "string"
              :description (str "Absolute path of the project to search within. "
                                "Defaults to the current session's working "
                                "directory. Ignored when `all` is true.")}
      :all   {:type "boolean"
              :description "Search across every project instead of a single directory."}}
     :required []}}
   {:name "session_view"
    :description
    (str "Read a previous session's conversation by its id (from "
         "session_search): the user and assistant messages, oldest first, "
         "without tool calls. A long session comes in pages; pass the "
         "`offset` the result names to read on.")
    :input_schema
    {:type "object"
     :properties
     {:id     {:type "string" :description "The session id."}
      :offset {:type "number"
               :description "Index of the first message to show (default 0)."}}
     :required ["id"]}}])

(defn- fmt-date [ts]
  (if (seq ts)
    (try (subs (.toISOString (js/Date. ts)) 0 10)
         (catch :default _ (str ts)))
    "unknown"))

(defn- fmt-result [agent? s]
  (str "• " (or (:name s) "(untitled)") "\n"
       "  id: " (:session-id s)
       "  · " (fmt-date (or (:last-accessed s) (:timestamp s)))
       (when (and (not agent?) (seq (:cwd s))) (str "  · " (:cwd s)))
       (when (:snippet s) (str "\n  " (:snippet s)))))

(defn- room-agent-id
  "The agent profile id of the calling room (xi.agent-profile/room-ext), nil
   for a project room."
  [{:keys [get-state room-id]}]
  (when get-state
    (get-in (get-state) [:rooms room-id :ext :agent :id])))

(defn- text-result [text & [error?]]
  {:content [{:type "text" :text text}] :is-error (boolean error?)})

(defn- session-search
  "In an agent profile's room the search covers that agent's own sessions
   only (`cwd` and `all` are ignored), and results carry no directories."
  [{:keys [query all cwd]} ctx]
  (let [agent-id  (room-agent-id ctx)
        scope-cwd (cond
                    all       nil
                    (seq cwd) cwd
                    :else     (:cwd ctx))
        opts      (when agent-id {:agent-id agent-id})
        recent?   (str/blank? query)
        results   (if recent?
                    (session/recent-sessions (when-not agent-id scope-cwd) opts)
                    (session/search-sessions (when-not agent-id scope-cwd) query opts))
        n         (count results)]
    (text-result
     (if (zero? n)
       (str (if recent?
              "No sessions"
              (str "No sessions found matching " (pr-str query)))
            (cond
              agent-id  " in your previous conversations."
              all       " across all projects."
              (seq cwd) (str " in " cwd ".")
              :else     " in the current project."))
       (str (if recent? "The newest of " "Found ") n " session" (when (> n 1) "s")
            (when (> n MAX_RESULTS) (str " (showing first " MAX_RESULTS ")"))
            ":\n\n"
            (str/join "\n\n" (map (partial fmt-result (some? agent-id))
                                  (take MAX_RESULTS results))))))))

(defn page
  "The messages of one session_view page starting at `start`: at least one,
   then as many as fit VIEW_MAX_CHARS → [messages next-offset|nil]. Public for
   tests."
  [msgs start]
  (loop [i start, size 0, acc []]
    (let [m (get msgs i)]
      (cond
        (nil? m) [acc nil]
        (and (seq acc) (> (+ size (count (:text m))) VIEW_MAX_CHARS)) [acc i]
        :else (recur (inc i) (+ size (count (:text m))) (conj acc m))))))

(defn- fmt-message [i {:keys [role text]}]
  (str "[" i "] " role ":\n"
       (if (> (count text) VIEW_MAX_CHARS)
         (str (subs text 0 VIEW_MAX_CHARS) "\n… (message cut)")
         text)))

(defn- session-view
  "In an agent profile's room only that agent's own sessions resolve."
  [{:keys [id offset]} ctx]
  (let [agent-id (room-agent-id ctx)
        id       (str/trim (str id))
        summary  (when (seq id)
                   (if agent-id
                     (session/find-personal-agent-session-by-id id agent-id)
                     (session/find-session-by-id id)))]
    (if-not summary
      (text-result (str "No session with id " (pr-str id)
                        " — take the id from session_search.")
                   true)
      (let [msgs  (session/conversation summary)
            n     (count msgs)
            start (min (max 0 (int (or offset 0))) n)
            [shown next] (page msgs start)]
        (text-result
         (str (or (:name summary) "(untitled)")
              " · " (fmt-date (or (:last-accessed summary) (:timestamp summary)))
              " · " n " message" (when (not= 1 n) "s")
              (cond
                (zero? n) "\n\nNo messages."
                (empty? shown) (str "\n\nNo messages from offset " start ".")
                :else
                (str " (showing " start "–" (+ start (count shown) -1) ")\n\n"
                     (str/join "\n\n" (map-indexed #(fmt-message (+ start %1) %2) shown))
                     (when next
                       (str "\n\n… more: session_view with offset " next))))))))))

(def extension
  {:id               :session-search
   :tool-definitions tool-defs
   :tool-registry    {"session_search" session-search
                      "session_view"   session-view}})
