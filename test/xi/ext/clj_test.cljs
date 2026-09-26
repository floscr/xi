(ns xi.ext.clj-test
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.ext.clj :as clj-ext]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(defn- result-text [res]
  (-> res :content first :text))

(defn- eval!
  "Evaluate synchronously via the worker-side entry (xi.ext.clj/eval-message).
   The real clj tool goes through a worker thread (spawned off process.argv[1]
   by xi.cli's isMainThread guard), which doesn't exist in the test bundle."
  [code & [{:keys [allowed allowed-writes allowed-reads allowed-bg cwd room-id]}]]
  (clj-ext/reply->result
   (clj-ext/eval-message #js {:id      0
                              :kind    "clj"
                              :code    code
                              :roomId  (str (or room-id :test))
                              :allowed (clj->js (or allowed []))
                              :allowedWrites (clj->js (or allowed-writes []))
                              :allowedReads  (clj->js (or allowed-reads []))
                              :allowedBg     (clj->js (or allowed-bg []))
                              :cwd     (or cwd (os/tmpdir))})
   nil))

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

;; ── scan-write-paths (write-helper gate pre-scan) ────────────────────────────

(deftest scan-write-paths-literals
  (testing "each write helper's target arg positions are collected"
    (is (= ["/a/b.txt"] (clj-ext/scan-write-paths "(spit \"/a/b.txt\" \"x\")")))
    (is (= ["/a" "/b"]  (clj-ext/scan-write-paths "(mv \"/a\" \"/b\")")))
    (is (= ["/dst"]     (clj-ext/scan-write-paths "(cp \"/src\" \"/dst\")")))
    (is (= ["/d"]       (clj-ext/scan-write-paths "(mkdir \"/d\")")))
    (is (= ["/t"]       (clj-ext/scan-write-paths "(touch \"/t\")")))
    (is (= ["/x" "/y"]  (clj-ext/scan-write-paths "(rm \"/x\" \"/y\")")))))

(deftest scan-write-paths-ignores-dynamic
  (testing "computed (non-string) paths are invisible to the static scan"
    (is (= [] (clj-ext/scan-write-paths "(spit p \"x\")")))
    (is (= [] (clj-ext/scan-write-paths "(mv from to)")))
    (is (= [] (clj-ext/scan-write-paths "(+ 1 2)")))
    (is (= [] (clj-ext/scan-write-paths "(spit")))))

;; ── scan-read-paths (read-helper gate pre-scan) ──────────────────────────────

(deftest scan-read-paths-literals
  (testing "each read helper's target arg positions are collected"
    (is (= ["/a/b.txt"] (clj-ext/scan-read-paths "(cat \"/a/b.txt\")")))
    (is (= ["/a/b.txt"] (clj-ext/scan-read-paths "(slurp \"/a/b.txt\")")))
    (is (= ["/d"]       (clj-ext/scan-read-paths "(ls \"/d\")")))
    (is (= ["/f"]       (clj-ext/scan-read-paths "(head \"/f\")")))
    (is (= ["/f"]       (clj-ext/scan-read-paths "(tail \"/f\")")))
    (is (= ["/f"]       (clj-ext/scan-read-paths "(stat \"/f\")")))
    (is (= ["/f"]       (clj-ext/scan-read-paths "(realpath \"/f\")")))
    (is (= ["/d"]       (clj-ext/scan-read-paths "(grep #\"x\" \"/d\")")))
    (is (= ["/d"]       (clj-ext/scan-read-paths "(find \"x\" \"/d\")")))
    (is (= ["/src"]     (clj-ext/scan-read-paths "(cp \"/src\" \"/dst\")")))))

(deftest scan-read-paths-glob-base
  (testing "glob's literal base dir (before the first metachar) is the read target"
    (is (= ["/etc"]     (clj-ext/scan-read-paths "(glob \"/etc/**/*.conf\")")))
    (is (= ["../src"]   (clj-ext/scan-read-paths "(glob \"../src/*.cljs\")")))
    (is (= ["."]        (clj-ext/scan-read-paths "(glob \"*.txt\")")))))

