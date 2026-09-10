(ns xi.config
  "Root config — declares which extensions load on each surface.

   One file for every build: the node builds read it with the :node
   reader feature, the browser build with :browser (set per build via
   :compiler-options {:reader-features …} in shadow-cljs.edn), so each
   target only requires + compiles its own extensions.

   Each entry is either an extension map (used as-is) or a factory fn
   `(fn [ctx] → ext|nil)` — instantiated by ext/instantiate at assembly
   time with a per-surface ctx (server gets {:ring … :ask! …}). Order
   matters: ext/compose chains handlers/gates in list order.

   Eventually these entries move out of the build and get loaded at
   runtime via SCI."
  (:require
   #?@(:node
       [[xi.ext.browser-open :as browser-open]
        [xi.ext.canvas-review :as canvas-review]
        [xi.ext.chrome-mcp :as chrome]
        [xi.ext.clipboard-image :as clipboard-image]
        [xi.ext.clj :as clj-tool]
        [xi.ext.clj-surgeon :as clj-surgeon]
        [xi.ext.commit :as commit]
        [xi.ext.dictation :as dictation]
        [xi.ext.diff.core :as diff]
        [xi.ext.done-notify :as done-notify]
        [xi.ext.events :as events]
        [xi.ext.extensions :as extensions]
        [xi.ext.file-finder :as file-finder]
        [xi.ext.file-view.core :as file-view]
        [xi.ext.github :as github]
        [xi.ext.github-code-search.core :as github-code-search]
        [xi.ext.gtd :as gtd]
        [xi.ext.image-graph :as image-graph]
        [xi.ext.kb :as kb]
        [xi.ext.mcp :as mcp]
        [xi.ext.permission-gate :as permission-gate]
        [xi.ext.perplexity :as perplexity]
        [xi.ext.plan-mode :as plan-mode]
        [xi.ext.process-manager :as process-manager]
        [xi.ext.product-search.core :as product-search]
        [xi.ext.projects :as projects]
        [xi.ext.pushover :as pushover]
        [xi.ext.render :as render]
        [xi.ext.resume :as resume]
        [xi.ext.review :as review]
        [xi.ext.sandbox :as ext-sandbox]
        [xi.ext.session-search :as session-search]
        [xi.ext.skills :as skills]
        [xi.ext.snippets :as snippets]
        [xi.ext.subagent :as subagent]
        [xi.ext.terminal-title :as terminal-title]
        [xi.ext.tmp-cleanup-intercept :as tmp-cleanup-intercept]
        [xi.ext.todo-intercept :as todo-intercept]
        [xi.ext.treesitter.core :as treesitter]
        [xi.ext.web :as web]
        [xi.ext.worktree.core :as worktree]]
       :browser
       [[xi.ext.canvas-review.web :as canvas-review-web]
        [xi.ext.diff.web :as diff-web]
        [xi.ext.file-view.web :as file-view-web]
        [xi.ext.github.web :as github-web]
        [xi.ext.gtd.web :as gtd-web]
        [xi.ext.image-graph.web :as image-graph-web]
        [xi.ext.subagent.web :as subagent-web]])))

(def tui
  "TUI display config overrides. Only keys the user wants to change from
   their default belong here — each option's default lives in its consuming
   namespace and is read via `tui-opt`. Available options (all optional):

     :truncate-output-block-after-n-lines
       Max tool-output lines shown in a tool block before the rest is
       collapsed into a \"... (N more lines)\" marker.
       Default 100 (xi.client.view).

     :pager-cursor-scroll-off
       Lines kept between the line-wise cursor and the top/bottom edge of a
       pager/diff viewport while moving with j/k (clamped to half the viewport
       height on short terminals).
       Default 4 (xi.tui.pager)."
  {})

(defn tui-opt
  "Read TUI config option `k` from `tui`, falling back to `default` when the
   key is not defined in the config. See the `deftui-opt` macro in
   xi.config-macros for the usual call site."
  [k default]
  (get tui k default))

(def quick-replies?
  "When true, run a cheap model over each finished assistant turn to detect a
   decision point (yes/no, pick-one) and show one-tap quick-reply chips below
   the response (xi.quick-replies). The response text is never modified — chips
   are additive UI. Default true. A cheap regex gate runs first, so most turns
   never call the model."
  false)

#?(:node
   (def server
     "Extensions whose state + provider/tool hooks run server-side (server,
      standalone, and mirrored into clients)."
     [plan-mode/extension
      done-notify/extension
      pushover/create
      diff/extension
      file-view/extension
      file-finder/extension
      worktree/create
      kb/extension
      session-search/extension
      web/extension
      perplexity/extension
      product-search/extension
      commit/extension
      resume/create
      review/extension
      canvas-review/extension
      browser-open/extension
      chrome/create
      clj-surgeon/extension
      github/extension
      github-code-search/extension
      gtd/extension
      image-graph/extension
      permission-gate/extension
      ;; clj (sandboxed SCI scripting tool) after the policy gates so its
      ;; tool calls still pass plan-mode/permission-gate first
      clj-tool/extension
      todo-intercept/extension
      treesitter/create
      tmp-cleanup-intercept/extension
      terminal-title/extension
      clipboard-image/extension
      extensions/create
      mcp/create
      render/create
      ;; sandbox after the policy gates (plan-mode, permission-gate) so
      ;; blocks/confirms run first, but BEFORE process-manager: its gate
      ;; executes bash itself, and backgrounded commands must not reach
      ;; process-manager's unsandboxed spawn while the sandbox is on
      ext-sandbox/extension
      process-manager/extension
      subagent/extension
      projects/extension
      skills/extension
      snippets/extension
      events/create]))

#?(:node
   (def client
     "Process-local extensions that run in the TUI client process."
     [dictation/create]))

#?(:browser
   (def web
     "Browser-safe extension web halves, composed by xi.web.core."
     [diff-web/extension
      file-view-web/extension
      gtd-web/extension
      github-web/extension
      canvas-review-web/extension
      image-graph-web/extension
      subagent-web/extension]))
