(ns xi.ext.design-mode
  "Persistent browser design mode → background sub-agents.

   `/design` (or Ctrl+Shift+D) injects a resident design-mode script into the
   MCP-controlled browser page. Unlike `/pick` (one-shot, submits a blocking
   prompt), design mode stays on: the user presses Ctrl+I or Ctrl+B *in the browser* to
   pick an element, types an instruction in an anchored popover, and each
   request spawns a background sub-agent (`:subagent/spawn` — the same
   machinery /review uses) with the element's HTML, selector, computed styles,
   and a page screenshot. The parent session is never blocked; progress shows
   in the Sub-agents panel.

   Surviving navigation: a watcher loop polls the page (evaluate_script) every
   ~700ms. The injected script sets `window.__xiDesignActive`; when that flag
   disappears (the page navigated or reloaded), the watcher simply re-injects —
   so the mode follows the user across pages with no chrome-devtools-mcp
   changes. The same poll drains `window.__xiDesignQueue`, the array the
   browser script pushes requests onto.

     /design        toggle design mode on the selected page
     /design off    turn it off

   This namespace is not a standalone extension; it's installed into
   xi.ext.chrome-mcp (which owns the shared MCP client) via `install`, exactly
   like xi.ext.element-picker."
  (:require [clojure.string :as str]
            [xi.agent :as agent]
            [xi.ext.chrome-mcp.scope :as scope]
            [xi.ext.subagent.handlers :as sah]
            [xi.image :as image])
  (:require-macros [xi.ext.design-mode-js :refer [inline-design-js]]))

;; ── Config ───────────────────────────────────────────────────────────────────

(def ^:private poll-interval-ms 700)

(def ^:private design-js (inline-design-js))

(def ^:private colors
  "Claude-flavored palette for the injected UI: warm ivory surfaces, coral
   accent, soft borders."
  {:surface      "#FAF9F5"
   :surfaceMuted "#F0EEE6"
   :text         "#1F1E1D"
   :textMuted    "#6E6B64"
   :textFaint    "#9C9A93"
   :border       "#E8E5DE"
   :accent       "#D97757"
   :accentBg     "rgba(217, 119, 87, 0.10)"})

(def ^:private cleanup-js
  (str "(function() {"
       "var ids = ['__xi-design-pill','__xi-design-overlay','__xi-design-hl',"
       "'__xi-design-tip','__xi-design-pop','__xi-design-toast','__xi-design-dock',"
       "'__xi-design-agents-btn','__xi-design-agents-pop','__xi-design-style'];"
       "for (var i = 0; i < ids.length; i++) { var el = document.getElementById(ids[i]); if (el) el.remove(); }"
       "delete window.__xiDesignActive; delete window.__xiDesignQueue; delete window.__XI_DESIGN_CFG__;"
       "delete window.__xiDesignCommitQueue; delete window.__xiDesignAgents; delete window.__xiDesignRender;"
       "})();"))

;; ── JS run in the page (via chrome-devtools-mcp evaluate_script) ─────────────

(defn- config-json []
  (js/JSON.stringify (clj->js {:maxHTML 15000
                               :colors colors})))

(defn- injection-fn
  "A JS arrow-function declaration for evaluate_script: clear any stale design
   UI, set the config, install the resident design script, and return true."
  []
  (str "() => {\n"
       cleanup-js "\n"
       "window.__XI_DESIGN_CFG__ = " (config-json) ";\n"
       design-js "\n"
       "return true;\n}"))

(defn- poll-fn
  "One round-trip: push the current design-agent list into the page (merging
   any client-side optimistic commit flag the watcher hasn't observed yet),
   re-render the dock, report whether the script is still installed (page not
   navigated), and splice-drain both the request and commit queues atomically.
   `agents-json` is a JSON array literal of {id label status commit}."
  [agents-json]
  (str "() => {"
       " var incoming = " agents-json ";"
       " var prev = window.__xiDesignAgents || [];"
       " var byId = {}; prev.forEach(function(a){ byId[a.id] = a; });"
       " incoming.forEach(function(a){ var p = byId[a.id]; if (p && !a.commit && p.commit) { a.commit = p.commit; } });"
       " window.__xiDesignAgents = incoming;"
       " if (window.__xiDesignRender) window.__xiDesignRender();"
       " var q = (window.__xiDesignQueue || []).splice(0);"
       " var c = (window.__xiDesignCommitQueue || []).splice(0);"
       " return { active: !!window.__xiDesignActive, q: q, c: c }; }"))

(def ^:private cleanup-fn
  (str "() => { " cleanup-js " return true; }"))

;; ── Parsing chrome-devtools-mcp tool results ─────────────────────────────────