(deftest scan-read-paths-ignores-dynamic
  (testing "computed (non-string) paths are invisible to the static scan"
    (is (= [] (clj-ext/scan-read-paths "(cat p)")))
    (is (= [] (clj-ext/scan-read-paths "(ls dir)")))
    (is (= [] (clj-ext/scan-read-paths "(+ 1 2)")))))

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

(deftest eval-getmessage-hints-ex-message
  ;; Java-style (.getMessage e) doesn't exist in the CLJS/SCI sandbox; the
  ;; error result should point at the portable (ex-message e).
  (let [res (eval! "(try (throw (ex-info \"boom\" {})) (catch :default e (.getMessage e)))")]
    (is (:is-error res))
    (is (str/includes? (result-text res) "(ex-message e)"))))

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

(deftest sleep-helper
  (let [res (eval! "(sleep 1)")]
    (is (not (:is-error res)) (result-text res))
    (is (str/includes? (result-text res) "=> nil"))))

(deftest thread-sleep-static
  (let [res (eval! "(Thread/sleep 1)")]
    (is (not (:is-error res)) (result-text res))
    (is (str/includes? (result-text res) "=> nil"))))

;; ── format ───────────────────────────────────────────────────────────────────

(deftest format-hex-padded
  ;; the motivating case: %02x for sha-digest hex bytes
  (let [res (eval! "(apply str (map #(format \"%02x\" %) [0 15 255 171]))")]
    (is (not (:is-error res)) (result-text res))
    (is (str/includes? (result-text res) "=> \"000fffab\""))))

(deftest format-basic-specifiers
  (let [res (eval! "(format \"%s=%d %.2f %x %X\" \"a\" 5 3.14159 255 255)")]
    (is (not (:is-error res)) (result-text res))
    (is (str/includes? (result-text res) "=> \"a=5 3.14 ff FF\""))))

(deftest format-width-and-flags
  (let [res (eval! "(format \"[%5d][%-5d][%05d][%+d]\" 42 42 42 42)")]
    (is (not (:is-error res)) (result-text res))
    (is (str/includes? (result-text res) "=> \"[   42][42   ][00042][+42]\""))))

(deftest format-percent-literal
  (let [res (eval! "(format \"100%% %s\" \"done\")")]
    (is (not (:is-error res)) (result-text res))
    (is (str/includes? (result-text res) "=> \"100% done\""))))

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

