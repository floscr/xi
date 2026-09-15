(ns xi.ext.clj
  "Sandboxed Clojure scripting tool (SCI).

   Exposes one tool, `clj`: a persistent per-room Clojure REPL evaluated by
   SCI. The sandbox is allowlist-only — scripts can call clojure.core plus a
   small set of injected, synchronous shell-ish helpers (cat/ls/grep/glob/
   spit/tmpdir/…). There is no JS interop, no filesystem, no process access
   beyond those helpers.

   Design goals (see docs/clj-tool.md):
   - readable structured scripts instead of unreviewable bash pipe chains
   - computation happens in the runtime, only distilled values enter context
   - REPL persistence: (def x …) survives across calls (external memory)

   Real CLIs run only through (sh \"cmd\" \"arg\" …), which is permission
   gated: a pre-scan of the code (edamame parse) collects literal sh targets
   in the :tool-gate, checks them against the global allowlist
   (~/.config/xi/ext/clj.edn → :allow-clis) and the per-room session
   allowlist, and raises confirm dialogs for the rest. Approved binaries are
   injected into the tool-call arguments (:_allowed) so the runtime check in
   `sh` only ever executes vetted commands; dynamically computed command
   names that were never approved fail at runtime.

   Paths: reads are blocked from credential paths (xi.sandbox.core
   hidden-paths); writes are limited to the room cwd and the OS tmp dir.

   Session allowlist lives room-scoped under [:rooms rid :ext :clj]
   (:allowed-clis). `/clj` shows status; `/clj allow|revoke <cli>` edits the
   session allowlist; `/clj reset` drops the room's REPL context.
   Disable at runtime with `/ext disable clj`."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [cljs.reader :as reader]
            [edamame.core :as e]
            [sci.core :as sci]
            [xi.core.state :as state]
            [xi.ext.permission-gate :as pg]
            [xi.sandbox.core :as sandbox]
            ["node:child_process" :as cp]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(def ^:private ext-id :clj)

(def ^:private MAX_RESULT 30000)
(def ^:private MAX_SH_OUTPUT 20000)
(def ^:private SH_TIMEOUT 120000)

;; ── Global config ────────────────────────────────────────────────────────────

(defn config-path []
  (node-path/join (os/homedir) ".config" "xi" "ext" "clj.edn"))

(def ^:private global-config
  (delay
    (try
      (when (fs/existsSync (config-path))
        (reader/read-string (fs/readFileSync (config-path) "utf8")))
      (catch :default e
        (js/console.error "[clj] failed to read" (config-path) e)
        nil))))

(defn- global-allow-clis []
  (set (:allow-clis @global-config)))

(defn- helper-hints? []
  (not= false (:helper-hints @global-config)))

;; ── bb.edn SHA trust ─────────────────────────────────────────────────────────
;; `bb` runs without an approval dialog when the project's bb.edn content-hash
;; is in the trust store. Trust is content-addressed (sha256 of bb.edn), so a
;; copied bb.edn is trusted too and an edited one auto-revokes until re-trusted.
;; Note: this trusts bb.edn's inline tasks/:init/:requires — task code that
;; lives in separate files is outside the hash.

(defn- bb-trust-path []
  (node-path/join (os/homedir) ".config" "xi" "ext" "bb-trust.edn"))

(defn- read-bb-trust []
  (try
    (when (fs/existsSync (bb-trust-path))
      (reader/read-string (fs/readFileSync (bb-trust-path) "utf8")))
    (catch :default _ nil)))

(defn- write-bb-trust! [m]
  (let [p (bb-trust-path)]
    (fs/mkdirSync (node-path/dirname p) #js {:recursive true})
    (fs/writeFileSync p (str (pr-str m) "\n"))))

(defn- find-bb-edn
  "Walk up from cwd to the nearest bb.edn; nil when none is found."
  [cwd]
  (loop [dir (sandbox/real-resolve (or cwd (.cwd js/process)) ".")]
    (let [f (node-path/join dir "bb.edn")]
      (if (fs/existsSync f)
        f
        (let [parent (node-path/dirname dir)]
          (when (not= parent dir) (recur parent)))))))

(defn- bb-sha
  "Hex sha256 of the nearest bb.edn, or nil when none is found."
  [cwd]
  (when-let [f (find-bb-edn cwd)]
    (-> (.createHash crypto "sha256")
        (.update (fs/readFileSync f))
        (.digest "hex"))))

(defn- bb-trusted? [cwd]
  (boolean (when-let [sha (bb-sha cwd)]
             (contains? (set (:shas (read-bb-trust))) sha))))

(defn- trust-bb!
  "Persist the nearest bb.edn's sha to the trust store. Returns {:path :sha}
   or nil when no bb.edn is found."
  [cwd]
  (when-let [f (find-bb-edn cwd)]
    (let [sha (bb-sha cwd)]
      (write-bb-trust! (update (or (read-bb-trust) {}) :shas (fnil conj #{}) sha))
      {:path f :sha sha})))

;; ── Path + output guards ─────────────────────────────────────────────────────

(defn- truncate [s max-len]
  (let [s (str s)]
    (if (> (count s) max-len)
      (str (subs s 0 max-len) "\n… [truncated to " max-len " chars]")
      s)))

(defn- resolve-read
  "Canonicalize p against cwd; throw on credential paths."
  [cwd p]
  (let [resolved (sandbox/real-resolve cwd (str p))]
    (when (some #(sandbox/path-within? resolved %) (sandbox/hidden-paths))
      (throw (ex-info (str "clj: reading credential paths is blocked: " p) {})))
    resolved))

(defn- resolve-write
  "Canonicalize p against cwd; only the working dir and the OS tmp dir are
   writable from clj scripts."
  [cwd p]
  (let [resolved (sandbox/real-resolve cwd (str p))
        real-cwd (sandbox/real-resolve cwd ".")
        tmp      (sandbox/real-resolve cwd (os/tmpdir))]
    (if (or (sandbox/path-within? resolved real-cwd)
            (sandbox/path-within? resolved tmp))
      resolved
      (throw (ex-info (str "clj: writes are limited to the working dir and "
                           (os/tmpdir) ": " p) {})))))

;; ── Script-visible helpers ───────────────────────────────────────────────────
;; All close over the per-room opts atom {:cwd … :allowed #{…}} which is
;; refreshed before every eval (the SCI ctx itself is long-lived).

(defn- opts-cwd [opts]
  (or (:cwd @opts) (.cwd js/process)))

(defn- read-file [opts p]
  (fs/readFileSync (resolve-read (opts-cwd opts) p) "utf8"))

(defn- regex->str [pattern]
  (if (regexp? pattern) (.-source pattern) (str pattern)))

(defn- spawn-sync!
  "Run argv synchronously (under setsid, like the bash tool — no tty).
   Optional `input` string is written to the child's stdin.
   Returns {:exit n :out s :err s}, output truncated."
  ([argv cwd] (spawn-sync! argv cwd nil))
  ([argv cwd input]
   (let [opts #js {:cwd cwd
                   :encoding "utf8"
                   :timeout SH_TIMEOUT
                   :stdio (if input #js ["pipe" "pipe" "pipe"] #js ["ignore" "pipe" "pipe"])}
         _    (when input (set! (.-input opts) input))
         r    (cp/spawnSync "setsid" (clj->js argv) opts)]
     {:exit (or (.-status r) (if (.-signal r) -1 0))
      :out  (truncate (or (.-stdout r) "") MAX_SH_OUTPUT)
      :err  (truncate (str (or (.-stderr r) "")
                           (when-let [e (.-error r)] (.-message e)))
                      MAX_SH_OUTPUT)})))

(defn- sh-fn [opts]
  (fn [& argv]
    (let [{:keys [allowed]} @opts
          bin (first argv)]
      (cond
        (not (string? bin))
        (throw (ex-info "clj: (sh \"cmd\" \"arg\" …) — the command must be a string" {}))

        (str/includes? bin " ")
        (throw (ex-info "clj: sh is argv-style — (sh \"cmd\" \"arg\" …), not a shell string" {}))

        (not (contains? (set allowed) bin))
        (throw (ex-info (str "clj: `" bin "` is not approved. Literal (sh \"" bin
                             "\" …) calls raise an approval dialog; dynamic command "
                             "names can't be pre-approved — use a literal, or the "
                             "user can run /clj allow " bin) {}))

        :else
        (spawn-sync! argv (opts-cwd opts))))))

(defn- grep-fn [opts]
  (fn [pattern & [p]]
    (let [cwd    (opts-cwd opts)
          target (resolve-read cwd (or p "."))
          {:keys [exit out err]}
          (spawn-sync! ["rg" "-n" "--no-heading" "--max-count" "500"
                        "-e" (regex->str pattern) target]
                       cwd)]
      (case exit
        0 out
        1 ""
        (throw (ex-info (str "clj: rg failed: " err) {}))))))

(defn- find-fn [opts]
  (fn [pattern & [dir]]
    (let [cwd (opts-cwd opts)
          target (resolve-read cwd (or dir "."))
          {:keys [exit out err]} (spawn-sync! ["fd" "--" (str pattern) target] cwd)]
      (if (zero? exit)
        ;; fd exits 0 with empty stdout when nothing matches; split-lines on ""
        ;; yields [""], which reads like a phantom match. Drop blanks so "no
        ;; matches" is an unambiguous [] (same empty semantics as glob).
        (into [] (remove str/blank?) (str/split-lines (str/trimr out)))
        (throw (ex-info (str "clj: fd failed: " err) {}))))))

(defn- curl-fn [opts]
  (fn curl*
    ([url] (curl* url nil))
    ([url {:keys [method headers body max-time]}]
     (when-not (re-matches #"(?i)https?://.*" (str url))
       (throw (ex-info "clj: curl only supports http(s) URLs" {})))
     (let [outfile (node-path/join (os/tmpdir)
                                   (str "xi-clj-curl-" (.getTime (js/Date.))
                                        "-" (rand-int 1000000)))
           argv    (cond-> ["curl" "-sS" "-L" "--max-time" (str (or max-time 30))
                            "-o" outfile "-w" "%{http_code}"]
                     method  (into ["-X" (str/upper-case (name method))])
                     body    (into ["--data-binary" (str body)])
                     headers (into (mapcat (fn [[k v]] ["-H" (str (name k) ": " v)])
                                           headers))
                     :always (conj (str url)))
           res     (spawn-sync! argv (opts-cwd opts))
           status  (js/parseInt (str/trim (:out res)) 10)
           body'   (when (fs/existsSync outfile)
                     (let [s (fs/readFileSync outfile "utf8")]
                       (fs/rmSync outfile #js {:force true})
                       (truncate s MAX_SH_OUTPUT)))]
       (if (and (zero? (:exit res)) (not (js/isNaN status)))
         {:status status :body (or body' "")}
         (throw (ex-info (str "clj: curl failed: " (:err res)) {})))))))

(def GIT_DENY
  "Git subcommands the `git` helper refuses — route through (sh \"git\" …)
   and its approval/guard flow instead. push mutates the remote (guarded),
   clean deletes untracked files."
  #{"push" "clean"})

(defn git-subcommand
  "First non-flag argv entry, skipping option-with-value globals (-C, -c, …)."
  [args]
  (loop [[a & more] (map str args)]
    (cond
      (nil? a) nil
      (contains? #{"-C" "-c" "--git-dir" "--work-tree" "--namespace"} a)
      (recur (rest more))
      (str/starts-with? a "-") (recur more)
      :else a)))

(def ^:private commit-summary-line-re
  "git commit's `[<branch> <sha>] subject` summary line. Successful commits
   record it into the runtime's :commit-outs so eval-code! can guarantee it
   appears in the tool result — session-commit tracking
   (xi.fx/session-commit-refs) extracts shas from result text, which would
   otherwise miss commits that aren't the eval's return value."
  #"\[[^\]]*?[0-9a-f]{7,40}\][^\n]*")

(defn- git-fn
  "Pre-approved git runner: (git \"status\" \"--short\") → stdout string.
   Throws on non-zero exit and on deny-listed subcommands (GIT_DENY)."
  [opts]
  (fn [& args]
    (let [args (mapv str args)
          sub  (git-subcommand args)]
      (when (contains? GIT_DENY (or sub ""))
        (throw (ex-info (str "clj: (git \"" sub "\" …) is not allowed via the "
                             "git helper — use (sh \"git\" \"" sub "\" …), "
                             "which asks the user for approval.")
                        {})))
      (let [{:keys [exit out err]} (spawn-sync! (into ["git"] args) (opts-cwd opts))]
        (if (zero? exit)
          (let [out' (str/trimr out)]
            (when (= sub "commit")
              (when-let [outs (:commit-outs @opts)]
                (when-let [line (re-find commit-summary-line-re out')]
                  (swap! outs conj line))))
            out')
          (throw (ex-info (str "git " (str/join " " args) " failed (exit " exit "): "
                               (str/trim (str err "\n" out)))
                          {:exit exit})))))))

(defn- jq-fn
  "Pre-approved jq runner — pipes JSON to jq on stdin, no tmp file needed.
   (jq filter input) → parsed Clojure data. `input` is a JSON string, or any
   Clojure value (encoded to JSON). By default jq's output (newline-delimited
   JSON) is parsed with keywordized keys: one value → the value, many → a
   vector, none → nil. Opts map: {:raw true} returns jq -r raw text as a
   trimmed string instead of parsing; {:args [...]} adds extra jq flags."
  [opts]
  (fn jq*
    ([filt input] (jq* filt input nil))
    ([filt input {:keys [raw args]}]
     (let [json (if (string? input) input (js/JSON.stringify (clj->js input)))
           argv (cond-> ["jq" (if raw "-r" "-c")]
                  (seq args) (into args)
                  :always    (conj (str filt)))
           {:keys [exit out err]} (spawn-sync! argv (opts-cwd opts) json)]
       (if (zero? exit)
         (if raw
           (str/trimr out)
           (let [vs (->> (str/split-lines (str/trimr out))
                         (remove str/blank?)
                         (mapv #(js->clj (js/JSON.parse %) :keywordize-keys true)))]
             (case (count vs)
               0 nil
               1 (first vs)
               vs)))
         (throw (ex-info (str "clj: jq failed: " (str/trim err)) {})))))))

(defn- helper-fns
  "The 'user-namespace helpers injected into the SCI ctx. `cat` and `find`
   shadow clojure.core (overridden in the 'clojure.core sci namespace)."
  [opts]
  (let [cat' (fn [p] (read-file opts p))]
    {'cat    cat'
     'slurp  cat'
     'spit   (fn [p s & [{:keys [append]}]]
               (fs/writeFileSync (resolve-write (opts-cwd opts) p) (str s)
                                 #js {:flag (if append "a" "w")})
               nil)
     'ls     (fn [& [p]]
               (let [dir (resolve-read (opts-cwd opts) (or p "."))]
                 (->> (fs/readdirSync dir #js {:withFileTypes true})
                      (mapv #(str (.-name %) (when (.isDirectory %) "/")))
                      sort vec)))
     'head   (fn [p & [n]] (vec (take (or n 10) (str/split-lines (read-file opts p)))))
     'tail   (fn [p & [n]] (vec (take-last (or n 10) (str/split-lines (read-file opts p)))))
     'glob   (fn [pattern]
               (let [cwd (opts-cwd opts)
                     pat (sandbox/expand-home (str pattern))]
                 (->> (if (exists? js/Bun)
                        (js/Array.from (.scanSync (js/Bun.Glob. pat)
                                                  #js {:cwd cwd}))
                        ;; node fallback (test target runs under node)
                        (js/Array.from (fs/globSync pat #js {:cwd cwd})))
                      sort vec)))
     'grep   (grep-fn opts)
     'find   (find-fn opts)
     'mkdir  (fn [p]
               (let [dir (resolve-write (opts-cwd opts) p)]
                 (fs/mkdirSync dir #js {:recursive true})
                 dir))
     'cp     (fn [from to]
               (fs/cpSync (resolve-read (opts-cwd opts) from)
                          (resolve-write (opts-cwd opts) to)
                          #js {:recursive true})
               nil)
     'mv     (fn [from to]
               (fs/renameSync (resolve-write (opts-cwd opts) from)
                              (resolve-write (opts-cwd opts) to))
               nil)
     'rm     (fn [& paths]
               (doseq [p paths]
                 (fs/rmSync (resolve-write (opts-cwd opts) p)
                            #js {:force true :recursive true}))
               nil)
     'tmpdir (fn [] (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-clj-")))
     'stat   (fn [p]
               (let [s (fs/statSync (resolve-read (opts-cwd opts) p))]
                 {:size     (.-size s)
                  :dir?     (.isDirectory s)
                  :file?    (.isFile s)
                  :mode     (.toString (bit-and (.-mode s) 0xfff) 8)
                  :mtime-ms (js/Math.round (.-mtimeMs s))
                  :mtime    (.toISOString (.-mtime s))
                  :ctime    (.toISOString (.-ctime s))}))
     'realpath (fn [p] (fs/realpathSync (resolve-read (opts-cwd opts) p)))
     'basename (fn [p & [ext]] (if ext (node-path/basename (str p) (str ext))
                                       (node-path/basename (str p))))
     'dirname  (fn [p] (node-path/dirname (str p)))
     'which  (fn [cmd]
               (if (exists? js/Bun)
                 (js/Bun.which (str cmd))
                 (some (fn [dir]
                         (let [p (node-path/join dir (str cmd))]
                           (when (fs/existsSync p) p)))
                       (str/split (or (aget js/process.env "PATH") "") #":"))))
     'touch  (fn [p]
               (let [f (resolve-write (opts-cwd opts) p)
                     now (js/Date.)]
                 (if (fs/existsSync f)
                   (fs/utimesSync f now now)
                   (fs/writeFileSync f ""))
                 nil))
     'now    (fn [] (.toISOString (js/Date.)))
     'cwd    (fn [] (opts-cwd opts))
     'curl   (curl-fn opts)
     'jq     (jq-fn opts)
     'git    (git-fn opts)
     'env    (fn [k]
               (let [scrubbed (sandbox/scrub-env)]
                 (or (aget scrubbed (str k))
                     (when (aget js/process.env (str k))
                       (throw (ex-info (str "clj: env key blocked: " k) {}))))))
     'sh     (sh-fn opts)}))

;; ── SCI runtime (persistent per room) ────────────────────────────────────────

(defonce ^:private runtimes (atom {}))

(def ^:private PRELUDE
  "(require '[clojure.string :as str] '[clojure.set :as set]
            '[clojure.walk :as walk] '[clojure.edn :as edn])")

(defn- make-ctx [opts]
  (let [helpers (helper-fns opts)
        ctx (sci/init {:namespaces
                       {'user helpers
                        ;; shadow the core vars our helpers collide with
                        'clojure.core (select-keys helpers '[cat find])}})]
    (sci/eval-string* ctx PRELUDE)
    ctx))

(defn- ensure-runtime! [room-id]
  (or (get @runtimes room-id)
      (let [opts (atom {})
            rt   {:opts opts :ctx (make-ctx opts)}]
        (swap! runtimes assoc room-id rt)
        rt)))

(defn- format-value [v]
  (cond
    ;; Print multi-line strings raw so newlines render as line breaks
    ;; instead of escaped \n (pr-str would escape them).
    (and (string? v) (str/includes? v "\n"))
    v

    ;; A collection of multi-line strings (e.g. per-file grep dumps): render
    ;; each element raw, one after another, so newlines show as line breaks
    ;; instead of a single crammed pr-str vector with escaped \n. Only kicks in
    ;; when an element actually spans lines, so short string vectors like
    ;; ["c.txt" "c" "/a/b"] keep their readable pr-str form.
    (and (sequential? v) (every? string? v)
         (some #(str/includes? % "\n") v))
    (str/join "\n" v)

    :else
    (binding [*print-length* 200
              *print-level*  12]
      (pr-str v))))

(defn- with-commit-lines
  "Append any recorded `[branch sha]` commit-summary lines that don't already
   appear in the result text, so session-commit tracking always sees the sha
   even when the commit's stdout was discarded mid-eval (or truncated away)."
  [text commit-outs]
  (let [missed (remove #(str/includes? text %) @commit-outs)]
    (cond-> text (seq missed) (str "\n" (str/join "\n" missed)))))

(defn- eval-code! [{:keys [code room-id cwd allowed]}]
  (let [{:keys [ctx opts]} (ensure-runtime! (or room-id :default))
        prints (atom "")
        commit-outs (atom [])]
    (swap! opts assoc :cwd cwd :allowed (set allowed) :commit-outs commit-outs)
    (try
      (let [v   (sci/binding [sci/print-fn     #(swap! prints str %)
                              sci/print-err-fn #(swap! prints str %)]
                  (sci/eval-string* ctx code))
            out @prints
            text (str out
                      (when (and (seq out) (not (str/ends-with? out "\n"))) "\n")
                      "=> " (format-value v))]
        {:content [{:type "text"
                    :text (with-commit-lines (truncate text MAX_RESULT) commit-outs)}]
         :is-error false})
      (catch :default err
        (let [{:keys [line column]} (ex-data err)
              out @prints]
          {:content [{:type "text"
                      :text (with-commit-lines
                             (str (when (seq out) (str (truncate out MAX_RESULT) "\n"))
                                  "Error: " (.-message err)
                                  (when line (str " (line " line
                                                  (when column (str ":" column)) ")")))
                             commit-outs)}]
           :is-error true})))))

;; ── Tool ─────────────────────────────────────────────────────────────────────

(def ^:private tool-def
  {:name "clj"
   :description
   (str "Sandboxed Clojure REPL (persistent per chat — (def x …) survives "
        "across calls, so load data once, query it later instead of re-reading). "
        "Sync helpers: (cat f) (ls d) (glob \"src/**/*.clj\") (grep re path) "
        "(find pat dir) (head f n) (tail f n) (spit f s) (mkdir d) (cp a b) "
        "(mv a b) (rm f) (tmpdir) (cwd) (env k) (stat f) → {:size :mtime-ms …} "
        "(realpath p) (which c) (basename p) (dirname p) (touch f) (now) "
        "(curl url) → {:status :body} "
        "(jq \".foo[]\" json-or-data) → parsed result, no tmp file (opts {:raw true}) "
        "(git \"status\" \"--short\") → stdout string (pre-approved; push/clean "
        "excluded) (sh \"cmd\" \"arg\" …). "
        "clojure.core + str/set/walk/edn aliases available. Paths accept a "
        "leading ~ or $HOME. Prefer this over "
        "bash pipelines: compute in-script, return small values. "
        "Example: (->> (glob \"src/**/*.cljs\") (filter #(str/includes? (cat %) \"TODO\")))\n"
        "sh runs real CLIs argv-style and needs user approval unless "
        "allowlisted; writes are limited to the working dir and /tmp.")
   :input_schema {:type "object"
                  :properties {:code {:type "string"
                                      :description "Clojure code; multiple forms ok, last value is returned"}}
                  :required ["code"]}})

(defn- clj-tool [args {:keys [cwd]}]
  (let [res (eval-code! {:code    (str (:code args))
                         :room-id (:_room-id args)
                         :allowed (:_allowed args)
                         :cwd     (or cwd (.cwd js/process))})]
    (if-let [hint (not-empty (str (:_hint args)))]
      (update-in res [:content 0 :text] str "\n\n" hint)
      res)))

;; ── bb tool ────────────────────────────────────────────────────────────────

(defn- bb-argv [args]
  (let [task  (not-empty (str/trim (str (:task args))))
        extra (mapv str (:args args))]
    (into ["bb"] (if task (into [task] extra) ["tasks"]))))

(def ^:private bb-tool-def
  {:name "bb"
   :description
   (str "Run a Babashka task from this project's bb.edn (build, test, check, "
        "serve, …). Pass {\"task\": \"test\"} to run `bb test`; add "
        "{\"args\": [\"--foo\"]} for extra CLI args; omit `task` to list the "
        "available tasks (`bb tasks`). The project's bb.edn must be trusted "
        "first — run /clj trust-bb, or approve once when prompted; editing "
        "bb.edn requires re-trusting. serve:restart / serve:stop run detached.")
   :input_schema {:type "object"
                  :properties {:task {:type "string"
                                      :description "Task name (e.g. test, build, check). Omit to list tasks."}
                               :args {:type "array" :items {:type "string"}
                                      :description "Extra CLI args appended to the task."}}
                  :required []}})

(defn- bb-tool [args {:keys [cwd]}]
  (let [argv (bb-argv args)
        {:keys [exit out err]} (spawn-sync! argv (or cwd (.cwd js/process)))
        body (str/trim (str out (when (seq err) (str "\n" err))))]
    {:content [{:type "text"
                :text (str "$ " (str/join " " argv) "\n"
                           (if (str/blank? body) "(no output)" body)
                           (when-not (zero? exit) (str "\n[exit " exit "]")))}]
     :is-error (not (zero? exit))}))

;; ── Tool gate: pre-scan (sh …) calls, approve CLIs ───────────────────────────

(def ^:private SHELLS #{"bash" "sh" "zsh" "fish" "dash"})

(defn scan-sh-calls
  "Parse code (edamame) and collect (sh …) call sites. Returns
   {:parse-error msg} on unreadable code, else
   {:literals #{bin…} :commands [\"bin arg…\"] :dynamic? bool :shell-c? bool}.
   :commands joins each call's literal string args — used for the guarded /
   server-control pattern checks (dynamic args are invisible to it; the
   binary itself must still be an approved literal)."
  [code]
  (try
    (let [forms (e/parse-string-all code {:all true
                                          :auto-resolve {:current 'user}
                                          :readers (fn [_] identity)})
          calls (atom [])]
      (walk/postwalk (fn [f]
                       (when (and (seq? f) (= 'sh (first f)))
                         (swap! calls conj (vec (rest f))))
                       f)
                     forms)
      {:literals (set (filter string? (map first @calls)))
       :commands (into []
                       (comp (filter #(string? (first %)))
                             (map #(str/join " " (filter string? %))))
                       @calls)
       :dynamic? (boolean (some (complement string?) (map first @calls)))
       :shell-c? (boolean (some (fn [args]
                                  (and (contains? SHELLS (first args))
                                       (some #{"-c"} (filter string? args))))
                                @calls))})
    (catch :default err
      {:parse-error (.-message err)})))

(defn- blocked [text]
  {:intercepted true
   :result {:content [{:type "text" :text text}] :is-error true}})

(defn- approve-clis!
  "Confirm each cli in turn. Resolves to {:approved #{…}} or {:denied cli}.
   :always answers persist to the room session allowlist — except `bb`, whose
   :always records the project's bb.edn sha in the persistent trust store."
  [confirm! dispatch! room-id cwd clis]
  (reduce
   (fn [chain cli]
     (.then chain
            (fn [acc]
              (if (:denied acc)
                acc
                (-> (confirm! (str "clj: allow running `" cli "`?")
                              {:allow-always? true})
                    (.then (fn [answer]
                             (cond
                               (= answer :always)
                               (do (if (= cli "bb")
                                     (trust-bb! cwd)
                                     (dispatch! {:type :ext.clj/allow-cli
                                                 :room-id room-id :cli cli}))
                                   (update acc :approved conj cli))

                               answer (update acc :approved conj cli)
                               :else  (assoc acc :denied cli)))))))))
   (js/Promise.resolve {:approved #{}})
   clis))

(defn- strip-quoted
  "Remove single- and double-quoted spans so quoted `;`/`|` don't count."
  [s]
  (-> s
      (str/replace #"'[^']*'" "_")
      (str/replace #"\"(?:\\.|[^\"\\])*\"" "_")))

(defn chained-bash?
  "True when a bash command uses shell composition — pipes, `;`/`&&`/`&`,
   command substitution, backticks, multiple lines, or a leading VAR= binding.
   These are the unreadable one-liners the clj tool exists to replace."
  [cmd]
  (let [s (strip-quoted (str/trim (str cmd)))]
    (boolean (or (re-find #"[;|&\n]" s)
                 (re-find #"\$\(" s)
                 (str/includes? s "`")
                 (re-find #"^\w+=" s)))))

(defn- gate-bash [tool-call]
  (if (chained-bash? (str (get-in tool-call [:arguments :command])))
    (blocked (str "bash: chained/piped shell commands are disabled — rewrite "
                  "this with the clj tool (sandboxed Clojure REPL): (cat f) "
                  "(glob …) (grep re path) (sh \"cmd\" \"arg\" …); compute "
                  "in-script and return small values. Bash remains available "
                  "for single simple commands."))
    tool-call))

(def ^:private HELPER_EQUIV
  "CLIs that have a builtin helper — (sh …) to these is bounced with a hint
   instead of raising an approval dialog. Allowlisted CLIs (global config or
   /clj allow) bypass this — the escape hatch for when flags are needed."
  {"ls"     "(ls dir)"
   "cat"    "(cat f)"
   "head"   "(head f n)"
   "tail"   "(tail f n)"
   "grep"   "(grep re path)"
   "rg"     "(grep re path)"
   "find"   "(find pat dir)"
   "fd"     "(find pat dir)"
   "mkdir"  "(mkdir dir)"
   "cp"     "(cp a b)"
   "mv"     "(mv a b)"
   "rm"     "(rm f) — deletes within the working dir / tmp"
   "echo"   "(println …)"
   "pwd"    "(cwd)"
   "mktemp" "(tmpdir)"
   "curl"   "(curl url)"
   "wget"   "(curl url)"
   "git"    "(git \"status\" \"--short\") → stdout string, pre-approved"
   "stat"     "(stat f) → {:size :mtime-ms :mtime :dir? …}"
   "du"       "(:size (stat f))"
   "readlink" "(realpath p)"
   "realpath" "(realpath p)"
   "which"    "(which \"cmd\")"
   "basename" "(basename p)"
   "dirname"  "(dirname p)"
   "touch"    "(touch f)"
   "date"     "(now)"
   "wc"       "(count (str/split-lines (cat f)))"
   "sort"     "(sort …) in Clojure"
   "uniq"     "(distinct …) in Clojure"
   "cut"      "(str/split …)"
   "awk"      "(str/split …) + Clojure"
   "sed"      "(str/replace …) + (spit …)"
   "tr"       "(str/replace …)"})

(def ^:private SAFE_AUTORUN
  "Read-only HELPER_EQUIV CLIs that are auto-allowed instead of bounced:
   the (sh …) call runs and the result gets a helper hint appended, so the
   model doesn't lose a turn. Other write CLIs (mkdir cp mv touch sed awk) and
   network CLIs (curl wget) stay bounced — raw sh would bypass the helpers'
   write-path / http-only guards.

   `rm` is the one write exception: deleting scratch files (typically under
   /tmp) is common enough that requiring approval each time is friction, so
   (sh \"rm\" …) is auto-allowed — including `rm -rf`, which is exempted from
   the guarded-pattern confirm in gate-clj (bash's rm -rf stays guarded). The
   attached hint points at the confined (rm f) helper and, for /tmp targets,
   notes the deletion is usually unnecessary."
  #{"ls" "cat" "head" "tail" "grep" "rg" "find" "fd" "pwd" "echo" "mktemp"
    "stat" "du" "readlink" "realpath" "which" "basename" "dirname" "date"
    "wc" "sort" "uniq" "cut" "tr" "git" "rm"})

(def ^:private REMOTE_CLIS
  "Never allowed via (sh …) — parity with the permission gate's blocked
   bash commands. `ssh` is intentionally excluded: it goes through the normal
   per-CLI approval prompt instead of being hard-blocked."
  #{"scp" "rsync" "sftp"})

(defn- confirm-all!
  "Confirm each prompt in turn; resolves false on the first deny."
  [confirm! prompts]
  (reduce (fn [chain prompt]
            (.then chain (fn [ok?] (if ok? (confirm! prompt) false))))
          (js/Promise.resolve true)
          prompts))

(defn- gate-clj [tool-call {:keys [get-state room-id confirm! dispatch! cwd]}]
  (let [code    (str (get-in tool-call [:arguments :code]))
        scan    (scan-sh-calls code)
        session (set (:allowed-clis (state/room-ext (get-state) room-id ext-id)))
        ;; A trusted bb.edn (sha in the trust store) makes `bb` an allowed CLI
        ;; for (sh "bb" …), same as the dedicated bb tool.
        base    (cond-> (into (global-allow-clis) session)
                  (bb-trusted? cwd) (conj "bb"))
        inject  (fn [allowed hint]
                  (cond-> (update tool-call :arguments assoc
                                  :_room-id room-id
                                  :_allowed (vec allowed))
                    hint (update :arguments assoc :_hint hint)))]
    (cond
      (:parse-error scan)
      (blocked (str "clj: parse error — " (:parse-error scan)))

      (:shell-c? scan)
      (blocked (str "clj: (sh \"bash\" \"-c\" …) is not allowed — write the "
                    "pipeline in Clojure instead (cat/grep/glob + clojure.core)."))

      :else
      (let [needed   (remove base (sort (:literals scan)))
            ;; (sh "git" …) bounces to the pre-approved (git …) helper —
            ;; unless a deny-listed subcommand (push, clean) is involved,
            ;; which the helper refuses; those go through approval instead.
            git-escalated? (some (fn [cmd]
                                   (and (str/starts-with? cmd "git")
                                        (contains? GIT_DENY
                                                   (git-subcommand
                                                    (rest (str/split cmd #"\s+"))))))
                                 (:commands scan))
            shadowed (filter (fn [bin]
                               (and (contains? HELPER_EQUIV bin)
                                    (or (not= "git" bin) (not git-escalated?))))
                             needed)
            ;; Safe read-only CLIs run anyway — result + helper hint — so
            ;; the model doesn't lose a turn; the rest of shadowed bounces.
            autorun  (filter #(contains? SAFE_AUTORUN %) shadowed)
            blockers (remove (set autorun) shadowed)
            needed'  (remove (set autorun) needed)
            tmp-rm?  (some (fn [cmd]
                             (and (str/starts-with? cmd "rm ")
                                  (or (str/includes? cmd "/tmp/")
                                      (str/includes? cmd (str (os/tmpdir))))))
                           (:commands scan))
            hint     (when (helper-hints?)
                       (not-empty
                        (str/join
                         " "
                         (remove
                          nil?
                          [(when (seq autorun)
                             (str "hint: prefer the builtin helpers over sh: "
                                  (str/join ", " (map #(str "`" % "` → " (HELPER_EQUIV %))
                                                      autorun))))
                           (when tmp-rm?
                             (str "note: removing files under /tmp is usually "
                                  "unnecessary — /tmp is temporary and cleared "
                                  "automatically; skip the rm unless you need "
                                  "the space back."))]))))
            remote   (filter REMOTE_CLIS (:literals scan))
            sc-cmd   (first (filter pg/server-control-kind (:commands scan)))
            ;; `rm` is auto-allowed from clj (SAFE_AUTORUN) — including rm -rf,
            ;; so drop rm commands from the guarded confirm here. bash's rm -rf
            ;; stays guarded (GUARDED_PATTERNS is unchanged).
            guarded  (->> (:commands scan)
                          (filter (fn [cmd]
                                    (some #(str/includes? cmd %) pg/GUARDED_PATTERNS)))
                          (remove #(str/starts-with? % "rm ")))]
        (cond
          (seq remote)
          (blocked (str "clj: remote shell commands ("
                        (str/join ", " (sort remote))
                        ") are not allowed."))

          ;; bb serve:restart / serve:stop would kill the server hosting this
          ;; agent mid-eval — delegate to the permission gate's detached-run
          ;; flow instead of ever letting sh run it inline.
          sc-cmd
          (-> (js/Promise.resolve
               (pg/ask-server-control confirm! sc-cmd (pg/server-control-kind sc-cmd)))
              (.then (fn [res]
                       (or res (blocked (str "clj: user denied `" sc-cmd "`"))))))

          (seq blockers)
          (blocked (str "clj: don't shell out to "
                        (str/join ", " (map #(str "`" % "`") blockers))
                        " — use the builtin helper: "
                        (str/join ", " (map #(str % " → " (HELPER_EQUIV %)) blockers))
                        ". Helpers run in-process with no approval needed."))

          :else
          ;; Guarded patterns (rm -rf, sudo, git push, kill …) need a confirm
          ;; even when the CLI itself is allowlisted — parity with the bash
          ;; gate. No confirm! (headless) passes through, like the bash gate.
          (-> (if (and (seq guarded) confirm!)
                (confirm-all! confirm! (map #(str "Guarded command: " %) guarded))
                (js/Promise.resolve true))
              (.then
               (fn [ok?]
                 (cond
                   (not ok?)
                   (blocked "clj: user denied a guarded command")

                   (empty? needed') (inject (into base autorun) hint)

                   (not confirm!)
                   (blocked (str "clj: these CLIs need approval but no client is "
                                 "attached to confirm: " (str/join ", " needed')))

                   :else
                   (-> (approve-clis! confirm! dispatch! room-id cwd needed')
                       (.then (fn [{:keys [approved denied]}]
                                (if denied
                                  (blocked (str "clj: user denied running `" denied "`"))
                                  (inject (into (into base autorun) approved)
                                          hint))))))))))))))

(defn- gate-bb [tool-call {:keys [cwd confirm!]}]
  (let [cmd (str/join " " (bb-argv (:arguments tool-call)))
        dir (or cwd (.cwd js/process))]
    (cond
      ;; serve:restart / serve:stop would kill the server hosting this agent —
      ;; run detached via the permission gate, never inline. Trust doesn't bypass.
      (pg/server-control-kind cmd)
      (-> (js/Promise.resolve
           (pg/ask-server-control confirm! cmd (pg/server-control-kind cmd)))
          (.then (fn [res] (or res (blocked (str "bb: user denied `" cmd "`"))))))

      ;; Trusted bb.edn: allow, but still confirm any guarded pattern (parity
      ;; with the bash/clj gates).
      (bb-trusted? dir)
      (let [guarded (filter #(str/includes? cmd %) pg/GUARDED_PATTERNS)]
        (if (and (seq guarded) confirm!)
          (-> (confirm-all! confirm! (map #(str "Guarded command: " %) guarded))
              (.then (fn [ok?]
                       (if ok? tool-call
                           (blocked "bb: user denied a guarded command")))))
          tool-call))

      (not confirm!)
      (blocked (str "bb: bb.edn is not trusted and no client is attached to "
                    "confirm — trust it with /clj trust-bb."))

      :else
      (-> (confirm! (str "Trust bb.edn at " (find-bb-edn dir) " and allow `bb` tasks?")
                    {:allow-always? true})
          (.then (fn [answer]
                   (cond
                     (= answer :always) (do (trust-bb! dir) tool-call)
                     answer             tool-call
                     :else              (blocked "bb: user denied running bb"))))))))

(defn- tool-gate [tool-call ctx]
  (case (str/lower-case (or (:name tool-call) ""))
    "bash" (gate-bash tool-call)
    "clj"  (gate-clj tool-call ctx)
    "bb"   (gate-bb tool-call ctx)
    tool-call))

;; ── Command + state ──────────────────────────────────────────────────────────

(defn- ext-state [st room-id]
  (state/room-ext st room-id ext-id))

(defn- status-line [st room-id text]
  (update-in st [:rooms room-id :history] conj {:kind :status :text text}))

(defn- allow-cli [st {:keys [room-id cli]}]
  (when (and room-id (seq (str cli)))
    {:state (update-in st [:rooms room-id :ext ext-id :allowed-clis]
                       (fnil conj #{}) (str cli))}))

(defn- status-text [st room-id]
  (let [session (sort (:allowed-clis (ext-state st room-id)))
        global  (sort (global-allow-clis))
        cwd     (get-in st [:rooms room-id :cwd])]
    (str "clj — sandboxed Clojure tool\n"
         "  Global allowlist:  " (if (seq global) (str/join ", " global)
                                    (str "(none — " (config-path) ")")) "\n"
         "  Session allowlist: " (if (seq session) (str/join ", " session) "(none)") "\n"
         "  bb.edn:            " (cond
                                   (not (find-bb-edn cwd)) "(none found)"
                                   (bb-trusted? cwd)       "trusted — bb tasks allowed"
                                   :else                   "not trusted — /clj trust-bb") "\n"
         "  /clj allow <cli> · /clj revoke <cli> · /clj trust-bb · /clj reset (drop REPL state)\n"
         "  Disable with /ext disable clj")))

(defn- command [st {:keys [room-id args]}]
  (let [[sub arg] (str/split (str/trim (str args)) #"\s+")]
    (case sub
      "allow"
      (if (seq (str arg))
        {:state (-> st
                    (update-in [:rooms room-id :ext ext-id :allowed-clis]
                               (fnil conj #{}) arg)
                    (status-line room-id (str "clj: `" arg "` allowed for this session")))}
        {:state (status-line st room-id "usage: /clj allow <cli>")})

      "revoke"
      (if (seq (str arg))
        {:state (-> st
                    (update-in [:rooms room-id :ext ext-id :allowed-clis]
                               (fnil disj #{}) arg)
                    (status-line room-id (str "clj: `" arg "` revoked")))}
        {:state (status-line st room-id "usage: /clj revoke <cli>")})

      "reset"
      {:state   (status-line st room-id "clj: REPL context reset")
       :effects [[:ext.clj/reset-runtime {:room-id room-id}]]}

      "trust-bb"
      {:state   st
       :effects [[:ext.clj/trust-bb {:room-id room-id
                                     :cwd     (get-in st [:rooms room-id :cwd])}]]}

      {:state (status-line st room-id (status-text st room-id))})))

(defn- reset-runtime-fx [_ {:keys [room-id]}]
  (swap! runtimes dissoc room-id))

(defn- ext-status [st {:keys [room-id text]}]
  {:state (status-line st room-id text)})

(defn- trust-bb-fx [{:keys [dispatch!]} {:keys [room-id cwd]}]
  (let [{:keys [path sha]} (trust-bb! cwd)]
    (dispatch! {:type :ext.clj/status :room-id room-id
                :text (if sha
                        (str "clj: trusted bb.edn — bb tasks allowed\n"
                             "  " path "\n"
                             "  sha256: " (subs sha 0 16) "…")
                        "clj: no bb.edn found from this directory")})))

(def ^:private SYSTEM_PROMPT
  (str "## clj tool\n"
       "There is no bash tool — all shell-style work goes through the `clj` "
       "tool (sandboxed Clojure REPL). File ops use the builtin helpers "
       "(cat ls glob grep find head tail spit mkdir cp mv rm tmpdir cwd stat "
       "realpath which basename dirname touch now); HTTP "
       "via (curl url) → {:status :body}; JSON via the pre-approved (jq "
       "filter json-or-data) helper — pipes to jq on stdin (no tmp file) and "
       "parses the result to Clojure data; git via the pre-approved (git …) "
       "helper — (git \"log\" \"--oneline\" \"-15\") → stdout string, no "
       "approval needed (push/clean excluded); other real "
       "CLIs run via (sh \"cmd\" \"arg\" …) — argv-style, one command, no "
       "pipes or shell strings (compose results in Clojure instead). "
       "The REPL "
       "persists across your tool calls: (def x …) once, reuse it later "
       "instead of re-reading files. "
       "Run this project's Babashka tasks (build, test, check, …) with the "
       "dedicated `bb` tool ({\"task\":\"test\"}, or no task to list tasks) — "
       "it needs the project's bb.edn trusted once (/clj trust-bb, or approve "
       "when prompted)."))

(def extension
  {:id               ext-id
   :init             {:room {:allowed-clis #{}}}
   :handlers         {:ext.clj/allow-cli allow-cli
                      :ext.clj/status    ext-status}
   :fx               {:ext.clj/reset-runtime reset-runtime-fx
                      :ext.clj/trust-bb      trust-bb-fx}
   :tool-definitions [tool-def bb-tool-def]
   :tool-registry    {"clj" clj-tool
                      "bb"  bb-tool}
   :remove-tools     #{"bash"}
   :tool-gate        tool-gate
   :system-prompt    SYSTEM_PROMPT
   :commands         [{:name "clj"
                       :description "Sandboxed Clojure tool — status, allow/revoke CLIs, trust bb.edn, reset REPL"
                       :handler command}]
   :on-disable       (fn [] (reset! runtimes {}))})