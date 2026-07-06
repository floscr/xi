(ns xi.ext.diff.difft
  "Difftastic rendering config for the diff extension.

   A /diff run selects a renderer (:git → native unified diff, :difft →
   difftastic structural output) and, for difft, a wrap width. Both are held
   in dynamic vars bound for the duration of a :diff/load effect and read by
   the git plumbing (xi.ext.diff.git) so a single git invocation can be routed
   through difftastic via GIT_EXTERNAL_DIFF.")

(def ^:dynamic *diff-engine*
  "The diff renderer for the current :diff/load run. :git → native unified
   diff (interactive viewer); :difft → difftastic structural output (ANSI).
   Bound for the whole effect body; read only by the git-diff helpers."
  :git)

(def ^:dynamic *diff-width*
  "Column width difftastic wraps at, requested by the client whose viewport
   the diff is for (the web client measures it from its browser width). nil →
   let difftastic pick (no tty headless → its 80-col default)."
  nil)

(defn difft-env
  "process.env extended so `git diff` routes through difftastic with color.
   Syntax highlighting is off so the only colors are the diff signal itself:
   red for removed, green for added — not a syntax rainbow. DFT_WIDTH carries
   the requesting client's column width so the output fills its viewport."
  []
  (js/Object.assign #js {} js/process.env
                     #js {"GIT_EXTERNAL_DIFF" "difft"
                          "DFT_COLOR" "always"
                          "DFT_SYNTAX_HIGHLIGHT" "off"}
                     (if *diff-width*
                       #js {"DFT_WIDTH" (str *diff-width*)}
                       #js {})))
