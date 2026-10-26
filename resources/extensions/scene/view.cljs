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

// The camera's start: the scene's center and size, framed again on every
// resize until someone moves the camera. A narrow frame (a phone) fits the
// scene's width, not its height, so it backs off.
let home = null, moved = false;
controls.addEventListener('start', () => { moved = true; });
function frameScene() {
  if (!home) return;
  const k = Math.max(1, 1.2 / camera.aspect);
  camera.position.copy(home.c).add(new THREE.Vector3(home.r * 0.6, home.r * 0.5, home.r * 0.8).multiplyScalar(k));
}

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
    home = { c, r }; moved = false;
    frameScene();
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
  pick(e.clientX, e.clientY);
});
function pick(clientX, clientY) {
  const r = canvas.getBoundingClientRect();
  pointer.set(((clientX - r.left) / r.width) * 2 - 1, -((clientY - r.top) / r.height) * 2 + 1);
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
}
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
  if (!moved) frameScene();
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
  } else if (m.xiTour && e.source === parent) {
    tour(m);
  }
});

// ── site tours ──
// The tour's autopilot (xi.web.tour) can't reach into this opaque-origin
// frame: it asks where an object (by label) or a button (by selector) is,
// moves its pointer there and asks for a click. Plain messages, not JSON-RPC,
// so Xi's bridge ignores them.
function locate({ label, selector }) {
  if (!group.children.length) return null; // the scene isn't drawn yet
  if (selector) {
    const r = document.querySelector(selector)?.getBoundingClientRect();
    return r && { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  }
  const o = group.children.find(c => c.userData.label === label);
  if (!o) return null;
  const v = new THREE.Box3().setFromObject(o).getCenter(new THREE.Vector3()).project(camera);
  const r = canvas.getBoundingClientRect();
  return { x: r.left + (v.x + 1) / 2 * r.width, y: r.top + (1 - v.y) / 2 * r.height };
}
function orbit(angle, ms) {
  moved = true;
  const offset = camera.position.clone().sub(controls.target), t0 = performance.now();
  const step = now => {
    const k = Math.min(1, (now - t0) / ms), ease = k * k * (3 - 2 * k);
    camera.position.copy(controls.target).add(offset.clone().applyAxisAngle(new THREE.Vector3(0, 1, 0), angle * ease));
    requestRender();
    if (k < 1) requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
}
function tour(m) {
  if (m.xiTour === 'locate') {
    parent.postMessage({ xiTour: 'located', id: m.id, at: locate(m) }, '*');
  } else if (m.xiTour === 'click') {
    const el = document.elementFromPoint(m.x, m.y);
    if (el === canvas) pick(m.x, m.y); else el?.click();
  } else if (m.xiTour === 'orbit') {
    orbit(m.angle, m.ms || 1500);
  }
}

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
