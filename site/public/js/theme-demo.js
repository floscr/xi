// The home page's theming section (xisite.pages/theme-controls): preset chips,
// a hue slider and light/dark drive the tour stage right after the section.
// Its web client applies the theme live (xi.web.embed); the params are
// xi.web.theme's, built here from one hue.
(function () {
  var root = document.querySelector('[data-theme-demo]');
  var section = root && root.closest('section');
  var stage = section && section.nextElementSibling;
  var frame = stage && stage.querySelector('.stage-frame');
  if (!frame) return;

  var chips = root.querySelectorAll('[data-hue]');
  var slider = root.querySelector('[data-theme-hue]');
  var modes = root.querySelectorAll('[data-mode]');
  var state = { hue: null, mode: null, touched: false };

  // A page color and the gray/accent scales around one hue.
  function params(hue) {
    if (hue === null) return null;
    return {
      'bg-dark': 'oklch(0.23 0.06 ' + hue + ')',
      'bg-light': 'oklch(0.985 0.012 ' + hue + ')',
      'gray-hue': hue,
      'gray-chroma': 0.6,
      'accent-hue': (hue + 20) % 360,
      'accent-chroma': 0.9
    };
  }

  function send() {
    if (!state.touched || !frame.contentWindow) return;
    frame.contentWindow.postMessage(
      { xiTheme: { params: params(state.hue), mode: state.mode } },
      location.origin);
  }

  function mark() {
    Array.prototype.forEach.call(chips, function (chip) {
      var hue = chip.getAttribute('data-hue');
      chip.classList.toggle('chip-active',
        hue === '' ? state.hue === null : Number(hue) === state.hue);
    });
    Array.prototype.forEach.call(modes, function (b) {
      b.classList.toggle('is-active', b.getAttribute('data-mode') === state.mode);
    });
  }

  function update(change) {
    for (var k in change) state[k] = change[k];
    state.touched = true;
    mark();
    send();
  }

  Array.prototype.forEach.call(chips, function (chip) {
    chip.addEventListener('click', function () {
      var hue = chip.getAttribute('data-hue');
      if (hue !== '') slider.value = hue;
      update({ hue: hue === '' ? null : Number(hue) });
    });
  });
  slider.addEventListener('input', function () { update({ hue: Number(slider.value) }); });
  Array.prototype.forEach.call(modes, function (b) {
    b.addEventListener('click', function () { update({ mode: b.getAttribute('data-mode') }); });
  });

  // The page's own light/dark marks the starting mode; the stage loads lazily
  // and Replay reloads it, so every load gets the current theme again.
  var dark = document.documentElement.getAttribute('data-theme') === 'dark' ||
    (!document.documentElement.getAttribute('data-theme') &&
     window.matchMedia('(prefers-color-scheme: dark)').matches);
  state.mode = dark ? 'dark' : 'light';
  mark();
  frame.addEventListener('load', send);
})();
