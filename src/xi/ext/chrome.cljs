(ns xi.ext.chrome
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
   chrome-devtools-mcp's live tools/list (xi.ext.chrome-defs /
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
     XI_CHROME_MCP_ARGS     extra CLI args for chrome-devtools-mcp (space-split)"
  (:require [clojure.string :as str]
            ["node:child_process" :as child-process])
  (:require-macros [xi.ext.chrome-defs :refer [inline-tool-defs]]))

(def ^:private tool-defs (inline-tool-defs))
(def ^:private tool-names (mapv :name tool-defs))

(defn- env [k] (aget js/process.env k))

(defn- server-args
  "CLI args for `npx` to launch chrome-devtools-mcp, honoring the env knobs."
  []
  (let [browser-url (env "XI_CHROME_BROWSER_URL")
        extra       (some->> (env "XI_CHROME_MCP_ARGS")
                             str/trim
                             (#(when (seq %) (remove str/blank? (str/split % #"\s+")))))]
    (cond-> ["-y" "chrome-devtools-mcp@latest"]
      (seq browser-url) (into ["--browserUrl" browser-url])
      (seq extra)       (into extra))))

;; ── Hand-rolled stdio JSON-RPC 2.0 client ────────────────────────────────────

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
                    (let [id (swap! next-id inc)]
                      (.set pending id #js {:resolve resolve :reject reject})
                      (write! (cond-> {:jsonrpc "2.0" :id id :method method}
                                (some? params) (assoc :params params))))))))
     :notify (fn [method params]
               (write! (cond-> {:jsonrpc "2.0" :method method}
                         (some? params) (assoc :params params))))
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

;; ── Factory ──────────────────────────────────────────────────────────────────

(defn create
  "Return the chrome extension, or nil when XI_CHROME_TOOLS is unset.
   The child MCP + Chrome are lazily spawned on the first tool call; the
   connect promise is memoized (and cleared on failure so a later call
   retries)."
  [_ctx]
  (when (seq (env "XI_CHROME_TOOLS"))
    (let [client* (atom nil)   ;; the live client map, for :on-shutdown
          ready*  (atom nil)]  ;; memoized Promise<connected-client>
      (letfn [(ensure! []
                (or @ready*
                    (let [c (make-client (server-args))
                          p (connect! c)]
                      (reset! client* c)
                      (reset! ready* p)
                      (.catch p (fn [_] (reset! ready* nil) (reset! client* nil)))
                      p)))
              (forward [tool-name args]
                (-> (ensure!)
                    (.then (fn [client]
                             ((:call client) "tools/call"
                              {:name tool-name :arguments (or args {})})))
                    (.then normalize-result)
                    (.catch (fn [e]
                              {:content  [{:type "text"
                                           :text (str "chrome-devtools-mcp error: "
                                                      (.-message e))}]
                               :is-error true}))))]
        {:id               :chrome
         :tool-definitions tool-defs
         :tool-registry    (into {} (map (fn [n] [n (fn [args _ctx] (forward n args))]))
                                 tool-names)
         :on-shutdown      (fn [] (when-let [c @client*] ((:kill c))))}))))
