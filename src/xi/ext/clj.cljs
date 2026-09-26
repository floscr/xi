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
            [xi.ext.clj-process :as proc]
            [xi.ext.clj-socket :as sock]
            [xi.ext.permission-gate :as pg]
            [xi.sandbox.core :as sandbox]
            ["node:child_process" :as cp]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]
            ["node:worker_threads" :as wt]))

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


(defn- opts-cwd [opts]
  (or (:cwd @opts) (.cwd js/process)))

;; Runtime path gate: 2×Int32 header [status root-len] + UTF-8 root bytes.
;; status: 0 pending · 1 allowed · 2 denied.
(def ^:private GATE_SAB_BYTES (+ 8 4096))

(defn- runtime-gate!
  "Ask the main thread to approve an out-of-sandbox `resolved` path hit
   mid-eval (a dynamic path the static gate couldn't pre-approve). Posts a
   gateRequest to the parent thread and blocks in an Atomics.wait loop on the
   request's SharedArrayBuffer until the user answers the approval dialog —
   waking early (and throwing) when the eval's abort flag flips on turn-end.
   Returns the approved root string, :denied, or nil when there is no parent
   thread to ask (eval running outside a worker)."
  [opts kind resolved]
  (when wt/parentPort
    (let [sab (js/SharedArrayBuffer. GATE_SAB_BYTES)
          i32 (js/Int32Array. sab 0 2)
          rid (:room-id @opts)
          _   (.postMessage wt/parentPort
                            #js {:gateRequest (name kind)
                                 :path        (str resolved)
                                 :roomKey     (if (keyword? rid) (name rid) (str rid))
                                 :sab         sab})
          ^js abort (:abort-arr @opts)]
      (loop []
        (js/Atomics.wait i32 0 0 250)
        (let [status (js/Atomics.load i32 0)]
          (cond
            (= status 1)
            (let [len (js/Atomics.load i32 1)]
              (.decode (js/TextDecoder.) (.slice (js/Uint8Array. sab 8 len))))

            (= status 2) :denied

            (and abort (not (zero? (js/Atomics.load abort 0))))
            (throw (ex-info "clj: aborted (turn ended)" {:aborted true}))

            :else (recur)))))))

