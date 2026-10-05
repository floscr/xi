(ns hello-mcp.main
  "Example babashka MCP server for xi: two small tools on top of mcp.server.

     git_log   recent commits of the repo xi's room is in — the directory
               comes from `_meta` \"xi/cwd\", not from a tool argument
     uuid      a random UUID

   Register it in ~/.config/xi/mcp.edn:
     {:bb-example {:command \"bb\"
                   :args [\"--config\" \"/path/to/xi/packages/mcp-bb-example/bb.edn\"
                          \"-m\" \"hello-mcp.main\"]}}
   then `/mcp refresh bb-example`."
  (:require [babashka.process :as process]
            [clojure.string :as str]
            [mcp.server :as server]))

(defn- git-log [{:keys [n]} meta]
  (let [cwd (get meta "xi/cwd")
        n   (max 1 (min 50 (or (some-> n long) 10)))]
    (if-not cwd
      {:content [{:type "text" :text "git_log: no xi/cwd in _meta — which repo?"}] :isError true}
      (let [{:keys [exit out err]} (process/shell {:dir cwd :out :string :err :string :continue true}
                                                  "git" "log" "--oneline" (str "-" n))]
        (if (zero? exit)
          (str "Last " n " commits in " cwd ":\n" (str/trimr out))
          {:content [{:type "text" :text (str "git_log: " (str/trim err))}] :isError true})))))

(def tools
  [{:name        "git_log"
    :description "Recent commits (one line each) of the git repo the current xi room is in."
    :inputSchema {:type "object"
                  :properties {"n" {:type "number" :description "How many commits (1-50, default 10)."}}}
    :handler     git-log}
   {:name        "uuid"
    :description "A random UUID."
    :inputSchema {:type "object" :properties {}}
    :handler     (fn [_ _] (str (random-uuid)))}])

(defn -main [& _]
  (server/serve! {:name "hello-mcp" :version "0.1.0" :tools tools}))
