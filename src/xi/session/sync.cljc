(ns xi.session.sync
  "Shared logic for resolving Xi session sync paths.
   Works in both Babashka (Clojure) and ClojureScript (Node).
   Returns relative paths from $HOME so callers can rsync them."
  (:require [clojure.string :as str]
            #?(:clj [cheshire.core :as json]
               :cljs ["node:fs" :as fs])
            #?(:clj [babashka.fs])))

;; ── Path encoding ─────────────────────────────────────────────────────────────

(defn encode-cwd-claude
  "Encode CWD for Claude CLI project directory.
   Strip leading / then replace / and . with -.
   /home/floscr/.config/dotfiles → -home-floscr--config-dotfiles"
  [cwd]
  (let [stripped (if (str/starts-with? cwd "/") (subs cwd 1) cwd)]
    (str "-" (str/replace stripped #"[/.]" "-"))))

;; ── Platform abstraction ──────────────────────────────────────────────────────

(defn- home-dir []
  #?(:clj  (System/getProperty "user.home")
     :cljs (aget js/process.env "HOME")))

(defn- path-join [& parts]
  (str/join "/" parts))

(defn- file-exists? [path]
  #?(:clj  (.exists (java.io.File. ^String path))
     :cljs (try (.accessSync fs path) true (catch :default _ false))))

(defn- glob-json-files
  "Return seq of absolute paths for *.json files under dir (recursive)."
  [dir]
  #?(:clj  (when (.isDirectory (java.io.File. ^String dir))
             (mapv str (babashka.fs/glob dir "**/*.json")))
     :cljs (when (try (.accessSync fs dir) true (catch :default _ false))
             (let [entries (.readdirSync fs dir #js {:recursive true})]
               (->> entries
                    (filter #(str/ends-with? % ".json"))
                    (mapv #(str dir "/" %)))))))

(defn- read-json
  "Read and parse a JSON file. Returns nil on failure."
  [path]
  #?(:clj  (try (json/parse-string (slurp path) true)
                (catch Exception _ nil))
     :cljs (try (let [content (.readFileSync fs path "utf8")]
                  (js->clj (js/JSON.parse content) :keywordize-keys true))
                (catch :default _ nil))))

;; ── Sync manifest ─────────────────────────────────────────────────────────────

(def ^:private session-dirs
  "Directories (relative to $HOME) that contain session metadata to sync."
  [".pi/agent/sessions"
   ".config/xi/sessions"])

(defn sync-manifest
  "Return a map describing all paths that need syncing for Xi sessions.

   {:session-dirs [\"rel/path\" ...]      ;; dirs to rsync wholesale
    :jsonl-files  [\"rel/path\" ...]}      ;; individual JSONL conversation logs

   All paths are relative to $HOME.
   JSONL paths are included regardless of whether they exist locally,
   so callers can pull missing files from a remote."
  []
  (let [home (home-dir)
        xi-root (path-join home ".config/xi/sessions")
        session-files (glob-json-files xi-root)
        jsonl-paths (->> session-files
                         (keep (fn [path]
                                 (when-let [data (read-json path)]
                                   (let [cli-sid (:cli-session-id data)
                                         cwd (:cwd data)]
                                     (when (and cli-sid cwd)
                                       (let [encoded (encode-cwd-claude cwd)]
                                         (str ".claude/projects/" encoded "/" cli-sid ".jsonl")))))))
                         distinct
                         vec)]
    {:session-dirs session-dirs
     :jsonl-files jsonl-paths}))
