(ns xi.ext.blocks.app
  "MCP Apps (SEP-1865) host, server side: a tool with a UI (an MCP server's
   `_meta.ui.resourceUri`, a user extension's `:ui {:resource-uri}`) shows its
   view as an agent block of the internal kind \"app\".

   - `:ext.blocks/app-open` (dispatched by xi.ext.mcp / xi.ext.user.guard
     after the tool ran) → `open-fx` reads the `ui://` resource and adds the
     block: tool input and result (with the UI-only structuredContent), CSP,
     border preference. The HTML is not in the block; it is cached here.
   - The browser's frame host (xi.ext.blocks.web) asks for the HTML with
     `:ext.blocks/app-load` and forwards the view's JSON-RPC requests that
     need the server with `:ext.blocks/app-rpc`; both are answered to that one
     client (`server-fx`). A view's `tools/call` runs through the rules
     engine like the agent's own call, only for its own server's tools whose
     visibility includes \"app\"; each one is noted on the block, so the agent
     sees it in ui_state."
  (:require [clojure.string :as str]
            [xi.blocks :as b]
            [xi.ext.blocks.handlers :as h]
            [xi.ext.manager :as manager]
            [xi.ext.mcp :as mcp]
            [xi.ext.rules :as rules-ext]
            [xi.mcp.client :as mcp-client]))

;; [source-key uri] → {:html :csp :prefers-border}
(defonce ^:private resources (atom {}))

(defn- source-key [{:keys [type server id]}]
  (if (= :mcp type) [:mcp server] [:ext id]))

(defn- decode-blob [s]
  (.toString (js/Buffer.from s "base64") "utf8"))

