(ns xi.paths
  "Path + env safety helpers shared by the rules engine and the clj tool:
   symlink-canonical resolution (so a path gate can't be laundered through a
   link), containment checks, the tmp scratch roots, the hidden credential
   paths, and the env allowlist."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(def HIDDEN_PATHS
  "Home-relative credential/secret paths the path gates never expose."
  [".ssh" ".gnupg" ".password-store" ".aws" ".kube"
   ".pi" ".config/xi" ".config/gh" ".netrc" ".npmrc"])

(def ENV_ALLOWLIST
  "Env vars forwarded to agent-spawned processes (everything else — API
   keys, tokens — is scrubbed). Includes the NixOS SSL/nix vars needed
   for basic tooling to function."
  ["PATH" "HOME" "USER" "LOGNAME" "SHELL" "TERM" "COLORTERM"
   "LANG" "LC_ALL" "LC_CTYPE" "TMPDIR" "TZ" "EDITOR" "PAGER"
   "XDG_DATA_DIRS" "XDG_CONFIG_DIRS" "XDG_CACHE_HOME" "XDG_CONFIG_HOME"
   "XDG_DATA_HOME" "SSL_CERT_FILE" "NIX_SSL_CERT_FILE" "CURL_CA_BUNDLE"
   "NIX_PATH" "NIX_PROFILES"])

(defn expand-home
  "Expand a leading ~ to the user's home directory, or a leading $VAR /
   ${VAR} (allowlisted env vars only) to its value. Non-allowlisted or
   unset vars are left untouched."
  [p]
  (cond
    (or (= p "~") (str/starts-with? p "~/"))
    (node-path/join (os/homedir) (subs p 1))

    (str/starts-with? p "$")
    (let [[_ var-name remainder] (re-matches #"\$\{?([A-Za-z_][A-Za-z0-9_]*)\}?((?:/.*)?)" p)]
      (or (when (and var-name (some #{var-name} ENV_ALLOWLIST))
            (when-some [v (aget js/process.env var-name)]
              (str v remainder)))
          p))

    :else p))

(defn path-within?
  "True when child (resolved) equals parent or lives under it."
  [child parent]
  (let [c (node-path/resolve child)
        p (node-path/resolve parent)]
    (or (= c p)
        (str/starts-with? c (str p node-path/sep)))))

(defn real-resolve
  "Resolve p against cwd and canonicalize symlinks, so in-process path
   gates can't be laundered through a symlink created inside cwd. For
   paths that don't exist yet, canonicalizes the nearest existing
   ancestor and rejoins the remainder."
  [cwd p]
  (let [abs (node-path/resolve cwd (expand-home (str p)))]
    (loop [probe abs rest-part ""]
      (if (fs/existsSync probe)
        (node-path/join (fs/realpathSync probe) rest-part)
        (let [parent (node-path/dirname probe)]
          (if (= parent probe)
            abs
            (recur parent (node-path/join (node-path/basename probe) rest-part))))))))

(defn hidden-paths
  "Absolute hidden paths (no existence check — usable for pure gating)."
  []
  (mapv #(node-path/join (os/homedir) %) HIDDEN_PATHS))

(defn tmp-roots
  "Canonical temp roots that path gates always treat as scratch space: the
   system `/tmp` plus `os.tmpdir()`. Both are needed because a server launched
   from a nix-shell inherits TMPDIR=/tmp/nix-shell.XXXX, so `os.tmpdir()` alone
   would leave plain `/tmp/foo` looking out-of-repo."
  [cwd]
  (into [] (comp (map #(real-resolve cwd %)) (distinct)) ["/tmp" (os/tmpdir)]))

(defn within-tmp?
  "True when canonical `resolved` lives under one of the `tmp-roots`."
  [cwd resolved]
  (boolean (some #(path-within? resolved %) (tmp-roots cwd))))

(defn scrub-env
  "JS env object containing only allowlisted vars from process.env."
  []
  (let [env (unchecked-get js/process "env")
        out #js {}]
    (doseq [k ENV_ALLOWLIST]
      (when-some [v (aget env k)]
        (unchecked-set out k v)))
    out))
