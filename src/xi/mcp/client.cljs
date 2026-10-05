(ns xi.mcp.client
  "Minimal MCP (Model Context Protocol) client — JSON-RPC 2.0 over two
   transports. This is the transport half of the MCP-as-extension helper
   (xi.ext.mcp); the extension wrapper turns a connected client's tool list
   into Xi tool-definitions + a tool-registry.

   Xi's no-runtime-deps rule (the Claude Agent SDK lives in the separate
   packages/providers/anthropic/ runner process) means we speak the wire protocol by hand rather than
   pulling in the MCP SDK.

   stdio (`connect`): newline-delimited JSON-RPC over a subprocess:
     → spawn the server, write one JSON object per line to its stdin
     ← read its stdout, one JSON object per line
     · requests carry an integer :id; responses echo it; notifications omit it

   Streamable HTTP (`connect-http`): JSON-RPC over HTTPS POSTs to a hosted
   server (e.g. Render). No npm bridge — plain `fetch`:
     → POST one JSON-RPC object per request, with an Authorization header
     ← the reply is either `application/json` (one object) or a
       `text/event-stream` SSE body carrying the object in a `data:` frame
     · the server may hand back an `Mcp-Session-Id` header on `initialize`;
       we echo it on every later request

   Handshake (both): `initialize` request → `notifications/initialized` notify.
   Then `tools/list` to discover tools and `tools/call` to invoke one.

   `connect`/`connect-http` both return a promise of a client map:
     {:request :notify :close :dead? :server-info}   (stdio also carries :proc)
   where (:request client) is (fn [method params] → Promise<js-result>), so
   list-tools/call-tool work uniformly across transports. `(:dead? client)`
   is true once a stdio server has exited (never for HTTP); the caller drops
   the client and reconnects.

   Every request is capped by `:timeout-ms` (default 120000; <= 0 disables),
   so a wedged server surfaces as an error instead of hanging the turn."
  (:require [clojure.string :as str]
            ["node:child_process" :as child-process]))

(def ^:private PROTOCOL_VERSION "2024-11-05")

(def default-timeout-ms 120000)

(def ^:private STDERR_TAIL 2000)

(defn- timeout-ms [opts]
  (let [t (:timeout-ms opts)]
    (if (number? t) t default-timeout-ms)))

(defn- with-timeout
  "Reject `p` with a timeout error after `ms` (no cap when ms <= 0); `on-timeout`
   runs first so the caller can drop its bookkeeping."
  [p ms method on-timeout]
  (if (pos? ms)
    (js/Promise.
     (fn [resolve reject]
       (let [timer (js/setTimeout
                    (fn []
                      (on-timeout)
                      (reject (js/Error. (str "MCP request " method " timed out after "
                                              (js/Math.round (/ ms 1000)) "s"))))
                    ms)]
         (.then p
                (fn [v] (js/clearTimeout timer) (resolve v))
                (fn [e] (js/clearTimeout timer) (reject e))))))
    p))

(defn- spawn-stdio
  "Spawn an MCP stdio server. `env` (a map) is merged over the current env."
  [{:keys [command args env cwd]}]
  (child-process/spawn
   command
   (clj->js (or args []))
   #js {:stdio #js ["pipe" "pipe" "pipe"]
        :env   (if env
                 (clj->js (merge (js->clj (unchecked-get js/process "env")) env))
                 (unchecked-get js/process "env"))
        :cwd   (or cwd (.cwd js/process))}))

