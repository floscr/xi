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
   workspace' bug. When there is no `:client-pid` (e.g. a web client, or a
   sub-agent turn before the pid was threaded) or it can't be placed, the guard
   falls back to the last pid-resolved workspace, then to the workspace the
   agent's Chrome windows already live on — and when nothing resolves it
   refuses to act. The currently-viewed workspace (`wm current`) is never used
   as a target: it follows the user's gaze, not the agent.

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
   unavailable, the destructive gates block and the rest passes through.

   Owned-windows-only mode (XI_CHROME_OWN_WINDOWS_ONLY): when several agents
   share ONE Chrome on ONE workspace (e.g. parallel `hn-hiring apply` runs),
   xmonad-workspace scoping can't isolate them — they all resolve to the same
   workspace and see the same tabs. In this mode the membership axis is
   *ownership*, not workspace: an agent only ever acts on the Chrome windows it
   itself created (tracked in `owned*`), so it can never navigate or close
   another agent's tab. Each agent is its own process with its own `owned*`, so
   ownership is naturally per-agent. Implemented by keying membership on a
   per-process sentinel workspace name: owned windows are recorded under the
   sentinel and `launch-workspace` is the sentinel, so `scope/classify`'s
   existing owned-window merge yields exactly the owned set. No `wm` calls are
   needed (or made) in this mode — isolation rides purely on CDP window ids."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [xi.ext.chrome-mcp.scope :as scope]
            [xi.ext.chrome-mcp.cdp :as cdp]
            [xi.ext.chrome-mcp.launch :as launch]
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

(defn- env [k] (aget js/process.env k))

(defn- owned-only?* []
  (boolean (some-> (env "XI_CHROME_OWN_WINDOWS_ONLY") str/trim not-empty)))

;; A synthetic workspace name used purely as the membership key in
;; owned-windows-only mode. Never a real xmonad workspace, so only windows this
;; process explicitly records under it count as "ours".
(def ^:private owned-sentinel "__xi-owned-window__")

;; ── install ──────────────────────────────────────────────────────────────────