(defn- result-text [result]
  (->> (:content result)
       (filter #(= "text" (:type %)))
       (map :text)
       (str/join "\n")))

(defn- image-data [result]
  (some #(when (= "image" (:type %)) (:data %)) (:content result)))

(defn- fenced-body
  "The body of the last ```…``` fenced block in `text`, else the whole text.
   evaluate_script wraps its JSON return value in a fenced block."
  [text]
  (let [blocks (re-seq #"(?s)```(?:json)?\n?(.*?)```" text)]
    (if (seq blocks)
      (second (last blocks))
      text)))

(defn- parse-eval-return [result]
  (let [body (str/trim (fenced-body (result-text result)))]
    (try (js->clj (js/JSON.parse body) :keywordize-keys true)
         (catch :default _ nil))))

;; ── Sub-agent prompt building (pure) ─────────────────────────────────────────

(defn request-label
  "Short human label for a queued request — the instruction's first line
   (truncated), falling back to the element selector."
  [{:keys [message selector]}]
  (let [t (some-> message str/trim not-empty str/split-lines first)
        t (or t selector "element")]
    (str "Design: " (if (> (count t) 44) (str (subs t 0 44) "…") t))))

(defn build-prompt
  "The sub-agent's prompt for one design request: the user's instruction plus
   the picked element's full context, and guidance to change *source* (not the
   live page) and stay out of the browser."
  [{:keys [url selector outerHTML computedStyles boundingRect message]} shot-path]
  (str "Design-mode request: the user picked an element on the live page in "
       "their browser and asked for a change.\n\n"
       "User request: "
       (if (str/blank? message)
         "(no text given — infer a tasteful improvement for this element)"
         message)
       "\nPage URL: " url "\n\n"
       "## Picked element\n"
       "Selector: `" selector "`\n\n"
       "```html\n" outerHTML "\n```\n\n"
       "Computed styles: " (js/JSON.stringify (clj->js computedStyles)) "\n"
       (when boundingRect
         (str "Bounding rect (page coords): "
              (js/JSON.stringify (clj->js boundingRect)) "\n"))
       (when shot-path
         (str "Screenshot of the page at pick time: " shot-path
              " (view it if visual context helps)\n"))
       "\n## How to work\n"
       "- Locate this element's source in the current project — search for "
       "distinctive class names, ids, or text content from the HTML above.\n"
       "- Make the requested change in the source files, matching the "
       "project's styling conventions.\n"
       "- Do NOT drive, navigate, or reload the browser — the user is actively "
       "using it, and their dev server hot-reloads the page.\n"
       "- Finish with one concise line: what changed, in which file(s)."))

(defn commit-prompt
  "The follow-up commit sub-agent's prompt for one completed design change.
   Carries the original agent's result summary so a fresh context can find and
   commit exactly those changes."
  [{:keys [label result]}]
  (str "A design change was just made in this project by another agent. Its "
       "summary:\n\n"
       (if (str/blank? result)
         (str "(no summary given \u2014 inspect `git status` / `git diff` to see what "
              "changed for: " (or label "the design request") ")")
         result)
       "\n\n## Task\n"
       "Commit these changes to git:\n"
       "- Review the uncommitted changes (`git status`, `git diff`).\n"
       "- Stage the file(s) for THIS change and create ONE commit with a concise "
       "Conventional-Commit message describing it.\n"
       "- Do NOT commit unrelated changes, and do NOT push.\n"
       "- Finish with one line: the commit hash and subject."))

(defn page-agents
  "The tracked design sub-agents as a compact list for the page:
   {id label status commit}. `status` is the sub-agent's status name;
   `commit` reflects the follow-up commit agent (nil | \"committing\" |
   \"committed\" | \"error\"). `tracked` is the watcher's ordered
   [{:sub-id :commit-sub-id}] list."
  [state room-id tracked]
  (let [all (into {} (map (juxt :id identity)) (sah/agents state room-id))]
    (mapv (fn [{:keys [sub-id commit-sub-id]}]
            (let [a (get all sub-id)
                  c (get all commit-sub-id)]
              {:id     sub-id
               :label  (or (:label a) "agent")
               :status (name (or (:status a) :running))
               :commit (cond
                         (nil? commit-sub-id)   nil
                         (= (:status c) :done)  "committed"
                         (= (:status c) :error) "error"
                         (:errored? c)          "error"
                         :else                  "committing")}))
          tracked)))

;; ── Feedback ─────────────────────────────────────────────────────────────────

(defn- status! [dispatch! room-id text]
  (dispatch! {:type :ui/status :room-id room-id :text text}))

;; ── Watcher loop ─────────────────────────────────────────────────────────────

(defn- capture-screenshot
  "One viewport PNG of the page, as base64, or nil. Best-effort."
  [call page-id]
  (-> (call "take_screenshot" {:format "png" :pageId page-id})
      (.then (fn [res] (when-not (:is-error res) (image-data res))))
      (.catch (fn [_] nil))))

(defn- inject! [call page-id]
  (-> (call "evaluate_script" {:function (injection-fn) :pageId page-id})
      (.then (fn [res]
               (when (:is-error res)
                 (throw (js/Error. (str "inject failed — " (result-text res)))))))))

(defn- cleanup! [call page-id]
  (when page-id
    (-> (call "evaluate_script" {:function cleanup-fn :pageId page-id})
        (.catch (fn [_] nil)))))

(defn- handle-request!
  "One drained queue entry → screenshot → spawn a background sub-agent.
   User-initiated (they typed the request in the browser), so this dispatches
   :subagent/spawn directly — no confirmation gate, same as /review. The
   spawned sub-id is tracked in `watch*` so it surfaces in the browser dock's
   agent list (and can later be committed)."
  [{:keys [call dispatch! watch*]} {:keys [page-id room-id]} req]
  (-> (capture-screenshot call page-id)
      (.then (fn [shot]
               (let [path   (when shot
                              (image/persist-image! {:data shot :media-type "image/png"}))
                     label  (request-label req)
                     sub-id (sah/gen-id "design")]
                 (swap! watch* update :agents (fnil conj [])
                        {:sub-id sub-id :commit-sub-id nil})
                 (dispatch! {:type   :subagent/spawn
                             :room-id room-id
                             :sub-id sub-id
                             :label  label
                             :task   (str "Design change on " (:selector req))
                             :prompt (build-prompt req path)})
                 (status! dispatch! room-id (str "✦ " label " — sub-agent spawned.")))))))

(defn- handle-commit!
  "One drained commit request ({:id <design sub-id>}) → spawn a follow-up
   sub-agent that commits the completed design change. No-op unless the target
   agent is done and hasn't already been committed; the commit agent's id is
   recorded so the dock can show its committing → committed progress."
  [{:keys [dispatch! get-state watch*]} {:keys [room-id]} {:keys [id]}]
  (let [state (get-state)
        agent (sah/find-child state room-id id)
        tracked (some #(when (= (:sub-id %) id) %) (:agents @watch*))]
    (when (and agent (= (:status agent) :done)
               tracked (not (:commit-sub-id tracked)))
      (let [commit-sub-id (sah/gen-id "commit")]
        (swap! watch* update :agents
               (fn [as] (mapv (fn [a] (if (= (:sub-id a) id)
                                        (assoc a :commit-sub-id commit-sub-id) a))
                              as)))
        (dispatch! {:type   :subagent/spawn
                    :room-id room-id
                    :sub-id commit-sub-id
                    :label  (str "Commit: " (or (:label agent) id))
                    :task   (str "Commit design change " id)
                    :prompt (commit-prompt {:label (:label agent) :result (:result agent)})})
        (status! dispatch! room-id (str "✦ Committing " (or (:label agent) id) "…"))))))

(defn- tick!
  "One watcher beat: poll the page. Re-inject when the resident script is gone
   (navigation/reload — this is what makes the mode persistent), drain the
   request queue, then schedule the next beat. Transient errors (mid-navigation
   evals) are skipped and retried on the next tick."
  [{:keys [call watch* get-state room-id] :as ctx}]
  (let [{:keys [running? page-id] :as w} @watch*]
    (when running?
      (let [agents-json (js/JSON.stringify
                         (clj->js (page-agents (get-state) room-id (:agents w))))]
        (-> (call "evaluate_script" {:function (poll-fn agents-json) :pageId page-id})
            (.then (fn [res]
                     (when-not (:is-error res)
                       (let [{:keys [active q c]} (parse-eval-return res)]
                         (doseq [req q] (handle-request! ctx w req))
                         (doseq [cm c] (handle-commit! ctx w cm))
                         (when-not active
                           (inject! call page-id))))))
            (.catch (fn [_] nil))
            (.then (fn [_]
                     (when (:running? @watch*)
                       (js/setTimeout #(tick! ctx) poll-interval-ms)))))))))

;; ── Start / stop ─────────────────────────────────────────────────────────────

(defn- page-options [pages]
  (mapv (fn [{:keys [id title url selected?]}]
          {:label (str (or (not-empty title) url)
                       (when (and (not-empty title) url) (str " — " url))
                       (when selected? "  [current]"))
           :value id})
        pages))

(defn- choose-page!
  "With multiple tabs open, ask which one design mode should live on and switch
   to it. Resolves to :cancelled on dismiss, else the chosen page id."
  [ask! fx-ctx call room-id pages]
  (if (and ask! (> (count pages) 1))
    (-> (ask! fx-ctx
              {:room-id room-id
               :dialog {:type :select
                        :message "Which browser tab should design mode run on?"
                        :options (page-options pages)}})
        (.then (fn [page-id]
                 (if (nil? page-id)
                   :cancelled
                   (-> (call "select_page" {:pageId page-id})
                       (.then (constantly page-id)))))))
    (js/Promise.resolve (or (some #(when (:selected? %) (:id %)) pages)
                            (:id (first pages))))))

(defn- stop!
  [{:keys [call dispatch! watch*]} & [{:keys [silent?]}]]
  (let [{:keys [running? page-id room-id] stored-call :call} @watch*]
    (swap! watch* assoc :running? false :page-id nil :room-id nil :call nil :agents [])
    ;; Prefer the call captured at start — it's scoped to the workspace design
    ;; mode actually runs on, even when /design off comes from another room.
    (when page-id (cleanup! (or stored-call call) page-id))
    (when (and running? (not silent?) dispatch! room-id)
      (status! dispatch! room-id "✦ Design mode off."))
    running?))

(defn- start!
  [{:keys [dispatch! get-state room-id call ask! watch*] :as ctx}]
  (status! dispatch! room-id "✦ Design mode: connecting to browser…")
  (-> (call "list_pages" {})
      (.then (fn [res]
               (when (:is-error res)
                 (throw (js/Error. (str "no browser page — " (result-text res)))))
               (choose-page! ask! {:dispatch! dispatch! :state (get-state)}
                             call room-id (scope/parse-pages (result-text res)))))
      (.then (fn [choice]
               (if (= choice :cancelled)
                 (status! dispatch! room-id "Design mode cancelled.")
                 (do (swap! watch* assoc :running? true :page-id choice :room-id room-id :call call :agents [])
                     (-> (inject! call choice)
                         (.then (fn [_]
                                  (status! dispatch! room-id
                                           (str "✦ Design mode on — press Ctrl+I or Ctrl+B in the "
                                                "browser to pick an element. Each request runs in a "
                                                "background sub-agent; /design turns it off."))
                                  (js/setTimeout #(tick! ctx) poll-interval-ms))))))))
      (.catch (fn [e]
                (swap! watch* assoc :running? false :page-id nil :room-id nil)
                (status! dispatch! room-id (str "Design mode error: " (.-message e)))))))

;; ── Install (into xi.ext.chrome-mcp, which owns the shared MCP client) ───────

(defn install
  "Return {:commands :keybindings :fx :shutdown!} for design mode, wired to
   `call` — the shared chrome-devtools-mcp caller. `ask!` powers the multi-tab
   chooser; absent (headless, no dialogs) it falls back to the selected page.
   `:shutdown!` stops the watcher loop (chrome-mcp calls it from :on-shutdown)."
  [call & [ask!]]
  (let [watch* (atom {:running? false :page-id nil :room-id nil :agents []})]
    {:commands
     [{:name        "design"
       :description "Toggle browser design mode — pick elements with Ctrl+I/Ctrl+B, changes run in sub-agents"
       :handler     (fn [_st {:keys [room-id args]}]
                      {:effects [[:ext.design-mode/toggle
                                  {:room-id room-id :args (or args "")}]]})
       :subcommands [{:name "off" :description "Turn design mode off"}]}]
     :keybindings
     [{:key   "ctrl+shift+d"
       :event {:type :command/run :name "design"}}]
     :fx
     {:ext.design-mode/toggle
      (fn [{:keys [dispatch! get-state]} {:keys [room-id args]}]
        (let [;; Scope browser calls to this room's driving client, so the
              ;; chrome guard anchors to *its* terminal workspace — not the
              ;; server's last-resolved workspace (see xi.ext.chrome-mcp.guard).
              pid    (agent/room-client-pid (get-state) room-id)
              scoped (if pid
                       (fn [tool targs] (call tool targs {:client-pid pid}))
                       call)
              ctx {:dispatch! dispatch! :get-state get-state :room-id room-id
                   :call scoped :ask! ask! :watch* watch*}
              off? (contains? #{"off" "stop"} (str/trim (str args)))]
          (cond
            (:running? @watch*) (stop! ctx)
            off?                (status! dispatch! room-id "Design mode is not on.")
            :else               (start! ctx))))}
     :shutdown!
     (fn [] (swap! watch* assoc :running? false))}))
