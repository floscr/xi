(ns xi.env
  "Startup environment sanitizer (node/Bun only).

   A long-lived xi server inherits its environment from whatever shell / tmux
   session launched it. On NixOS a `nixos-rebuild` / home-manager switch made
   *after* that launch leaves the process carrying env vars that point at old
   `/nix/store` paths the current generation no longer sets — most damagingly
   `DEPS_CLJ_TOOLS_DIR`, which makes every babashka tool (kb, gtd, review, …)
   try to install clojure-tools into the read-only store and crash. Since tool
   subprocesses spawn with `js/process.env`, that stale env poisons all of them.

   This mirrors Emacs' `exec-path-from-shell`: capture a *clean* login-shell
   environment and reconcile `process.env` with it — refresh PATH and drop
   orphaned store-pointing vars — while preserving launcher / service vars
   (.env's PUSHOVER_*/XI_*, TMUX, XDG_RUNTIME_DIR, …) that a login shell would
   not set. Fixing `process.env` in place means every existing tool spawn picks
   up the corrected environment with no per-tool changes."
  (:require [clojure.string :as str]))

(defn- capture-clean-env
  "Run the user's login shell under a minimal seed env and return its full
   environment as a {string string} map, or nil on any failure.

   The minimal seed (an `env -i`-style reset) is what sheds inherited poison:
   a login shell spawned as a plain child would simply re-inherit it. We seed
   only enough to locate and run the shell; the login shell rebuilds the real
   PATH itself. Output is captured NUL-delimited (`env -0`) so values that
   contain newlines parse correctly."
  []
  (try
    (let [penv  (unchecked-get js/process "env")
          shell (or (aget penv "SHELL") "/bin/sh")
          seed  #js {"HOME"  (aget penv "HOME")
                     "USER"  (aget penv "USER")
                     "TERM"  (or (aget penv "TERM") "xterm")
                     "SHELL" shell
                     "LANG"  (or (aget penv "LANG") "")
                     ;; minimal PATH only so the shell binary resolves
                     "PATH"  "/run/current-system/sw/bin:/usr/bin:/bin"}
          res   (js/Bun.spawnSync
                 #js [shell "-l" "-i" "-c" "env -0"]
                 #js {:env seed :stdout "pipe" :stderr "ignore"})
          out   (when (and res (.-success res) (.-stdout res))
                  (.toString (.-stdout res)))]
      (when (seq out)
        (into {}
              (keep (fn [entry]
                      (let [i (.indexOf entry "=")]
                        (when (pos? i)
                          [(subs entry 0 i) (subs entry (inc i))]))))
              (str/split out #"\u0000"))))
    (catch :default _ nil)))

(def ^:private preserved-prefixes
  "Env-key prefixes for launcher / service vars that are intentionally set by
   xi's own launcher (systemd unit, .env) and legitimately point into
   /nix/store — e.g. XI_AMAZON_CHROME=/nix/store/…/chromium/bin/chromium. A
   login shell never sets these, so without this guard the store-pointer drop
   below would delete them and break the services that read them."
  ["XI_" "PUSHOVER_"])

(defn- preserved-key?
  [k]
  (boolean (some #(str/starts-with? k %) preserved-prefixes)))

(defn plan-sanitize
  "Pure reconciliation. Given `inherited` and `clean` env maps ({string string}),
   decide what to change:
   - :dropped — orphaned vars the clean env does not set AND whose value points
     into /nix/store (stale references left by an old generation, e.g.
     DEPS_CLJ_TOOLS_DIR / JAVA_HOME / LOCALE_ARCHIVE_2_27). Non-store orphans
     (launcher / service vars like PUSHOVER_*/XI_*) are kept, as are launcher /
     service vars matching `preserved-prefixes` even when they point into the
     store.
   - :path — the refreshed PATH from `clean`, or nil when it already matches."
  [inherited clean]
  (let [dropped  (->> inherited
                      (keep (fn [[k v]]
                              (when (and (not (contains? clean k))
                                         (not (preserved-key? k))
                                         (string? v)
                                         (str/starts-with? v "/nix/store/"))
                                k)))
                      sort
                      vec)
        new-path (get clean "PATH")
        path     (when (and new-path (not= new-path (get inherited "PATH")))
                   new-path)]
    {:dropped dropped :path path}))

(defn sanitize-inherited-env!
  "Reconcile js/process.env with a freshly captured login-shell env (see
   `plan-sanitize`): drop orphaned /nix/store vars and refresh PATH.

   No-op returning nil if the clean env can't be captured (fail-safe: keep the
   inherited env untouched). Otherwise mutates js/process.env in place, logs a
   one-line summary, and returns {:dropped [k…] :path-refreshed bool}."
  []
  (when-let [clean (capture-clean-env)]
    (let [penv (unchecked-get js/process "env")
          {:keys [dropped path]} (plan-sanitize (js->clj penv) clean)]
      (doseq [k dropped] (js-delete penv k))
      (when path (aset penv "PATH" path))
      (when (or (seq dropped) path)
        (js/console.error
         (str "[xi] env sanitized"
              (when (seq dropped) (str "; dropped stale nix vars: " (str/join ", " dropped)))
              (when path "; refreshed PATH"))))
      {:dropped dropped :path-refreshed (some? path)})))
