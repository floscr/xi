;; The deploys demo's MCP Apps view (resources/extensions/deploys.cljs): a plain
;; JSON-RPC client of the host over postMessage (SEP-1865), no SDK.
(ns deploys.map)

(def html
  "<!doctype html>
<html>
<head>
<meta charset='utf-8'>
<style>
  :root { color-scheme: light dark; }
  body { margin: 0; padding: 12px; font-family: var(--font-sans, system-ui); font-size: 14px;
         background: var(--color-background-primary, Canvas); color: var(--color-text-primary, CanvasText); }
  .envs { display: flex; gap: 12px; flex-wrap: wrap; }
  .env { flex: 1 1 140px; border: 1px solid var(--color-border-primary, #8884); border-radius: var(--border-radius-md, 8px);
         padding: 10px; cursor: pointer; }
  .env.sel { outline: 2px solid var(--color-ring-primary, Highlight); }
  .dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 6px; }
  .healthy { background: var(--color-text-success, green); }
  .degraded { background: var(--color-text-warning, orange); }
  .down { background: var(--color-text-danger, red); }
  .ver { font-family: var(--font-mono, monospace); color: var(--color-text-secondary, GrayText); }
  .bar { display: flex; gap: 8px; margin-top: 12px; flex-wrap: wrap; }
  button { font: inherit; padding: 4px 10px; border-radius: var(--border-radius-sm, 6px);
           border: 1px solid var(--color-border-primary, #8884); background: var(--color-background-secondary, ButtonFace);
           color: inherit; cursor: pointer; }
  #log { margin-top: 8px; color: var(--color-text-tertiary, GrayText); font-size: 12px; }
</style>
</head>
<body>
<div class='envs' id='envs'>Waiting for the tool result…</div>
<div class='bar'>
  <button id='refresh'>Refresh</button>
  <button id='ask'>Ask the agent</button>
  <button id='full'>Full screen</button>
  <button id='docs'>Open docs</button>
</div>
<div id='log'></div>
<script>
  let next = 1, pending = {}, envs = [], selected = null, mode = 'inline';
  const send = (method, params) => new Promise((resolve, reject) => {
    const id = next++; pending[id] = { resolve, reject };
    parent.postMessage({ jsonrpc: '2.0', id, method, params }, '*');
  });
  const notify = (method, params) => parent.postMessage({ jsonrpc: '2.0', method, params }, '*');
  const log = t => { document.getElementById('log').textContent = t; };
  function render() {
    const root = document.getElementById('envs');
    root.innerHTML = '';
    for (const e of envs) {
      const d = document.createElement('div');
      d.className = 'env' + (selected === e.name ? ' sel' : '');
      d.innerHTML = `<div><span class='dot ${e.status}'></span><b></b></div><div class='ver'></div>`;
      d.querySelector('b').textContent = e.name;
      d.querySelector('.ver').textContent = e.version + ' · ' + e.status;
      d.onclick = () => { selected = e.name; render(); };
      root.appendChild(d);
    }
  }
  function applyContext(ctx) {
    const vars = (ctx.styles || {}).variables || {};
    for (const k in vars) document.documentElement.style.setProperty(k, vars[k]);
    if (ctx.displayMode) mode = ctx.displayMode;
    document.getElementById('full').textContent = mode === 'fullscreen' ? 'Exit full screen' : 'Full screen';
  }
  window.addEventListener('message', e => {
    const m = e.data || {};
    if (m.id && pending[m.id]) {
      const p = pending[m.id]; delete pending[m.id];
      m.error ? p.reject(new Error(m.error.message)) : p.resolve(m.result);
    } else if (m.method === 'ui/notifications/tool-result') {
      envs = ((m.params || {}).structuredContent || {}).envs || [];
      render();
    } else if (m.method === 'ui/notifications/host-context-changed') {
      applyContext(m.params || {});
    }
  });
  document.getElementById('refresh').onclick = async () => {
    try {
      const r = await send('tools/call', { name: 'deploy_status', arguments: {} });
      envs = (r.structuredContent || {}).envs || envs; render();
      log('Refreshed at ' + new Date().toLocaleTimeString());
      send('ui/update-model-context', { content: [{ type: 'text', text: 'Selected environment: ' + (selected || 'none') }] });
    } catch (err) { log('Refresh failed: ' + err.message); }
  };
  document.getElementById('ask').onclick = () =>
    send('ui/message', { role: 'user', content: { type: 'text', text: 'What should I do about ' + (selected || 'prod') + '?' } });
  document.getElementById('full').onclick = async () => {
    const r = await send('ui/request-display-mode', { mode: mode === 'fullscreen' ? 'inline' : 'fullscreen' });
    applyContext({ displayMode: r.mode });
  };
  document.getElementById('docs').onclick = () =>
    send('ui/open-link', { url: 'https://modelcontextprotocol.io/extensions/apps/overview' }).catch(err => log(err.message));
  new ResizeObserver(() => notify('ui/notifications/size-changed',
    { width: document.body.scrollWidth, height: Math.ceil(document.body.getBoundingClientRect().height) })).observe(document.body);
  send('ui/initialize', { protocolVersion: '2026-01-26', clientInfo: { name: 'deploys-map', version: '1' },
                          appCapabilities: { availableDisplayModes: ['inline', 'fullscreen'] } })
    .then(r => { applyContext(r.hostContext || {}); notify('ui/notifications/initialized', {}); });
</script>
</body>
</html>
")
