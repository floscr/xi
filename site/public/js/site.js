// Tiny, dependency-free behaviour on top of the clj-ui-framework runtime
// (/js/ui-runtime.js, loaded first, provides window.__uiTheme):
//   - copy buttons on code blocks,
//   - the docs sidebar toggle on small screens.
(function () {
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
