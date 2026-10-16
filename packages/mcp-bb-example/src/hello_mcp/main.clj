(ns hello-mcp.main
  "Example babashka MCP server for xi: two small tools on top of mcp.server.

     git_log   recent commits of the repo xi's room is in — the directory
               comes from `_meta` \"xi/cwd\", not from a tool argument
     uuid      a random UUID
     uuids     a few UUIDs, with an MCP Apps view (ui://hello-mcp/uuids) whose
               Another button calls uuid through xi

   Register it in ~/.config/xi/mcp.edn:
     {:bb-example {:command \"bb\"
                   :args [\"--config\" \"/path/to/xi/packages/mcp-bb-example/bb.edn\"
                          \"-m\" \"hello-mcp.main\"]}}
   then `/mcp refresh bb-example`."
  (:require [babashka.process :as process]
            [clojure.string :as str]
            [mcp.server :as server]))

(defn- git-log [{:keys [n]} meta]
  (let [cwd (get meta "xi/cwd")
        n   (max 1 (min 50 (or (some-> n long) 10)))]
    (if-not cwd
      {:content [{:type "text" :text "git_log: no xi/cwd in _meta — which repo?"}] :isError true}
      (let [{:keys [exit out err]} (process/shell {:dir cwd :out :string :err :string :continue true}
                                                  "git" "log" "--oneline" (str "-" n))]
        (if (zero? exit)
          (str "Last " n " commits in " cwd ":\n" (str/trimr out))
          {:content [{:type "text" :text (str "git_log: " (str/trim err))}] :isError true})))))

(def ^:private uuids-view
  "<!doctype html>
<html><head><meta charset='utf-8'>
<style>
  body { margin: 0; padding: 12px; font-family: var(--font-sans, system-ui);
         color: var(--color-text-primary, CanvasText); background: var(--color-background-primary, Canvas); }
  code { font-family: var(--font-mono, monospace); }
  li { margin: 2px 0; }
  button { font: inherit; padding: 4px 10px; border-radius: 6px; cursor: pointer;
           border: 1px solid var(--color-border-primary, #8884); background: transparent; color: inherit; }
</style></head>
<body>
<ul id='ids'></ul>
<button id='more'>Another</button>
<script>
  let next = 1; const pending = {};
  const send = (method, params) => new Promise((ok, ko) => {
    const id = next++; pending[id] = { ok, ko };
    parent.postMessage({ jsonrpc: '2.0', id, method, params }, '*');
  });
  const notify = (method, params) => parent.postMessage({ jsonrpc: '2.0', method, params }, '*');
  const add = id => { const li = document.createElement('li'); li.innerHTML = '<code></code>';
                      li.firstChild.textContent = id; document.getElementById('ids').appendChild(li); };
  window.addEventListener('message', e => {
    const m = e.data || {};
    if (m.id && pending[m.id]) { const p = pending[m.id]; delete pending[m.id]; m.error ? p.ko(m.error) : p.ok(m.result); }
    else if (m.method === 'ui/notifications/tool-result') ((m.params.structuredContent || {}).uuids || []).forEach(add);
  });
  document.getElementById('more').onclick = () =>
    send('tools/call', { name: 'uuid', arguments: {} }).then(r => add(r.content[0].text));
  new ResizeObserver(() => notify('ui/notifications/size-changed',
    { width: document.body.scrollWidth, height: Math.ceil(document.body.getBoundingClientRect().height) })).observe(document.body);
  send('ui/initialize', { protocolVersion: '2026-01-26', clientInfo: { name: 'uuids', version: '1' }, appCapabilities: {} })
    .then(r => { const v = (r.hostContext.styles || {}).variables || {};
                 for (const k in v) document.documentElement.style.setProperty(k, v[k]);
                 notify('ui/notifications/initialized', {}); });
</script>
</body></html>
")

(def resources
  [{:uri      "ui://hello-mcp/uuids"
    :name     "uuids"
    :mimeType "text/html;profile=mcp-app"
    :text     uuids-view
    :_meta    {:ui {:prefersBorder true}}}])

(def tools
  [{:name        "git_log"
    :description "Recent commits (one line each) of the git repo the current xi room is in."
    :inputSchema {:type "object"
                  :properties {"n" {:type "number" :description "How many commits (1-50, default 10)."}}}
    :handler     git-log}
   {:name        "uuid"
    :description "A random UUID."
    :inputSchema {:type "object" :properties {}}
    :handler     (fn [_ _] (str (random-uuid)))}
   {:name        "uuids"
    :description "A few random UUIDs, shown as a list the user can add to."
    :inputSchema {:type "object"
                  :properties {"n" {:type "number" :description "How many (1-20, default 3)."}}}
    :_meta       {:ui {:resourceUri "ui://hello-mcp/uuids"}}
    :handler     (fn [{:keys [n]} _]
                   (let [ids (vec (repeatedly (max 1 (min 20 (or (some-> n long) 3))) #(str (random-uuid))))]
                     {:content           [{:type "text" :text (str/join "\n" ids)}]
                      :structuredContent {:uuids ids}}))}])

(defn -main [& _]
  (server/serve! {:name "hello-mcp" :version "0.1.0" :tools tools :resources resources}))