(defn- mcp-resource
  "The view of `uri` on MCP server `server` → Promise of {:html :csp
   :prefers-border}."
  [server uri]
  (-> (mcp/read-resource! server uri)
      (.then (fn [{:keys [contents]}]
               (let [c    (or (some #(when (= uri (:uri %)) %) contents) (first contents))
                     html (or (:text c) (some-> (:blob c) decode-blob))
                     ui   (get-in c [:_meta :ui])]
                 (when-not (string? html)
                   (throw (js/Error. (str "resource " uri " has no HTML"))))
                 (when-not (some-> (:mimeType c) (str/starts-with? "text/html"))
                   (throw (js/Error. (str "resource " uri " is " (:mimeType c) ", not " mcp-client/ui-mime-type))))
                 (cond-> {:html html}
                   (map? (:csp ui))              (assoc :csp (:csp ui))
                   (boolean? (:prefersBorder ui)) (assoc :prefers-border (:prefersBorder ui))))))))

(defn- ext-resource
  "A user extension's own `:ui-resources` entry → Promise of {:html …}."
  [mgr ext-id uri]
  (let [r (get-in (manager/composed mgr) [:ui-resources uri])]
    (if (and r (= ext-id (:ext r)))
      (js/Promise.resolve (select-keys r [:html :csp :prefers-border]))
      (js/Promise.reject (js/Error. (str "extension " (some-> ext-id name) " has no resource " uri))))))

(defn- resolve-resource
  "The view `uri` of `source`. An MCP server's is cached for the process; an
   extension's is read from the live composition, so a reload shows the new
   one."
  [mgr source uri]
  (let [k [(source-key source) uri]]
    (case (:type source)
      :ext (ext-resource mgr (:id source) uri)
      :mcp (if-let [hit (get @resources k)]
             (js/Promise.resolve hit)
             (-> (mcp-resource (:server source) uri)
                 (.then (fn [r] (swap! resources assoc k r) r))))
      (js/Promise.reject (js/Error. "unknown app source")))))

;; ── Opening a view ───────────────────────────────────────────────────────────

(defn- gen-id []
  (str "app-" (.toString (js/Math.floor (* (js/Math.random) 0x7fffffff)) 36)))

(defn open-fx
  "Resolve the view of a tool that ran and add its block."
  [mgr {:keys [dispatch! get-state]} {:keys [room-id sub-id call call-id source resource-uri tool-input tool-result]}]
  (-> (resolve-resource mgr source resource-uri)
      (.then
       (fn [{:keys [csp prefers-border]}]
         (let [st   (get-state)
               room (get-in st [:rooms room-id])]
           (when (and room (< (count (h/blocks st room-id)) b/max-blocks))
             (let [{:keys [record error]}
                   (b/make-record {:id (gen-id) :kind "app"
                                   :title (or (:tool source) (some-> (:id source) name))
                                   :data {:resource-uri resource-uri :tool-input tool-input
                                          :tool-result tool-result :csp csp
                                          :prefers-border prefers-border
                                          :tool {:name (or (:tool source) (:name call))}}
                                   :source source
                                   :call-id call-id
                                   :anchor (count (h/call-history st room-id sub-id))
                                   :sub-id sub-id
                                   :at (js/Date.now)})]
               (if error
                 (js/console.error "[blocks] app view not shown:" error)
                 (dispatch! {:type :ext.blocks/add :room-id room-id :block record})))))))
      (.catch (fn [e] (js/console.error "[blocks] app view not shown:" (.-message e))))))

;; ── The view's requests ──────────────────────────────────────────────────────

(defn- rpc-error [code message] {:code code :message message})

(defn- confirm-fn [ask! dispatch! get-state room-id]
  (when ask!
    (fn confirm!
      ([message] (confirm! message nil))
      ([message opts]
       (ask! {:dispatch! dispatch! :state (get-state)}
             {:room-id room-id
              :dialog  (cond-> {:type :confirm :message message}
                         (:options opts)   (assoc :options (:options opts))
                         (:hardened? opts) (assoc :hardened? true)
                         (:guard opts)     (assoc :guard (:guard opts)))})))))

(defn- ext-tool-fn
  "The (guarded) tool `tool` of user extension `ext-id`, or nil when it isn't
   one of that extension's."
  [mgr ext-id tool]
  (let [composed (manager/composed mgr)
        ext      (some #(when (= ext-id (:id %)) %) (:extensions composed))]
    (when (some #(= tool (:name %)) (:tool-definitions ext))
      (get (:tool-registry composed) tool))))

(defn- call-tool
  "A view's `tools/call` → Promise of {:result} or {:error}: its own server's
   (or extension's) tool, through the rules engine as the agent's call would."
  [mgr ask! {:keys [dispatch! get-state]} {:keys [room-id block-id user]} record {:keys [name arguments]}]
  (let [{:keys [type server id]} (:source record)
        tool      (str name)
        args      (if (map? arguments) arguments {})
        qualified (if (= :mcp type) (mcp/qualify-name server tool) tool)
        run       (case type
                    :mcp (when (mcp/app-tool server tool)
                           #(mcp/call-tool! server tool args %))
                    :ext (when-let [f (ext-tool-fn mgr id tool)] #(f args %))
                    nil)
        room      (get-in (get-state) [:rooms room-id])
        ctx       {:dispatch! dispatch! :get-state get-state :room-id room-id
                   :cwd (:cwd room) :user user :from-app? true
                   :confirm! (confirm-fn ask! dispatch! get-state room-id)}]
    (if-not run
      (js/Promise.resolve {:error (rpc-error -32602 (str "tool " tool " is not available to this view"))})
      (-> (rules-ext/tool-policy {:name qualified :arguments args} ctx)
          (.then (fn [gated]
                   (cond
                     (nil? gated) {:error (rpc-error -32000 "Blocked by Xi permission gate")}
                     (:intercepted gated)
                     {:error (rpc-error -32000 (->> (get-in gated [:result :content]) (keep :text) (str/join "\n")))}
                     :else
                     (do (dispatch! {:type :ext.blocks/note :room-id room-id :block-id block-id
                                     :action "tools/call" :target tool :value args})
                         (-> (js/Promise.resolve (run ctx))
                             (.then (fn [{:keys [content is-error structured-content]}]
                                      {:result (cond-> {:content (if (string? content)
                                                                   [{:type "text" :text content}]
                                                                   (vec content))
                                                        :isError (boolean is-error)}
                                                 structured-content (assoc :structuredContent structured-content))})))))))
          (.catch (fn [e] {:error (rpc-error -32000 (.-message e))}))))))

(defn- context-text
  "A ui/update-model-context payload as the text the agent reads."
  [{:keys [content structuredContent]}]
  (let [t (str/join "\n" (keep :text content))
        s (str t (when (map? structuredContent)
                   (str (when (seq t) "\n") (js/JSON.stringify (clj->js structuredContent)))))]
    (subs s 0 (min 4000 (count s)))))

(defn- rpc
  "Answer one forwarded request of a view → Promise of {:result}|{:error}."
  [mgr ask! ctx {:keys [room-id block-id method params] :as req}]
  (let [record (h/find-block ((:get-state ctx)) room-id block-id)]
    (cond
      (not= "app" (:kind record))
      (js/Promise.resolve {:error (rpc-error -32000 "no such app view")})

      (= "tools/call" method)
      (call-tool mgr ask! ctx req record params)

      (= "resources/read" method)
      (if (= :mcp (get-in record [:source :type]))
        (-> (mcp/read-resource! (get-in record [:source :server]) (str (:uri params)))
            (.then (fn [r] {:result r}))
            (.catch (fn [e] {:error (rpc-error -32000 (.-message e))})))
        (js/Promise.resolve {:error (rpc-error -32601 "resources/read is only for MCP views")}))

      (= "ui/update-model-context" method)
      (do ((:dispatch! ctx) {:type :ext.blocks/note :room-id room-id :block-id block-id
                             :action "context" :value (context-text params)})
          (js/Promise.resolve {:result {}}))

      :else
      (js/Promise.resolve {:error (rpc-error -32601 (str "method not found: " method))}))))

(defn server-fx
  "Replies to one client: the view's HTML, and its forwarded requests."
  [mgr ask!]
  (fn [{:keys [send!]}]
    {:ext.blocks/app-load-reply
     (fn [{:keys [get-state]} {:keys [client-id room-id block-id]}]
       (let [record (h/find-block (get-state) room-id block-id)
             reply  (fn [m] (send! client-id (merge {:type :ext.blocks/app-html :room-id room-id
                                                     :block-id block-id} m)))]
         (if (= "app" (:kind record))
           (-> (resolve-resource mgr (:source record) (get-in record [:spec :resource-uri]))
               (.then (fn [{:keys [html]}] (reply {:html html})))
               (.catch (fn [e] (reply {:error (.-message e)}))))
           (reply {:error "no such app view"}))))

     :ext.blocks/app-rpc-reply
     (fn [ctx {:keys [client-id room-id block-id rpc-id] :as req}]
       (-> (rpc mgr ask! ctx req)
           (.then (fn [answer]
                    (send! client-id (merge {:type :ext.blocks/app-reply :room-id room-id
                                             :block-id block-id :rpc-id rpc-id}
                                            answer))))))}))

(defn- client-request
  "A browser's request about one of its room's app views, answered by
   `server-fx` to that client only."
  [fx-type]
  (fn [_st {:keys [client-id] :as ev}]
    (when client-id
      {:effects [[fx-type (dissoc ev :type)]]})))

(def handlers
  {:ext.blocks/app-open h/app-open
   :ext.blocks/app-load (client-request :ext.blocks/app-load-reply)
   :ext.blocks/app-rpc  (client-request :ext.blocks/app-rpc-reply)})

(def no-broadcast
  "Requests and their raw payloads stay off the room's clients; the block
   they lead to is broadcast as :ext.blocks/add."
  #{:ext.blocks/app-open :ext.blocks/app-load :ext.blocks/app-rpc})
