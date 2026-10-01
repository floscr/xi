(ns xi.rules.store-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.rules :as rules]
            [xi.rules.defaults :as defaults]
            [xi.rules.store :as store]
            [xi.paths :as paths]
            [xi.ext.treesitter.parse :as ts]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(defn- denied? [result]
  (and (map? result) (:intercepted result) (:is-error (:result result))))

(deftest tool-kind-classification
  (is (= :write (store/tool-kind "write")))
  (is (= :edit  (store/tool-kind "Edit")))
  (is (= :bash  (store/tool-kind "bash")))
  (is (= :clj   (store/tool-kind "clj")))
  (is (= :mcp   (store/tool-kind "mcp__context7__search")))
  (is (= :other (store/tool-kind "some_tool"))))

(deftest hard-block-denies-rules-file-writes
  (testing "write/edit to a rules file is blocked"
    (is (denied? (store/hard-block {:name "write"
                                    :arguments {:path "~/.config/xi/rules.edn"}}
                                   {:cwd "/tmp"})))
    (is (denied? (store/hard-block {:name "edit"
                                    :arguments {:file_path "/home/x/proj/.xi/rules.edn"}}
                                   {:cwd "/tmp"}))))
  (testing "write to an ordinary file passes through"
    (is (nil? (store/hard-block {:name "write" :arguments {:path "/tmp/notes.txt"}}
                                {:cwd "/tmp"})))
    (is (nil? (store/hard-block {:name "write" :arguments {:path "~/.config/xi/other.edn"}}
                                {:cwd "/tmp"})))))

(deftest hard-block-denies-rules-file-shell-writes
  (testing "a shell command that writes a rules file is blocked"
    (is (denied? (store/hard-block {:name "bash"
                                    :arguments {:command "echo x >> ~/.config/xi/rules.edn"}}
                                   {:cwd "/tmp"})))
    (is (denied? (store/hard-block {:name "bash"
                                    :arguments {:command "sed -i s/a/b/ .xi/rules.edn"}}
                                   {:cwd "/tmp"}))))
  (testing "reading a rules file via shell is allowed (no write token)"
    (is (nil? (store/hard-block {:name "bash"
                                 :arguments {:command "cat ~/.config/xi/rules.edn"}}
                                {:cwd "/tmp"}))))
  (testing "clj code that spits a rules file is blocked"
    (is (denied? (store/hard-block {:name "clj"
                                    :arguments {:code "(spit \"~/.config/xi/rules.edn\" \"[]\")"}}
                                   {:cwd "/tmp"})))))

(deftest decision-request-normalizes
  (testing "write tool call → request with tool + path + effective-cwd"
    (let [req (store/decision-request {:name "write" :arguments {:path "deploy.sh"}}
                                      {:cwd "/home/x/proj"})]
      (is (= :write (:tool req)))
      (is (= "deploy.sh" (:path req)))
      (is (= "/home/x/proj" (:effective-cwd req)))))
  (testing "clj code maps into :command"
    (let [req (store/decision-request {:name "clj" :arguments {:code "(println 1)"}}
                                      {:cwd "/home/x/proj"})]
      (is (= :clj (:tool req)))
      (is (= "(println 1)" (:command req)))))
  (testing "bb tool call → :bb kind, :command is the bb command line"
    (let [req (store/decision-request {:name "bb" :arguments {:task "serve:restart"}}
                                      {:cwd "/tmp"})]
      (is (= :bb (:tool req)))
      (is (= "bb serve:restart" (:command req))))
    (is (= "bb test --focus x"
           (:command (store/decision-request {:name "bb" :arguments {:task "test" :args ["--focus" "x"]}}
                                             {:cwd "/tmp"}))))
    (is (= "bb tasks"
           (:command (store/decision-request {:name "bb" :arguments {}} {:cwd "/tmp"})))
        "no task lists the tasks"))
  (testing "mcp tool call parses server + tool"
    (let [req (store/decision-request {:name "mcp__context7__search" :arguments {}}
                                      {:cwd "/tmp"})]
      (is (= :mcp (:tool req)))
      (is (= "context7" (:mcp-server req)))
      (is (= "search" (:mcp-tool req)))))
  (testing "non-empty arguments are carried on the request (for ask dialogs)"
    (let [req (store/decision-request {:name "mcp__ctx__search" :arguments {:q "react"}}
                                      {:cwd "/tmp"})]
      (is (= {:q "react"} (:arguments req))))
    (let [req (store/decision-request {:name "mcp__ctx__ping" :arguments {}}
                                      {:cwd "/tmp"})]
      (is (nil? (:arguments req)) "empty arguments are omitted"))))

