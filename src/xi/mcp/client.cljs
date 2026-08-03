(ns xi.mcp.client
  "Minimal MCP (Model Context Protocol) client — JSON-RPC 2.0 over a stdio
   subprocess. This is the transport half of the MCP-as-extension helper
   (xi.ext.mcp); the extension wrapper turns a connected client's tool list
   into Xi tool-definitions + a tool-registry.

   Xi's single-runtime-dep rule (only @anthropic-ai/claude-agent-sdk) means
   we speak the wire protocol by hand rather than pulling in the MCP SDK.
   That is fine — stdio MCP is newline-delimited JSON-RPC:

     → spawn the server, write one JSON object per line to its stdin
     ← read its stdout, one JSON object per line
     · requests carry an integer :id; responses echo it; notifications omit it

   Handshake: `initialize` request → `notifications/initialized` notify.
   Then `tools/list` to discover tools and `tools/call` to invoke one.

   `connect` returns a promise of a client map:
     {:proc :request :notify :close :server-info}
   where (:request client) is (fn [method params] → Promise<js-result>)."
  (:require [clojure.string :as str]
            ["node:child_process" :as child-process]))

(def ^:private PROTOCOL_VERSION "2024-11-05")

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
  [^js proc]
  (let [next-id (atom 0)
        pending (atom {})            ;; id -> {:resolve :reject}
        buf     (atom "")
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
        request (fn [method params]
                  (js/Promise.
                   (fn [resolve reject]
                     (let [id (swap! next-id inc)]
                       (swap! pending assoc id {:resolve resolve :reject reject})
                       (try
                         (write! {:jsonrpc "2.0" :id id :method method
                                  :params (or params {})})
                         (catch :default e
                           (swap! pending dissoc id)
                           (reject e)))))))
        notify  (fn [method params]
                  (try (write! {:jsonrpc "2.0" :method method
                                :params (or params {})})
                       (catch :default _ nil)))
        close   (fn [] (try (.kill proc) (catch :default _ nil)))]
    (.on (.-stdout proc) "data" on-data)
    (.on proc "exit" (fn [_code]
                       (reject-all! (js/Error. "MCP server exited"))))
    (.on proc "error" (fn [e] (reject-all! e)))
    {:proc proc :request request :notify notify :close close}))

(defn connect
  "Spawn an MCP stdio server and perform the initialize handshake. Returns a
   promise of the client map with :server-info attached."
  [opts]
  (let [proc   (spawn-stdio opts)
        client (make-client proc)]
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
   promise of the raw JS result ({content, isError})."
  [client tool-name arguments]
  ((:request client) "tools/call" {:name tool-name :arguments (or arguments #js {})}))
