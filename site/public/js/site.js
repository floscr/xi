// Tiny, dependency-free behaviour: docs sidebar toggle on small screens and a
// copy button on every code block.
(function () {
  var toggle = document.querySelector('.docs-nav-toggle');
  var sidebar = document.getElementById('docs-sidebar');
  if (toggle && sidebar) {
    toggle.addEventListener('click', function () {
      var open = sidebar.classList.toggle('open');
      toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
  }

  document.querySelectorAll('.code-block').forEach(function (block) {
    var bar = block.querySelector('.code-block-bar');
    var code = block.querySelector('code');
    if (!bar || !code || !navigator.clipboard) return;
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'code-copy';
    btn.textContent = 'Copy';
    btn.addEventListener('click', function () {
      navigator.clipboard.writeText(code.textContent).then(function () {
        btn.textContent = 'Copied';
        setTimeout(function () { btn.textContent = 'Copy'; }, 1500);
      });
    });
    bar.appendChild(btn);
  });
})();
