// The home page's theming section (xisite.pages/theme-controls): preset chips,
// a hue slider and light/dark theme the whole site and every live preview.
// Light/dark is the framework runtime's stored choice (window.__uiTheme). A
// hue goes to the tour stage after the section as xi.web.theme params; its
// web client derives the CSS properties and stores them for the page and the
// other previews (xi.web.embed, site.js). The picked hue is kept so the
// chips and slider show it on the next visit.
(function () {
  var root = document.querySelector('[data-theme-demo]');
  var section = root && root.closest('section');
  var stage = section && section.nextElementSibling;
  var frame = stage && stage.querySelector('.stage-frame');
  if (!frame || !window.__uiTheme) return;

  var HUE_KEY = 'xi/site-theme-hue';
  var chips = root.querySelectorAll('[data-hue]');
  var slider = root.querySelector('[data-theme-hue]');
  var modes = root.querySelectorAll('[data-mode]');
  var stored = null;
  try { stored = localStorage.getItem(HUE_KEY); } catch (e) {}
  var hue = stored === null ? null : Number(stored);
  var pending = false; // a pick the stage hasn't loaded for yet

  // A page color and the gray/accent scales around one hue.
  function params(h) {
    if (h === null) return null;
    return {
      'bg-dark': 'oklch(0.23 0.06 ' + h + ')',
      'bg-light': 'oklch(0.985 0.012 ' + h + ')',
      'gray-hue': h,
      'gray-chroma': 0.6,
      'accent-hue': (h + 20) % 360,
      'accent-chroma': 0.9
    };
  }

  function send() {
    if (!frame.contentWindow || !frame.getAttribute('src')) return;
    frame.contentWindow.postMessage({ xiTheme: { params: params(hue) } }, location.origin);
    pending = false;
  }

  function mark() {
    Array.prototype.forEach.call(chips, function (chip) {
      var h = chip.getAttribute('data-hue');
      chip.classList.toggle('chip-active', h === '' ? hue === null : Number(h) === hue);
    });
    var mode = window.__uiTheme.effective();
    Array.prototype.forEach.call(modes, function (b) {
      b.classList.toggle('is-active', b.getAttribute('data-mode') === mode);
    });
  }

  function pick(h) {
    hue = h;
    try {
      if (h === null) localStorage.removeItem(HUE_KEY);
      else localStorage.setItem(HUE_KEY, String(h));
    } catch (e) {}
    pending = true;
    mark();
    send();
  }

  Array.prototype.forEach.call(chips, function (chip) {
    chip.addEventListener('click', function () {
      var h = chip.getAttribute('data-hue');
      if (h !== '') slider.value = h;
      pick(h === '' ? null : Number(h));
    });
  });
  slider.addEventListener('input', function () { pick(Number(slider.value)); });
  Array.prototype.forEach.call(modes, function (b) {
    b.addEventListener('click', function () { window.__uiTheme.set(b.getAttribute('data-mode')); });
  });
  window.__uiTheme.subscribe(mark);
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', mark);

  if (hue !== null) slider.value = String(hue);
  mark();
  // the stage loads lazily: a pick made before that is sent once it has
  frame.addEventListener('load', function () { if (pending) send(); });
})();
