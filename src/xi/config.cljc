(ns xi.config
  "Root config — declares which extensions and providers load on each
   surface.

   One file for every build: the node builds read it with the :node
   reader feature, the browser build with :browser (set per build via
   :compiler-options {:reader-features …} in shadow-cljs.edn), so each
   target only requires + compiles its own extensions.

   Each entry is either an extension map (used as-is) or a factory fn
   `(fn [ctx] → ext|nil)` — instantiated by ext/instantiate at assembly
   time with a per-surface ctx (server gets {:ring … :ask! …}). Order
   matters: ext/compose chains handlers/gates in list order.

   These built-in entries are compiled in. Extensions that are personal
   or optional load at runtime instead, as sandboxed user extensions
   (see docs/guide/extensions.md)."
  (:require
   #?@(:node
       [[xi.ext.canvas-review :as canvas-review]
        [xi.ext.clipboard-image :as clipboard-image]
        [xi.ext.clj :as clj-tool]
        [xi.ext.clj-surgeon :as clj-surgeon]
        [xi.ext.commit :as commit]
        [xi.ext.diff.core :as diff]
        [xi.ext.events :as events]
        [xi.ext.extensions :as extensions]
        [xi.ext.file-finder :as file-finder]
        [xi.ext.file-view.core :as file-view]
        [xi.ext.mcp :as mcp]
        [xi.ext.plan-mode :as plan-mode]
        [xi.ext.process-manager :as process-manager]
        [xi.ext.projects :as projects]
        [xi.ext.resume :as resume]
        [xi.ext.rules :as rules]
        [xi.ext.session-search :as session-search]
        [xi.ext.clojure-skills :as skills]
        [xi.ext.snippets :as snippets]
        [xi.ext.subagent :as subagent]
        [xi.ext.terminal-title :as terminal-title]
        [xi.ext.user :as user-ext]
        [xi.ext.treesitter.core :as treesitter]
        [xi.ext.worktree.core :as worktree]
        [xi.providers.anthropic :as anthropic]
        [xi.providers.ollama :as ollama]
        [xi.providers.openai.codex :as openai-codex]
        [xi.providers.zen :as zen]]
       :browser
       [[xi.ext.canvas-review.web :as canvas-review-web]
        [xi.ext.diff.web :as diff-web]
        [xi.ext.file-view.web :as file-view-web]
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

(def appearance
  "Web client appearance overrides — how the chat timeline renders its
   collapsible blocks. Same shape as `tui`: only keys you want to change from
   their default belong here; the defaults live in xi.web.appearance and a
   browser's own settings (Appearance dialog, persisted per device) override
   both. Available options (all optional):

     :viewer-mode?
       Fold each run of consecutive tool / thinking posts into one grouped
       box of header rows.
       Default true.

     :super-collapsed?
       Fold every viewer group whose blocks are all collapsed into a single
       summary row (step count + the latest block); click it to reveal the
       header rows. Has no effect without :viewer-mode?, and a group holding an
       open block (:tool-blocks / :thinking-blocks :open) stays unfolded.
       Default false.

     :tool-blocks
       :open | :collapsed — whether a tool call's details (arguments, result)
       start expanded. A tool awaiting an Allow/Deny answer is always open.
       Default :collapsed.

     :thinking-blocks
       :open | :collapsed — whether thinking blocks start expanded.
       Default :collapsed."
  {})

(def quick-replies?
  "When true, run a cheap model over each finished assistant turn to detect a
   decision point (yes/no, pick-one) and show one-tap quick-reply chips below
   the response (xi.quick-replies). The response text is never modified — chips
   are additive UI. Currently disabled. A cheap regex gate runs first, so most
   turns never call the model."
  false)

(def recommend-rule?
  "When true, every :ask rule's confirm dialog carries a \"Recommend a rule\"
   option that spawns a sub-agent to draft a rule for the guarded call and opens
   an editable save dialog with its answer (xi.ext.rules). Threaded into the
   rules engine as the tool-policy ctx key :recommend-rule? by xi.cli — the
   extension can't require xi.config (config requires it). Default false."
  false)

#?(:node
   (def server
     "Extensions whose state + provider/tool hooks run server-side (server,
      standalone, and mirrored into clients)."
     [;; The rules engine's extension half (rule state, /rules). Deciding tool
      ;; calls is core, not an extension surface — xi.cli wires
      ;; xi.ext.rules/tool-policy in front of every tool call.
      rules/create
      plan-mode/extension
      diff/extension
      file-view/extension
      file-finder/extension
      worktree/create
      session-search/extension
      commit/extension
      resume/create
      canvas-review/extension
      clj-surgeon/extension
      ;; clj (sandboxed SCI scripting tool)
      clj-tool/extension
      treesitter/create
      terminal-title/extension
      clipboard-image/extension
      extensions/create
      ;; hands user extensions' web halves (~/.config/xi/extensions/<name>/web.cljs)
      ;; to browsers; the user extensions themselves load after the built-ins
      ;; (xi.ext.user/install!, called in xi.cli next to mcp/install!)
      user-ext/server-extension
      mcp/create
      ;; registry-only: tracks processes spawned by clj's `process` namespace
      ;; (/ps, /kill, room keep-alive); spawning is gated in clj-tool
      process-manager/extension
      subagent/extension
      projects/extension
      skills/create
      snippets/extension
      events/create]))

#?(:node
   (def providers
     "Providers available on the node surfaces, in model-picker order. Each
      entry is a provider map (:id, :start-turn!, optional :list-models!);
      xi.cli derives the id → provider lookup from this vector. Routing by
      model name stays in xi.util/provider-for-model (shared with the
      browser build)."
     [anthropic/provider
      ollama/provider
      openai-codex/provider
      zen/provider]))

#?(:node
   (def client
     "Process-local extensions that run in the TUI client process."
     []))

#?(:browser
   (def web
     "Browser-safe extension web halves, composed by xi.web.core."
     [diff-web/extension
      file-view-web/extension
      canvas-review-web/extension
      subagent-web/extension]))
