(ns xi.api.mcp
  "Rules-gated calls to the MCP servers configured in ~/.config/xi/mcp.edn,
   for user extensions.

     (call ctx :chrome \"evaluate_script\" {:function \"() => document.title\"})
     → Promise<{:content [...] :is-error bool}>

   The call goes over the same connection the agent's `mcp__<server>__<tool>`
   tools use, so a stateful server (a browser) is shared, not started twice.
   Each call is the request `{:tool :mcp :mcp-server :mcp-tool :arguments}`
   tagged with the extension, decided like the agent's MCP calls: the default
   asks, `[a]lways` pins server + tool to this extension, and a rules.edn
   allow such as `{:match {:tool :mcp :mcp-server \"chrome\"} …}` covers both.
   A disabled or unknown server rejects.

   The server gets the room's cwd / id / driving client pid and the
   extension's id as `_meta` (xi.ext.mcp/call-meta). An optional trailing
   opts map takes :room-id, for an :fx that has its room only in its payload."
  (:require [clojure.string :as str]
            [xi.agent :as agent]
            [xi.api.core :as core]
            [xi.ext.mcp :as mcp]))

(defn- server-name [server]
  (if (keyword? server) (name server) (str server)))

(defn call
  "Call `tool` on MCP `server` (keyword or string) with `args` (a map)."
  ([ctx server tool] (call ctx server tool {} nil))
  ([ctx server tool args] (call ctx server tool args nil))
  ([ctx server tool args opts]
   (let [srv (server-name server)
         ctx (cond-> ctx (:room-id opts) (assoc :room-id (:room-id opts)))]
     (-> (js/Promise.resolve nil)
         (.then
          (fn [_]
            (when (or (str/blank? srv) (str/blank? (str tool)))
              (throw (ex-info "xi.api.mcp: (call ctx server tool args) needs a server and a tool" {})))
            (when-not (or (nil? args) (map? args))
              (throw (ex-info "xi.api.mcp: tool args must be a map" {})))
            (core/gate! ctx {:tool       :mcp
                             :tool-name  (mcp/qualify-name srv tool)
                             :mcp-server srv
                             :mcp-tool   (str tool)
                             :arguments  (or args {})})))
         (.then
          (fn [_]
            (let [{:keys [get-state room-id]} ctx]
              (mcp/call-tool! srv (str tool) (or args {})
                              {:cwd        (core/base-cwd ctx)
                               :room-id    room-id
                               :client-pid (when (and get-state room-id)
                                             (agent/room-client-pid (get-state) room-id))
                               :extension  (core/caller ctx)}))))))))
