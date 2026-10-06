(ns xi.users-test
  (:require [cljs.test :refer [deftest is]]
            [xi.user-config :as cfg]
            [xi.users :as users]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(deftest public-profiles-publish-name-and-avatar-only
  (let [dir  (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-users-profiles-"))
        file (node-path/join dir "config.edn")
        prev (cfg/config-file)]
    (fs/writeFileSync file (str "{:type :xi/config :version 1 :users "
                                "{\"alice\" {:name \"Alice\" :avatar \"https://example.com/a.png\" "
                                ":meta {:secret \"x\"}}}}"))
    (cfg/set-config-file! file)
    (try
      (is (= {"alice" {:name "Alice" :avatar "https://example.com/a.png"}
              "bob"   {}}
             (users/public-profiles [{:users ["bob"]} {:users ["alice" "bob"]}]))
          "declared users and everyone in a room, :meta never")
      (finally
        (cfg/set-config-file! prev)
        (fs/rmSync dir #js {:recursive true :force true})))))
