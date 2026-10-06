// Tiny, dependency-free behaviour on top of the clj-ui-framework runtime
// (/js/ui-runtime.js, loaded first, provides window.__uiTheme):
//   - wires the framework's theme toggle (light / auto / dark),
//   - copy buttons on code blocks,
//   - the docs sidebar toggle on small screens.
(function () {
  // ---- theme toggle -------------------------------------------------------
  // The framework renders the toggle's markup; the page connects it to the
  // runtime's __uiTheme (which persists the choice and sets data-theme).
  var theme = window.__uiTheme;
  var toggle = document.querySelector('.theme-toggle');
  if (theme && toggle) {
    theme.init();
    var buttons = Array.prototype.slice.call(toggle.querySelectorAll('.theme-toggle-btn'));
    var modeOf = function (btn) {
      var label = (btn.getAttribute('aria-label') || '').toLowerCase();
      return label.indexOf('light') === 0 ? 'light' : label.indexOf('dark') === 0 ? 'dark' : 'auto';
    };
    var paint = function (mode) {
      buttons.forEach(function (btn) {
        var on = modeOf(btn) === mode;
        btn.classList.toggle('theme-toggle-btn-active', on);
        btn.setAttribute('aria-checked', on ? 'true' : 'false');
      });
    };
    paint(theme.get());
    buttons.forEach(function (btn) {
      btn.addEventListener('click', function () {
        var mode = modeOf(btn);
        theme.set(mode);
        paint(mode);
      });
    });
  }

  // ---- docs sidebar -------------------------------------------------------
  var navToggle = document.querySelector('.docs-nav-toggle');
  var sidebar = document.getElementById('docs-sidebar');
  if (navToggle && sidebar) {
    navToggle.addEventListener('click', function () {
      var open = sidebar.classList.toggle('open');
      navToggle.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
  }

  // ---- code block copy ----------------------------------------------------
  document.querySelectorAll('.code-block').forEach(function (block) {
    var btn = block.querySelector('.code-copy');
    var code = block.querySelector('code');
    if (!btn || !code) return;
    if (!navigator.clipboard) { btn.hidden = true; return; }
    var label = btn.lastChild; // the button's text node, after the icon
    btn.addEventListener('click', function () {
      navigator.clipboard.writeText(code.textContent).then(function () {
        var before = label.textContent;
        label.textContent = 'Copied';
        setTimeout(function () { label.textContent = before; }, 1500);
      });
    });
  });
})();
