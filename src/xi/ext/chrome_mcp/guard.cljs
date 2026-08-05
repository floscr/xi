(ns xi.ext.chrome-mcp.guard
  "Workspace scoping for the chrome-devtools-mcp proxy.

   Wraps the raw MCP `forward` fn so an agent only ever acts on Chrome windows
   on the xmonad workspace the user is *currently viewing*, and creates new
   windows there. See docs/chrome-mcp.md for the model; xi.ext.chrome-mcp.scope for
   the pure classification.

   The target workspace is the workspace of **this session's TUI terminal**,
   resolved **live per operation** by *name* (never index — xmonad workspaces
   grow/shrink, so an index is unstable; only the name is a durable handle).

   The tool ctx carries `:client-pid` — the pid of the WS client (a TUI/CLI
   `xi` process) driving this room's turn. `wm/workspace-for-pid` walks that
   pid's process ancestry to its terminal-emulator X11 window and reads that
   window's workspace name. This is per-session and correct even when the user
   is *looking at* a different workspace — unlike the global `wm current`, which
   only reflects wherever the user's eyes are and was the 'opens on the wrong
   workspace' bug. When there is no `:client-pid` (e.g. a web client, which has
   no local terminal window) it falls back to `wm current` — best-effort for a
   single local user.

   Invariant: before any page-acting tool (click, navigate, screenshot, …) the
   mcp *selected page* is made a current-workspace page, so those tools are
   scoped automatically. The four page-management tools are gated:

     list_pages   → read-only; filtered to current-workspace pages (never
                    creates a window).
     select_page  → blocked when the target page is on another workspace.
     close_page   → blocked when the target page is on another workspace.
     new_page     → the brand-new window it spawns is moved onto the current
                    workspace (Chrome opens new windows on whatever workspace is
                    viewed, which is normally the same one — but this pins it).

   Self-heal: any *other* (page-acting) tool first reconciles — select an
   existing current-workspace page, or, if none, create a fresh window there.
   Self-created windows are tracked as {cdp-window-id → workspace-name}
   (`owned*`) so a titleless about:blank window is still placed on its own
   workspace, and the reconcile step recognizes it instead of spawning a second
   one every call.

   Fail-safe: whenever a page's workspace can't be determined, it is treated as
   NOT ours — the agent never touches what it can't place. If CDP/wm are
   unavailable, the destructive gates block and the rest passes through."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [xi.ext.chrome-mcp.scope :as scope]
            [xi.ext.chrome-mcp.cdp :as cdp]
            [xi.ext.chrome-mcp.wm :as wm]))

;; ── result helpers ───────────────────────────────────────────────────────────

(defn- result->text [result]
  (->> (:content result)
       (keep (fn [b] (when (= "text" (:type b)) (:text b))))
       (str/join "\n")))

(defn- with-text [result text]
  (assoc result :content [{:type "text" :text text}]))

(defn- blocked [msg]
  {:content [{:type "text" :text msg}] :is-error true})

(defn- sleep [ms] (js/Promise. (fn [r] (js/setTimeout r ms))))

;; ── install ──────────────────────────────────────────────────────────────────

