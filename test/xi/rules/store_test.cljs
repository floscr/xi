(ns xi.rules.store-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.rules :as rules]
            [xi.rules.store :as store]
            [xi.ext.treesitter.parse :as ts]
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
      (is (denied? "/home/floscr/.ssh/id_rsa"))
      (is (denied? "/home/floscr/.ssh/id_ed25519"))
      (is (denied? "~/.ssh/id_ecdsa_sk"))
      (is (denied? "/home/floscr/.ssh/mycustomkey"))
      (is (denied? "/home/floscr/.ssh/keys/id_rsa"))
      (testing "also for grep/find/ls read surfaces"
        (is (= :deny (:type (:action (hit {:tool :grep :path "/home/floscr/.ssh/id_rsa"})))))))
    (testing "public keys and non-secret ssh files are NOT hardened-denied"
      (doseq [p ["/home/floscr/.ssh/id_rsa.pub"
                 "/home/floscr/.ssh/config"
                 "/home/floscr/.ssh/known_hosts"
                 "/home/floscr/.ssh/authorized_keys"]]
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
      (is (denied? :sh "cat /home/floscr/.ssh/id_ed25519"))
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
