// Tiny, dependency-free behaviour on top of the clj-ui-framework runtime
// (/js/ui-runtime.js, loaded first, provides window.__uiTheme):
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
})();
