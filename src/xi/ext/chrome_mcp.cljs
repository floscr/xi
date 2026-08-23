(ns xi.ext.chrome-mcp
  "chrome-devtools-mcp proxied into xi's own tool surface.

   xi's provider disables the Claude CLI's native MCP servers (only xi's
   in-process MCP is exposed — see xi.provider.claude/query-opts), so a
   chrome-devtools-mcp declared in ~/.claude.json never reaches an agent run
   inside xi. This extension re-provides it as xi tools: xi spawns
   chrome-devtools-mcp as a child MCP server over stdio and forwards each
   call. Every call then flows through xi's tool-gate — governable and
   redirectable exactly like the built-in tools.

     model → xi in-process MCP (mcp__xi-tools__navigate_page …)
           → tool-gate → this registry fn
           → hand-rolled stdio JSON-RPC client → chrome-devtools-mcp → Chrome

   The tool definitions are baked into the bundle at compile time from
   chrome-devtools-mcp's live tools/list (xi.ext.chrome-mcp.defs /
   scripts/sync-chrome-tools.mjs), because xi collects :tool-definitions
   synchronously at assembly — there is no async seam to discover them per
   run.

   Opt-in: the factory returns nil unless XI_CHROME_TOOLS is set, so the 29
   browser tools aren't advertised on every turn by default. Chrome is only
   launched lazily on the first tool call, and killed on shutdown.

   Env:
     XI_CHROME_TOOLS        enable the extension (any non-empty value)
     XI_CHROME_BROWSER_URL  attach to an existing Chrome's remote-debugging
                            URL (passed as --browserUrl); otherwise chrome-
                            devtools-mcp launches its own managed Chrome
     XI_CHROME_MCP_ARGS     extra CLI args for chrome-devtools-mcp (space-split)
     XI_CHROME_LAUNCH_BIN   absolute path to the launcher used to start the
                            shared OS Chrome when it isn't running (attach mode;
                            default dotfiles `browser` bin)"
  (:require [clojure.string :as str]
            [xi.ext.chrome-mcp.guard :as guard]
            [xi.ext.chrome-mcp.launch :as launch]
            [xi.ext.chrome-mcp.scope :as scope]
            [xi.ext.element-picker :as element-picker]
            [xi.ext.style-editor :as style-editor]
            ["node:child_process" :as child-process])
  (:require-macros [xi.ext.chrome-mcp.defs :refer [inline-tool-defs]]))

(def ^:private tool-defs (inline-tool-defs))
(def ^:private tool-names (mapv :name tool-defs))

(defn- env [k] (aget js/process.env k))

