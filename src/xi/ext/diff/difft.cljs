(ns xi.ext.diff.difft
  "Difftastic rendering config for the diff extension.

   A /diff run selects a renderer (:git → native unified diff, :difft →
   difftastic), for difft an output format (:ansi → its own structural text,
   wrapped at a width; :json → one record per file for xi.diff.difft, the web
   viewer's rows). They are held in dynamic vars bound for the duration of a
   :diff/load effect and read by the git plumbing (xi.ext.diff.git) so a
   single git invocation can be routed through difftastic via
   GIT_EXTERNAL_DIFF."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(def ^:dynamic *diff-engine*
  "The diff renderer for the current :diff/load run. :git → native unified
   diff (interactive viewer); :difft → difftastic.
   Bound for the whole effect body; read only by the git-diff helpers."
  :git)

(def ^:dynamic *diff-format*
  "difftastic's output for the current run: :ansi (the TUI's structural text)
   or :json (records for the web viewer, see json-hook)."
  :ansi)

(def ^:dynamic *diff-width*
  "Column width difftastic wraps at, requested by the client whose viewport
   the diff is for. nil → let difftastic pick (no tty headless → its 80-col
   default). :ansi only."
  nil)

;; git runs GIT_EXTERNAL_DIFF per file as `path old-file old-hex old-mode
;; new-file new-hex new-mode [new-path xfrm]` and deletes the temp files
;; after, so the hook reads both sides while it runs. Record layout:
;; xi.diff.difft/split-records.
(def ^:private json-hook-script
  (str "#!/bin/sh\n"
       "# xi diff viewer: one difftastic JSON record per file (xi.diff.difft)\n"
       "[ -n \"$5\" ] || exit 0\n"
       "printf '\\036%s\\037' \"${XI_DIFF_PREFIX}${8:-$1}\"\n"
       "DFT_UNSTABLE=yes difft --display json \"$2\" \"$5\" | tr -d '\\n'\n"
       "printf '\\037'\n"
       "base64 < \"$2\" | tr -d '\\n'\n"
       "printf '\\037'\n"
       "base64 < \"$5\" | tr -d '\\n'\n"
       "printf '\\n'\n"))

(defn- json-hook
  "Path of the hook script, written to the temp dir on first use (or when
   another version left a different one)."
  []
  (let [path (.join node-path (.tmpdir os) "xi-difft-json-hook.sh")]
    (when-not (= json-hook-script (try (fs/readFileSync path "utf-8") (catch :default _ nil)))
      (fs/writeFileSync path json-hook-script #js {:mode 0755})
      (fs/chmodSync path 0755))
    path))

(defn difft-env
  "process.env extended so `git diff` routes through difftastic. :ansi:
   colored, syntax highlighting off so the only colors are the diff signal
   (red removed, green added), DFT_WIDTH the requesting client's columns.
   :json: the json-hook, `prefix` (see xi.ext.diff.git/path-prefix) put in
   front of each record's path."
  ([] (difft-env nil))
  ([prefix]
   (if (= :json *diff-format*)
     (js/Object.assign #js {} js/process.env
                       #js {"GIT_EXTERNAL_DIFF" (json-hook)
                            "XI_DIFF_PREFIX"    (or prefix "")})
     (js/Object.assign #js {} js/process.env
                       #js {"GIT_EXTERNAL_DIFF" "difft"
                            "DFT_COLOR" "always"
                            "DFT_SYNTAX_HIGHLIGHT" "off"}
                       (if *diff-width*
                         #js {"DFT_WIDTH" (str *diff-width*)}
                         #js {})))))