(defn install
  "Return a scoped `forward` fn `(fn [tool args] | [tool args ctx])`, given the
   raw `forward` and the browser's remote-debugging URL (attach mode). Each call
   resolves this session's TUI-terminal workspace live from `(:client-pid ctx)`,
   falling back to the last pid-resolved workspace, then to where the agent's
   Chrome windows already are; when nothing resolves the call is blocked —
   never forwarded unscoped, never aimed at the viewed workspace."
  [forward browser-url]
  (let [owned-only? (owned-only?*) ;; isolate to windows THIS agent created
        cdp*     (atom nil)   ;; memoized Promise<cdp-client>
        owned*   (atom {})    ;; {cdp-window-id → workspace-name} of windows we created
        last-ws* (atom nil)]  ;; last workspace resolved from a client pid — the
                              ;; anchor for pid-less turns (sub-agents, web clients)
    (letfn [(cdp-connect! []
              (reset! cdp* (-> (cdp/connect browser-url)
                               (.catch (fn [e] (reset! cdp* nil) (throw e))))))
            (cdp-client []
              ;; Reconnect a memoized client whose socket has since died (Chrome
              ;; relaunched / browser endpoint dropped). Reusing a dead client
              ;; would `.send` into the void and hang forever (see xi.ext.chrome-mcp.cdp).
              ;; Retry a failed connect once within the same call: a cold
              ;; first connect (fresh server) rejecting would otherwise drop the
              ;; whole operation into the unscoped raw-forward fallback — which
              ;; is how a new_page once landed in a foreign-workspace window.
              (-> (or @cdp* (cdp-connect!))
                  (.then (fn [c] (if ((:closed? c)) (cdp-connect!) c)))
                  (.catch (fn [_] (cdp-connect!)))))

            (classify [ws]
              ;; → Promise<{:result :text :scope}>
              (-> (js/Promise.all
                   #js [(forward "list_pages" {})
                        (-> (cdp-client) (.then cdp/page-windows) (.catch (fn [_] [])))
                        ;; owned-only: membership is by CDP window ownership, so
                        ;; the WM isn't consulted (avoid the subprocess spawn).
                        (if owned-only? [] (wm/chrome-windows))])
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
              (if owned-only?
                ;; Owned-only: no workspace placement — just create the window,
                ;; adopt it by its CDP window id (that's the whole isolation),
                ;; and select it. No wm calls (wm is intentionally unused here).
                (-> (cdp-client)
                    (.then (fn [c]
                             (-> (cdp/create-window c "about:blank")
                                 (.then (fn [tid] (cdp/window-for-target c tid)))
                                 (.then (fn [win-id]
                                          (when win-id (swap! owned* assoc win-id ws)))))))
                    (.then (fn [_] (sleep 150)))
                    (.then (fn [_] (select-workspace-page! ws))))
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
                                 (.then (fn [_] (select-workspace-page! ws)))))))))

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
                               (blocked (if owned-only?
                                          (str "Page " pid " belongs to another agent's "
                                               "Chrome window. This agent only controls windows "
                                               "it opened itself — run list_pages to see them.")
                                          (str "Page " pid " is on another xmonad workspace. "
                                               "This agent only controls Chrome windows on the "
                                               "current workspace — run list_pages to see them."))))))
                    (.catch (fn [_]
                              (blocked (str "Could not verify which "
                                            (if owned-only? "window" "workspace") " page " pid
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

            (new-window-page! [ws url]
              ;; No existing window on `ws`: open a real new OS window via CDP
              ;; (Target.createTarget :newWindow), move it onto `ws`, record it
              ;; as owned, then select it — same primitive as ensure-window!.
              ;; This is the only path that can flash (a window maps on the
              ;; viewed workspace for an instant before `wm move` relocates it).
              (if owned-only?
                ;; Owned-only: adopt the new window by CDP id, no wm move.
                (-> (cdp-client)
                    (.then (fn [c]
                             (-> (cdp/create-window c url)
                                 (.then (fn [tid] (cdp/window-for-target c tid))))))
                    (.then (fn [win-id]
                             (when win-id (swap! owned* assoc win-id ws))))
                    (.then (fn [_] (sleep 150)))
                    (.then (fn [_] (select-new-page! ws url))))
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
                                 (.then (fn [_] (select-new-page! ws url)))))))))

            (new-tab-in-window! [ws url win-id]
              ;; Reuse an existing window on `ws`: open a plain tab in it. No new
              ;; OS window, no `wm move`, so no flash. select-new-page! then
              ;; brings the tab to front.
              (-> (cdp-client)
                  (.then (fn [c] (cdp/create-tab c url win-id)))
                  (.then (fn [_] (sleep 100)))
                  (.then (fn [_] (select-new-page! ws url)))))

            (gate-new-page [ws args]
              ;; chrome-devtools-mcp `new_page` only adds a *tab* to the focused
              ;; window, which need not be on our workspace — so we can't use it
              ;; blindly. Best case: `ws` already has an empty page (a New Tab /
              ;; about:blank — e.g. Chrome's cold-launch tab, or our own
              ;; bootstrap window) → navigate it in place instead of stacking a
              ;; tab beside it. Else favor reusing a window already on `ws`
              ;; (open a tab in it via CDP with an explicit windowId — cheap, no
              ;; flash); only when `ws` has no window do we spawn a fresh OS
              ;; window.
              (let [url (or (:url args) "about:blank")]
                (-> (classify ws)
                    (.then (fn [{:keys [scope text]}]
                             (let [pages    (scope/parse-pages text)
                                   empty-id (some (fn [p]
                                                    (when (and (contains? (:in-workspace scope) (:id p))
                                                               (scope/empty-page-url? (:url p)))
                                                      (:id p)))
                                                  pages)]
                               (cond
                                 empty-id
                                 (-> (forward "select_page" {:pageId empty-id :bringToFront true})
                                     (.then (fn [_] (forward "navigate_page" {:type "url" :url url})))
                                     (.then (fn [_] (gate-list-pages ws))))

                                 :else
                                 (if-let [win-id (first (:in-workspace-window-ids scope))]
                                   (new-tab-in-window! ws url win-id)
                                   (new-window-page! ws url))))))
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

            (workspace-from-windows []
              ;; → Promise<ws|nil> — where the agent's Chrome already lives: a
              ;; workspace this process placed a window on (owned*), else the
              ;; single workspace holding ALL MCP-Chrome windows (unambiguous);
              ;; nil when there's nothing to anchor to.
              (if-let [ws (first (vals @owned*))]
                (js/Promise.resolve ws)
                (-> (wm/chrome-windows)
                    (.then (fn [wins]
                             (let [wss (into #{} (keep :workspace) wins)]
                               (when (= 1 (count wss)) (first wss)))))
                    (.catch (fn [_] nil)))))

            (fallback-workspace []
              ;; → Promise<ws|nil> for turns with no (resolvable) client pid.
              ;; NEVER `wm current`: the viewed workspace follows the user's
              ;; eyes, not the agent — anchoring to it was the 'about:blank
              ;; windows chase my gaze' bug (every action while the user viewed
              ;; another workspace self-healed a blank window *there*).
              (if-let [ws @last-ws*]
                (js/Promise.resolve ws)
                (workspace-from-windows)))

            (resolve-workspace [ctx]
              ;; Owned-only: a fixed per-process sentinel — membership is by
              ;; ownership, not workspace, so we never ask the WM anything.
              ;; Otherwise: this session's TUI-terminal workspace name from the
              ;; driving client's pid, cached in last-ws* so pid-less turns
              ;; (sub-agents, web clients) stay anchored to the agent's
              ;; workspace; else where the agent's Chrome windows already are.
              ;; The user's currently-viewed workspace is never consulted.
              (cond
                owned-only?       (js/Promise.resolve owned-sentinel)
                (:client-pid ctx) (-> (wm/workspace-for-pid (:client-pid ctx))
                                      (.then (fn [ws]
                                               (if ws
                                                 (do (reset! last-ws* ws) ws)
                                                 (fallback-workspace))))
                                      (.catch (fn [_] (fallback-workspace))))
                :else             (fallback-workspace)))

            (place-launched! [ws]
              ;; Right after a *cold* launch, Chrome maps its window on the
              ;; currently-viewed workspace (not the agent's). Since we only
              ;; launch when no Chrome was reachable at all, every current
              ;; Chrome window belongs to this launch — so move each off any
              ;; other workspace onto `ws`, and adopt every CDP window as ours
              ;; on `ws` (so a still-blank/loading window with no correlatable
              ;; title is recognized as the agent's instead of triggering a
              ;; second self-healed window).
              (-> (js/Promise.all
                   #js [(-> (cdp-client) (.then cdp/page-windows) (.catch (fn [_] [])))
                        (wm/chrome-windows)])
                  (.then (fn [arr]
                           (let [[targets wins] (vec arr)
                                 cdp-wids (into #{} (keep :window-id) targets)]
                             (doseq [w cdp-wids] (swap! owned* assoc w ws))
                             (-> (js/Promise.all
                                  (clj->js (for [w wins :when (not= (:workspace w) ws)]
                                             (wm/move-window (:wid w) ws))))
                                 (.then (fn [_] :ok))))))
                  (.catch (fn [_] :ok))))

            (ensure-chrome! [ws]
              ;; Make sure the shared OS Chrome is running before we act. When
              ;; it was down and we launched it, place its fresh window(s) on
              ;; the agent's workspace so the bootstrap window doesn't strand on
              ;; whatever workspace the user was viewing. In owned-only mode we
              ;; only need the process up — no window placement (no wm).
              (if owned-only?
                (-> (launch/ensure-process! browser-url)
                    (.then (fn [_] :ok))
                    (.catch (fn [_] :ok)))
                (-> (launch/ensure-process! browser-url)
                  (.then (fn [res]
                           (if (= res :launched)
                             ;; wait until Chrome's window is actually mapped
                             ;; (its endpoint answers slightly before the X11
                             ;; window shows up in wm), then place it on `ws`.
                             (-> (wait-new-wid #{})
                                 (.then (fn [_] (place-launched! ws))))
                             :ok)))
                  (.catch (fn [_] :ok)))))

            (scoped [tool args & [ctx]]
              ;; Anchor every call to the agent's OWN workspace, then make sure
              ;; Chrome is running (launch + place its window on `ws` if it was
              ;; down), then scope. When no workspace resolves at all, REFUSE:
              ;; both guessing with `wm current` and passing through raw act
              ;; wherever the *user* currently is (viewed workspace / focused
              ;; window). Same on errors — fail closed, never forward unscoped.
              (-> (resolve-workspace ctx)
                  (.then (fn [ws]
                           (if (nil? ws)
                             (blocked (str "Could not determine this agent's workspace: "
                                           "no driving terminal client, no previously "
                                           "resolved workspace, and no unambiguous "
                                           "existing MCP-Chrome window to anchor to. "
                                           "Refusing to act on the user's currently-viewed "
                                           "workspace (fail-safe). Drive the session from "
                                           "a terminal, or open the shared Chrome on the "
                                           "agent's workspace first."))
                             (-> (ensure-chrome! ws)
                                 (.then (fn [_] (dispatch tool args ws)))))))
                  (.catch (fn [e]
                            (blocked (str "Chrome workspace scoping failed ("
                                          (or (some-> e .-message) e)
                                          "); refusing to act unscoped (fail-safe). "
                                          "Retry, or check the shared Chrome."))))))]
      scoped)))
