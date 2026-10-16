(ns xi.e2e.app-views
  "The fixture of the app-view scenarios (xi.e2e.app-views-e2e): `orbit`, a
   user extension shaped like a three.js scene viewer. `orbit_show` shows a
   view (ui://orbit/view) of the objects it gets; `orbit_pick` is app-only,
   callable from that view alone.

   The view is a probe: a WebGL scene drawn with shaders (three.js's work,
   without the CDN, so the scenarios stay offline) that checks its sandbox
   from the inside: its origin, whether it reaches the page, storage, cookies
   or the network past its CSP. It then calls its app-only tool and a foreign
   one, and reports everything as `PROBE {json}` through
   ui/update-model-context, which the host notes on the block. Last it sends
   a ui/message and a ui/open-link, which the browser host must not act on
   without the user.")

(def probe-html
  "<!doctype html><html><head><meta charset='utf-8'></head>
<body style='margin:0'><canvas id='c' width='64' height='64'></canvas>
<script>
const report = { csp: [] };
let next = 1; const pending = {};
const send = (method, params) => new Promise((ok, ko) => {
  const id = next++; pending[id] = { ok, ko };
  parent.postMessage({ jsonrpc: '2.0', id, method, params }, '*');
});
const notify = (method, params) => parent.postMessage({ jsonrpc: '2.0', method, params }, '*');
document.addEventListener('securitypolicyviolation', e => report.csp.push(e.effectiveDirective || e.violatedDirective));
report.origin = String(window.origin);
try { report.parentDoc = !!parent.document.body; } catch (e) { report.parentDoc = false; }
try { localStorage.getItem('x'); report.storage = true; } catch (e) { report.storage = false; }
try { document.cookie = 'probe=1'; report.cookie = document.cookie.includes('probe=1'); } catch (e) { report.cookie = false; }
const fetched = fetch('https://example.com/probe').then(() => 'ok', () => 'blocked').then(v => { report.fetch = v; });
const gl = document.getElementById('c').getContext('webgl', { preserveDrawingBuffer: true });
report.webgl = !!gl;
if (gl) {
  const shader = (type, src) => { const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s); return s; };
  const pr = gl.createProgram();
  gl.attachShader(pr, shader(gl.VERTEX_SHADER, 'attribute vec2 p; void main() { gl_Position = vec4(p, 0.0, 1.0); }'));
  gl.attachShader(pr, shader(gl.FRAGMENT_SHADER, 'precision mediump float; void main() { gl_FragColor = vec4(0.545, 0.361, 0.965, 1.0); }'));
  gl.linkProgram(pr); gl.useProgram(pr);
  gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
  const loc = gl.getAttribLocation(pr, 'p');
  gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
  gl.drawArrays(gl.TRIANGLES, 0, 3);
  const px = new Uint8Array(4); gl.readPixels(32, 32, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
  report.pixel = Array.from(px);
}
window.addEventListener('message', async e => {
  const m = e.data || {};
  if (m.id && pending[m.id]) { const p = pending[m.id]; delete pending[m.id]; m.error ? p.ko(m.error) : p.ok(m.result); return; }
  if (m.method === 'ui/notifications/tool-input') report.input = m.params.arguments;
  if (m.method !== 'ui/notifications/tool-result') return;
  const objects = ((m.params.structuredContent || {}).scene || {}).objects || [];
  report.objects = objects;
  try { const r = await send('tools/call', { name: 'orbit_pick', arguments: { label: objects[0] } });
        report.pick = r.structuredContent.picked; } catch (err) { report.pick = 'error ' + err.code; }
  try { await send('tools/call', { name: 'write', arguments: { path: 'x', content: 'y' } }); report.foreign = 'allowed'; }
  catch (err) { report.foreign = err.code; }
  await fetched;
  await new Promise(r => setTimeout(r, 200));
  await send('ui/update-model-context', { content: [{ type: 'text', text: 'PROBE ' + JSON.stringify(report) }] });
  await send('ui/message', { role: 'user', content: { type: 'text', text: 'Tell me about the ' + objects[0] } });
  send('ui/open-link', { url: 'https://example.com/docs' }).then(() => {}, () => {});
});
send('ui/initialize', { protocolVersion: '2026-01-26', clientInfo: { name: 'probe', version: '1' }, appCapabilities: {} })
  .then(r => {
    report.host = r.hostInfo.name; report.theme = r.hostContext.theme;
    report.vars = Object.keys((r.hostContext.styles || {}).variables || {}).length;
    notify('ui/notifications/initialized', {});
  });
</script></body></html>")

(def extension-source
  (str "(ns orbit)\n\n(def html " (pr-str probe-html) ")\n\n"
       (pr-str
        '(def extension
           {:id :orbit
            :ui-resources {"ui://orbit/view" {:html html :csp {:resourceDomains ["https://cdn.jsdelivr.net"]}}
                           "ui://somebody-else/view" "<p>not ours</p>"}
            :tool-definitions [{:name "orbit_show" :description "Show objects in a 3D view."
                                :input_schema {:type "object" :properties {:objects {:type "array"}}}
                                :ui {:resource-uri "ui://orbit/view"}}
                               {:name "orbit_pick" :description "The view picked an object."
                                :input_schema {:type "object" :properties {:label {:type "string"}}}
                                :ui {:visibility ["app"]}}]
            :tool-registry {"orbit_show" (fn [{:keys [objects]} _]
                                           {:content [{:type "text" :text (str (count objects) " objects")}]
                                            :structured-content {:scene {:objects objects}}})
                            "orbit_pick" (fn [{:keys [label]} _]
                                           {:content [{:type "text" :text (str "picked " label)}]
                                            :structured-content {:picked label}})}}))
       "\n"))

(defn profile
  "~/.config/xi with the orbit extension enabled and `rules` (default none)."
  ([] (profile []))
  ([rules]
   {".config/xi/config.edn" (pr-str {:type :xi/config :version 1 :extensions ["orbit.cljs"]})
    ".config/xi/rules.edn" (pr-str {:type :xi/rules :version 1 :rules rules})
    ".config/xi/extensions/orbit.cljs" extension-source}))