(defn- resolve-read
  "Canonicalize p against cwd; throw on credential paths and on reads that
   escape the working dir, the OS tmp dir, and any gate-approved roots
   (:allowed-reads, plus :allowed-writes since a write grant implies read).
   An unapproved out-of-repo path raises the approval dialog at runtime via
   runtime-gate! (the eval blocks until answered); the approved root is added
   to :allowed-reads so further reads under it pass without a round-trip."
  [opts p]
  (let [cwd      (opts-cwd opts)
        resolved (sandbox/real-resolve cwd (str p))
        real-cwd (sandbox/real-resolve cwd ".")
        tmp      (sandbox/real-resolve cwd (os/tmpdir))
        allowed  (into (set (:allowed-reads @opts)) (:allowed-writes @opts))]
    (when (some #(sandbox/path-within? resolved %) (sandbox/hidden-paths))
      (throw (ex-info (str "clj: reading credential paths is blocked: " p) {})))
    (if (or (sandbox/path-within? resolved real-cwd)
            (sandbox/path-within? resolved tmp)
            (some #(sandbox/path-within? resolved %) allowed))
      resolved
      (let [verdict (runtime-gate! opts :read resolved)]
        (cond
          (string? verdict)
          (do (swap! opts update :allowed-reads (fnil conj #{}) verdict)
              resolved)

          (= verdict :denied)
          (throw (ex-info (str "clj: user denied reading outside the repo: " p) {}))

          :else
          (throw (ex-info (str "clj: reads are limited to the working dir and "
                               (os/tmpdir)
                               " (out-of-repo paths need gate approval): " p) {})))))))

(defn- resolve-write
  "Canonicalize p against cwd; only the working dir, the OS tmp dir, and any
   out-of-repo roots the user pre-approved at the gate (`:allowed-writes` in
   opts) are writable from clj scripts. An unapproved out-of-repo path raises
   the approval dialog at runtime via runtime-gate! (the eval blocks until
   answered); the approved root is added to :allowed-writes."
  [opts p]
  (let [cwd      (opts-cwd opts)
        resolved (sandbox/real-resolve cwd (str p))
        real-cwd (sandbox/real-resolve cwd ".")
        tmp      (sandbox/real-resolve cwd (os/tmpdir))
        allowed  (:allowed-writes @opts)]
    (if (or (sandbox/path-within? resolved real-cwd)
            (sandbox/path-within? resolved tmp)
            (some #(sandbox/path-within? resolved %) allowed))
      resolved
      (let [verdict (runtime-gate! opts :write resolved)]
        (cond
          (string? verdict)
          (do (swap! opts update :allowed-writes (fnil conj #{}) verdict)
              resolved)

          (= verdict :denied)
          (throw (ex-info (str "clj: user denied writing outside the repo: " p) {}))

          :else
          (throw (ex-info (str "clj: writes are limited to the working dir and "
                               (os/tmpdir) ": " p) {})))))))

(defn- glob-base
  "The literal directory prefix of a glob pattern — everything before the first
   glob metacharacter, trimmed to its last path segment (\".\" when there is
   none). Used to confine glob to the same roots as the other read helpers: an
   absolute (`/etc/**`) or `../`-escaping pattern would otherwise slip past the
   repo boundary, since glob doesn't route through resolve-read."
  [pat]
  (let [meta   (->> ["*" "?" "[" "{"]
                    (keep #(str/index-of pat %))
                    (reduce min (count pat)))
        prefix (subs pat 0 meta)]
    (if (str/includes? prefix "/")
      (subs prefix 0 (str/last-index-of prefix "/"))
      ".")))

;; ── Script-visible helpers ───────────────────────────────────────────────────
;; All close over the per-room opts atom {:cwd … :allowed #{…}} which is
;; refreshed before every eval (the SCI ctx itself is long-lived).


(defn- read-file [opts p]
  (fs/readFileSync (resolve-read opts p) "utf8"))

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

(defn- sh-fn
  "(sh \"cmd\" \"arg\" …) → stdout string on exit 0 (falls back to stderr when
   stdout is empty — ffmpeg-style tools); throws ex-info with
   {:exit :out :err} on non-zero exit. Mirrors the git helper."
  [opts]
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
        (let [{:keys [exit out err]} (spawn-sync! argv (opts-cwd opts))]
          (if (zero? exit)
            (let [out' (str/trimr out)]
              (if (str/blank? out') (str/trimr err) out'))
            (throw (ex-info (str "sh: " (str/join " " argv) " failed (exit " exit "): "
                                 (str/trim (str err "\n" out)))
                            {:exit exit :out out :err err}))))))))

(defn- grep-fn [opts]
  (fn [pattern & [p]]
    (let [cwd    (opts-cwd opts)
          target (resolve-read opts (or p "."))
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
          target (resolve-read opts (or dir "."))
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

(defn ss-escalated-command?
  "True when an `ss` command uses a flag that isn't read-only: -K/--kill
   destroys matching sockets (SOCK_DESTROY), -D/--diag writes raw socket
   dumps to an arbitrary file, bypassing the sandbox's write guards. These
   escalate to the normal approval flow instead of auto-running. Handles
   bundled short flags (-tK)."
  [cmd]
  (let [[bin & args] (str/split cmd #"\s+")]
    (and (= bin "ss")
         (boolean (some #(or (re-matches #"-[^-]*[KD].*" %)
                             (str/starts-with? % "--kill")
                             (str/starts-with? % "--diag"))
                        args)))))

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

(defn parse-ss-line
  "Parse one `ss -lntupH` output line → {:proto :addr :port :process :pid}.
   :process/:pid are nil when ss can't see the owning process (not ours)."
  [line]
  (let [fields (str/split (str/trim line) #"\s+")
        local  (nth fields 4 "")
        i      (str/last-index-of local ":")
        [_ pname pid] (re-find #"\(\"([^\"]+)\",pid=(\d+)" line)]
    {:proto   (first fields)
     :addr    (if i (subs local 0 i) local)
     :port    (when i (js/parseInt (subs local (inc i)) 10))
     :process pname
     :pid     (when pid (js/parseInt pid 10))}))

(defn- ports-fn
  "Pre-approved listening-socket lister (the `ss`/`netstat` agents keep
   reaching for). (ports) → vector of {:proto :addr :port :process :pid} for
   every listening TCP/UDP socket; (ports 7474) filters to that port."
  [opts]
  (fn ports*
    ([] (ports* nil))
    ([port]
     (let [{:keys [exit out err]} (spawn-sync! ["ss" "-lntupH"] (opts-cwd opts))]
       (if (zero? exit)
         (let [rows (->> (str/split-lines (str/trimr out))
                         (remove str/blank?)
                         (mapv parse-ss-line))]
           (if port (filterv #(= port (:port %)) rows) rows))
         (throw (ex-info (str "clj: ss failed: " (str/trim err)) {})))))))

;; blocking-sleep lives in xi.ext.clj-process (shared with the process ns);
;; the `sleep` helper and `Thread/sleep` use the abortable variant so an
;; ESC/turn-end wakes them instead of blocking the worker to the bitter end.

(defn- helper-fns
  "The 'user-namespace helpers injected into the SCI ctx. `cat` and `find`
   shadow clojure.core (overridden in the 'clojure.core sci namespace)."
  [opts]
  (let [cat' (fn [p] (read-file opts p))]
    {'cat    cat'
     'slurp  cat'
     'spit   (fn [p s & [{:keys [append]}]]
               (fs/writeFileSync (resolve-write opts p) (str s)
                                 #js {:flag (if append "a" "w")})
               nil)
     'ls     (fn [& [p]]
               (let [dir (resolve-read opts (or p "."))]
                 (->> (fs/readdirSync dir #js {:withFileTypes true})
                      (mapv #(str (.-name %) (when (.isDirectory %) "/")))
                      sort vec)))
     'head   (fn [p & [n]] (vec (take (or n 10) (str/split-lines (read-file opts p)))))
     'tail   (fn [p & [n]] (vec (take-last (or n 10) (str/split-lines (read-file opts p)))))
     'glob   (fn [pattern]
               (let [cwd (opts-cwd opts)
                     pat (sandbox/expand-home (str pattern))]
                 ;; Confine glob to the allowed roots: resolve-read on the
                 ;; pattern's literal base dir throws when it escapes the repo.
                 (resolve-read opts (glob-base pat))
                 (->> (if (exists? js/Bun)
                        (js/Array.from (.scanSync (js/Bun.Glob. pat)
                                                  #js {:cwd cwd}))
                        ;; node fallback (test target runs under node)
                        (js/Array.from (fs/globSync pat #js {:cwd cwd})))
                      sort vec)))
     'grep   (grep-fn opts)
     'find   (find-fn opts)
     'mkdir  (fn [p]
               (let [dir (resolve-write opts p)]
                 (fs/mkdirSync dir #js {:recursive true})
                 dir))
     'cp     (fn [from to]
               (fs/cpSync (resolve-read opts from)
                          (resolve-write opts to)
                          #js {:recursive true})
               nil)
     'mv     (fn [from to]
               (fs/renameSync (resolve-write opts from)
                              (resolve-write opts to))
               nil)
     'rm     (fn [& paths]
               (doseq [p paths]
                 (fs/rmSync (resolve-write opts p)
                            #js {:force true :recursive true}))
               nil)
     'tmpdir (fn [] (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-clj-")))
     'stat   (fn [p]
               (let [s (fs/statSync (resolve-read opts p))]
                 {:size     (.-size s)
                  :dir?     (.isDirectory s)
                  :file?    (.isFile s)
                  :mode     (.toString (bit-and (.-mode s) 0xfff) 8)
                  :mtime-ms (js/Math.round (.-mtimeMs s))
                  :mtime    (.toISOString (.-mtime s))
                  :ctime    (.toISOString (.-ctime s))}))
     'realpath (fn [p] (fs/realpathSync (resolve-read opts p)))
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
               (let [f (resolve-write opts p)
                     now (js/Date.)]
                 (if (fs/existsSync f)
                   (fs/utimesSync f now now)
                   (fs/writeFileSync f ""))
                 nil))
     'now    (fn [] (.toISOString (js/Date.)))
     'cwd    (fn [] (opts-cwd opts))
     'curl   (curl-fn opts)
     'ports  (ports-fn opts)
     'jq     (jq-fn opts)
     'git    (git-fn opts)
     'sleep  (fn [ms] (proc/sleep-abortable opts ms))
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
            '[clojure.walk :as walk] '[clojure.edn :as edn]
            '[clojure.data.json :as json] '[cheshire.core])")

(defn- json-transform
  "Apply data.json-style :key-fn / :value-fn to already js->clj'd data."
  [data key-fn value-fn]
  (walk/postwalk
   (fn [x]
     (if (map? x)
       (reduce-kv
        (fn [m k v]
          (let [k' (if key-fn (key-fn k) k)]
            (assoc m k' (if value-fn (value-fn k' v) v))))
        {} x)
       x))
   data))

(def ^:private json-data-namespace
  "clojure.data.json shim backed by the host's js/JSON."
  {'read-str (fn [s & {:keys [key-fn value-fn]}]
               (cond-> (js->clj (js/JSON.parse s))
                 (or key-fn value-fn) (json-transform key-fn value-fn)))
   'write-str (fn [x & _] (js/JSON.stringify (clj->js x)))
   'json-str (fn [x & _] (js/JSON.stringify (clj->js x)))})

(def ^:private cheshire-namespace
  "cheshire.core shim backed by the host's js/JSON."
  (let [parse (fn [s & [key-fn]]
                (cond
                  (nil? s) nil
                  (true? key-fn) (js->clj (js/JSON.parse s) :keywordize-keys true)
                  (fn? key-fn) (json-transform (js->clj (js/JSON.parse s)) key-fn nil)
                  :else (js->clj (js/JSON.parse s))))
        gen (fn [x & _] (js/JSON.stringify (clj->js x)))]
    {'parse-string parse
     'parse-string-strict parse
     'decode parse
     'generate-string gen
     'encode gen}))

(defn- format-sign [neg? flags]
  (cond neg?                        "-"
        (str/includes? flags "+") "+"
        (str/includes? flags " ") " "
        :else                      ""))

(defn- format-pad
  "Pad `body` (prefixed by `sign` for numbers) to `width`, honoring the -/0 flags."
  [body sign flags width]
  (let [width (if (seq width) (js/parseInt width 10) 0)
        left? (str/includes? flags "-")
        zero? (and (str/includes? flags "0") (not left?))
        pad-n (max 0 (- width (count sign) (count body)))]
    (cond
      left? (str sign body (str/join (repeat pad-n " ")))
      zero? (str sign (str/join (repeat pad-n "0")) body)
      :else (str (str/join (repeat pad-n " ")) sign body))))

(defn- sandbox-format
  "sprintf-like `format` for the sandbox — SCI/goog's format lacks hex (%x) and
   several other conversions. Supports %s %S %d %i %u %o %x %X %e %E %f %g %G %c
   %b %B %% with -/+/space/0/# flags, width and .precision."
  [fmt & args]
  (let [remaining (atom args)
        next-arg! (fn [] (let [[a & r] @remaining] (reset! remaining r) a))]
    (str/replace
     fmt
     #"%([-+ 0#]*)(\d+)?(?:\.(\d+))?([%sSdiouxXeEfgGcbB])"
     (fn [[whole flags width prec conv]]
       (let [flags (or flags "")]
         (if (= conv "%")
           "%"
           (let [v (next-arg!)]
             (case conv
               ("s" "S")
               (let [s (if (nil? v) "null" (str v))
                     s (if (= conv "S") (str/upper-case s) s)
                     s (if (seq prec) (subs s 0 (min (count s) (js/parseInt prec 10))) s)
                     left? (str/includes? flags "-")
                     width (if (seq width) (js/parseInt width 10) 0)
                     pad-n (max 0 (- width (count s)))]
                 (if left?
                   (str s (str/join (repeat pad-n " ")))
                   (str (str/join (repeat pad-n " ")) s)))

               ("d" "i" "u" "o" "x" "X")
               (let [n      (js/Math.trunc v)
                     neg?   (neg? n)
                     mag    (js/Math.abs n)
                     digits (case conv
                              ("d" "i" "u") (.toString mag 10)
                              "o"           (.toString mag 8)
                              "x"           (.toString mag 16)
                              "X"           (str/upper-case (.toString mag 16)))
                     digits (if (seq prec)
                              (let [p (js/parseInt prec 10)]
                                (str (str/join (repeat (max 0 (- p (count digits))) "0")) digits))
                              digits)
                     prefix (if (and (str/includes? flags "#") (not (zero? mag)))
                              (case conv "x" "0x" "X" "0X" "o" "0" "")
                              "")]
                 (format-pad (str prefix digits) (format-sign neg? flags) flags width))

               ("f" "e" "E" "g" "G")
               (let [p    (if (seq prec) (js/parseInt prec 10) 6)
                     neg? (neg? v)
                     mag  (js/Math.abs v)
                     body (case conv
                            "f"       (.toFixed mag p)
                            ("e" "E") (.toExponential mag p)
                            ("g" "G") (.toPrecision mag (max 1 p)))
                     body (if (or (= conv "E") (= conv "G")) (str/upper-case body) body)]
                 (format-pad body (format-sign neg? flags) flags width))

               "c"
               (let [s     (if (number? v) (js/String.fromCharCode v) (str v))
                     left? (str/includes? flags "-")
                     width (if (seq width) (js/parseInt width 10) 0)
                     pad-n (max 0 (- width (count s)))]
                 (if left?
                   (str s (str/join (repeat pad-n " ")))
                   (str (str/join (repeat pad-n " ")) s)))

               ("b" "B")
               (let [s (if v "true" "false")
                     s (if (= conv "B") (str/upper-case s) s)]
                 (format-pad s "" flags width))

               whole))))))))

(defn- make-ctx [opts]
  (let [helpers (helper-fns opts)
        ctx (sci/init {:namespaces
                       {'user helpers
                        ;; shadow the core vars our helpers collide with, plus
                        ;; the parse-* fns SCI's built-in core lacks (backed by
                        ;; the host cljs.core implementations).
                        'clojure.core (merge (select-keys helpers '[cat find])
                                             {'parse-long    parse-long
                                              'parse-double  parse-double
                                              'parse-boolean parse-boolean
                                              'parse-uuid    parse-uuid
                                              'format        sandbox-format})
                        'clojure.data.json json-data-namespace
                        'cheshire.core cheshire-namespace
                        ;; background processes: (process/start "cmd") etc.,
                        ;; command strings gated + enforced via :allowed-bg.
                        'process (proc/sci-namespace opts)
                        ;; synchronous loopback TCP sockets:
                        ;; (socket/connect "127.0.0.1" 7474) … see
                        ;; xi.ext.clj-socket.
                        'socket (sock/sci-namespace opts)}
                       :classes {'Math js/Math
                           ;; JVM-style Thread/sleep, backed by a synchronous
                           ;; (abortable) worker-thread block.
                           'Thread  #js {:sleep (fn [ms] (proc/sleep-abortable opts ms))}
                                 ;; JVM-style numeric parsing statics so code
                                 ;; like (Long/parseLong s) resolves.
                                 'Long    #js {:parseLong   (fn [s & [radix]]
                                                              (js/parseInt s (or radix 10)))}
                                 'Integer #js {:parseInt    (fn [s & [radix]]
                                                              (js/parseInt s (or radix 10)))}
                                 'Double  #js {:parseDouble (fn [s] (js/parseFloat s))}
                                 'js/Error js/Error
                                 'Exception js/Error
                                 'Throwable js/Error}})]
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

(defn- interop-hint
  "When SCI blocked a raw Java/JS method call (e.g. `(.contains s \"x\")`),
   return a hint pointing at the clojure.string / clojure.core equivalent;
   otherwise nil. The sandbox only allows an allowlist of interop, so most
   `(.method obj …)` forms fail with a \"… not allowed!\" message, and a few
   (like Java-style exception accessors) fail with \"Could not find instance
   method: …\"."
  [^js err]
  (let [msg (str (.-message err))]
    (cond
      ;; Java-style exception accessors: (.getMessage e) / (.getCause e). The
      ;; wording differs by exception type — a plain js/Error yields "Could not
      ;; find instance method: getMessage", an ExceptionInfo "Method getMessage
      ;; on function … not allowed!" — so match the accessor name in either.
      (re-find #"(?:instance method: |Method )(?:getMessage|getCause|getLocalizedMessage)\b" msg)
      (str "Hint: clj runs in a sandbox (ClojureScript/SCI, not the JVM) — "
           "Java interop like (.getMessage e) doesn't exist. Use the portable "
           "(ex-message e) for the message and (ex-cause e) for the cause, and "
           "catch with (catch :default e …) rather than (catch Exception e …).")

      (and (str/includes? msg "not allowed!")
           (re-find #"Method \S" msg))
      (str "Hint: clj runs in a sandbox — raw interop (.method obj …) is "
           "blocked. Use clojure.string / clojure.core instead, e.g. "
           "(str/includes? s \"x\") for .contains, (str/starts-with? s \"x\") "
           "for .startsWith, (str/lower-case s) for .toLowerCase, "
           "(str/split s #\",\") for .split."))))

(defn- eval-code! [{:keys [code room-id cwd allowed allowed-writes allowed-reads
                           allowed-bg abort-arr]}]
  (let [{:keys [ctx opts]} (ensure-runtime! (or room-id :default))
        prints (atom "")
        commit-outs (atom [])]
    (swap! opts assoc :cwd cwd :allowed (set allowed)
           :allowed-writes (set allowed-writes)
           :allowed-reads (set allowed-reads)
           :allowed-bg (set allowed-bg)
           :abort-arr abort-arr
           :room-id (or room-id :default)
           :commit-outs commit-outs)
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
                                                  (when column (str ":" column)) ")"))
                                  (when-let [hint (interop-hint err)]
                                    (str "\n\n" hint)))
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
        "(ports) / (ports 7474) → listening sockets as {:proto :addr :port :process :pid} "
        "(jq \".foo[]\" json-or-data) → parsed result, no tmp file (opts {:raw true}) "
        "(git \"status\" \"--short\") → stdout string (pre-approved; push/clean "
        "excluded) (sh \"cmd\" \"arg\" …) → stdout string, throws on "
        "non-zero exit ({:exit :out :err} in ex-data). "
        "clojure.core + str/set/walk/edn aliases available. Paths accept a "
        "leading ~ or $HOME. Prefer this over "
        "bash pipelines: compute in-script, return small values. "
        "Example: (->> (glob \"src/**/*.cljs\") (filter #(str/includes? (cat %) \"TODO\")))\n"
        "sh runs real CLIs argv-style and needs user approval unless "
        "allowlisted; reads and writes are confined to the working dir and "
        "/tmp — a literal path outside the repo (cat/ls/glob/grep/… to read, "
        "spit/mv/cp/mkdir/touch/rm to write) raises an approval dialog (like "
        "the read/write/edit tools), and a dynamic out-of-repo path raises "
        "the same dialog at runtime (the eval blocks until answered). "
        "Credential paths (~/.ssh, auth files) are always blocked. "
        "Long-running commands: (process/start \"cmd\") → {:pid :log} runs "
        "detached (dev servers, watchers, slow builds); (process/wait pid) "
        "blocks until exit; (process/output pid) tails its log; "
        "(process/list) / (process/stop pid) manage them; "
        "(process/poll-until \"cmd\" {:until …}) waits on external state. "
        "Raw TCP to local services (nREPL, daemons — loopback only): "
        "(socket/connect \"127.0.0.1\" port) → handle; (socket/write s str-or-bytes); "
        "(socket/read s) blocks for the next chunk, {:n k} exactly k bytes, "
        "{:until \"\\n\"} through a delimiter (consumed, not returned), "
        "{:bytes? true} → byte vector, {:timeout-ms 30000}; (socket/close s). "
        "Sockets persist across calls like the rest of the REPL.")
   :input_schema {:type "object"
                  :properties {:code {:type "string"
                                      :description "Clojure code; multiple forms ok, last value is returned"}}
                  :required ["code"]}})

;; ── Worker client (main thread) ──────────────────────────────────────────────
;; The clj/bb tools do not eval inline: they post a request to the room's
;; long-lived worker thread (target/main.js re-entered via xi.cli/main's
;; isMainThread guard → xi.ext.clj-worker) and await the reply. The blocking
;; synchronous spawnSync inside an eval then stalls only that worker thread,
;; never the server's single event loop. Workers are per room: a blocking
;; eval (process/wait, poll-until, a slow bb task) stalls only its own room —
;; other rooms' clj/bb calls and WS clients stay responsive. A room's worker
;; is spawned lazily on first use and terminated on :room/close / /clj reset.

(defn- room-key
  "Normalize a room-id to a stable string key for the worker's per-room SCI
   runtimes (keywords don't survive structured clone across the thread)."
  [room-id]
  (or (some-> room-id name) "default"))

(defonce ^:private workers (atom {}))       ;; room-key str → Worker
(defonce ^:private pending (atom {}))       ;; id → {:resolve fn :room-key str :abort Int32Array|nil}
(defonce ^:private next-id (atom 0))

;; The worker mirrors process/start registrations back to the main thread as
;; `processEvent` messages; dispatching them into app state needs the app's
;; dispatch! and the original room-id (the worker only sees the string room
;; key). Both are captured by gate-clj on each gated call.
(defonce ^:private app-dispatch! (atom nil))
(defonce ^:private room-ids (atom {}))      ;; room-key str → room-id
(defonce ^:private gate-ctxs (atom {}))     ;; room-key str → tool-gate ctx (runtime path gate)

(defn- settle-worker-death!
  "A room's worker died (crash / exit / terminate) with evals possibly in
   flight — drop it and resolve each of its pending calls with an error
   result so the tool returns cleanly instead of hanging."
  [rk reason]
  (swap! workers dissoc rk)
  (let [mine (filter (fn [[_ entry]] (= rk (:room-key entry))) @pending)]
    (swap! pending #(apply dissoc % (map first mine)))
    (doseq [[_ {:keys [resolve]}] mine]
      (resolve #js {:text (str "clj/bb worker stopped: " reason
                               " — retry the command.")
                    :isError true}))))

(defn- on-process-event
  "Worker → main mirror of a background-process register/deregister: translate
   the room key back to a room-id and dispatch the process-manager event so
   room state (/ps, /kill, keep-alive) tracks worker-spawned processes."
  [^js m]
  (when-let [dispatch! @app-dispatch!]
    (when-let [rid (get @room-ids (.-roomKey m))]
      (case (.-processEvent m)
        "register"
        (dispatch! {:type    :ext.process-manager/register
                    :room-id rid
                    :process {:pid     (.-pid m)
                              :command (.-command m)
                              :started (.-started m)
                              :logfile (.-logfile m)}})
        "deregister"
        (dispatch! {:type :ext.process-manager/deregister
                    :room-id rid :pid (.-pid m)})
        nil))))

(defn- on-gate-request
  "Worker → main runtime path gate: the worker hit a dynamic out-of-repo
   read/write mid-eval and is blocked (Atomics.wait) on the request's
   SharedArrayBuffer. Run the same permission-gate approval dialog the static
   gate uses (auto-approved when the path sits under an already-allowed repo,
   or headless), then write the verdict into the SAB — status 1 + the UTF-8
   approved root on allow, status 2 on deny — and notify the waiting worker."
  [^js m]
  (let [sab     (.-sab m)
        i32     (js/Int32Array. sab 0 2)
        ctx     (get @gate-ctxs (.-roomKey m))
        path    (.-path m)
        settle! (fn [root]
                  (let [bytes (when root (.encode (js/TextEncoder.) (str root)))]
                    (if (and bytes (<= (.-length bytes) (- (.-byteLength sab) 8)))
                      (do (.set (js/Uint8Array. sab 8 (.-length bytes)) bytes)
                          (js/Atomics.store i32 1 (.-length bytes))
                          (js/Atomics.store i32 0 1))
                      (js/Atomics.store i32 0 2))
                    (js/Atomics.notify i32 0)))]
    (if-not ctx
      (settle! nil)
      (-> (js/Promise.resolve
           (if (= "write" (.-gateRequest m))
             (pg/approve-write-path ctx path)
             (pg/approve-read-path ctx path)))
          (.then settle!)
          (.catch (fn [_] (settle! nil)))))))

(defn- ensure-worker!
  "Get or lazily spawn the room's dedicated worker thread. The error/exit
   handlers only settle when this worker is still the room's registered one —
   an explicit terminate (reset, room close) settles first, so the late exit
   event of the old worker must not touch its replacement."
  [rk]
  (or (get @workers rk)
      (let [w (wt/Worker. (aget js/process.argv 1))]
        (.on w "message"
             (fn [^js m]
               (cond
                 (.-gateRequest m)  (on-gate-request m)
                 (.-processEvent m) (on-process-event m)
                 :else
                 (let [id (.-id m)]
                   (when-let [{:keys [resolve]} (get @pending id)]
                     (swap! pending dissoc id)
                     (resolve m))))))
        (.on w "error" (fn [^js e] (when (identical? w (get @workers rk))
                                     (settle-worker-death! rk (.-message e)))))
        (.on w "exit"  (fn [code] (when (identical? w (get @workers rk))
                                    (settle-worker-death! rk (str "exit " code)))))
        (swap! workers assoc rk w)
        w)))

(defn- terminate-worker!
  "Kill a room's worker (room closed, /clj reset, extension disabled): settle
   its in-flight evals, drop the mapping, terminate the thread. The room's
   REPL state ((def …)s) dies with it; the next eval spawns a fresh worker."
  [room-id reason]
  (let [rk (room-key room-id)]
    (when-let [^js w (get @workers rk)]
      (settle-worker-death! rk reason)
      (.terminate w))))

(defn- run-in-worker
  "Post a request message to its room's worker and return a Promise of the JS
   reply. clj evals get a per-eval abort flag (Int32Array over a
   SharedArrayBuffer, shared with the worker) so a turn-end can wake their
   blocking sleeps."
  [^js msg]
  (js/Promise.
   (fn [resolve _]
     (let [id  (swap! next-id inc)
           rk  (or (.-roomId msg) "default")
           w   (ensure-worker! rk)
           sab (when (= "clj" (.-kind msg)) (js/SharedArrayBuffer. 4))]
       (aset msg "id" id)
       (when sab (aset msg "abortSab" sab))
       (swap! pending assoc id {:resolve  resolve
                                :room-key rk
                                :abort    (when sab (js/Int32Array. sab))})
       (.postMessage w msg)))))

(defn reply->result
  "Worker reply #js {:text :isError} → tool result map, appending the gate's
   helper hint when present. Public for tests."
  [^js m hint]
  (let [res {:content  [{:type "text" :text (.-text m)}]
             :is-error (boolean (.-isError m))}]
    (if-let [hint (not-empty (str hint))]
      (update-in res [:content 0 :text] str "\n\n" hint)
      res)))

(defn- clj-tool [args {:keys [cwd]}]
  (-> (run-in-worker #js {:kind    "clj"
                          :code    (str (:code args))
                          :roomId  (room-key (:_room-id args))
                          :allowed (clj->js (vec (:_allowed args)))
                          :allowedWrites (clj->js (vec (:_allowed-writes args)))
                          :allowedReads  (clj->js (vec (:_allowed-reads args)))
                          :allowedBg     (clj->js (vec (:_allowed-bg args)))
                          :cwd     (or cwd (.cwd js/process))})
      (.then (fn [^js m] (reply->result m (:_hint args))))))

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
  (-> (run-in-worker #js {:kind   "bb"
                          :roomId (room-key (:_room-id args))
                          :task   (some-> (:task args) str)
                          :args   (clj->js (mapv str (:args args)))
                          :cwd    (or cwd (.cwd js/process))})
      (.then
       (fn [^js m]
         {:content  [{:type "text" :text (.-text m)}]
          :is-error (boolean (.-isError m))}))))

;; ── Worker: eval side (worker thread) ────────────────────────────────────────
;; Runs on the worker thread (xi.ext.clj-worker → here). Handles one request
;; message and returns a JS reply object. The blocking spawnSync inside
;; eval-code! / spawn-sync! stalls only this worker thread. The worker holds
;; its own long-lived `runtimes` atom, so (def x …) persists across evals.

(defn eval-message
  "Worker-thread handler: process one request map (JS object) → JS reply
   #js {:id :text :isError}."
  [^js m]
  (let [id  (.-id m)
        cwd (or (.-cwd m) (.cwd js/process))]
    (case (.-kind m)
      "clj"
      (let [res (eval-code! {:code    (str (.-code m))
                             :room-id (.-roomId m)
                             :allowed (js->clj (.-allowed m))
                             :allowed-writes (js->clj (.-allowedWrites m))
                             :allowed-reads (js->clj (.-allowedReads m))
                             :allowed-bg (js->clj (.-allowedBg m))
                             :abort-arr (when-let [sab (.-abortSab m)]
                                          (js/Int32Array. sab))
                             :cwd     cwd})]
        #js {:id      id
             :text    (get-in res [:content 0 :text])
             :isError (boolean (:is-error res))})

      "bb"
      (let [argv (bb-argv {:task (.-task m) :args (js->clj (.-args m))})
            {:keys [exit out err]} (spawn-sync! argv cwd)
            body (str/trim (str out (when (seq err) (str "\n" err))))
            text (str "$ " (str/join " " argv) "\n"
                      (if (str/blank? body) "(no output)" body)
                      (when-not (zero? exit) (str "\n[exit " exit "]")))]
        #js {:id id :text text :isError (not (zero? exit))}))))

;; ── Tool gate: pre-scan (sh …) calls, approve CLIs ───────────────────────────

(def ^:private SHELLS #{"bash" "sh" "zsh" "fish" "dash"})

(def ^:private WRITE_HELPER_TARGETS
  "clj builtin write helpers -> the 0-based arg positions whose literal string
   values are write targets (:all = every arg, for rm's varargs). The gate uses
   this to spot out-of-repo writes and raise an approval dialog, mirroring how
   the write/edit tools gate writes that escape the project."
  {'spit  [0]
   'mv    [0 1]
   'cp    [1]
   'mkdir [0]
   'touch [0]
   'rm    :all})

(def ^:private READ_HELPER_TARGETS
  "clj builtin read helpers -> the 0-based arg positions whose literal string
   values are read targets. The gate uses this to spot out-of-repo reads and
   raise an approval dialog, mirroring the write-target gating. (glob is scanned
   separately since its arg is a pattern, not a plain path — see scan-rules.)"
  {'cat      [0]
   'slurp    [0]
   'ls       [0]
   'head     [0]
   'tail     [0]
   'stat     [0]
   'realpath [0]
   'grep     [1]
   'find     [1]
   'cp       [0]})

(def ^:private SCAN_OPTS
  {:all true :auto-resolve {:current 'user} :readers (fn [_] identity)})

(def ^:private scan-rules
  "Single-pass gate-scan rules. Each rule fires on a set of head symbols and
   folds the matched (head arg…) list form into the scan accumulator. To collect
   a new class of call site for the gate, add a rule here — the code is still
   parsed and walked exactly once (see scan-code)."
  [;; (sh …) call sites: keep the raw arg vectors for the CLI / guarded checks.
   {:heads   #{'sh}
    :collect (fn [acc _head args] (update acc :sh-calls conj args))}
   ;; builtin write helpers: record each literal write-target path tagged with
   ;; the helper it came from (so rm targets can be singled out for dir-delete).
   {:heads   (set (keys WRITE_HELPER_TARGETS))
    :collect (fn [acc head args]
               (let [pos   (WRITE_HELPER_TARGETS head)
                     picks (if (= :all pos) args (map #(nth args % nil) pos))]
                 (reduce (fn [a p]
                           (cond-> a
                             (string? p) (update :writes conj {:head head :path p})))
                         acc picks)))}
   ;; builtin read helpers: record each literal read-target path so the gate can
   ;; approve out-of-repo reads (mirrors the write-target collection above).
   {:heads   (set (keys READ_HELPER_TARGETS))
    :collect (fn [acc head args]
               (let [picks (map #(nth args % nil) (READ_HELPER_TARGETS head))]
                 (reduce (fn [a p]
                           (cond-> a
                             (string? p) (update :reads conj {:head head :path p})))
                         acc picks)))}
   ;; background-process starts: (process/start "cmd") / (process/poll-until
   ;; "cmd" …) run shell command strings, so collect the literals for the same
   ;; gating sh gets (sudo/remote/guarded/CLI approval). Non-literal commands
   ;; are flagged and blocked — the worker only runs approved exact strings.
   {:heads   #{'process/start 'process/poll-until}
    :collect (fn [acc _head args]
               (let [c (first args)]
                 (if (string? c)
                   (update acc :bg conj c)
                   (assoc acc :bg-dynamic? true))))}
   ;; glob: its literal arg is a pattern, not a path — record the pattern's
   ;; literal base dir so out-of-repo globs (`/etc/**`, `../x/*`) are gated too.
   {:heads   #{'glob}
    :collect (fn [acc _head args]
               (let [p (first args)]
                 (cond-> acc
                   (string? p)
                   (update :reads conj
                           {:head 'glob
                            :path (glob-base (sandbox/expand-home p))}))))}])

(defn scan-code
  "Parse `code` once (edamame) and postwalk it once, dispatching every list form
   through `scan-rules`. Returns {:parse-error msg} on unreadable code, else the
   raw accumulated scan {:sh-calls [[arg…]…] :writes [{:head h :path p}…]}. The
   derived views below (scan-sh-calls / scan-write-paths / rm targets) read from
   this, so one gate pass parses the code a single time."
  [code]
  (try
    (let [forms (e/parse-string-all code SCAN_OPTS)
          acc   (atom {:sh-calls [] :writes [] :reads [] :bg []})]
      (walk/postwalk
       (fn [f]
         (when (seq? f)
           (let [head (first f)
                 args (vec (rest f))]
             (doseq [{:keys [heads collect]} scan-rules
                     :when (contains? heads head)]
               (swap! acc collect head args))))
         f)
       forms)
      @acc)
    (catch :default err {:parse-error (.-message err)})))

(defn- sh-summary
  "Derive the (sh …) view {:literals :commands :dynamic? :shell-c?} from a
   scan-code result. :commands joins each call's literal string args — used for
   the guarded / server-control pattern checks (dynamic args are invisible to
   it; the binary itself must still be an approved literal)."
  [scan]
  (let [calls (:sh-calls scan)]
    {:literals (set (filter string? (map first calls)))
     :commands (into []
                     (comp (filter #(string? (first %)))
                           (map #(str/join " " (filter string? %))))
                     calls)
     :dynamic? (boolean (some (complement string?) (map first calls)))
     :shell-c? (boolean (some (fn [args]
                                (and (contains? SHELLS (first args))
                                     (some #{"-c"} (filter string? args))))
                              calls))}))

(defn- write-paths-of
  "Distinct literal write-target paths (spit/mv/cp/mkdir/touch/rm) from a
   scan-code result, in first-seen order."
  [scan]
  (into [] (comp (map :path) (distinct)) (:writes scan)))

(defn- read-paths-of
  "Distinct literal read-target paths (cat/slurp/ls/head/tail/stat/realpath/
   grep/find/cp-source/glob-base) from a scan-code result, in first-seen order."
  [scan]
  (into [] (comp (map :path) (distinct)) (:reads scan)))

(defn- rm-targets-of
  "Distinct literal paths passed to the builtin (rm …) helper, from a scan-code
   result. Directory targets among these are gated as recursive tree deletes."
  [scan]
  (into [] (comp (filter #(= 'rm (:head %))) (map :path) (distinct)) (:writes scan)))

(defn scan-sh-calls
  "The (sh …) scan view (see sh-summary), parsing `code` via scan-code. Returns
   {:parse-error msg} on unreadable code. For callers/tests that scan a snippet
   directly; the gate derives this from a single shared scan-code pass."
  [code]
  (let [scan (scan-code code)]
    (if (:parse-error scan) scan (sh-summary scan))))

(defn scan-write-paths
  "Literal write-target paths in `code` (see write-paths-of); empty on parse
   error. For callers/tests that scan a snippet directly."
  [code]
  (let [scan (scan-code code)]
    (if (:parse-error scan) [] (write-paths-of scan))))

(defn scan-read-paths
  "Literal read-target paths in `code` (see read-paths-of); empty on parse
   error. For callers/tests that scan a snippet directly."
  [code]
  (let [scan (scan-code code)]
    (if (:parse-error scan) [] (read-paths-of scan))))

(defn- existing-dir?
  "True when p (resolved against cwd) is an existing directory — i.e. an (rm p)
   would be a recursive directory tree deletion."
  [cwd p]
  (try
    (let [resolved (sandbox/real-resolve cwd (str p))]
      (and (fs/existsSync resolved)
           (.isDirectory (fs/statSync resolved))))
    (catch :default _ false)))

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
                              {:options [:yes :no :always]})
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
  "Remove single- and double-quoted spans so quoted `;`/`|` don't count,
   and redirection operators (`2>&1`, `>&2`, `&>`) so their `&`/`|` don't
   read as command separators."
  [s]
  (-> s
      (str/replace #"'[^']*'" "_")
      (str/replace #"\"(?:\\.|[^\"\\])*\"" "_")
      (str/replace #"\d*>&\d*" " ")
      (str/replace #"&>>?" " ")))

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

(defn bg-command-clis
  "Leading CLI names of background shell command strings (process/start /
   poll-until run whole bash command lines, unlike argv-style sh): strip
   quoted segments, split on shell separators, skip VAR= env prefixes, take
   each segment's first word. These join the sh literals for CLI approval."
  [cmds]
  (->> cmds
       (mapcat (fn [cmd] (str/split (strip-quoted (str cmd)) #"[;|&\n]+")))
       (keep (fn [seg]
               (->> (str/split (str/trim seg) #"\s+")
                    (remove #(re-matches #"[A-Za-z_][A-Za-z0-9_]*=.*" %))
                    first
                    not-empty)))
       set))

(def ^:private HELPER_EQUIV
  "CLIs that have a builtin helper — (sh …) to these carries a hint pointing at
   the helper. Read-only ones (SAFE_AUTORUN) auto-run with the hint appended;
   the rest go through the normal per-CLI approval dialog (no longer a hard
   block) with the hint attached. Allowlisted CLIs (global config or /clj allow)
   skip the dialog — the escape hatch for when flags are needed."
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
   "ss"       "(ports) / (ports 7474) → listening sockets as data"
   "netstat"  "(ports)"
   "lsof"     "(ports) — for listening sockets"
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
   network CLIs (curl wget) aren't auto-run — raw sh would bypass the helpers'
   write-path / http-only guards — but they're no longer hard-blocked either:
   they fall through to the normal per-CLI approval flow (with the helper hint
   attached), the same as any non-allowlisted CLI. So the helper nudge is a
   warning, not a dead-end error.

   `rm` is the one write exception: deleting scratch files (typically under
   /tmp) is common enough that requiring approval each time is friction, so
   (sh \"rm\" …) is auto-allowed — including `rm -rf`, which is exempted from
   the guarded-pattern confirm in gate-clj (bash's rm -rf stays guarded). The
   attached hint points at the confined (rm f) helper and, for /tmp targets,
   notes the deletion is usually unnecessary."
  #{"ls" "cat" "head" "tail" "grep" "rg" "find" "fd" "pwd" "echo" "mktemp"
    "stat" "du" "readlink" "realpath" "which" "basename" "dirname" "date"
    "wc" "sort" "uniq" "cut" "tr" "git" "rm" "ss" "netstat" "lsof"})

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

(defn- approve-reads
  "Approve each out-of-repo read `path` via pg/approve-read-path — a dialog
   offering [y]/[n]/[r allow repo], auto-allowed when the path already sits
   under an approved read (or write) repo. Returns a promise of
   {:approved #{roots} :denied path|nil}; resolves :denied on the first refusal.
   Paths already covered by a just-approved root are skipped so several reads
   into one repo prompt only once."
  [paths ctx cwd]
  (reduce
   (fn [chain path]
     (.then chain
            (fn [{:keys [approved denied] :as acc}]
              (let [resolved (sandbox/real-resolve cwd (str path))]
                (cond
                  denied acc
                  (some #(sandbox/path-within? resolved %) approved) acc
                  :else
                  (-> (pg/approve-read-path ctx path)
                      (.then (fn [root]
                               (if root
                                 (update acc :approved conj root)
                                 (assoc acc :denied path))))))))))
   (js/Promise.resolve {:approved #{} :denied nil})
   paths))

(defn- approve-writes
  "Approve each out-of-repo write `path` via the permission gate's outside-write
   flow (pg/approve-write-path) — a dialog offering [y]/[n]/[r allow repo], auto
   allowed when the path already sits under an approved repo. Returns a promise
   of {:approved #{roots} :denied path|nil}; resolves :denied on the first
   refusal. Paths already covered by a just-approved root are skipped so a
   two-path mv into one repo prompts only once."
  [paths ctx cwd]
  (reduce
   (fn [chain path]
     (.then chain
            (fn [{:keys [approved denied] :as acc}]
              (let [resolved (sandbox/real-resolve cwd (str path))]
                (cond
                  denied acc
                  (some #(sandbox/path-within? resolved %) approved) acc
                  :else
                  (-> (pg/approve-write-path ctx path)
                      (.then (fn [root]
                               (if root
                                 (update acc :approved conj root)
                                 (assoc acc :denied path))))))))))
   (js/Promise.resolve {:approved #{} :denied nil})
   paths))

(defn- confirm-rm-dirs
  "Confirm each builtin (rm dir) that would recursively delete an existing
   directory. The prompt calls out when the target is OUTSIDE the project repo.
   Returns a promise of {:ok? bool :approved-roots #{resolved-out-of-repo-dirs}};
   resolves :ok? false on the first deny. With no confirm! attached (headless),
   directory deletions pass through (parity with the clj gate's other guarded
   confirms); out-of-repo dirs stay blocked by resolve-write since they're not
   injected as approved write roots."
  [confirm! cwd dirs]
  (reduce
   (fn [chain p]
     (.then chain
            (fn [{:keys [ok?] :as acc}]
              (let [outside? (pg/outside-project? cwd p)]
                (cond
                  (not ok?)  acc
                  (not confirm!) acc
                  :else
                  (-> (confirm! (str "Recursively delete directory `" p "`"
                                     (when outside? " — OUTSIDE the project repo")
                                     "?"))
                      (.then (fn [yes?]
                               (if yes?
                                 (cond-> acc
                                   outside? (update :approved-roots conj
                                                    (sandbox/real-resolve cwd (str p))))
                                 (assoc acc :ok? false))))))))))
   (js/Promise.resolve {:ok? true :approved-roots #{}})
   dirs))

(defn- gate-clj [tool-call {:keys [get-state room-id confirm! dispatch! cwd] :as ctx}]
  (let [code    (str (get-in tool-call [:arguments :code]))
        scan    (scan-code code)
        sh      (sh-summary scan)
        bg      (vec (:bg scan))
        bg-clis (bg-command-clis bg)
        ;; Capture dispatch! + the room-key ↔ room-id mapping so the worker's
        ;; processEvent mirrors (process/start registrations) can be
        ;; dispatched into app state (see on-process-event).
        _       (when dispatch! (reset! app-dispatch! dispatch!))
        _       (when room-id (swap! room-ids assoc (room-key room-id) room-id))
        ;; Capture the gate ctx per room so a worker's runtime gateRequest
        ;; (dynamic out-of-repo path mid-eval) can run the same approval
        ;; dialogs (see on-gate-request).
        _       (when room-id (swap! gate-ctxs assoc (room-key room-id) ctx))
        session (set (:allowed-clis (state/room-ext (get-state) room-id ext-id)))
        ;; A trusted bb.edn (sha in the trust store) makes `bb` an allowed CLI
        ;; for (sh "bb" …), same as the dedicated bb tool.
        base    (cond-> (into (global-allow-clis) session)
                  (bb-trusted? cwd) (conj "bb"))
        inject  (fn [allowed hint]
                  (cond-> (update tool-call :arguments assoc
                                  :_room-id room-id
                                  :_allowed (vec allowed))
                    (seq bg) (update :arguments assoc :_allowed-bg bg)
                    hint (update :arguments assoc :_hint hint)))]
    (cond
      (:parse-error scan)
      (blocked (str "clj: parse error — " (:parse-error scan)))

      (:shell-c? sh)
      (blocked (str "clj: (sh \"bash\" \"-c\" …) is not allowed — write the "
                    "pipeline in Clojure instead (cat/grep/glob + clojure.core)."))

      (:bg-dynamic? scan)
      (blocked (str "clj: process/start / process/poll-until need a literal "
                    "command string in this eval — a dynamically built "
                    "command can't be approved by the gate."))

      (some #(re-find #"\$\(|`" %) bg)
      (blocked (str "clj: command substitution ($(…) / backticks) is not "
                    "allowed in background commands — run the inner command "
                    "separately and interpolate its result in Clojure."))

      :else
      (let [needed   (remove base (sort (:literals sh)))
            ;; (sh "git" …) bounces to the pre-approved (git …) helper —
            ;; unless a deny-listed subcommand (push, clean) is involved,
            ;; which the helper refuses; those go through approval instead.
            git-escalated? (some (fn [cmd]
                                   (and (str/starts-with? cmd "git")
                                        (contains? GIT_DENY
                                                   (git-subcommand
                                                    (rest (str/split cmd #"\s+"))))))
                                 (:commands sh))
            ;; ss is auto-run for its read-only uses, but -K/--kill and
            ;; -D/--diag mutate (kill sockets / write files) — escalate.
            ss-escalated? (some ss-escalated-command? (:commands sh))
            shadowed (filter (fn [bin]
                               (and (contains? HELPER_EQUIV bin)
                                    (or (not= "git" bin) (not git-escalated?))
                                    (or (not= "ss" bin) (not ss-escalated?))))
                             needed)
            ;; Safe read-only CLIs run anyway — result + helper hint — so
            ;; the model doesn't lose a turn. The rest of shadowed (write /
            ;; network helper-equivalent CLIs) aren't hard-blocked; they fall
            ;; through to the normal per-CLI approval flow below (like any other
            ;; CLI), carrying the helper hint — a nudge, not a dead-end error.
            autorun  (filter #(contains? SAFE_AUTORUN %) shadowed)
            ;; bg CLIs skip the helper bounce (a background `npm run dev` has
            ;; no helper equivalent) but still need per-CLI user approval.
            bg-needed (->> bg-clis
                           (remove base)
                           (remove #(contains? SAFE_AUTORUN %))
                           sort)
            needed'  (distinct (concat (remove (set autorun) needed) bg-needed))
            tmp-rm?  (some (fn [cmd]
                             (and (str/starts-with? cmd "rm ")
                                  (or (str/includes? cmd "/tmp/")
                                      (str/includes? cmd (str (os/tmpdir))))))
                           (:commands sh))
            hint     (when (helper-hints?)
                       (not-empty
                        (str/join
                         " "
                         (remove
                          nil?
                          [(when (seq shadowed)
                             (str "hint: prefer the builtin helpers over sh: "
                                  (str/join ", " (map #(str "`" % "` → " (HELPER_EQUIV %))
                                                      shadowed))))
                           (when tmp-rm?
                             (str "note: removing files under /tmp is usually "
                                  "unnecessary — /tmp is temporary and cleared "
                                  "automatically; skip the rm unless you need "
                                  "the space back."))]))))
            remote   (filter REMOTE_CLIS (into (:literals sh) bg-clis))
            sc-cmd   (first (filter pg/server-control-kind
                                    (concat (:commands sh) bg)))
            ;; `rm` is auto-allowed from clj (SAFE_AUTORUN) — including rm -rf,
            ;; so drop rm commands from the guarded confirm here. bash's rm -rf
            ;; stays guarded (GUARDED_PATTERNS is unchanged).
            guarded  (concat
                      (->> (:commands sh)
                           (filter (fn [cmd]
                                     (some #(str/includes? cmd %) pg/GUARDED_PATTERNS)))
                           (remove #(str/starts-with? % "rm ")))
                      ;; bg command lines keep the rm guard — a background
                      ;; `rm -rf` runs through bash, not the confined helper.
                      (filter (fn [cmd]
                                (some #(str/includes? cmd %) pg/GUARDED_PATTERNS))
                              bg))
            ;; Builtin (rm dir) targets that are existing directories — a
            ;; recursive tree deletion. Gate each with its own confirm (handled
            ;; below, in or out of repo), so they're excluded from the generic
            ;; outside-write approval to avoid double-prompting.
            rm-dirs  (filter #(existing-dir? cwd %) (rm-targets-of scan))
            ;; Literal write-target paths that escape the repo (+tmp). These get
            ;; the same approval dialog the write/edit tools use, then are
            ;; injected so the worker's resolve-write allows them.
            outside-writes (->> (write-paths-of scan)
                                (filter #(pg/outside-project? cwd %))
                                (remove (set rm-dirs)))
            ;; Literal read-target paths that escape the repo (+tmp). Same
            ;; approval dialog as writes, then injected so resolve-read allows
            ;; them. A write-approved root implies read, so drop those overlaps.
            outside-reads (->> (read-paths-of scan)
                               (filter #(pg/outside-project? cwd %)))]
        (cond
          ;; sudo is never allowed from the agent — hard block, no confirm.
          (or (contains? (:literals sh) "sudo")
              (contains? bg-clis "sudo")
              (some #(str/includes? % "sudo ") (concat (:commands sh) bg)))
          (blocked "clj: `sudo` is never allowed from the agent.")

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

          :else
          ;; First clear any out-of-repo builtin reads (cat/ls/grep/…) through
          ;; the outside-read dialog, then the writes, then guarded/CLI approval.
          (-> (approve-reads outside-reads ctx cwd)
              (.then
               (fn [{reads :approved rdenied :denied}]
                 (if rdenied
                   (blocked (str "clj: user denied reading outside the repo: " rdenied))
          (-> (approve-writes outside-writes ctx cwd)
              (.then
               (fn [{writes :approved wdenied :denied}]
                 (if wdenied
                   (blocked (str "clj: user denied writing outside the repo: " wdenied))
                   ;; Confirm every recursive (rm dir) tree deletion before it
                   ;; runs; out-of-repo dirs the user OKs are injected as write
                   ;; roots so resolve-write lets them through.
                   (-> (confirm-rm-dirs confirm! cwd rm-dirs)
                       (.then
                        (fn [{rm-ok? :ok? rm-roots :approved-roots}]
                          (if-not rm-ok?
                            (blocked "clj: user denied a directory deletion")
                            (let [writes   (into (set writes) rm-roots)
                                  inject-w (fn [allowed hint]
                                             (cond-> (inject allowed hint)
                                               (seq writes) (update :arguments assoc
                                                                    :_allowed-writes (vec writes))
                                               (seq reads) (update :arguments assoc
                                                                   :_allowed-reads (vec reads))))]
                              ;; Guarded patterns (rm -rf, sudo, git push, kill …)
                              ;; need a confirm even when the CLI itself is
                              ;; allowlisted — parity with the bash gate. No
                              ;; confirm! (headless) passes through.
                              (-> (if (and (seq guarded) confirm!)
                                    (confirm-all! confirm! (map #(str "Guarded command: " %) guarded))
                                    (js/Promise.resolve true))
                                  (.then
                                   (fn [ok?]
                                     (cond
                                       (not ok?)
                                       (blocked "clj: user denied a guarded command")

                                       (empty? needed') (inject-w (into base autorun) hint)

                                       (not confirm!)
                                       (blocked (str "clj: these CLIs need approval but no client is "
                                                     "attached to confirm: " (str/join ", " needed')))

                                       :else
                                       (-> (approve-clis! confirm! dispatch! room-id cwd needed')
                                           (.then (fn [{:keys [approved denied]}]
                                                    (if denied
                                                      (blocked (str "clj: user denied running `" denied "`"))
                                                      (inject-w (into (into base autorun) approved)
                                                                hint)))))))))))))))))))))))))))

(defn- gate-bb [tool-call {:keys [cwd confirm! room-id]}]
  (let [;; bb runs in the room's eval worker — route it there (see bb-tool).
        tool-call (update tool-call :arguments assoc :_room-id room-id)
        cmd (str/join " " (bb-argv (:arguments tool-call)))
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
                    {:options [:yes :no :always]})
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
  ;; The room's live runtime is its worker thread — terminate it; the next
  ;; eval spawns a fresh one with a clean SCI context.
  (terminate-worker! room-id "REPL reset"))

(defn- on-room-close
  "Room destroyed — terminate its dedicated eval worker (and its REPL state)."
  [_st {:keys [room-id]}]
  {:effects [[:ext.clj/terminate-worker {:room-id room-id}]]})

(defn- terminate-worker-fx [_ {:keys [room-id]}]
  (terminate-worker! room-id "room closed"))

(defn- on-turn-end
  "Turn ended (incl. ESC abort): wake any still-blocked clj evals for this
   room — their abortable sleeps (process/wait, poll-until, sleep) throw so
   the blocked eval unwinds instead of outliving its turn."
  [_st {:keys [room-id]}]
  {:effects [[:ext.clj/abort-evals {:room-id room-id}]]})

(defn- abort-evals-fx [_ {:keys [room-id]}]
  (let [rk (room-key room-id)]
    (doseq [[_ entry] @pending
            :let [^js abort (:abort entry)]
            :when (and abort (= rk (:room-key entry)))]
      (js/Atomics.store abort 0 1)
      (js/Atomics.notify abort 0))))

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
       "via (curl url) → {:status :body}; listening ports via (ports) / "
       "(ports 7474) → {:proto :addr :port :process :pid} maps (no ss/lsof "
       "needed); JSON via the pre-approved (jq "
       "filter json-or-data) helper — pipes to jq on stdin (no tmp file) and "
       "parses the result to Clojure data, or via the pre-required "
       "clojure.data.json (as `json`) / cheshire.core namespaces; git via the "
       "pre-approved (git …) "
       "helper — (git \"log\" \"--oneline\" \"-15\") → stdout string, no "
       "approval needed (push/clean excluded); other real "
       "CLIs run via (sh \"cmd\" \"arg\" …) → stdout string, throws on "
       "non-zero exit — argv-style, one command, no "
       "pipes or shell strings (compose results in Clojure instead). "
       "The REPL "
       "persists across your tool calls: (def x …) once, reuse it later "
       "instead of re-reading files. "
       "Run this project's Babashka tasks (build, test, check, …) with the "
       "dedicated `bb` tool ({\"task\":\"test\"}, or no task to list tasks) — "
       "it needs the project's bb.edn trusted once (/clj trust-bb, or approve "
       "when prompted). "
       "Background processes (dev servers, watchers, slow builds — anything "
       "that outlives the eval) use the `process` namespace: "
       "(process/start \"npm run dev\") → {:pid :log} spawns detached with "
       "output to the :log file; (process/wait pid) (optional timeout-ms, "
       "default 120s — re-call to keep waiting) blocks until exit → "
       "{:status :exited/:running :exit :output}; (process/output pid) → last "
       "log lines; (process/list) → tracked processes; (process/stop pid) "
       "kills one. To wait on something external you did not start, "
       "(process/poll-until \"cmd\" {:until :exit-zero|:stdout-matches|"
       ":stdout-not-matches :pattern \"re\" :interval-ms 5000 :timeout-ms "
       "120000}) reruns cmd until the condition holds. NEVER (sleep n) to "
       "wait a process out — wait/poll-until instead. Both start and "
       "poll-until take literal shell command strings, gated like sh. "
       "Raw TCP to loopback services (nREPL, mpv/daemon IPC — remote hosts "
       "are refused) via the `socket` namespace: (socket/connect "
       "\"127.0.0.1\" port) → handle; (socket/write s str-or-byte-seq); "
       "(socket/read s) blocks for the next chunk — opts {:n k} exactly k "
       "bytes, {:until \"\\n\"} through a delimiter (consumed, not "
       "returned), {:bytes? true} → byte vector, {:timeout-ms 30000}; "
       "(socket/close s); sockets persist across your tool calls like the "
       "rest of the REPL."))

(def extension
  {:id               ext-id
   :init             {:room {:allowed-clis #{}}}
   :handlers         {:ext.clj/allow-cli allow-cli
                      :ext.clj/status    ext-status
                      :agent/turn-end    on-turn-end
                      :room/close        on-room-close}
   :fx               {:ext.clj/reset-runtime    reset-runtime-fx
                      :ext.clj/trust-bb         trust-bb-fx
                      :ext.clj/abort-evals      abort-evals-fx
                      :ext.clj/terminate-worker terminate-worker-fx}
   :tool-definitions [tool-def bb-tool-def]
   :tool-registry    {"clj" clj-tool
                      "bb"  bb-tool}
   :remove-tools     #{"bash"}
   :tool-gate        tool-gate
   :system-prompt    SYSTEM_PROMPT
   :commands         [{:name "clj"
                       :description "Sandboxed Clojure tool — status, allow/revoke CLIs, trust bb.edn, reset REPL"
                       :handler command}]
   :on-disable       (fn []
                       (reset! runtimes {})
                       (doseq [rk (keys @workers)]
                         (terminate-worker! rk "extension disabled")))})