(defn- make-client
  "Wire up request/response correlation over a spawned subprocess. Returns
   the client map (before the initialize handshake)."
  [^js proc opts]
  (let [next-id (atom 0)
        pending (atom {})            ;; id -> {:resolve :reject}
        buf     (atom "")
        dead    (atom false)
        ;; stderr must be drained (a full pipe blocks the server); its tail
        ;; explains an exit
        err-buf (atom "")
        write!  (fn [obj]
                  (.write (.-stdin proc)
                          (str (js/JSON.stringify (clj->js obj)) "\n")))
        settle! (fn [msg]
                  (let [id (unchecked-get msg "id")]
                    (when (some? id)
                      (when-let [{:keys [resolve reject]} (get @pending id)]
                        (swap! pending dissoc id)
                        (if-let [err (unchecked-get msg "error")]
                          (reject (js/Error. (or (unchecked-get err "message")
                                                 "MCP error")))
                          (resolve (unchecked-get msg "result")))))))
        on-data (fn [chunk]
                  (swap! buf str (str chunk))
                  (loop []
                    (let [b   @buf
                          idx (str/index-of b "\n")]
                      (when idx
                        (let [line (subs b 0 idx)]
                          (reset! buf (subs b (inc idx)))
                          (when-not (str/blank? line)
                            (try (settle! (js/JSON.parse line))
                                 (catch :default _ nil)))
                          (recur))))))
        reject-all! (fn [err]
                      (doseq [[_ {:keys [reject]}] @pending] (reject err))
                      (reset! pending {}))
        died!   (fn [err]
                  (reset! dead true)
                  (reject-all! err))
        request (fn [method params]
                  (if @dead
                    (js/Promise.reject (js/Error. "MCP server exited"))
                    (let [id (swap! next-id inc)]
                      (with-timeout
                        (js/Promise.
                         (fn [resolve reject]
                           (swap! pending assoc id {:resolve resolve :reject reject})
                           (try
                             (write! {:jsonrpc "2.0" :id id :method method
                                      :params (or params {})})
                             (catch :default e
                               (swap! pending dissoc id)
                               (reject e)))))
                        (timeout-ms opts) method
                        #(swap! pending dissoc id)))))
        notify  (fn [method params]
                  (try (write! {:jsonrpc "2.0" :method method
                                :params (or params {})})
                       (catch :default _ nil)))
        close   (fn [] (try (.kill proc) (catch :default _ nil)))]
    (.on (.-stdout proc) "data" on-data)
    (.on (.-stderr proc) "data"
         (fn [chunk]
           (swap! err-buf #(let [s (str % chunk)]
                             (subs s (max 0 (- (count s) STDERR_TAIL)))))))
    ;; a write to a dead child's stdin errors asynchronously (EPIPE)
    (.on (.-stdin proc) "error" (fn [e] (died! e)))
    (.on proc "exit" (fn [code]
                       (let [tail (str/trim @err-buf)]
                         (died! (js/Error. (str "MCP server exited (code " code ")"
                                                (when (seq tail) (str ": " tail))))))))
    (.on proc "error" (fn [e] (died! e)))
    {:proc proc :request request :notify notify :close close
     :dead? (fn [] @dead)}))

(defn connect
  "Spawn an MCP stdio server ({:command :args :env :cwd :timeout-ms}) and
   perform the initialize handshake. Returns a promise of the client map with
   :server-info attached."
  [opts]
  (let [proc   (spawn-stdio opts)
        client (make-client proc opts)]
    (-> ((:request client) "initialize"
         {:protocolVersion PROTOCOL_VERSION
          :capabilities    {}
          :clientInfo      {:name "xi" :version "0.1.0"}})
        (.then (fn [init-result]
                 ((:notify client) "notifications/initialized" {})
                 (assoc client :server-info
                        (js->clj (unchecked-get init-result "serverInfo")
                                 :keywordize-keys true)))))))

;; ── Streamable HTTP transport ────────────────────────────────────────────

(defn- extract-sse-message
  "Pull the first JSON-RPC object out of an SSE body's `data:` frames (a
   Streamable-HTTP reply carries the single response in one `data:` line).
   Returns a JS object or nil."
  [text]
  (->> (str/split-lines (or text ""))
       (keep (fn [line]
               (when (str/starts-with? line "data:")
                 (let [payload (str/trim (subs line 5))]
                   (when-not (str/blank? payload)
                     (try (js/JSON.parse payload) (catch :default _ nil)))))))
       (some (fn [m]
               (when (or (some? (unchecked-get m "result"))
                         (some? (unchecked-get m "error")))
                 m)))))

(defn- parse-http-reply
  "Read a fetch Response as a JSON-RPC message (js object), handling both a
   plain JSON body and an SSE (text/event-stream) body. Returns a promise."
  [^js resp]
  (let [ct (or (.get (.-headers resp) "content-type") "")]
    (-> (.text resp)
        (.then (fn [text]
                 (cond
                   (str/blank? text)                       nil
                   (str/includes? ct "text/event-stream")  (extract-sse-message text)
                   :else                                   (js/JSON.parse text)))))))

(defn- make-http-client
  "Wire up JSON-RPC over HTTPS POSTs to `url`, merging `base-headers` (a clj
   map, e.g. an Authorization pair) onto each request and threading any
   Mcp-Session-Id the server assigns. Returns the client map (before the
   initialize handshake)."
  [url base-headers opts]
  (let [next-id (atom 0)
        session (atom nil)
        headers (fn []
                  (clj->js
                   (cond-> (merge {"Content-Type" "application/json"
                                   "Accept"       "application/json, text/event-stream"}
                                  base-headers)
                     @session (assoc "Mcp-Session-Id" @session))))
        post    (fn [body]
                  (js/fetch url #js {:method  "POST"
                                     :headers (headers)
                                     :body    (js/JSON.stringify (clj->js body))}))
        request (fn [method params]
                  (let [id (swap! next-id inc)]
                    (-> (with-timeout
                          (post {:jsonrpc "2.0" :id id :method method
                                 :params  (or params {})})
                          (timeout-ms opts) method (fn []))
                        (.then (fn [^js resp]
                                 (when-let [sid (.get (.-headers resp) "mcp-session-id")]
                                   (reset! session sid))
                                 (if (.-ok resp)
                                   (parse-http-reply resp)
                                   (-> (.text resp)
                                       (.then (fn [t]
                                                (throw (js/Error.
                                                        (str "MCP HTTP " (.-status resp) ": "
                                                             (subs t 0 (min 300 (count t)))))))))) ))
                        (.then (fn [msg]
                                 (if-let [err (and msg (unchecked-get msg "error"))]
                                   (throw (js/Error. (or (unchecked-get err "message")
                                                         "MCP error")))
                                   (and msg (unchecked-get msg "result"))))))))
        notify  (fn [method params]
                  (-> (post {:jsonrpc "2.0" :method method :params (or params {})})
                      (.catch (fn [_] nil))))
        close   (fn []
                  (when @session
                    (-> (js/fetch url #js {:method "DELETE" :headers (headers)})
                        (.catch (fn [_] nil)))))]
    {:request request :notify notify :close close :dead? (constantly false)}))

(defn connect-http
  "Connect to a Streamable-HTTP MCP server at `:url`, sending `:headers` (a
   clj map, e.g. {\"Authorization\" \"Bearer …\"}) on every request. Performs
   the initialize handshake and returns a promise of the client map with
   :server-info attached."
  [{:keys [url headers] :as opts}]
  (let [client (make-http-client url headers opts)]
    (-> ((:request client) "initialize"
         {:protocolVersion PROTOCOL_VERSION
          :capabilities    {}
          :clientInfo      {:name "xi" :version "0.1.0"}})
        (.then (fn [init-result]
                 ((:notify client) "notifications/initialized" {})
                 (assoc client :server-info
                        (js->clj (unchecked-get init-result "serverInfo")
                                 :keywordize-keys true)))))))

(defn list-tools
  "Fetch the server's tool list. Returns a promise of a vector of tool maps
   (keywordized: {:name :description :inputSchema})."
  [client]
  (-> ((:request client) "tools/list" {})
      (.then (fn [result]
               (js->clj (unchecked-get result "tools") :keywordize-keys true)))))

(defn call-tool
  "Invoke a tool by (unqualified) name with a JS arguments object. Returns a
   promise of the raw JS result ({content, isError}). `meta` (a map), when
   given, goes out as the request's `_meta`: the MCP spec's slot for context
   that isn't a tool argument."
  ([client tool-name arguments] (call-tool client tool-name arguments nil))
  ([client tool-name arguments meta]
   ((:request client) "tools/call"
    (cond-> {:name tool-name :arguments (or arguments #js {})}
      (seq meta) (assoc :_meta meta)))))
