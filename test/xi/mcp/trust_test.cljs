(ns xi.mcp.trust-test
  "MCP server trust: once per server, revoked by a change to its code or entry."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [xi.mcp.trust :as trust]
            [xi.user-config :as user-config]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def ^:private dir (atom nil))

(defn- file [& parts] (apply path/join @dir parts))

(defn- write-registry! [m] (fs/writeFileSync (file "mcp.edn") (pr-str m)))

(use-fixtures :each
  {:before (fn []
             (reset! dir (fs/mkdtempSync (path/join (os/tmpdir) "xi-mcp-trust-")))
             (trust/set-registry-file! (file "mcp.edn"))
             (trust/set-trust-file! (file "trust.edn")))
   :after  (fn []
             ;; back to the hermetic paths (xi.hermetic-test)
             (trust/set-registry-file! "/nonexistent/xi-test/mcp.edn")
             (trust/set-trust-file! "/nonexistent/xi-test/mcp-trust.edn")
             (fs/rmSync @dir #js {:recursive true :force true}))})

(deftest trusted-once-until-the-code-changes
  (let [server (file "server.js")]
    (fs/writeFileSync server "console.log(1)")
    (write-registry! {:chrome {:command "bun" :args [server]}})
    (is (not (trust/trusted? :chrome)) "untrusted until approved")
    (is (= "chrome" (:id (trust/trust! "chrome"))))
    (is (trust/trusted? "chrome"))
    (testing "the same code stays trusted"
      (is (trust/trusted? :chrome)))
    (testing "a rebuild with different code asks again"
      (fs/writeFileSync server "console.log(2)")
      ;; a new mtime/size, so the memoized hash is recomputed
      (fs/utimesSync server 1 2)
      (is (not (trust/trusted? :chrome))))))

(deftest an-edited-entry-asks-again
  (write-registry! {:x {:command "npx" :args ["-y" "pkg@1.0.0"]}})
  (trust/trust! :x)
  (is (trust/trusted? :x))
  (write-registry! {:x {:command "npx" :args ["-y" "pkg@2.0.0"]}})
  (is (not (trust/trusted? :x)))
  (testing ":env and :enabled are not code"
    (trust/trust! :x)
    (write-registry! {:x {:command "npx" :args ["-y" "pkg@2.0.0"] :env {"A" "1"} :enabled false}})
    (is (trust/trusted? :x))))

(deftest code-paths-cover-code-the-command-line-does-not-name
  (fs/mkdirSync (file "src" "mcp") #js {:recursive true})
  (fs/writeFileSync (file "src" "mcp" "server.clj") "(ns mcp.server)")
  (write-registry! {:bb {:command "bb" :args ["-m" "hello.main"] :code-paths [(file "src")]}})
  (trust/trust! :bb)
  (is (trust/trusted? :bb))
  (fs/writeFileSync (file "src" "mcp" "server.clj") "(ns mcp.server) (def evil 1)")
  (is (not (trust/trusted? :bb))))

(deftest unknown-servers-are-never-trusted
  (write-registry! {})
  (is (nil? (trust/trust! :nope)))
  (is (not (trust/trusted? :nope))))

(deftest untrust-asks-again
  (write-registry! {:x {:command "npx" :args ["pkg"]}})
  (trust/trust! :x)
  (trust/untrust! :x)
  (is (not (trust/trusted? :x))))

(deftest extension-servers-are-trusted-by-their-id
  (write-registry! {})
  (trust/set-extension-entries! :shop {"shop/browser" {:command "npx" :args ["pkg@1"]}})
  (try
    (is (not (trust/trusted? "shop/browser")))
    (is (= "shop/browser" (:id (trust/trust! "shop/browser"))))
    (is (trust/trusted? "shop/browser"))
    (testing "a changed declaration asks again"
      (trust/set-extension-entries! :shop {"shop/browser" {:command "npx" :args ["pkg@2"]}})
      (is (not (trust/trusted? "shop/browser"))))
    (finally (trust/set-extension-entries! :shop nil))))

(deftest config-trusts-a-server-outright
  (let [config (file "config.edn")]
    (write-registry! {:chrome {:command "bun" :args ["server.js"]}})
    (fs/writeFileSync config (pr-str {:type :xi/config :version 1
                                      :trusted-mcp-servers ["chrome" "shop/browser"]}))
    (user-config/set-config-file! config)
    (try
      (is (trust/trusted? :chrome) "no store entry needed")
      (testing "a changed entry stays trusted: the config is the decision"
        (write-registry! {:chrome {:command "bun" :args ["other.js"]}})
        (is (trust/trusted? "chrome")))
      (testing "extension servers by their <ext>/<name> id"
        (trust/set-extension-entries! :shop {"shop/browser" {:command "npx"}})
        (is (trust/trusted? "shop/browser")))
      (finally
        (trust/set-extension-entries! :shop nil)
        (user-config/set-config-file! "/nonexistent/xi-test/config.edn")))))