(defn- server-args
  "CLI args for `npx` to launch chrome-devtools-mcp, honoring the env knobs.

   `--allow-unrestricted-paths` lifts chrome-devtools-mcp's own path gate on
   file tools (upload_file, save_*, screenshots). Without it, because xi's
   hand-rolled JSON-RPC client never negotiates the MCP `roots` capability,
   chrome-devtools-mcp restricts those tools to the OS temp dir — so uploading
   e.g. a CV from a project dir fails with a 'not within any of the configured
   workspace roots' error. Path safety is already enforced upstream of the proxy by
   xi's sandbox + permission-gate, so the child server's redundant gate only
   gets in the way."
  []
  (let [browser-url (env "XI_CHROME_BROWSER_URL")
        extra       (some->> (env "XI_CHROME_MCP_ARGS")
                             str/trim
                             (#(when (seq %) (remove str/blank? (str/split % #"\s+")))))]
    (cond-> ["-y" "chrome-devtools-mcp@latest" "--allow-unrestricted-paths"]
      (seq browser-url) (into ["--browserUrl" browser-url])
      (seq extra)       (into extra))))

;; ── Hand-rolled stdio JSON-RPC 2.0 client ────────────────────────────────────

(defn- call-timeout-ms
  "Per-call cap on a chrome-devtools-mcp JSON-RPC request. Without it a wedged
   child process or a stalled stdio pipe leaves the pending promise unresolved
   forever, hanging the agent's turn (the same failure class as the dead CDP
   socket). On timeout the pending entry is dropped and the call rejects, which
   `raw-forward` turns into a normal error tool-result.

   The default is deliberately generous — real chrome ops (navigation, waits,
   performance traces) are slow, and a spurious abort of a legit call is worse
   than a rare long wait — so it only ever fires on a true wedge. Override with
   `XI_CHROME_MCP_TIMEOUT_MS` (milliseconds); a value <= 0 disables the timeout."
  []
  (let [n (some-> (env "XI_CHROME_MCP_TIMEOUT_MS") str/trim not-empty js/parseInt)]
    (if (and n (not (js/isNaN n))) n 120000)))

(defn- make-client
  "Spawn an MCP stdio server (`npx <args>`) and return
   {:call (fn [method params] -> Promise<result>)
    :notify (fn [method params])
    :kill (fn [])}.
   Messages are newline-delimited JSON-RPC 2.0; responses are matched to
   pending calls by id. Once the child closes, pending + future calls reject."
  [args]
  (let [child   (.spawn child-process "npx" (clj->js args)
                        #js {:stdio #js ["pipe" "pipe" "ignore"]
                             :env js/process.env})
        pending (js/Map.)
        next-id (atom 0)
        buf     (atom "")
        dead    (atom nil)
        write!  (fn [obj]
                  (.write (.-stdin child)
                          (str (js/JSON.stringify (clj->js obj)) "\n")))
        settle-dead!
        (fn [reason]
          (reset! dead reason)
          (doseq [k (js/Array.from (.keys pending))]
            (when-let [p (.get pending k)]
              (.delete pending k)
              ((.-reject p) (js/Error. reason)))))]
    (.on (.-stdout child) "data"
         (fn [chunk]
           (swap! buf str (.toString chunk "utf8"))
           (loop []
             (let [s @buf idx (.indexOf s "\n")]
               (when (>= idx 0)
                 (let [line (str/trim (subs s 0 idx))]
                   (reset! buf (subs s (inc idx)))
                   (when (seq line)
                     (when-let [msg (try (js/JSON.parse line) (catch :default _ nil))]
                       (let [id (.-id msg)]
                         (when (and (some? id) (.has pending id))
                           (let [p (.get pending id)]
                             (.delete pending id)
                             (if-let [err (.-error msg)]
                               ((.-reject p) (js/Error. (or (.-message err) "MCP error")))
                               ((.-resolve p) (.-result msg)))))))))
                 (recur))))))
    (.on child "close" (fn [_] (settle-dead! "chrome-devtools-mcp exited")))
    (.on child "error" (fn [e] (settle-dead! (str "chrome-devtools-mcp spawn error: "
                                                  (.-message e)))))
    {:call   (fn [method params]
               (js/Promise.
                (fn [resolve reject]
                  (if @dead
                    (reject (js/Error. @dead))
                    (let [id (swap! next-id inc)
                          ms (call-timeout-ms)
                          t  (when (pos? ms)
                               (js/setTimeout
                                (fn []
                                  (when (.has pending id)
                                    (.delete pending id)
                                    (reject (js/Error. (str "chrome-devtools-mcp call timed out after "
                                                            ms "ms: " method)))))
                                ms))]
                      ;; Wrap resolve/reject to clear the timer, so the normal
                      ;; onmessage / settle-dead! paths (which call these) also
                      ;; cancel it — no separate bookkeeping needed.
                      (.set pending id #js {:resolve (fn [v] (when t (js/clearTimeout t)) (resolve v))
                                            :reject  (fn [e] (when t (js/clearTimeout t)) (reject e))})
                      (write! (cond-> {:jsonrpc "2.0" :id id :method method}
                                (some? params) (assoc :params params))))))))
     :notify (fn [method params]
               (write! (cond-> {:jsonrpc "2.0" :method method}
                         (some? params) (assoc :params params))))
     :dead?  (fn [] (some? @dead))
     :kill   (fn [] (try (.kill child) (catch :default _ nil)))}))

(defn- connect!
  "Run the MCP initialize handshake; resolve to the connected client."
  [client]
  (-> ((:call client) "initialize"
       {:protocolVersion "2024-11-05"
        :capabilities    {}
        :clientInfo      {:name "xi" :version "1.0.0"}})
      (.then (fn [_]
               ((:notify client) "notifications/initialized" nil)
               client))))

(defn- normalize-result
  "MCP tools/call result → xi tool result {:content … :is-error}."
  [result]
  (let [m (when result (js->clj result :keywordize-keys true))]
    {:content  (or (:content m) [{:type "text" :text ""}])
     :is-error (boolean (:isError m))}))

(defn- result-text [result]
  (->> (:content result)
       (keep (fn [b] (when (= "text" (:type b)) (:text b))))
       (str/join "\n")))

(defn- wedged-selection?
  "chrome-devtools-mcp 1.7.0 bug: once the *selected* page is closed, EVERY
   tool call — including list_pages and select_page, the tools that would
   repair the selection — throws 'The selected page has been closed' from an
   unconditional getSelectedMcpPage() in ToolHandler, wedging the child
   permanently. (Fixed on upstream main, not yet released.)"
  [result]
  (and (:is-error result)
       (str/includes? (result-text result) "The selected page has been closed")))

;; ── Factory ──────────────────────────────────────────────────────────────────

