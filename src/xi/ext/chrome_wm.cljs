(ns xi.ext.chrome-wm
  "Thin async wrappers around the dotfiles `wm` CLI (xmonad/EWMH queries).

   Used by the chrome-mcp workspace scoping to learn the currently-viewed
   workspace, enumerate the shared Chrome's X11 windows (with their workspace +
   active-tab title), and relocate a window onto a workspace.

   Everything is keyed by workspace *name*, never index: xmonad workspaces are
   dynamic — they grow and shrink as they are created/destroyed, so a desktop
   *index* is unstable and can point at a different workspace minutes later.
   Names are stable, so all referencing goes through them.

   `wm` is not on the server process PATH, so it is invoked by absolute path
   (`XI_CHROME_WM_BIN`, defaulting to the dotfiles location). Every fn resolves
   to a benign value (nil / []) on failure so scoping degrades gracefully."
  (:require [clojure.string :as str]
            ["node:child_process" :as child-process]
            ["node:fs" :as fs]))

(defn- env [k] (aget js/process.env k))

(defn- wm-bin
  "Absolute path to the dotfiles `wm` CLI. Overridable via XI_CHROME_WM_BIN.
   Must be absolute: the long-lived server's PATH does not include dotfiles/bin,
   so a bare `wm` silently fails and scoping turns off."
  []
  (or (some-> (env "XI_CHROME_WM_BIN") str/trim not-empty)
      "/home/floscr/.config/dotfiles/bin/wm"))

(defn- wmctrl-bin
  "Absolute path to `wmctrl` (maps window ids to PIDs, which `wm` doesn't
   expose). Overridable via XI_CHROME_WMCTRL_BIN; same PATH caveat as wm-bin."
  []
  (or (some-> (env "XI_CHROME_WMCTRL_BIN") str/trim not-empty)
      "/etc/profiles/per-user/floscr/bin/wmctrl"))

(defn- wm-class
  "WM_CLASS substring identifying the MCP-controlled Chrome (its dedicated
   profile). Overridable via XI_CHROME_WM_CLASS."
  []
  (or (some-> (env "XI_CHROME_WM_CLASS") str/trim not-empty)
      "chrome-profile-stable"))

(defn- run-bin
  "Run `<bin> <args>` and resolve to trimmed stdout, or nil on any failure."
  [bin args]
  (js/Promise.
   (fn [resolve _reject]
     (.execFile child-process bin (clj->js args)
                #js {:env js/process.env}
                (fn [err stdout _stderr]
                  (resolve (when-not err (str/trim (str stdout)))))))))

(defn- run
  "Run `wm <args>` (absolute binary) and resolve to trimmed stdout, or nil on
   any failure."
  [args]
  (run-bin (wm-bin) args))

(defn current-workspace
  "The *name* of the currently-viewed xmonad workspace, read live via
   `wm current`, or nil if `wm` is unavailable.

   Read dynamically (per operation), NOT cached: the chrome-mcp guard runs in a
   single long-lived server that serves many sessions from different
   workspaces, so a value captured once at boot would freeze every later
   session onto the boot workspace. Names address any workspace — numbered
   (`1`…`9`) and named (`agents-chrome-mcp`, `paint`, …) alike — and stay valid
   even as workspaces are added/removed."
  []
  (-> (run ["current"]) (.then (fn [s] (some-> s not-empty)))))

(defn chrome-windows
  "→ Promise<[{:wid :workspace :desktop :title …}]> for every MCP-Chrome X11
   window across all workspaces. `:workspace` is the workspace *name* (what we
   key on); `:desktop` is its current index (unstable). `:title` is the
   active-tab title (`… - Google Chrome`)."
  []
  (-> (run ["windows" "--all" "--class" (wm-class) "--json"])
      (.then (fn [s]
               (if (str/blank? s)
                 []
                 (try (js->clj (js/JSON.parse s) :keywordize-keys true)
                      (catch :default _ [])))))))

(defn chrome-window-ids
  "→ Promise<#{wid}> — the set of MCP-Chrome X11 window ids (for before/after
   diffing around window creation)."
  []
  (-> (chrome-windows) (.then (fn [ws] (into #{} (map :wid) ws)))))

(defn move-window
  "Send X11 window `wid` to workspace *name* `workspace`. Resolves to bool.
   Uses `--to` (name-first resolution) so it targets the stable workspace name,
   not a shifting index."
  [wid workspace]
  (-> (run ["move" "--window" (str wid) "--to" (str workspace)])
      (.then some?)))

;; ── pid → workspace (for scoping to the session's TUI terminal) ──────────────

(defn- ppid
  "Parent pid of `pid` via /proc/<pid>/stat, or nil. The comm field can contain
   spaces/parens, so parse after the last ')': fields are then state, ppid, …."
  [pid]
  (try
    (let [stat  (.readFileSync fs (str "/proc/" pid "/stat") "utf8")
          close (.lastIndexOf stat ")")
          tail  (subs stat (+ close 2))
          flds  (str/split (str/trim tail) #"\s+")
          pp    (js/parseInt (nth flds 1) 10)]
      (when (pos? pp) pp))
    (catch :default _ nil)))

(defn- ancestry
  "`pid` and its process ancestors (up to `init`/limit), nearest first. The TUI
   client (bun) is a descendant of its terminal emulator, so its terminal's X11
   window is owned by one of these ancestor pids."
  [pid]
  (loop [p pid acc [] n 0]
    (if (or (nil? p) (<= p 1) (>= n 16))
      acc
      (recur (ppid p) (conj acc p) (inc n)))))

(defn- wmctrl-pid->wid
  "→ Promise<{pid wid}> from `wmctrl -lp` (`<wid> <desktop> <pid> <host>
   <title>`). wmctrl is the only tool here that exposes _NET_WM_PID, which is
   how a process is tied to its X11 window."
  []
  (-> (run-bin (wmctrl-bin) ["-lp"])
      (.then (fn [out]
               (reduce (fn [m line]
                         (let [parts (str/split (str/trim line) #"\s+")]
                           (if (>= (count parts) 3)
                             (let [pid (js/parseInt (nth parts 2) 10)]
                               (cond-> m (and (not (js/isNaN pid)) (not (contains? m pid)))
                                       (assoc pid (nth parts 0))))
                             m)))
                       {} (str/split-lines (or out "")))))
      (.catch (fn [_] {}))))

(defn- all-windows
  "→ Promise<[{:wid :workspace …}]> for *every* X11 window (no class filter) —
   needed to look up a terminal window's workspace name by its id."
  []
  (-> (run ["windows" "--all" "--json"])
      (.then (fn [s]
               (if (str/blank? s)
                 []
                 (try (js->clj (js/JSON.parse s) :keywordize-keys true)
                      (catch :default _ [])))))))

(defn workspace-for-pid
  "→ Promise<workspace-name|nil>: the xmonad workspace *name* of the terminal
   window owning `pid` (walk the process ancestry to the emulator's X11 window,
   then map that window id to its workspace name). nil when it can't be placed
   (e.g. a non-terminal client) — the caller then leaves the call unscoped."
  [pid]
  (let [line (ancestry pid)]
    (-> (js/Promise.all #js [(wmctrl-pid->wid) (all-windows)])
        (.then (fn [arr]
                 (let [[pid->wid wins] (vec arr)
                       wid  (some pid->wid line)
                       widl (some-> wid str/lower-case)]
                   (when widl
                     (some (fn [w]
                             (when (= (some-> (:wid w) str/lower-case) widl)
                               (:workspace w)))
                           wins)))))
        (.catch (fn [_] nil)))))
