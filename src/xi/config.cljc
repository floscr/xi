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
       [[xi.ext.amazon :as amazon]
        [xi.ext.browser-open :as browser-open]
        [xi.ext.chrome :as chrome]
        [xi.ext.clipboard-image :as clipboard-image]
        [xi.ext.clj-surgeon :as clj-surgeon]
        [xi.ext.commit :as commit]
        [xi.ext.dictation :as dictation]
        [xi.ext.diff.core :as diff]
        [xi.ext.done-notify :as done-notify]
        [xi.ext.events :as events]
        [xi.ext.extensions :as extensions]
        [xi.ext.github :as github]
        [xi.ext.github-code-search.core :as github-code-search]
        [xi.ext.gtd :as gtd]
        [xi.ext.kb :as kb]
        [xi.ext.mcp :as mcp]
        [xi.ext.permission-gate :as permission-gate]
        [xi.ext.perplexity :as perplexity]
        [xi.ext.plan-mode :as plan-mode]
        [xi.ext.process-manager :as process-manager]
        [xi.ext.projects :as projects]
        [xi.ext.pushover :as pushover]
        [xi.ext.review :as review]
        [xi.ext.sandbox :as ext-sandbox]
        [xi.ext.session-search :as session-search]
        [xi.ext.skills :as skills]
        [xi.ext.snippets :as snippets]
        [xi.ext.terminal-title :as terminal-title]
        [xi.ext.todo-intercept :as todo-intercept]
        [xi.ext.web :as web]
        [xi.ext.worktree.core :as worktree]]
       :browser
       [[xi.ext.diff.web :as diff-web]
        [xi.ext.github.web :as github-web]
        [xi.ext.gtd.web :as gtd-web]])))

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

#?(:node
   (def server
     "Extensions whose state + provider/tool hooks run server-side (server,
      standalone, and mirrored into clients)."
     [plan-mode/extension
      done-notify/extension
      pushover/create
      diff/extension
      worktree/create
      kb/extension
      session-search/extension
      web/extension
      perplexity/extension
      amazon/extension
      commit/extension
      review/extension
      browser-open/extension
      chrome/create
      clj-surgeon/extension
      github/extension
      github-code-search/extension
      gtd/extension
      permission-gate/extension
      todo-intercept/extension
      terminal-title/extension
      clipboard-image/extension
      extensions/create
      mcp/create
      ;; sandbox after the policy gates (plan-mode, permission-gate) so
      ;; blocks/confirms run first, but BEFORE process-manager: its gate
      ;; executes bash itself, and backgrounded commands must not reach
      ;; process-manager's unsandboxed spawn while the sandbox is on
      ext-sandbox/extension
      process-manager/extension
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
      gtd-web/extension
      github-web/extension]))