(defn create
  "Return the chrome extension, or nil when XI_CHROME_TOOLS is unset.
   The child MCP + Chrome are lazily spawned on the first tool call; the
   connect promise is memoized (and cleared on failure so a later call
   retries)."
  [{:keys [ask!] :as _ctx}]
  (when (seq (env "XI_CHROME_TOOLS"))
    (let [client* (atom nil)   ;; the live client map, for :on-shutdown
          ready*  (atom nil)]  ;; memoized Promise<connected-client>
      (letfn [(ensure! []
                ;; Respawn when the memoized child has since died (its exit
                ;; settles pending calls but must not wedge future ones).
                (if (and @ready* (not (when-let [c @client*] ((:dead? c)))))
                  @ready*
                  (let [c (make-client (server-args))
                        p (connect! c)]
                    (reset! client* c)
                    (reset! ready* p)
                    (.catch p (fn [_] (reset! ready* nil) (reset! client* nil)))
                    p)))
              (call! [tool-name args]
                (-> (ensure!)
                    (.then (fn [client]
                             ((:call client) "tools/call"
                              {:name tool-name :arguments (or args {})})))
                    (.then normalize-result)
                    (.catch (fn [e]
                              {:content  [{:type "text"
                                           :text (str "chrome-devtools-mcp error: "
                                                      (.-message e))}]
                               :is-error true}))))
              (heal-selection! []
                ;; Un-wedge a closed-selected-page child (see wedged-selection?).
                ;; new_page is the one call that still works: its handler
                ;; re-selects the fresh page BEFORE the buggy post-handler line
                ;; runs. Then move selection onto another live page and drop the
                ;; helper tab so the heal leaves no clutter behind (skipped when
                ;; the blank tab is the only page — the last page can't close).
                (-> (call! "new_page" {:url "about:blank"})
                    (.then (fn [res]
                             (let [pages (scope/parse-pages (result-text res))
                                   blank (some #(when (:selected? %) (:id %)) pages)
                                   other (some #(when-not (:selected? %) (:id %)) pages)]
                               (if (and blank other)
                                 (-> (call! "select_page" {:pageId other})
                                     (.then (fn [_] (call! "close_page" {:pageId blank}))))
                                 (js/Promise.resolve nil)))))
                    (.catch (fn [_] nil))))
              (raw-forward [tool-name args]
                (-> (call! tool-name args)
                    (.then (fn [res]
                             (if (wedged-selection? res)
                               (-> (heal-selection!)
                                   (.then (fn [_] (call! tool-name args))))
                               res)))))]
        ;; In attach mode, scope every call to the agent's launch xmonad
        ;; workspace (unless XI_CHROME_NO_SCOPE is set). See xi.ext.chrome-mcp.guard.
        (let [browser-url (env "XI_CHROME_BROWSER_URL")
              scope?      (and (seq browser-url) (not (seq (env "XI_CHROME_NO_SCOPE"))))
              ;; `forward` is uniformly variadic `(fn [tool args & [ctx]])`. When
              ;; scoped, the guard reads (:client-pid ctx) to pick this session's
              ;; TUI-terminal workspace; the style-editor / element-picker call
              ;; it 2-arg (no ctx) and fall back to `wm current`.
              ;; In attach mode, make sure the shared OS Chrome is actually
              ;; running before any tool call — probe its CDP endpoint and
              ;; launch it (detached) if it's down, so an agent can bootstrap
              ;; Chrome itself instead of erroring when nothing is running. When
              ;; scoped, the guard does the launch itself (it also needs to
              ;; place the fresh window on the agent's workspace); the no-scope
              ;; attach path only needs the process up, so it wraps here.
              forward     (cond
                            scope?            (guard/install raw-forward browser-url)
                            (seq browser-url) (fn [tool args & _]
                                                (-> (launch/ensure-process! browser-url)
                                                    (.then (fn [_] (raw-forward tool args)))))
                            :else             (fn [tool args & _] (raw-forward tool args)))
              editor (style-editor/install forward)]
          (merge
           {:id               :chrome
            ;; Combine the proxied chrome tools with the style-editor's own tool
            ;; (both contribute :tool-definitions/:tool-registry, so merge them
            ;; explicitly — a plain map merge would clobber one).
            :tool-definitions (into tool-defs (:tool-definitions editor))
            :tool-registry    (merge (into {} (map (fn [n] [n (fn [args ctx] (forward n args ctx))]))
                                           tool-names)
                                     (:tool-registry editor))
            :on-shutdown      (fn [] (when-let [c @client*] ((:kill c))))}
           ;; The element picker drives the same MCP client. With multiple tabs
           ;; open it first asks (via ask!) which one to pick from; otherwise it
           ;; acts on chrome-devtools-mcp's currently selected page.
           (element-picker/install forward ask!)))))))