(deftest outside-cwd?-classifies-paths
  (let [cwd (.cwd js/process)]
    (is (not (store/outside-cwd? cwd "src/xi/rules.cljs")) "in-repo path is inside")
    (is (store/outside-cwd? cwd "/etc/hosts") "an absolute path outside cwd is outside")
    (is (not (store/outside-cwd? cwd nil)) "nil path is not outside")
    (is (not (store/outside-cwd? cwd "")) "blank path is not outside")))

(deftest outside-cwd?-always-allows-system-tmp
  (testing "/tmp stays inside even when TMPDIR points elsewhere (nix-shell)"
    (let [env  (unchecked-get js/process "env")
          prev (aget env "TMPDIR")]
      (aset env "TMPDIR" "/tmp/nix-shell.test")
      (try
        (is (not (store/outside-cwd? "/home" "/tmp/views.patch")) "plain /tmp")
        (is (not (store/outside-cwd? "/home" "/tmp/nix-shell.test/x")) "TMPDIR")
        (finally
          (if (some? prev)
            (aset env "TMPDIR" prev)
            (js-delete env "TMPDIR")))))))

(deftest enrich-request-populates-outside-only-when-needed
  (let [cwd (.cwd js/process)
        base (store/decision-request {:name "write" :arguments {:path "/etc/hosts"}}
                                     {:cwd cwd})]
    (testing "no :outside rule → request is untouched"
      (is (not (contains? (store/enrich-request base [{:match {:tool :write}}])
                          :outside-cwd?))))
    (testing "an :outside rule → :outside-cwd? is computed"
      (is (true? (:outside-cwd?
                  (store/enrich-request
                   base [{:match {:tool :write :outside :cwd}}])))))))

