;; The scene demo's MCP Apps view (resources/extensions/scene.cljs): a
;; three.js renderer of the scene the agent described, talking to Xi over
;; postMessage (JSON-RPC, SEP-1865). three.js comes from jsDelivr, the one
;; host its CSP allows (scene.cljs).
(ns scene.view)

(def html
  "<!doctype html>
<html>
<head>
<meta charset=\"utf-8\">
<style>
  html, body { margin: 0; height: 100%; overflow: hidden;
               background: var(--color-background-primary, #111); color: var(--color-text-primary, #eee);
               font-family: var(--font-sans, system-ui); font-size: 13px; }
  canvas { display: block; width: 100%; height: 100%; cursor: grab; }
  #bar { position: absolute; left: 8px; top: 8px; right: 8px; display: flex; gap: 6px; align-items: center; pointer-events: none; }
  #bar > * { pointer-events: auto; }
  button { font: inherit; padding: 3px 9px; border-radius: var(--border-radius-sm, 6px); cursor: pointer;
           border: 1px solid var(--color-border-primary, #8884); color: inherit;
           background: var(--color-background-secondary, #2228); }
  button:disabled { opacity: .5; cursor: default; }
  #info { opacity: .8; text-shadow: 0 0 4px var(--color-background-primary, #000); }
</style>
<script type=\"importmap\">
{\"imports\": {\"three\": \"https://cdn.jsdelivr.net/npm/three@0.170.0/build/three.module.js\",
             \"three/addons/\": \"https://cdn.jsdelivr.net/npm/three@0.170.0/examples/jsm/\"}}
</script>
<script>
  window.addEventListener('error', e => {
    const i = document.getElementById('info');
    if (i) i.textContent = 'The view failed: ' + (e.message || 'could not load three.js');
  });
</script>
</head>
<body>
<canvas id=\"c\"></canvas>
<div id=\"bar\">
  <button id=\"full\">Full screen</button>
  <button id=\"ask\" disabled>Ask about it</button>
  <span id=\"info\">Loading three.js…</span>
</div>
<script type=\"module\">
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

// ── the host (Xi) ──
let next = 1, mode = 'inline';
const pending = {};
const send = (method, params) => new Promise((ok, ko) => {
  const id = next++; pending[id] = { ok, ko };
  parent.postMessage({ jsonrpc: '2.0', id, method, params }, '*');
});
const notify = (method, params) => parent.postMessage({ jsonrpc: '2.0', method, params }, '*');
const info = t => { document.getElementById('info').textContent = t; };

// ── three.js ──
const canvas = document.getElementById('c');
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
// a phone's 3x screen would mean 9x the pixels for every frame; 2x looks the same
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(50, 1, 0.1, 1000);
camera.position.set(6, 5, 8);
const controls = new OrbitControls(camera, canvas);
controls.enableDamping = true;

// Draw on demand: while the camera moves (damping included) and when the
// scene, size or selection changes, never in an idle loop.
let frame = null;
const render = () => { frame = null; controls.update(); renderer.render(scene, camera); };
const requestRender = () => { if (frame === null) frame = requestAnimationFrame(render); };
controls.addEventListener('change', requestRender);
scene.add(new THREE.HemisphereLight(0xffffff, 0x444444, 1.2));
const sun = new THREE.DirectionalLight(0xffffff, 1.5);
sun.position.set(5, 10, 7);
scene.add(sun);
const grid = new THREE.GridHelper(20, 20, 0x888888, 0x444444);
grid.material.opacity = 0.35; grid.material.transparent = true;
scene.add(grid);
const group = new THREE.Group();
scene.add(group);

const geometry = (shape, s) => {
  const [w, h, d] = Array.isArray(s) ? s : [s, s, s];
  switch (shape) {
    case 'sphere':    return new THREE.SphereGeometry(w / 2, 32, 16);
    case 'torus':     return new THREE.TorusGeometry(w / 2, w / 6, 16, 48);
    case 'torusKnot': return new THREE.TorusKnotGeometry(w / 3, w / 10, 96, 12);
    case 'cone':      return new THREE.ConeGeometry(w / 2, h, 32);
    case 'cylinder':  return new THREE.CylinderGeometry(w / 2, w / 2, h, 32);
    default:          return new THREE.BoxGeometry(w, h, d);
  }
};

function build(spec) {
  group.clear();
  for (const o of (spec.objects || [])) {
    const mesh = new THREE.Mesh(geometry(o.shape, o.size ?? 1),
                                new THREE.MeshStandardMaterial({ color: o.color || '#8ab4f8', roughness: 0.5 }));
    const [x, y, z] = o.position || [0, 0, 0];
    mesh.position.set(x, y, z);
    mesh.userData.label = o.label || o.shape || 'object';
    group.add(mesh);
  }
  if (spec.background) scene.background = new THREE.Color(spec.background);
  const box = new THREE.Box3().setFromObject(group);
  if (!box.isEmpty()) {
    const c = box.getCenter(new THREE.Vector3()), r = box.getSize(new THREE.Vector3()).length() || 4;
    controls.target.copy(c);
    camera.position.copy(c).add(new THREE.Vector3(r, r * 0.8, r * 1.2));
  }
  info(`${group.children.length} object${group.children.length === 1 ? '' : 's'} · drag to orbit, scroll to zoom, click one`);
  requestRender();
}

// ── picking ──
let selected = null;
const ray = new THREE.Raycaster(), pointer = new THREE.Vector2();
let downAt = null;
canvas.addEventListener('pointerdown', e => { downAt = [e.clientX, e.clientY]; });
canvas.addEventListener('pointerup', e => {
  if (!downAt || Math.hypot(e.clientX - downAt[0], e.clientY - downAt[1]) > 4) return; // a drag, not a click
  const r = canvas.getBoundingClientRect();
  pointer.set(((e.clientX - r.left) / r.width) * 2 - 1, -((e.clientY - r.top) / r.height) * 2 + 1);
  ray.setFromCamera(pointer, camera);
  const hit = ray.intersectObjects(group.children)[0];
  if (selected) { selected.material.emissive.setHex(0x000000); requestRender(); }
  selected = hit ? hit.object : null;
  document.getElementById('ask').disabled = !selected;
  if (selected) {
    selected.material.emissive.setHex(0x333333);
    requestRender();
    info('Selected: ' + selected.userData.label);
    send('ui/update-model-context',
         { content: [{ type: 'text', text: 'In the 3D view the user selected: ' + selected.userData.label }] });
  }
});
document.getElementById('ask').onclick = () => selected &&
  send('ui/message', { role: 'user', content: { type: 'text', text: 'Tell me about the ' + selected.userData.label + ' in the scene.' } });
document.getElementById('full').onclick = async () => {
  const r = await send('ui/request-display-mode', { mode: mode === 'fullscreen' ? 'inline' : 'fullscreen' });
  setMode(r.mode);
};
function setMode(m) {
  mode = m || mode;
  document.getElementById('full').textContent = mode === 'fullscreen' ? 'Exit full screen' : 'Full screen';
}

// ── size, theme, loop ──
function fit() {
  const w = document.body.clientWidth, h = document.body.clientHeight;
  renderer.setSize(w, h, false);
  camera.aspect = w / Math.max(h, 1);
  camera.updateProjectionMatrix();
  requestRender();
}
new ResizeObserver(fit).observe(document.body);
function applyContext(ctx) {
  const vars = (ctx.styles || {}).variables || {};
  for (const k in vars) document.documentElement.style.setProperty(k, vars[k]);
  if (ctx.displayMode) setMode(ctx.displayMode);
}

window.addEventListener('message', e => {
  const m = e.data || {};
  if (m.id && pending[m.id]) {
    const p = pending[m.id]; delete pending[m.id];
    m.error ? p.ko(m.error) : p.ok(m.result);
  } else if (m.method === 'ui/notifications/tool-result') {
    build(((m.params || {}).structuredContent || {}).scene || {});
  } else if (m.method === 'ui/notifications/host-context-changed') {
    applyContext(m.params || {});
  }
});

const r = await send('ui/initialize', { protocolVersion: '2026-01-26', clientInfo: { name: 'scene', version: '1' },
                                        appCapabilities: { availableDisplayModes: ['inline', 'fullscreen'] } });
applyContext(r.hostContext || {});
notify('ui/notifications/size-changed', { width: document.body.clientWidth, height: 420 });
notify('ui/notifications/initialized', {});
fit();
</script>
</body>
</html>
")