(deftest write-outside-cwd-allowed-when-approved
  (testing "a gate-approved out-of-repo root (:allowed-writes) is writable"
    (let [dir (fs/mkdtempSync (node-path/join (os/homedir) ".xi-clj-test-"))
          f   (node-path/join dir "ok.txt")]
      (try
        (let [res (eval! (str "(spit \"" f "\" \"nope\")"))]
          (is (:is-error res))
          (is (str/includes? (result-text res) "writes are limited")))
        (let [res (eval! (str "(spit \"" f "\" \"hi\")") {:allowed-writes [dir]})]
          (is (not (:is-error res)) (result-text res))
          (is (= "hi" (fs/readFileSync f "utf8"))))
        (finally
          (fs/rmSync dir #js {:recursive true :force true}))))))

(deftest read-credentials-blocked
  (let [res (eval! (str "(cat \"" (os/homedir) "/.ssh/id_rsa\")"))]
    (is (:is-error res))
    (is (str/includes? (result-text res) "blocked"))))

(deftest read-outside-cwd-blocked
  (testing "a builtin read escaping the working dir is refused at runtime"
    (let [res (eval! (str "(ls \"" (os/homedir) "\")"))]
      (is (:is-error res))
      (is (str/includes? (result-text res) "reads are limited")))))

(deftest read-parent-dir-blocked
  (testing "a `..` traversal above the working dir is refused at runtime"
    (let [res (eval! "(ls \"..\")")]
      (is (:is-error res))
      (is (str/includes? (result-text res) "reads are limited")))))

(deftest read-outside-cwd-allowed-when-approved
  (testing "a gate-approved out-of-repo root (:allowed-reads) is readable"
    (let [dir (fs/mkdtempSync (node-path/join (os/homedir) ".xi-clj-rtest-"))
          f   (node-path/join dir "data.txt")]
      (fs/writeFileSync f "payload")
      (try
        (let [res (eval! (str "(cat \"" f "\")"))]
          (is (:is-error res))
          (is (str/includes? (result-text res) "reads are limited")))
        (let [res (eval! (str "(cat \"" f "\")") {:allowed-reads [dir]})]
          (is (not (:is-error res)) (result-text res))
          (is (str/includes? (result-text res) "payload")))
        (finally
          (fs/rmSync dir #js {:recursive true :force true}))))))

(deftest read-allowed-under-write-grant
  (testing "an :allowed-writes root also grants reads (write implies read)"
    (let [dir (fs/mkdtempSync (node-path/join (os/homedir) ".xi-clj-wr-"))
          f   (node-path/join dir "data.txt")]
      (fs/writeFileSync f "payload")
      (try
        (let [res (eval! (str "(cat \"" f "\")") {:allowed-writes [dir]})]
          (is (not (:is-error res)) (result-text res))
          (is (str/includes? (result-text res) "payload")))
        (finally
          (fs/rmSync dir #js {:recursive true :force true}))))))

(deftest glob-outside-cwd-blocked
  (testing "a glob whose base dir escapes the working dir is refused"
    (let [res (eval! (str "(glob \"" (os/homedir) "/*\")"))]
      (is (:is-error res))
      (is (str/includes? (result-text res) "reads are limited")))))

;; ── sh runtime allowlist ─────────────────────────────────────────────────────

(deftest sh-blocked-without-approval
  (let [res (eval! "(sh \"echo\" \"hi\")")]
    (is (:is-error res))
    (is (str/includes? (result-text res) "not approved"))))

(deftest sh-runs-when-allowed
  (let [res (eval! "(sh \"echo\" \"hi\")" {:allowed ["echo"]})]
    (is (not (:is-error res)) (result-text res))
    (is (str/includes? (result-text res) "hi"))))

(deftest sh-returns-stdout-string
  (let [res (eval! "(str/trim (sh \"echo\" \"  hi  \"))" {:allowed ["echo"]})]
    (is (not (:is-error res)) (result-text res))
    (is (str/includes? (result-text res) "=> \"hi\""))))

(deftest sh-throws-on-nonzero-exit
  (let [res (eval! "(try (sh \"false\") (catch :default e (:exit (ex-data e))))"
                   {:allowed ["false"]})]
    (is (not (:is-error res)) (result-text res))
    (is (str/includes? (result-text res) "=> 1"))))

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
  ;; curl is no longer hard-blocked — it routes through per-CLI approval. With
  ;; no confirm! attached (gate-ctx) that surfaces the "needs approval" message.
  (async done
    (-> (js/Promise.resolve
         (gate {:name "clj" :arguments {:code "(sh \"curl\" \"https://x.y\")"}}
               (gate-ctx)))
        (.then (fn [res]
                 (is (:intercepted res))
                 (is (str/includes? (intercepted-text res) "need approval"))
                 (done))))))

(deftest curl-rejects-non-http
  (let [res (eval! "(curl \"file:///etc/passwd\")")]
    (is (:is-error res))
    (is (str/includes? (result-text res) "http(s)"))))

(deftest gate-confirms-outside-read
  ;; A builtin read escaping the repo prompts; a deny blocks the whole eval.
  (async done
    (let [ctx (assoc (gate-ctx) :cwd (os/tmpdir)
                     :confirm! (fn [_ & _] (js/Promise.resolve false)))]
      (-> (js/Promise.resolve
           (gate {:name "clj"
                  :arguments {:code (str "(cat \"" (os/homedir) "/xi-gate-read.txt\")")}}
                 ctx))
          (.then (fn [r]
                   (is (:intercepted r))
                   (is (str/includes? (intercepted-text r) "denied reading outside"))
                   (done)))))))

(deftest gate-approves-outside-read-injects-allowed-reads
  ;; Approving the out-of-repo read lets the call through with the approved
  ;; root injected as :_allowed-reads so the worker's resolve-read allows it.
  (async done
    (let [ctx (assoc (gate-ctx) :cwd (os/tmpdir)
                     :confirm! (fn [_ & _] (js/Promise.resolve true)))]
      (-> (js/Promise.resolve
           (gate {:name "clj"
                  :arguments {:code (str "(cat \"" (os/homedir) "/xi-gate-read.txt\")")}}
                 ctx))
          (.then (fn [r]
                   (is (not (:intercepted r)))
                   (is (seq (get-in r [:arguments :_allowed-reads])))
                   (done)))))))

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

(deftest ss-escalated-command-detection
  (is (clj-ext/ss-escalated-command? "ss -K dport = :443"))
  (is (clj-ext/ss-escalated-command? "ss --kill state established"))
  (is (clj-ext/ss-escalated-command? "ss -tK"))
  (is (clj-ext/ss-escalated-command? "ss -D /tmp/dump"))
  (is (clj-ext/ss-escalated-command? "ss --diag=/tmp/dump"))
  (is (not (clj-ext/ss-escalated-command? "ss -lntup")))
  (is (not (clj-ext/ss-escalated-command? "ss -ltnpH")))
  ;; long flags with K/D letters don't false-positive the short-flag match
  (is (not (clj-ext/ss-escalated-command? "ss --tcp")))
  ;; other CLIs are not ss
  (is (not (clj-ext/ss-escalated-command? "lsof -K"))))

(deftest gate-autoruns-readonly-ss-with-hint
  (async done
    (-> (js/Promise.resolve
         (gate {:name "clj" :arguments {:code "(sh \"ss\" \"-ltnp\")"}}
               (gate-ctx)))
        (.then (fn [res]
                 (is (not (:intercepted res)))
                 (is (some #{"ss"} (get-in res [:arguments :_allowed])))
                 (is (str/includes? (str (get-in res [:arguments :_hint])) "(ports)"))
                 (done))))))

(deftest gate-escalates-ss-kill-to-approval
  ;; ss -K destroys sockets — not auto-run; goes through the approval flow.
  (async done
    (let [ctx (assoc (gate-ctx) :confirm! (fn [_ & _] (js/Promise.resolve false)))
          res (gate {:name "clj" :arguments {:code "(sh \"ss\" \"-K\" \"dport\" \"=\" \":443\")"}}
                    ctx)]
      (-> (js/Promise.resolve res)
          (.then (fn [r]
                   (is (:intercepted r))
                   (is (str/includes? (intercepted-text r) "user denied"))
                   (done)))))))

(deftest gate-write-clis-route-through-approval
  ;; write / network helper-equivalent CLIs (sed, curl, …) are no longer
  ;; hard-blocked with a dead-end error — they fall through to the normal
  ;; per-CLI approval flow. With no confirm! attached (gate-ctx), that surfaces
  ;; the standard "needs approval, no client attached" message, not a helper
  ;; bounce.
  (async done
    (-> (js/Promise.resolve
         (gate {:name "clj" :arguments {:code "(sh \"sed\" \"-i\" \"s/a/b/\" \"f\")"}}
               (gate-ctx)))
        (.then (fn [res]
                 (is (:intercepted res))
                 (is (str/includes? (intercepted-text res) "need approval"))
                 (is (not (str/includes? (intercepted-text res) "builtin helper")))
                 (done))))))

(deftest gate-approves-network-cli-with-helper-hint
  ;; Approving the CLI lets it through (not intercepted), with the CLI allowed
  ;; and the helper hint attached — a warning nudge, not an error.
  (async done
    (let [ctx (assoc (gate-ctx) :confirm! (fn [_ & _] (js/Promise.resolve true)))]
      (-> (js/Promise.resolve
           (gate {:name "clj" :arguments {:code "(sh \"curl\" \"http://localhost:8199/\")"}}
                 ctx))
          (.then (fn [res]
                   (is (not (:intercepted res)))
                   (is (some #{"curl"} (get-in res [:arguments :_allowed])))
                   (is (str/includes? (str (get-in res [:arguments :_hint])) "(curl url)"))
                   (done)))))))

(deftest clj-tool-appends-hint
  (let [res (clj-ext/reply->result
             (clj-ext/eval-message #js {:id 0 :kind "clj" :code "(+ 1 2)"
                                        :roomId "test" :allowed #js []
                                        :cwd (os/tmpdir)})
             "hint: use (ls dir)")]
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

(deftest parse-ss-line-tcp-with-process
  (let [row (clj-ext/parse-ss-line
             "tcp   LISTEN 0      4096   127.0.0.1:7474    0.0.0.0:*    users:((\"bun\",pid=1234,fd=20))")]
    (is (= {:proto "tcp" :addr "127.0.0.1" :port 7474 :process "bun" :pid 1234}
           row))))

(deftest parse-ss-line-ipv6-no-process
  (let [row (clj-ext/parse-ss-line "tcp   LISTEN 0      511    [::]:8100    [::]:*")]
    (is (= {:proto "tcp" :addr "[::]" :port 8100 :process nil :pid nil}
           row))))

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

(defn- mk-tmp-dir []
  (fs/mkdtempSync (node-path/join (os/tmpdir) "clj-gate-")))

(deftest gate-confirms-rm-directory
  ;; The builtin (rm dir) on an existing directory is a recursive tree
  ;; deletion — it must prompt, and a deny blocks the whole eval.
  (async done
    (let [dir     (mk-tmp-dir)
          prompts (atom [])
          ctx     (assoc (gate-ctx) :cwd (os/tmpdir)
                         :confirm! (fn [msg & _]
                                     (swap! prompts conj msg)
                                     (js/Promise.resolve false)))]
      (-> (js/Promise.resolve
           (gate {:name "clj" :arguments {:code (str "(rm \"" dir "\")")}} ctx))
          (.then (fn [res]
                   (is (:intercepted res))
                   (is (str/includes? (intercepted-text res) "directory deletion"))
                   (is (some #(str/includes? % "Recursively delete directory") @prompts))
                   (fs/rmSync dir #js {:recursive true :force true})
                   (done)))))))

(deftest gate-allows-rm-directory-on-confirm
  ;; Approving the deletion lets the (rm dir) through (not intercepted).
  (async done
    (let [dir (mk-tmp-dir)
          ctx (assoc (gate-ctx) :cwd (os/tmpdir)
                     :confirm! (fn [_ & _] (js/Promise.resolve true)))]
      (-> (js/Promise.resolve
           (gate {:name "clj" :arguments {:code (str "(rm \"" dir "\")")}} ctx))
          (.then (fn [res]
                   (is (not (:intercepted res)))
                   (fs/rmSync dir #js {:recursive true :force true})
                   (done)))))))

(deftest gate-autoruns-rm-file-no-directory-prompt
  ;; (rm file) on a plain file stays auto-allowed — no deletion prompt, even
  ;; with a confirm! attached (only directories are gated).
  (async done
    (let [dir     (mk-tmp-dir)
          f       (node-path/join dir "scratch.txt")
          _       (fs/writeFileSync f "x")
          prompts (atom [])
          ctx     (assoc (gate-ctx) :cwd (os/tmpdir)
                         :confirm! (fn [msg & _]
                                     (swap! prompts conj msg)
                                     (js/Promise.resolve true)))]
      (-> (js/Promise.resolve
           (gate {:name "clj" :arguments {:code (str "(rm \"" f "\")")}} ctx))
          (.then (fn [res]
                   (is (not (:intercepted res)))
                   (is (empty? @prompts))
                   (fs/rmSync dir #js {:recursive true :force true})
                   (done)))))))

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

;; ── background processes (process/… namespace) ──────────────────────────

(deftest scan-collects-bg-literals
  (let [scan (clj-ext/scan-code
              "(process/start \"npm run dev\") (process/poll-until \"curl -sf x\")")]
    (is (= ["npm run dev" "curl -sf x"] (:bg scan)))
    (is (not (:bg-dynamic? scan)))))

(deftest scan-flags-dynamic-bg-command
  (is (:bg-dynamic? (clj-ext/scan-code "(let [c \"x\"] (process/start c))"))))

(deftest gate-blocks-dynamic-bg-command
  (let [res (gate {:name "clj" :arguments {:code "(process/start (str \"npm \" x))"}}
                  (gate-ctx))]
    (is (:intercepted res))
    (is (str/includes? (intercepted-text res) "literal"))))

(deftest gate-blocks-bg-command-substitution
  (let [res (gate {:name "clj" :arguments {:code "(process/start \"echo $(whoami)\")"}}
                  (gate-ctx))]
    (is (:intercepted res))
    (is (str/includes? (intercepted-text res) "substitution"))))

(deftest gate-blocks-bg-sudo
  (let [res (gate {:name "clj" :arguments {:code "(process/start \"sudo systemctl restart x\")"}}
                  (gate-ctx))]
    (is (:intercepted res))
    (is (str/includes? (intercepted-text res) "sudo"))))

(deftest gate-blocks-bg-remote-cli
  (let [res (gate {:name "clj" :arguments {:code "(process/start \"rsync -av a b\")"}}
                  (gate-ctx))]
    (is (:intercepted res))
    (is (str/includes? (intercepted-text res) "remote shell"))))

(deftest gate-approves-bg-command-injects-allowed-bg
  ;; Approving the bg command's CLI lets the call through with the literal
  ;; command injected as :_allowed-bg, which the worker enforces.
  (async done
    (let [ctx (assoc (gate-ctx) :confirm! (fn [_ & _] (js/Promise.resolve true)))]
      (-> (js/Promise.resolve
           (gate {:name "clj" :arguments {:code "(process/start \"npm run dev\")"}}
                 ctx))
          (.then (fn [res]
                   (is (not (:intercepted res)))
                   (is (= ["npm run dev"] (get-in res [:arguments :_allowed-bg])))
                   (done)))))))

(deftest gate-bg-safe-cli-passes-without-approval
  ;; A SAFE_AUTORUN leading CLI (sleep isn't one — use tail) doesn't need a
  ;; confirm; headless ctx would block if approval were required.
  (async done
    (-> (js/Promise.resolve
         (gate {:name "clj" :arguments {:code "(process/start \"tail -f /tmp/x.log\")"}}
               (gate-ctx)))
        (.then (fn [res]
                 (is (not (:intercepted res)))
                 (is (= ["tail -f /tmp/x.log"] (get-in res [:arguments :_allowed-bg])))
                 (done))))))

(deftest bg-command-clis-extraction
  (is (= #{"npm"} (clj-ext/bg-command-clis ["npm run dev"])))
  (is (= #{"cd" "npm"} (clj-ext/bg-command-clis ["cd web && npm start"])))
  (is (= #{"node"} (clj-ext/bg-command-clis ["PORT=3000 node server.js"])))
  (is (= #{"echo" "wc"} (clj-ext/bg-command-clis ["echo hi | wc -c"]))))

(deftest worker-refuses-unapproved-bg-command
  (let [res (eval! "(process/start \"echo hi\")")]
    (is (:is-error res))
    (is (str/includes? (result-text res) "approved"))))

(deftest process-start-wait-roundtrip
  (let [res (eval! (str "(let [{:keys [pid]} (process/start \"sleep 0.2; echo done\")]"
                        "  (process/wait pid 5000))")
                   {:allowed-bg ["sleep 0.2; echo done"]})]
    (is (not (:is-error res)) (result-text res))
    (let [text (result-text res)]
      (is (str/includes? text ":status :exited"))
      (is (str/includes? text ":exit 0"))
      (is (str/includes? text "done")))))

(deftest process-list-and-stop
  (let [start (eval! "(:pid (process/start \"sleep 30\"))"
                     {:allowed-bg ["sleep 30"] :room-id :bg-test})
        _     (is (not (:is-error start)) (result-text start))
        listed (eval! "(mapv :command (process/list))" {:room-id :bg-test})
        stopped (eval! "(let [{:keys [pid]} (first (process/list))] (process/stop pid))"
                       {:room-id :bg-test})]
    (is (str/includes? (result-text listed) "sleep 30"))
    (is (str/includes? (result-text stopped) ":killed? true"))))

(deftest process-stop-unknown-pid-errors
  (let [res (eval! "(process/stop 999999999)")]
    (is (:is-error res))
    (is (str/includes? (result-text res) "unknown pid"))))

(deftest poll-until-stdout-matches
  (let [res (eval! "(process/poll-until \"echo ready\" {:until :stdout-matches :pattern \"ready\" :interval-ms 50 :timeout-ms 2000})"
                   {:allowed-bg ["echo ready"]})]
    (is (not (:is-error res)) (result-text res))
    (is (str/includes? (result-text res) ":met? true"))))

(deftest poll-until-times-out
  (let [res (eval! "(process/poll-until \"false\" {:interval-ms 20 :timeout-ms 100})"
                   {:allowed-bg ["false"]})]
    (is (not (:is-error res)) (result-text res))
    (let [text (result-text res)]
      (is (str/includes? text ":met? false"))
      (is (str/includes? text ":note")))))