(deftest enrich-request-populates-resolved-path-only-when-needed
  (let [cwd  (.cwd js/process)
        base (store/decision-request {:name "read" :arguments {:path "src/xi/rules.cljs"}}
                                     {:cwd cwd})]
    (testing "no :path rule → request is untouched"
      (is (not (contains? (store/enrich-request base [{:match {:tool :read}}])
                          :resolved-path))))
    (testing "a :path rule → :resolved-path is the canonical absolute path"
      (let [req (store/enrich-request base [{:match {:tool :read :path #"rules\.cljs"}}])]
        (is (= (paths/real-resolve cwd "src/xi/rules.cljs") (:resolved-path req)))))))

(deftest path-rule-matches-relative-via-resolved-path
  (let [cwd      (.cwd js/process)
        real-cwd (paths/real-resolve cwd ".")
        ;; absolute-anchored allow rule, like the ~/Code/Projects rule
        ruleset [{:match {:tool #{:read} :path (re-pattern (str "^" real-cwd "/src/"))}
                  :action {:type :allow}}]
        raw-req (fn [p] (store/enrich-request
                         (store/decision-request {:name "read" :arguments {:path p}}
                                                 {:cwd cwd})
                         ruleset))]
    (testing "absolute path under the anchored dir matches"
      (is (= :allow (get-in (rules/first-match ruleset (raw-req (str cwd "/src/xi/rules.cljs")))
                            [:action :type]))))
    (testing "relative path resolving into the anchored dir also matches"
      (is (= :allow (get-in (rules/first-match ruleset (raw-req "src/xi/rules.cljs"))
                            [:action :type]))))
    (testing "a path outside the anchored dir does not match"
      (is (nil? (rules/first-match ruleset (raw-req "/etc/hosts")))))))

(deftest path-rule-matches-tilde-form
  ;; Real temp dir under $HOME so real-resolve returns an absolute path we can
  ;; home-collapse (no symlink surprises); the rule is written home-relative (~).
  (let [home     (os/homedir)
        dir-name (str ".xi-rules-tilde-test-" (.getTime (js/Date.)))
        base     (node-path/join home dir-name)
        sub      (node-path/join base "sub")]
    (fs/mkdirSync sub #js {:recursive true})
    (try
      (let [ruleset [{:match {:tool #{:read}
                             :path (re-pattern (str "^~/" dir-name "(?:/|$)"))}
                      :action {:type :allow}}]
            req     (fn [p] (store/enrich-request
                            (store/decision-request {:name "read" :arguments {:path p}}
                                                    {:cwd home})
                            ruleset))]
        (testing "absolute path under $HOME matches the ~-anchored rule"
          (is (= :allow (get-in (rules/first-match ruleset (req (node-path/join sub "x.edn")))
                                [:action :type]))))
        (testing "the dir itself matches"
          (is (= :allow (get-in (rules/first-match ruleset (req base))
                                [:action :type]))))
        (testing "a ~-typed path matches"
          (is (= :allow (get-in (rules/first-match ruleset (req (str "~/" dir-name "/sub/x.edn")))
                                [:action :type]))))
        (testing "a lookalike sibling does not match"
          (is (nil? (rules/first-match ruleset (req (str base "X"))))))
        (testing ":resolved-home-path is populated for a $HOME path"
          (is (= (str "~/" dir-name "/sub")
                 (:resolved-home-path (req sub))))))
      (finally
        (fs/rmSync base #js {:recursive true :force true})))))

(deftest credential-path?-classifies-paths
  (let [cwd (.cwd js/process)
        home (os/homedir)]
    (is (store/credential-path? cwd (str home "/.ssh/config")) ".ssh is a credential dir")
    (is (store/credential-path? cwd (str home "/.gnupg/gpg.conf")) ".gnupg is a credential dir")
    (is (not (store/credential-path? cwd "src/xi/rules.cljs")) "a source path is not credential")
    (is (not (store/credential-path? cwd nil)) "nil path is not credential")
    (is (not (store/credential-path? cwd "")) "blank path is not credential")))

(deftest enrich-request-populates-credential-only-when-needed
  (let [cwd (.cwd js/process)
        base (store/decision-request {:name "read" :arguments {:path (str (os/homedir) "/.ssh/config")}}
                                     {:cwd cwd})]
    (testing "no :credential rule → request is untouched"
      (is (not (contains? (store/enrich-request base [{:match {:tool :read}}])
                          :credential-path?))))
    (testing "a :credential rule → :credential-path? is computed"
      (is (true? (:credential-path?
                  (store/enrich-request
                   base [{:match {:tool :read :credential :read}}])))))))

(deftest enrich-request-populates-nodes-only-when-needed
  (if-not (ts/available?)
    (is true "skipped")
    (let [path (node-path/join (os/tmpdir)
                               (str (.getTime (js/Date.)) "-xi-store-node.ts"))
          _    (fs/writeFileSync path "export function fn0(a) {\n  return a;\n}\n")
          base (store/decision-request
                {:name "edit"
                 :arguments {:path path
                             :edits [{:oldText "return a;" :newText "return a + 1;"}]}}
                {:cwd (os/tmpdir)})]
      (testing "no :node rule → request is untouched"
        (is (not (contains? (store/enrich-request base [{:match {:tool :edit}}])
                            :nodes))))
      (testing "a :node rule → :nodes is computed and the enclosing def matches"
        (let [ruleset [{:match {:tool :edit :node {:type "function_declaration"}}
                        :action {:type :deny}}]
              req     (store/enrich-request base ruleset)]
          (is (some #(= "function_declaration" (:type %)) (:nodes req)))
          (is (= :deny (get-in (rules/first-match ruleset req) [:action :type])))))
      (fs/unlinkSync path))))

(deftest hardened-tier-prepended-and-flag-removable
  (let [rs (store/ordered-rules {} "r1" (os/tmpdir))]
    (testing "hardened rules are prepended above every config/runtime/default rule"
      (is (= :hardened (:scope (first rs))))
      (is (some #(= :hardened (:scope %)) rs))
      (is (= :default (:scope (last rs))) "defaults stay last"))
    (testing "hardened deny wins over a user allow-rule (higher precedence)"
      (let [state {:rooms {"r1" {:ext {:rules {:rules [{:match  {:tool :sh :cli "scp"}
                                                          :action {:type :allow}}]}}}}}
            ruleset (store/ordered-rules state "r1" (os/tmpdir))
            hit (rules/first-match ruleset {:tool :sh :cli "scp" :command "scp a b"})]
        (is (= :hardened (:scope hit)))
        (is (= :deny (:type (:action hit)))))))
  (testing "the flag drops the tier; re-enable restores it"
    (store/set-hardened-disabled! true)
    (is (not (some #(= :hardened (:scope %))
                   (store/ordered-rules {} "r1" (os/tmpdir)))))
    (store/set-hardened-disabled! false)
    (is (some #(= :hardened (:scope %))
              (store/ordered-rules {} "r1" (os/tmpdir))))))

(deftest hardened-sudo-and-remote-denies
  (let [ruleset (store/ordered-rules {} "r1" (os/tmpdir))
        hit (fn [req] (rules/first-match ruleset req))]
    (testing "sudo denied for both clj sh-outs and the bash tool"
      (is (= :deny (:type (:action (hit {:tool :sh :cli "sudo" :command "sudo rm"})))))
      (is (= :deny (:type (:action (hit {:tool :bash :command "sudo apt install"}))))))
    (testing "remote-copy shells denied"
      (is (= :deny (:type (:action (hit {:tool :sh :cli "rsync" :command "rsync a b"})))))
      (is (= :deny (:type (:action (hit {:tool :bash :command "scp a b"}))))))
    (testing "ssh is NOT hardened-denied for clj sh-outs (falls through)"
      (let [h (hit {:tool :sh :cli "ssh" :command "ssh host"})]
        (is (not (and h (= :hardened (:scope h)))))))))

(deftest hardened-shell-interpreter-via-clj-sh-denied
  (let [ruleset (store/ordered-rules {} "r1" (os/tmpdir))
        hit     (fn [req] (rules/first-match ruleset req))]
    (testing "shell interpreters run via clj (sh …) are hard-denied"
      (doseq [cli ["bash" "sh" "zsh" "fish" "dash" "ksh" "csh" "tcsh" "ash" "mksh"]]
        (let [h (hit {:tool :sh :cli cli :command (str cli " -lc 'grep x | head'")})]
          (is (= :deny (:type (:action h))) cli)
          (is (= :hardened (:scope h)) cli))))
    (testing "the real bash tool (:tool :bash) is untouched by this rule"
      (let [h (hit {:tool :bash :command "grep x | head"})]
        (is (not (and h (= :hardened (:scope h)) (= :sh (get-in h [:match :tool])))))))))

(deftest hardened-ssh-private-key-read-denied
  (let [ruleset (store/ordered-rules {} "r1" (os/tmpdir))
        hit     (fn [req] (rules/first-match ruleset req))
        denied? (fn [path]
                  (let [h (hit {:tool :read :path path})]
                    (and h (= :deny (:type (:action h))) (= :hardened (:scope h)))))]
    (testing "private key files under ~/.ssh are hard-denied (no allow button)"
      (is (denied? "/home/user/.ssh/id_rsa"))
      (is (denied? "/home/user/.ssh/id_ed25519"))
      (is (denied? "~/.ssh/id_ecdsa_sk"))
      (is (denied? "/home/user/.ssh/mycustomkey"))
      (is (denied? "/home/user/.ssh/keys/id_rsa"))
      (testing "also for grep/find/ls read surfaces"
        (is (= :deny (:type (:action (hit {:tool :grep :path "/home/user/.ssh/id_rsa"})))))))
    (testing "public keys and non-secret ssh files are NOT hardened-denied"
      (doseq [p ["/home/user/.ssh/id_rsa.pub"
                 "/home/user/.ssh/config"
                 "/home/user/.ssh/known_hosts"
                 "/home/user/.ssh/authorized_keys"]]
        (let [h (hit {:tool :read :path p})]
          (is (not (and h (= :hardened (:scope h)))) p))))))

(deftest hardened-ssh-private-key-shell-read-denied
  (let [ruleset (store/ordered-rules {} "r1" (os/tmpdir))
        hit     (fn [req] (rules/first-match ruleset req))
        denied? (fn [tool command]
                  (let [h (hit {:tool tool :command command})]
                    (and h (= :deny (:type (:action h))) (= :hardened (:scope h)))))]
    (testing "reading a private key through the shell is hard-denied (closes the sh-cat bypass)"
      (is (denied? :sh "cat ~/.ssh/id_rsa"))
      (is (denied? :sh "cat /home/user/.ssh/id_ed25519"))
      (is (denied? :sh "head -c 9 ~/.ssh/id_rsa"))
      (is (denied? :sh "base64 ~/.ssh/id_ecdsa_sk"))
      (is (denied? :sh "cp ~/.ssh/id_rsa /tmp/x"))
      (is (denied? :sh "cat ~/.ssh/keys/id_rsa"))
      (testing "same for the bash tool"
        (is (denied? :bash "cat ~/.ssh/id_rsa"))
        (is (denied? :bash "xxd ~/.ssh/id_ed25519 | head"))))
    (testing "public keys / non-secret ssh files / dir listings are NOT hardened-denied"
      (doseq [c ["cat ~/.ssh/id_rsa.pub"
                 "cat ~/.ssh/config"
                 "cat ~/.ssh/known_hosts"
                 "cat ~/.ssh/authorized_keys"
                 "ls ~/.ssh/"
                 "ls -la ~/.ssh"]]
        (let [h (hit {:tool :sh :cli (first (str/split c #"\s+")) :command c})]
          (is (not (and h (= :hardened (:scope h)))) c))))))

(deftest runtime-rules-precedence
  (testing "server rules come before session rules, each scope-tagged"
    (let [state {:ext    {:rules {:rules [{:match {:tool :write} :action {:type :deny}}]}}
                 :rooms  {"r1" {:ext {:rules {:rules [{:match {:tool :bash} :action {:type :ask}}]}}}}}
          rs (store/runtime-rules state "r1")]
      (is (= [:server :session] (map :scope rs)))
      (is (= :deny (:type (:action (first rs))))))))

(deftest parse-rules-config-requires-version
  (let [err (fn [data] (:error (store/parse-rules-config data)))]
    (testing "invalid files error"
      (is (re-find #"not valid EDN" (err nil)))
      (is (re-find #"bare rule vectors" (err [{:match {:tool :read}}])))
      (is (re-find #"missing required :version" (err {:rules []})))
      (is (re-find #"unsupported :version 2" (err {:version 2 :rules []})))
      (is (re-find #"unsupported :version \"1\"" (err {:version "1" :rules []})))
      (is (re-find #"missing required :type" (err {:version 1 :rules []})))
      (is (re-find #"wrong :type :xi/config" (err {:type :xi/config :version 1 :rules []})))
      (is (re-find #"unknown key\(s\) :default" (err {:type :xi/rules :version 1 :default []})))
      (is (re-find #":rules must be a vector" (err {:type :xi/rules :version 1 :rules {:a 1}})))
      (is (re-find #"aliases under :defaults"
                   (err {:type :xi/rules :version 1 :rules [:xi.rules.defaults/plan-mode]})))
      (is (re-find #"unknown default-rules alias"
                   (err {:type :xi/rules :version 1 :defaults [:xi.rules.defaults/nope]}))))
    (testing "a valid file parses; :defaults nil unless set"
      (is (= {:rules [] :defaults nil} (store/parse-rules-config {:type :xi/rules :version 1})))
      (is (= {:rules [{:match {:tool :read}}] :defaults nil}
             (store/parse-rules-config {:type :xi/rules :version 1 :rules [{:match {:tool :read}}]})))
      (is (= [] (:defaults (store/parse-rules-config {:type :xi/rules :version 1 :defaults []}))))
      (is (= (defaults/expand [:xi.rules.defaults/plan-mode])
             (:defaults (store/parse-rules-config
                         {:type :xi/rules :version 1 :defaults [:xi.rules.defaults/plan-mode]})))))))

(deftest extensions-moved-to-the-config-file
  ;; User extensions are enabled in config.edn now; a rules file still naming
  ;; them fails closed with a pointer instead of silently ignoring the list.
  (is (re-find #":extensions moved to .*config\.edn"
               (:error (store/parse-rules-config
                        {:type :xi/rules :version 1 :extensions ["kb.cljs"] :rules []})))))

(defn- with-repo-rules
  "Run `f` with a throwaway git repo whose .xi/rules.edn holds `content`
   (a string; nil = no file). `f` gets the repo dir."
  [content f]
  (let [repo (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-rules-file-"))
        file (node-path/join repo ".xi" "rules.edn")]
    (fs/mkdirSync (node-path/join repo ".git"))
    (fs/mkdirSync (node-path/join repo ".xi"))
    (when content (fs/writeFileSync file content))
    (store/clear-cache!)
    (try (f repo file)
         (finally
           (store/clear-cache!)
           (fs/rmSync repo #js {:recursive true :force true})))))

(deftest repo-defaults-replace-the-default-tier
  (with-repo-rules
    "{:type :xi/rules :version 1 :defaults [:xi.rules.defaults/mcp-confirm]}"
    (fn [repo _]
      (is (= (defaults/expand [:xi.rules.defaults/mcp-confirm])
             (store/default-rules repo)))
      (let [rs (store/ordered-rules {} "r1" repo)]
        (is (= :default (:scope (last rs))))
        (is (not-any? #(= (:match %) {:tool :sh}) rs)
            "the dropped clj-sh bundle is gone"))))
  (with-repo-rules
    "{:type :xi/rules :version 1 :rules []}"
    (fn [repo _]
      (testing "no :defaults in the repo file → falls through (global / built-in)"
        (is (seq (store/default-rules repo)))))))

(deftest invalid-rules-file-fails-closed
  (with-repo-rules
    "[{:match {:tool :read} :action {:type :allow}}]"
    (fn [repo file]
      (let [rs  (store/ordered-rules {} "r1" repo)
            hit (rules/first-match rs {:tool :read :path "README.md"})]
        (is (= :repo (:scope hit)))
        (is (= :deny (get-in hit [:action :type])))
        (is (str/includes? (get-in hit [:action :message]) file))
        (is (str/includes? (get-in hit [:action :message]) "bare rule vectors")))
      (testing "the invalid file isn't rewritten by a saved rule"
        (is (:error (store/append-rule-file! :repo repo {:match {:tool :ls}})))
        (is (= "[{:match {:tool :read} :action {:type :allow}}]"
               (str (fs/readFileSync file "utf8"))))))))

(deftest append-rule-file-writes-versioned-file
  (with-repo-rules
    nil
    (fn [repo file]
      (is (= {:file file}
             (store/append-rule-file! :repo repo {:match {:tool :ls :path #"\.md$"}
                                                  :action {:type :allow}
                                                  :scope :session})))
      (let [data (store/read-rule-edn (str (fs/readFileSync file "utf8")))]
        (is (= :xi/rules (:type data)))
        (is (= 1 (:version data)))
        (is (= 1 (count (:rules data))))
        (is (not (contains? (first (:rules data)) :scope)))
        (is (= "\\.md$" (.-source (get-in data [:rules 0 :match :path]))))
        (is (not (contains? data :defaults))))))
  (with-repo-rules
    "{:type :xi/rules :version 1 :defaults [:xi.rules.defaults/plan-mode] :rules [{:match {:tool :read} :action {:type :allow}}]}"
    (fn [repo file]
      (store/append-rule-file! :repo repo {:match {:tool :ls} :action {:type :allow}})
      (let [data (store/read-rule-edn (str (fs/readFileSync file "utf8")))]
        (is (= [:xi.rules.defaults/plan-mode] (:defaults data))
            ":defaults kept as written (unexpanded aliases)")
        (is (= [:ls :read] (map #(get-in % [:match :tool]) (:rules data)))
            "new rule prepended")))))

(deftest xi-rules-file-change-detection
  (let [dir        (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-rules-edit-"))
        xi-file    (node-path/join dir "xi" "rules.edn")
        other-file (node-path/join dir "other" "rules.edn")
        legacy     (node-path/join dir "legacy" "rules.edn")
        not-rules  (node-path/join dir "settings.edn")
        _          (doseq [d ["xi" "other" "legacy"]]
                     (fs/mkdirSync (node-path/join dir d)))
        _          (fs/writeFileSync xi-file "{:type :xi/rules :version 1 :rules [{:match {:tool :read :path #\"x\"} :action {:type :allow}}]}")
        _          (fs/writeFileSync other-file "{:lint {:level :warn}}")
        _          (fs/writeFileSync legacy "[{:match {:tool :read} :action {:type :allow}}]")
        _          (fs/writeFileSync not-rules "{:type :xi/rules :version 1}")
        change?    (fn [tool args]
                     (store/xi-rules-file-change?
                      (store/decision-request {:name tool :arguments args} {:cwd dir})))]
    (try
      (testing "write/edit of a versioned rules.edn is an xi rules-file change"
        (is (change? "edit" {:path xi-file :edits [{:oldText ":read" :newText ":ls"}]}))
        (is (change? "write" {:path "xi/rules.edn" :content "{}"}) "relative path"))
      (testing "unrelated rules.edn / non-rules.edn files are left alone"
        (is (not (change? "edit" {:path other-file :edits [{:oldText ":warn" :newText ":off"}]})))
        (is (not (change? "write" {:path not-rules :content "{:version 2}"})))
        (is (not (change? "edit" {:path legacy :edits [{:oldText ":read" :newText ":ls"}]}))))
      (testing "introducing :version (creating or migrating an xi file) counts"
        (is (change? "edit" {:path legacy :edits [{:oldText "[" :newText "{:type :xi/rules :version 1 :rules ["}]}))
        (is (change? "write" {:path (node-path/join dir "new" "rules.edn")
                              :content "{:type :xi/rules :version 1 :rules []}"})))
      (testing "shell / clj writes naming a versioned rules.edn count; reads don't"
        (is (change? "bash" {:command (str "sed -i s/read/ls/ " xi-file)}))
        (is (change? "bash" {:command "cp /tmp/x xi/rules.edn"}) "relative to cwd")
        (is (change? "clj" {:code (str "(spit \"" xi-file "\" \"{}\")")}))
        (is (not (change? "bash" {:command (str "cat " xi-file)})))
        (is (not (change? "bash" {:command (str "sed -i s/a/b/ " other-file)}))))
      (testing "other tools never count"
        (is (not (change? "read" {:path xi-file}))))
      (testing "hardened: always asks, above any user allow-rule"
        (let [state   {:rooms {"r1" {:ext {:rules {:rules [{:match  {:tool #{:write :edit}}
                                                               :action {:type :allow}}]}}}}}
              ruleset (store/ordered-rules state "r1" dir)
              hit     (fn [tool args]
                        (rules/first-match
                         ruleset
                         (store/enrich-request
                          (store/decision-request {:name tool :arguments args} {:cwd dir})
                          ruleset)))
              h       (hit "edit" {:path xi-file :edits [{:oldText ":read" :newText ":ls"}]})]
          (is (= :hardened (:scope h)))
          (is (= :ask (get-in h [:action :type])))
          (is (= [:yes :no :repo] (get-in h [:action :options])) "no [a]lways grant")
          (is (= :session (:scope (hit "edit" {:path other-file
                                              :edits [{:oldText ":warn" :newText ":off"}]})))
              "an unrelated rules.edn falls through to the user's allow")))
      (finally
        (fs/rmSync dir #js {:recursive true :force true})))))

(deftest operands-within-repo
  (let [repo (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-within-"))
        _    (fs/mkdirSync (node-path/join repo ".git"))
        _    (fs/mkdirSync (node-path/join repo "src"))
        in?  (fn [& argv] (store/operands-within-repo? repo repo (vec argv)))]
    (try
      (testing "operands inside the repo (or tmp) match"
        (is (in? "mv" "a.txt" "src/b.txt"))
        (is (in? "cp" "-rv" "src" "src2"))
        (is (in? "mkdir" "-p" "src/x/y"))
        (is (in? "mv" "src/a" (node-path/join (os/tmpdir) "xi-junk"))))
      (testing "any operand outside the repo → no match"
        (is (not (in? "mv" "a" "/etc/x")))
        (is (not (in? "cp" (node-path/join (os/homedir) ".bashrc") "a"))))
      (testing "the repo root, .git/ and .xi/ are off-limits (even under tmp)"
        (is (not (in? "mv" repo "/tmp/r")))
        (is (not (in? "mv" "." "x")))
        (is (not (in? "mv" "hook" ".git/hooks/pre-commit")))
        (is (not (in? "mv" ".xi" "old"))))
      (testing "long / glued-value flags and missing operands → no match"
        (is (not (in? "mv" "--target-directory=/etc" "a")))
        (is (not (in? "mv" "-t/etc" "a")))
        (is (not (in? "mv" "--" "a" "b")))
        (is (not (in? "mkdir" "-p"))))
      (testing "no git repo → no match"
        (is (not (store/operands-within-repo? repo nil ["mv" "a" "b"]))))
      (finally
        (fs/rmSync repo #js {:recursive true :force true})))))

(deftest operands-git-tracked
  ;; A real (index-only, no commit needed) git repo: tracked = in the index.
  (let [repo (fs/realpathSync (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-tracked-")))
        git  (fn [& args] (cp/spawnSync "git" (clj->js args) #js {:cwd repo :encoding "utf8"}))
        file (fn [rel s]
               (fs/mkdirSync (node-path/dirname (node-path/join repo rel)) #js {:recursive true})
               (fs/writeFileSync (node-path/join repo rel) s))
        ok?  (fn [& argv] (store/operands-git-tracked? repo repo (vec argv)))]
    (try
      (git "init" "-q")
      (file "src/a.clj" "a")
      (file "src/b.clj" "b")
      (file "mixed/t.clj" "t")
      (file "logs/keep.clj" "k")
      (file ".gitignore" "*.log\n")
      (git "add" "src" "mixed/t.clj" "logs/keep.clj" ".gitignore")
      (file "untracked.txt" "u")
      (file "mixed/u.txt" "u")
      (file "logs/out.log" "ignored")
      (testing "a tracked file / an all-tracked directory match"
        (is (ok? "rm" "src/a.clj"))
        (is (ok? "rm" "-f" "src/a.clj" "mixed/t.clj"))
        (is (ok? "rm" "-r" "src"))
        (is (ok? "rm" "-r" (node-path/join repo "src")) "absolute operand"))
      (testing "untracked or ignored content never matches"
        (is (not (ok? "rm" "untracked.txt")))
        (is (not (ok? "rm" "missing.txt")))
        (is (not (ok? "rm" "src/a.clj" "untracked.txt")) "one untracked operand spoils it")
        (is (not (ok? "rm" "-r" "mixed")) "dir holding an untracked file")
        (is (not (ok? "rm" "-r" "logs")) "dir holding an ignored file"))
      (testing "the repo root, .git/, outside paths and long flags → no match"
        (is (not (ok? "rm" "-r" ".")))
        (is (not (ok? "rm" "-r" ".git")))
        (is (not (ok? "rm" "/etc/passwd")))
        (is (not (ok? "rm" "--force" "src/a.clj")))
        (is (not (ok? "rm" "-r"))))
      (testing "no git repo → no match"
        (is (not (store/operands-git-tracked? repo nil ["rm" "src/a.clj"]))))
      (finally
        (fs/rmSync repo #js {:recursive true :force true})))))

;; ── chained bash detection ───────────────────────────────────────────────────

(deftest chained-command-detection
  (testing "chained/piped commands are flagged"
    (is (store/chained-command? "S=/tmp/x; wc -l $S/a $S/b"))
    (is (store/chained-command? "grep foo *.clj | head -5"))
    (is (store/chained-command? "npm install && npm test"))
    (is (store/chained-command? "cat a.txt\ncat b.txt"))
    (is (store/chained-command? "echo $(date)"))
    (is (store/chained-command? "echo `date`"))
    (is (store/chained-command? "sleep 100 &")))
  (testing "single plain commands pass"
    (is (not (store/chained-command? "git status")))
    (is (not (store/chained-command? "npm test")))
    (is (not (store/chained-command? "ls -la src")))
    (is (not (store/chained-command? "steam-run npx biome check --write .")))) 
  (testing "separators inside quotes don't count"
    (is (not (store/chained-command? "git commit -m 'a; b && c'")))
    (is (not (store/chained-command? "grep \"a|b\" file.txt"))))
  (testing "redirections are not composition"
    (is (not (store/chained-command? "deploy app:prod --service x 2>&1")))
    (is (not (store/chained-command? "npm test >&2")))
    (is (not (store/chained-command? "npm test &> out.log")))))

(deftest enrich-adds-chained-and-bb-trust-only-when-a-rule-needs-them
  (let [chained [{:match {:tool :bash :chained true} :action {:type :deny}}]
        bb      [{:match {:tool :bb :bb-trusted false} :action {:type :ask}}]]
    (is (true? (:chained? (store/enrich-request {:tool :bash :command "a | b"} chained))))
    (is (false? (:chained? (store/enrich-request {:tool :bash :command "ls"} chained))))
    (is (not (contains? (store/enrich-request {:tool :bash :command "a | b"} []) :chained?)))
    (is (not (contains? (store/enrich-request {:tool :sh :command "a | b"} chained) :chained?))
        "only the bash tool")
    (is (false? (:bb-trusted? (store/enrich-request {:tool :bb :effective-cwd "/tmp"} bb)))
        "no trusted bb.edn above /tmp")
    (is (not (contains? (store/enrich-request {:tool :bash :effective-cwd "/tmp"} bb)
                        :bb-trusted?)))
    (testing "matching"
      (is (rules/matches? (first chained) {:tool :bash :chained? true}))
      (is (not (rules/matches? (first chained) {:tool :bash :chained? false})))
      (is (rules/matches? (first bb) {:tool :bb :bb-trusted? false}))
      (is (not (rules/matches? (first bb) {:tool :bb :bb-trusted? true})))
      (is (not (rules/matches? (first bb) {:tool :bb})) "unknown trust never matches"))))