(defn install
  "Return a scoped `forward` fn `(fn [tool args] | [tool args ctx])`, given the
   raw `forward` and the browser's remote-debugging URL (attach mode). Each call
   resolves this session's TUI-terminal workspace live from `(:client-pid ctx)`
   (falling back to `wm current`); when `wm` can't resolve one the raw `forward`
   runs unscoped."
  [forward browser-url]
  (let [cdp*   (atom nil)   ;; memoized Promise<cdp-client>
        owned* (atom {})]   ;; {cdp-window-id → workspace-name} of windows we created
    (letfn [(cdp-client []
              (or @cdp*
                  (reset! cdp* (-> (cdp/connect browser-url)
                                   (.catch (fn [e] (reset! cdp* nil) (throw e)))))))

            (classify [ws]
              ;; → Promise<{:result :text :scope}>
              (-> (js/Promise.all
                   #js [(forward "list_pages" {})
                        (-> (cdp-client) (.then cdp/page-windows) (.catch (fn [_] [])))
                        (wm/chrome-windows)])
                  (.then (fn [arr]
                           (let [[result cdp-targets wm-windows] (vec arr)
                                 text  (result->text result)
                                 pages (scope/parse-pages text)
                                 sc    (scope/classify {:pages pages
                                                        :cdp-targets cdp-targets
                                                        :wm-windows wm-windows
                                                        :launch-workspace ws
                                                        :owned-window-workspaces @owned*})]
                             {:result result :text text :scope sc})))))

            (wait-new-wid [before]
              ;; poll wmctrl until a Chrome window id not in `before` appears
              (letfn [(step [tries]
                        (-> (wm/chrome-window-ids)
                            (.then (fn [after]
                                     (let [new-wid (first (set/difference after before))]
                                       (cond
                                         new-wid (js/Promise.resolve new-wid)
                                         (pos? tries) (-> (sleep 150)
                                                          (.then (fn [_] (step (dec tries)))))
                                         :else (js/Promise.resolve nil)))))))]
                (step 12)))

            (ensure-window! [ws]
              ;; open a new Chrome window, record it as owned on `ws`, move it
              ;; there, and select it in mcp
              (-> (wm/chrome-window-ids)
                  (.then (fn [before]
                           (-> (cdp-client)
                               (.then (fn [c]
                                        (-> (cdp/create-window c "about:blank")
                                            (.then (fn [tid] (cdp/window-for-target c tid)))
                                            (.then (fn [win-id]
                                                     (when win-id (swap! owned* assoc win-id ws)))))))
                               (.then (fn [_] (wait-new-wid before)))
                               (.then (fn [new-wid]
                                        (when new-wid (wm/move-window new-wid ws))))
                               (.then (fn [_] (sleep 150)))
                               (.then (fn [_] (select-workspace-page! ws))))))))

            (select-workspace-page! [ws]
              (-> (classify ws)
                  (.then (fn [{:keys [scope]}]
                           (when-let [pid (first (:in-workspace scope))]
                             (forward "select_page" {:pageId pid :bringToFront true}))))))

            (reconcile! [ws]
              ;; ensure the mcp selected page is a current-workspace page
              (-> (classify ws)
                  (.then (fn [{:keys [scope]}]
                           (cond
                             (:selected-in? scope) (js/Promise.resolve :ok)
                             (seq (:in-workspace scope))
                             (forward "select_page"
                                      {:pageId (first (:in-workspace scope)) :bringToFront true})
                             :else (ensure-window! ws))))
                  (.catch (fn [_] :ok))))

            ;; ── gates ──
            (gate-list-pages [ws]
              ;; read-only: classify + filter, never create
              (-> (classify ws)
                  (.then (fn [{:keys [result text scope]}]
                           (with-text result
                             (scope/filter-list-pages-text text (:in-workspace scope)))))
                  (.catch (fn [_] (forward "list_pages" {})))))

            (gate-page-op [tool args ws]
              (let [pid (:pageId args)]
                (-> (classify ws)
                    (.then (fn [{:keys [scope]}]
                             (if (contains? (:in-workspace scope) pid)
                               (forward tool args)
                               (blocked (str "Page " pid " is on another xmonad workspace. "
                                             "This agent only controls Chrome windows on the "
                                             "current workspace — run list_pages to see them.")))))
                    (.catch (fn [_]
                              (blocked (str "Could not verify which workspace page " pid
                                            " is on; refusing to touch it (fail-safe). "
                                            "Retry list_pages.")))))))

            (select-new-page! [ws url]
              ;; select the mcp page for the just-created window (prefer the one
              ;; whose url matches the request; else the first current-workspace
              ;; page) and return a current-workspace-filtered list_pages result.
              (-> (classify ws)
                  (.then (fn [{:keys [scope text]}]
                           (let [pages  (scope/parse-pages text)
                                 in     (:in-workspace scope)
                                 target (or (some (fn [p]
                                                    (when (and (contains? in (:id p))
                                                               (= (some-> (:url p) str/trim)
                                                                  (some-> url str/trim)))
                                                      (:id p)))
                                                  pages)
                                            (first in))]
                             (if target
                               (-> (forward "select_page" {:pageId target :bringToFront true})
                                   (.then (fn [_] (gate-list-pages ws))))
                               (gate-list-pages ws)))))))

            (gate-new-page [ws args]
              ;; chrome-devtools-mcp `new_page` only adds a *tab* to the focused
              ;; window, so the new page inherits that window's workspace and
              ;; can't be placed. Instead open a real new OS window via CDP
              ;; (Target.createTarget :newWindow), move it onto `ws`, record it
              ;; as owned, then select it — same primitive as ensure-window!.
              (let [url (or (:url args) "about:blank")]
                (-> (wm/chrome-window-ids)
                    (.then (fn [before]
                             (-> (cdp-client)
                                 (.then (fn [c]
                                          (-> (cdp/create-window c url)
                                              (.then (fn [tid] (cdp/window-for-target c tid))))))
                                 (.then (fn [win-id]
                                          (when win-id (swap! owned* assoc win-id ws))))
                                 (.then (fn [_] (wait-new-wid before)))
                                 (.then (fn [new-wid]
                                          (when new-wid (wm/move-window new-wid ws))))
                                 (.then (fn [_] (sleep 150)))
                                 (.then (fn [_] (select-new-page! ws url))))))
                    (.catch (fn [_] (forward "new_page" args))))))

            (dispatch [tool args ws]
              (case tool
                "list_pages"  (gate-list-pages ws)
                "select_page" (gate-page-op tool args ws)
                "close_page"  (gate-page-op tool args ws)
                "new_page"    (gate-new-page ws args)
                ;; every other tool acts on the selected page → make sure it's a
                ;; current-workspace page first (self-heal if none exists)
                (-> (reconcile! ws)
                    (.then (fn [_] (forward tool args))))))

            (resolve-workspace [ctx]
              ;; This session's TUI-terminal workspace name (per-session), or
              ;; the globally-viewed one when no client pid is available.
              (if-let [pid (:client-pid ctx)]
                (wm/workspace-for-pid pid)
                (wm/current-workspace)))

            (scoped [tool args & [ctx]]
              ;; Only scope when the WM can actually see our Chrome's X11
              ;; windows. If `wm` is blind (empty — e.g. the attached Chrome
              ;; has a different WM_CLASS than we filter on), managing/creating
              ;; windows would spawn unrecognized windows forever, so pass
              ;; through raw instead.
              (-> (js/Promise.all #js [(resolve-workspace ctx) (wm/chrome-windows)])
                  (.then (fn [arr]
                           (let [[ws wins] (vec arr)]
                             (if (or (nil? ws) (empty? wins))
                               (forward tool args)   ;; wm blind → no scoping
                               (dispatch tool args ws)))))
                  (.catch (fn [_] (forward tool args)))))]
      scoped)))
