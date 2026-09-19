(ns xi.ext.clj-test
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.ext.clj :as clj-ext]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(def ^:private run-clj
  (get (:tool-registry clj-ext/extension) "clj"))

(defn- result-text [res]
  (-> res :content first :text))

(defn- eval! [code & [{:keys [allowed cwd room-id]}]]
  (run-clj {:code code :_allowed (or allowed []) :_room-id (or room-id :test)}
           {:cwd (or cwd (os/tmpdir))}))

;; ── scan-sh-calls (gate pre-scan) ────────────────────────────────────────────

(deftest scan-literals
  (let [scan (clj-ext/scan-sh-calls "(sh \"ffmpeg\" \"-i\" x) (sh \"convert\" a b)")]
    (is (= #{"ffmpeg" "convert"} (:literals scan)))
    (is (not (:dynamic? scan)))
    (is (not (:shell-c? scan)))))

(deftest scan-nested-and-threaded
  (let [scan (clj-ext/scan-sh-calls "(when x (->> files (map #(sh \"gzip\" %))))")]
    (is (= #{"gzip"} (:literals scan)))))

(deftest scan-dynamic
  (let [scan (clj-ext/scan-sh-calls "(let [b \"ff\"] (sh b \"-i\"))")]
    (is (= #{} (:literals scan)))
    (is (:dynamic? scan))))

(deftest scan-shell-c
  (is (:shell-c? (clj-ext/scan-sh-calls "(sh \"bash\" \"-c\" \"ls | wc -l\")")))
  (is (not (:shell-c? (clj-ext/scan-sh-calls "(sh \"bash\" \"script.sh\")")))))

(deftest scan-parse-error
  (is (:parse-error (clj-ext/scan-sh-calls "(sh \"x\""))))

(deftest scan-no-sh
  (let [scan (clj-ext/scan-sh-calls "(+ 1 2)")]
    (is (= #{} (:literals scan)))
    (is (not (:dynamic? scan)))))

;; ── chained bash detection ───────────────────────────────────────────────────

(deftest chained-bash-detection
  (testing "chained/piped commands are flagged"
    (is (clj-ext/chained-bash? "S=/tmp/x; wc -l $S/a $S/b"))
    (is (clj-ext/chained-bash? "grep foo *.clj | head -5"))
    (is (clj-ext/chained-bash? "npm install && npm test"))
    (is (clj-ext/chained-bash? "cat a.txt\ncat b.txt"))
    (is (clj-ext/chained-bash? "echo $(date)"))
    (is (clj-ext/chained-bash? "echo `date`"))
    (is (clj-ext/chained-bash? "sleep 100 &")))
  (testing "single plain commands pass"
    (is (not (clj-ext/chained-bash? "git status")))
    (is (not (clj-ext/chained-bash? "npm test")))
    (is (not (clj-ext/chained-bash? "ls -la src")))
    (is (not (clj-ext/chained-bash? "steam-run npx biome check --write .")))) 
  (testing "separators inside quotes don't count"
    (is (not (clj-ext/chained-bash? "git commit -m 'a; b && c'")))
    (is (not (clj-ext/chained-bash? "grep \"a|b\" file.txt")))))

;; ── eval basics ──────────────────────────────────────────────────────────────

(deftest eval-value
  (let [res (eval! "(+ 1 2)")]
    (is (not (:is-error res)))
    (is (str/includes? (result-text res) "=> 3"))))

(deftest eval-println-captured
  (let [res (eval! "(println \"hello\") :done")]
    (is (str/includes? (result-text res) "hello"))
    (is (str/includes? (result-text res) "=> :done"))))

(deftest eval-string-alias
  (let [res (eval! "(str/upper-case \"ab\")")]
    (is (str/includes? (result-text res) "=> \"AB\""))))

(deftest eval-multiline-string-raw
  (let [res (eval! "\"a\\nb\\nc\"")]
    ;; multi-line strings render raw (newlines preserved, no escaped \n)
    (is (str/includes? (result-text res) "=> a\nb\nc"))
    (is (not (str/includes? (result-text res) "\\n")))))

(deftest eval-multiline-string-vector-raw
  (let [res (eval! "[\"a\\n1\" \"b\\n2\"]")]
    ;; a vector of multi-line strings renders each raw, no escaped \n or brackets
    (is (str/includes? (result-text res) "=> a\n1\nb\n2"))
    (is (not (str/includes? (result-text res) "\\n")))
    (is (not (str/includes? (result-text res) "[\"")))))

(deftest eval-short-string-vector-pr-str
  (let [res (eval! "[\"c.txt\" \"c\" \"/a/b\"]")]
    ;; no element spans lines, so it stays a readable pr-str vector
    (is (str/includes? (result-text res) "=> [\"c.txt\" \"c\" \"/a/b\"]"))))

(deftest eval-mixed-vector-pr-str
  (let [res (eval! "[\"a\" 1]")]
    ;; not all-strings, so it stays a normal pr-str vector
    (is (str/includes? (result-text res) "=> [\"a\" 1]"))))

(deftest eval-error-reported
  (let [res (eval! "(undefined-fn 1)")]
    (is (:is-error res))
    (is (str/includes? (result-text res) "Error:"))))

(deftest eval-no-js-interop
  (is (:is-error (eval! "(js/process.exit 1)"))))

(deftest repl-persistence
  (eval! "(def xi-test-x 41)" {:room-id :persist})
  (let [res (eval! "(inc xi-test-x)" {:room-id :persist})]
    (is (str/includes? (result-text res) "=> 42"))))

;; ── JSON namespaces ────────────────────────────────────────────────────────────

(deftest json-read-str
  (let [res (eval! "(json/read-str \"{\\\"a\\\":1,\\\"b\\\":[2,3]}\")")]
    (is (not (:is-error res)))
    (is (str/includes? (result-text res) "=> {\"a\" 1, \"b\" [2 3]}"))))

(deftest json-read-str-keywordize
  (let [res (eval! "(json/read-str \"{\\\"a\\\":1}\" :key-fn keyword)")]
    (is (str/includes? (result-text res) "=> {:a 1}"))))

(deftest json-write-str
  (let [res (eval! "(= (json/read-str (json/write-str {\"a\" 1 \"b\" [2 3]})) {\"a\" 1 \"b\" [2 3]})")]
    (is (str/includes? (result-text res) "=> true"))))

(deftest cheshire-parse-string
  (let [res (eval! "(cheshire.core/parse-string \"{\\\"a\\\":1}\" true)")]
    (is (not (:is-error res)))
    (is (str/includes? (result-text res) "=> {:a 1}"))))

(deftest cheshire-generate-string
  (let [res (eval! "(= (cheshire.core/parse-string (cheshire.core/generate-string {:a 1}) true) {:a 1})")]
    (is (str/includes? (result-text res) "=> true"))))

;; ── numeric parsing (parse-long / Long/parseLong) ────────────────────────────

(deftest parse-long-fn
  (let [res (eval! "(parse-long \"42\")")]
    (is (not (:is-error res)) (result-text res))
    (is (str/includes? (result-text res) "=> 42"))))

(deftest parse-long-invalid-nil
  (let [res (eval! "(parse-long \"nope\")")]
    (is (str/includes? (result-text res) "=> nil"))))

(deftest parse-double-fn
  (let [res (eval! "(parse-double \"3.5\")")]
    (is (str/includes? (result-text res) "=> 3.5"))))

(deftest long-parse-long-static
  (let [res (eval! "(Long/parseLong \"1024\")")]
    (is (not (:is-error res)) (result-text res))
    (is (str/includes? (result-text res) "=> 1024"))))

;; ── fs helpers ───────────────────────────────────────────────────────────────

(deftest tmpdir-and-file-roundtrip
  (let [res (eval! "(let [d (tmpdir)]
                      (spit (str d \"/a.txt\") \"line1\\nline2\")
                      [(cat (str d \"/a.txt\")) (ls d)])")]
    (is (not (:is-error res)) (result-text res))
    (is (str/includes? (result-text res) "line1\\nline2"))
    (is (str/includes? (result-text res) "a.txt"))))

(deftest write-outside-cwd-blocked
  (let [res (eval! (str "(spit \"" (os/homedir) "/xi-clj-escape.txt\" \"nope\")"))]
    (is (:is-error res))
    (is (str/includes? (result-text res) "writes are limited"))))

(deftest read-credentials-blocked
  (let [res (eval! (str "(cat \"" (os/homedir) "/.ssh/id_rsa\")"))]
    (is (:is-error res))
    (is (str/includes? (result-text res) "blocked"))))

;; ── sh runtime allowlist ─────────────────────────────────────────────────────

(deftest sh-blocked-without-approval
  (let [res (eval! "(sh \"echo\" \"hi\")")]
    (is (:is-error res))
    (is (str/includes? (result-text res) "not approved"))))

(deftest sh-runs-when-allowed
  (let [res (eval! "(:out (sh \"echo\" \"hi\"))" {:allowed ["echo"]})]
    (is (not (:is-error res)) (result-text res))
    (is (str/includes? (result-text res) "hi"))))

;; ── tool gate ──────────────────────────────────────────────────────────────────

(def ^:private gate (:tool-gate clj-ext/extension))

(defn- gate-ctx []
  {:get-state (fn [] {}) :room-id "r" :confirm! nil :dispatch! (fn [_])})

(defn- intercepted-text [res]
  (get-in res [:result :content 0 :text]))

(deftest gate-bounces-chained-bash
  (let [res (gate {:name "bash" :arguments {:command "ls src | head -3"}}
                  (gate-ctx))]
    (is (:intercepted res))
    (is (str/includes? (intercepted-text res) "clj tool"))))

(deftest gate-passes-plain-bash
  (let [tc {:name "bash" :arguments {:command "git status"}}]
    (is (= tc (gate tc (gate-ctx))))))

(deftest scan-commands-joined
  (is (= ["git status --short"]
         (:commands (clj-ext/scan-sh-calls "(sh \"git\" \"status\" \"--short\")")))))

(deftest gate-blocks-sudo
  (let [res (gate {:name "clj" :arguments {:code "(sh \"sudo\" \"rm\" \"-rf\" \"/\")"}}
                  (gate-ctx))]
    (is (:intercepted res))
    (is (str/includes? (intercepted-text res) "sudo"))))

(deftest gate-blocks-sudo-nested-in-command
  (let [res (gate {:name "clj" :arguments {:code "(sh \"env\" \"sudo\" \"whoami\")"}}
                  (gate-ctx))]
    (is (:intercepted res))
    (is (str/includes? (intercepted-text res) "sudo"))))

(deftest gate-blocks-remote-clis
  (let [res (gate {:name "clj" :arguments {:code "(sh \"scp\" \"a\" \"b\")"}}
                  (gate-ctx))]
    (is (:intercepted res))
    (is (str/includes? (intercepted-text res) "remote shell"))))

(deftest gate-asks-approval-for-ssh
  (async done
    (let [ctx (assoc (gate-ctx) :confirm! (fn [_ & _] (js/Promise.resolve false)))
          res (gate {:name "clj" :arguments {:code "(sh \"ssh\" \"host\" \"ls\")"}}
                     ctx)]
      (-> (js/Promise.resolve res)
          (.then (fn [r]
                   (is (:intercepted r))
                   (is (str/includes? (intercepted-text r) "user denied"))
                   (done)))))))

(deftest extension-removes-bash
  (is (contains? (:remove-tools clj-ext/extension) "bash")))

(deftest gate-hints-curl-helper
  (let [res (gate {:name "clj" :arguments {:code "(sh \"curl\" \"https://x.y\")"}}
                  (gate-ctx))]
    (is (:intercepted res))
    (is (str/includes? (intercepted-text res) "(curl url)"))))

(deftest curl-rejects-non-http
  (let [res (eval! "(curl \"file:///etc/passwd\")")]
    (is (:is-error res))
    (is (str/includes? (result-text res) "http(s)"))))

(deftest gate-autoruns-ls-with-hint
  ;; safe read-only CLIs run anyway (no bounce, no approval) — the tool-call
  ;; passes through with the CLI allowed and a helper hint attached.
  (async done
    (-> (js/Promise.resolve
         (gate {:name "clj" :arguments {:code "(sh \"ls\" \"src\")"}}
               (gate-ctx)))
        (.then (fn [res]
                 (is (not (:intercepted res)))
                 (is (some #{"ls"} (get-in res [:arguments :_allowed])))
                 (is (str/includes? (str (get-in res [:arguments :_hint])) "(ls dir)"))
                 (done))))))

(deftest gate-bounces-write-clis
  ;; write CLIs keep the hard bounce — raw sh would bypass the helpers'
  ;; write-path guard.
  (let [res (gate {:name "clj" :arguments {:code "(sh \"sed\" \"-i\" \"s/a/b/\" \"f\")"}}
                  (gate-ctx))]
    (is (:intercepted res))
    (is (str/includes? (intercepted-text res) "builtin helper"))))

(deftest clj-tool-appends-hint
  (let [res (run-clj {:code "(+ 1 2)" :_allowed [] :_room-id :test
                      :_hint "hint: use (ls dir)"}
                     {:cwd (os/tmpdir)})]
    (is (str/includes? (result-text res) "3"))
    (is (str/includes? (result-text res) "hint: use (ls dir)"))))

;; ── coreutils helpers ─────────────────────────────────────────────────────────

(deftest stat-helper
  (let [res (eval! "(let [d (tmpdir) f (str d \"/s.txt\")]
                      (spit f \"abc\")
                      (select-keys (stat f) [:size :file? :dir?]))")]
    (is (not (:is-error res)) (result-text res))
    (is (str/includes? (result-text res) ":size 3"))
    (is (str/includes? (result-text res) ":file? true"))))

(deftest path-helpers
  (let [res (eval! "[(basename \"/a/b/c.txt\") (basename \"/a/b/c.txt\" \".txt\") (dirname \"/a/b/c.txt\")]")]
    (is (str/includes? (result-text res) "[\"c.txt\" \"c\" \"/a/b\"]"))))

(deftest which-helper
  (let [res (eval! "[(boolean (which \"git\")) (which \"definitely-not-a-cli-xyz\")]")]
    (is (str/includes? (result-text res) "[true nil]"))))

(deftest touch-and-realpath
  (let [res (eval! "(let [d (tmpdir) f (str d \"/t.txt\")]
                      (touch f)
                      [(:file? (stat f)) (string? (realpath f))])")]
    (is (not (:is-error res)) (result-text res))
    (is (str/includes? (result-text res) "[true true]"))))

(deftest rm-helper
  (let [res (eval! "(let [d (tmpdir) f (str d \"/r.txt\")]
                      (spit f \"x\")
                      [(:file? (stat f)) (do (rm f) (some #{\"r.txt\"} (ls d)))])")]
    (is (not (:is-error res)) (result-text res))
    (is (str/includes? (result-text res) "[true nil]"))))

(deftest rm-helper-force-missing-is-noop
  ;; (rm f) uses force — deleting a non-existent path is a no-op, not an error.
  (let [res (eval! "(do (rm (str (tmpdir) \"/does-not-exist.txt\")) :ok)")]
    (is (not (:is-error res)) (result-text res))
    (is (str/includes? (result-text res) ":ok"))))

(deftest gate-autoruns-stat-with-hint
  (async done
    (-> (js/Promise.resolve
         (gate {:name "clj" :arguments {:code "(sh \"stat\" \"-c\" \"%Y\" \"f\")"}}
               (gate-ctx)))
        (.then (fn [res]
                 (is (not (:intercepted res)))
                 (is (some #{"stat"} (get-in res [:arguments :_allowed])))
                 (is (str/includes? (str (get-in res [:arguments :_hint])) "(stat f)"))
                 (done))))))

(deftest gate-autoruns-text-clis-with-clojure-hint
  (async done
    (-> (js/Promise.resolve
         (gate {:name "clj" :arguments {:code "(sh \"wc\" \"-l\" \"f\")"}}
               (gate-ctx)))
        (.then (fn [res]
                 (is (not (:intercepted res)))
                 (is (some #{"wc"} (get-in res [:arguments :_allowed])))
                 (is (str/includes? (str (get-in res [:arguments :_hint])) "str/split-lines"))
                 (done))))))

(deftest gate-autoruns-rm-with-tmp-note
  ;; (sh "rm" …) is auto-allowed; a /tmp target adds the "unnecessary" note.
  (async done
    (-> (js/Promise.resolve
         (gate {:name "clj" :arguments {:code "(sh \"rm\" \"-f\" \"/tmp/org-display-test.org\")"}}
               (gate-ctx)))
        (.then (fn [res]
                 (is (not (:intercepted res)))
                 (is (some #{"rm"} (get-in res [:arguments :_allowed])))
                 (is (str/includes? (str (get-in res [:arguments :_hint])) "(rm f)"))
                 (is (str/includes? (str (get-in res [:arguments :_hint])) "temporary"))
                 (done))))))

(deftest gate-autoruns-rm-rf-without-approval
  ;; rm -rf is exempted from the guarded confirm in clj — even with no
  ;; confirm! attached it passes straight through (autorun), not blocked.
  (async done
    (-> (js/Promise.resolve
         (gate {:name "clj" :arguments {:code "(sh \"rm\" \"-rf\" \"/tmp/scratch\")"}}
               (gate-ctx)))
        (.then (fn [res]
                 (is (not (:intercepted res)))
                 (is (some #{"rm"} (get-in res [:arguments :_allowed])))
                 (done))))))

;; ── git helper ───────────────────────────────────────────────────────────────────────

(deftest git-subcommand-detection
  (is (= "status" (clj-ext/git-subcommand ["status" "--short"])))
  (is (= "log" (clj-ext/git-subcommand ["--no-pager" "log"])))
  (is (= "push" (clj-ext/git-subcommand ["-C" "." "push"])))
  (is (nil? (clj-ext/git-subcommand []))))

(deftest git-helper-runs-without-approval
  (let [res (eval! "(git \"version\")")]
    (is (not (:is-error res)) (result-text res))
    (is (str/includes? (result-text res) "git version"))))

(deftest git-helper-denies-push
  (let [res (eval! "(git \"push\" \"origin\")")]
    (is (:is-error res))
    (is (str/includes? (result-text res) "(sh \"git\" \"push\""))))

(deftest git-helper-denies-dynamic-push
  (let [res (eval! "(let [c \"push\"] (git c))")]
    (is (:is-error res))))

(deftest gate-autoruns-git-with-hint
  (async done
    (-> (js/Promise.resolve
         (gate {:name "clj" :arguments {:code "(sh \"git\" \"status\")"}}
               (gate-ctx)))
        (.then (fn [res]
                 (is (not (:intercepted res)))
                 (is (some #{"git"} (get-in res [:arguments :_allowed])))
                 (is (str/includes? (str (get-in res [:arguments :_hint])) "(git \"status\""))
                 (done))))))

(deftest gate-lets-sh-git-push-through-to-approval
  ;; headless ctx (no confirm!) → push is not bounced to the helper hint but
  ;; falls through to the approval flow, which blocks without a client.
  ;; The guarded-pattern branch makes this path async (a promise chain).
  (async done
    (-> (js/Promise.resolve
         (gate {:name "clj" :arguments {:code "(sh \"git\" \"push\" \"origin\")"}}
               (gate-ctx)))
        (.then (fn [res]
                 (is (:intercepted res))
                 (is (str/includes? (intercepted-text res) "need approval"))
                 (is (not (str/includes? (intercepted-text res) "builtin helper")))
                 (done))))))
