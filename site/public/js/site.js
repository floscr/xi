// Tiny, dependency-free behaviour on top of the clj-ui-framework runtime
// (/js/ui-runtime.js, loaded first, provides window.__uiTheme):
//   - the shared theme: follow a change made in another tab or a preview
//   - the docs sidebar toggle on small screens
//   - the tour stage on the home page: scaling and Replay
//   - the release videos on /changelog/: tours played as slides
//   - the live phones: scaling, starting together, looping.
(function () {
  // ---- shared theme --------------------------------------------------------
  // The page and its previews (/tour/, xi.web.embed) keep one theme in
  // localStorage: the light/dark choice and a custom theme's CSS properties.
  // The `storage` event reports a change made by any other of them;
  // xisite.theme/head-script applies both before first paint.
  var THEME_KEY = 'ui-theme';
  var VARS_KEY = 'xi/theme-vars';
  var applyVars = function () {
    var style = document.documentElement.style;
    var vars = null;
    try { vars = JSON.parse(localStorage.getItem(VARS_KEY) || 'null'); } catch (e) {}
    // <html> carries no custom properties but the theme's
    for (var i = style.length - 1; i >= 0; i--) {
      if (style[i].indexOf('--') === 0) style.removeProperty(style[i]);
    }
    for (var k in vars || {}) style.setProperty(k, vars[k]);
  };
  window.addEventListener('storage', function (e) {
    if (e.key === THEME_KEY && window.__uiTheme) window.__uiTheme.set(e.newValue || 'auto');
    else if (e.key === VARS_KEY) applyVars();
  });

  // ---- docs sidebar -------------------------------------------------------
  var navToggle = document.querySelector('.docs-nav-toggle');
  var sidebar = document.getElementById('docs-sidebar');
  if (navToggle && sidebar) {
    navToggle.addEventListener('click', function () {
      var open = sidebar.classList.toggle('open');
      navToggle.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
  }

  // ---- copy buttons ---------------------------------------------------------
  // [data-copy] (the hero's install bar): copy the value, say so in the
  // button's label (its last text node, after the icon) for a moment.
  // With [data-run] the button also grows into a terminal line over its
  // parent bar and types that command (main.css, .hero-term); the line stays
  // open while the pointer is over the bar.
  var reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  var runTerminal = function (btn, cmd) {
    var bar = btn.parentNode;
    var term = document.createElement('span');
    term.className = 'hero-term';
    term.setAttribute('aria-hidden', 'true');
    term.innerHTML = '<span class="hero-term-prompt">$</span><span class="hero-term-cmd"></span>' +
      '<span class="hero-term-caret"></span><span class="hero-term-note">Copied</span>';
    bar.appendChild(term);
    var typed = term.querySelector('.hero-term-cmd');
    var timers = [];
    var later = function (ms, f) { timers.push(setTimeout(f, ms)); };
    var closeTimer;
    var typedOut = false;
    var closeSoon = function (ms) {
      clearTimeout(closeTimer);
      closeTimer = setTimeout(function () { bar.classList.remove('is-running'); }, ms);
    };
    bar.addEventListener('mouseenter', function () { clearTimeout(closeTimer); });
    bar.addEventListener('mouseleave', function () { if (typedOut) closeSoon(900); });
    return function () {
      timers.forEach(clearTimeout);
      timers = [];
      clearTimeout(closeTimer);
      typedOut = false;
      // the clip starts from the button's current width: flush it first
      bar.style.setProperty('--copy-w', btn.offsetWidth + 'px');
      void term.offsetWidth;
      typed.textContent = '';
      bar.classList.add('is-running');
      var instant = reducedMotion.matches;
      var start = instant ? 0 : 420;
      var step = 45;
      if (instant) typed.textContent = cmd;
      else cmd.split('').forEach(function (ch, i) {
        later(start + i * step + Math.random() * 20, function () { typed.textContent += ch; });
      });
      later(start + (instant ? 0 : cmd.length * step + 20), function () {
        typed.textContent = cmd;
        typedOut = true;
        if (!bar.matches(':hover')) closeSoon(2600);
      });
    };
  };
  document.querySelectorAll('[data-copy]').forEach(function (btn) {
    // Icon-only buttons have no text label to swap for "Copied".
    var label = btn.lastChild && btn.lastChild.nodeType === 3 ? btn.lastChild : null;
    var text = label && label.nodeValue;
    var run = btn.hasAttribute('data-run') && runTerminal(btn, btn.getAttribute('data-run'));
    var timer;
    btn.addEventListener('click', function () {
      if (!navigator.clipboard) return;
      navigator.clipboard.writeText(btn.getAttribute('data-copy')).then(function () {
        if (run) run();
        if (!label) return;
        label.nodeValue = 'Copied';
        clearTimeout(timer);
        timer = setTimeout(function () { label.nodeValue = text; }, 1600);
      });
    });
  });

  // ---- tour prefetch -------------------------------------------------------
  // The tour iframes below load only once in view; fetch what they need while
  // the page sits idle, so they start from cache (~1.7 MB before gzip). Once
  // one frame has started, its own load fills the cache for the rest.
  var tourFrames = Array.prototype.slice.call(document.querySelectorAll('.stage-frame, .phone-frame'));
  var tourSrcs = tourFrames.map(function (f) { return f.getAttribute('data-src'); })
    .concat(Array.prototype.map.call(document.querySelectorAll('[data-slide-src]'),
                                     function (c) { return c.getAttribute('data-slide-src'); }));
  if (tourFrames.length) {
    var prefetch = function () {
      if (tourFrames.some(function (f) { return f.getAttribute('src'); })) return;
      var urls = ['/tour/main.js', '/tour/style.css', '/tour/theme.css',
                  '/tour/ui-runtime.js', '/css/tour.css'];
      tourSrcs.forEach(function (src) {
        var tape = new URL(src, location.href).searchParams.get('tape');
        if (tape && tape.indexOf('/') < 0) urls.push('/tours/' + tape + '.json');
      });
      urls.filter(function (u, i) { return urls.indexOf(u) === i; }).forEach(function (u) {
        var link = document.createElement('link');
        link.rel = 'prefetch';
        link.href = u;
        document.head.appendChild(link);
      });
    };
    var idle = window.requestIdleCallback || function (f) { setTimeout(f, 200); };
    var whenIdle = function () { idle(prefetch); };
    if (document.readyState === 'complete') whenIdle();
    else window.addEventListener('load', whenIdle);
  }

  // ---- tour stage ----------------------------------------------------------
  // The iframe renders the web client at 1280x800 (xisite.pages/stage);
  // scale it to the frame. It loads (and the tour starts) once the stage is
  // mostly in view, and posts {xiTour: 'done'} at its end. A stage with a
  // data-phone-src plays that phone variant on small screens instead: a
  // 390x844 phone frame that loops (no Replay bar).
  var TOUR_WIDTH = 1280;
  var PHONE_WIDTH = 390;
  var STAGE_PHONE_QUERY = '(max-width: 820px)';
  var fitStage = function (viewport) {
    var fit = function () {
      viewport.style.setProperty('--stage-scale', String(viewport.clientWidth / TOUR_WIDTH));
    };
    fit();
    if (window.ResizeObserver) new ResizeObserver(fit).observe(viewport);
    else window.addEventListener('resize', fit);
  };
  var whenInView = function (el, start) {
    if (!window.IntersectionObserver) return start();
    var seen = new IntersectionObserver(function (entries) {
      if (entries.some(function (e) { return e.isIntersecting; })) { seen.disconnect(); start(); }
    }, { threshold: 0.4 });
    seen.observe(el);
  };
  Array.prototype.forEach.call(document.querySelectorAll('.stage .stage-window'), function (win) {
    var viewport = win.querySelector('.stage-viewport');
    var frame = win.querySelector('.stage-frame');
    var replay = win.querySelector('.stage-replay');
    var phoneSrc = frame.getAttribute('data-phone-src');
    var phone = !!phoneSrc && window.matchMedia(STAGE_PHONE_QUERY).matches;
    var width = phone ? PHONE_WIDTH : TOUR_WIDTH;
    var src = phone ? phoneSrc : frame.getAttribute('data-src');
    win.classList.toggle('stage-window--phone', phone);
    var start = function () { if (!frame.getAttribute('src')) frame.src = src; };
    if (window.IntersectionObserver) {
      var seen = new IntersectionObserver(function (entries) {
        if (entries.some(function (e) { return e.isIntersecting; })) { seen.disconnect(); start(); }
      }, { threshold: 0.4 });
      seen.observe(viewport);
    } else start();
    var fit = function () {
      viewport.style.setProperty('--stage-scale', String(viewport.clientWidth / width));
    };
    fit();
    if (window.ResizeObserver) new ResizeObserver(fit).observe(viewport);
    else window.addEventListener('resize', fit);
    window.addEventListener('message', function (e) {
      if (e.source !== frame.contentWindow || !e.data || e.data.xiTour !== 'done') return;
      if (phone) setTimeout(function () { frame.src = src; }, 5000);
      else replay.hidden = false;
    });
    replay.addEventListener('click', function () {
      replay.hidden = true;
      // the client pushed /chat/<id> into the frame's history: start over at src
      frame.src = src;
    });
  });

  // ---- release videos ------------------------------------------------------
  // A .slideshow (xisite.pages/slideshow) plays its slides' tours in one
  // stage, each opening on its title over the viewport, and moves on a
  // moment after a tour posts done; Replay after the last. A chip jumps to
  // its slide.
  var SLIDE_PAUSE = 2500;
  Array.prototype.forEach.call(document.querySelectorAll('.slideshow'), function (show) {
    var viewport = show.querySelector('.stage-viewport');
    var frame = show.querySelector('.stage-frame');
    var replay = show.querySelector('.stage-replay');
    var title = show.querySelector('.slideshow-title');
    var chips = Array.prototype.slice.call(show.querySelectorAll('[data-slide-src]'));
    var current = 0;
    var next = null;
    var titleTimer = null;
    var go = function (i) {
      clearTimeout(next);
      clearTimeout(titleTimer);
      current = i;
      replay.hidden = true;
      chips.forEach(function (c, j) { c.classList.toggle('chip-active', j === i); });
      title.textContent = (i + 1) + ' / ' + chips.length + '  ' + chips[i].getAttribute('data-slide-title');
      title.classList.add('is-shown');
      titleTimer = setTimeout(function () { title.classList.remove('is-shown'); }, 2200);
      frame.src = chips[i].getAttribute('data-slide-src');
    };
    fitStage(viewport);
    whenInView(viewport, function () { if (!frame.getAttribute('src')) go(0); });
    window.addEventListener('message', function (e) {
      if (e.source !== frame.contentWindow || !e.data || e.data.xiTour !== 'done') return;
      if (current + 1 < chips.length) next = setTimeout(function () { go(current + 1); }, SLIDE_PAUSE);
      else replay.hidden = false;
    });
    chips.forEach(function (c, i) { c.addEventListener('click', function () { go(i); }); });
    replay.addEventListener('click', function () { go(0); });
  });

  // ---- live phones ---------------------------------------------------------
  // Each .phone-frame renders the web client at 390x844 (xisite.pages/phone).
  // A .phones group starts together once mostly in view and replays a few
  // seconds after every phone in it is done. Each send a phone's tour makes
  // is relayed to the others, so the session list (a mirroring variant of the
  // same tape) moves in step with the chat beside it.
  Array.prototype.forEach.call(document.querySelectorAll('.phones'), function (group) {
    var screens = Array.prototype.slice.call(group.querySelectorAll('.phone-screen'));
    var frames = screens.map(function (s) { return s.querySelector('.phone-frame'); });
    if (!frames.length) return;
    var done = [];
    var sent = []; // [frame index, type] of this run, for frames that load late
    var start = function () {
      done = [];
      sent = [];
      frames.forEach(function (f) { f.src = f.getAttribute('data-src'); });
    };
    var relay = function (to, type) {
      if (to.contentWindow) to.contentWindow.postMessage({ xiTourSent: type }, location.origin);
    };
    var fit = function () {
      screens.forEach(function (s) {
        s.style.setProperty('--phone-scale', String(s.clientWidth / PHONE_WIDTH));
      });
    };
    fit();
    if (window.ResizeObserver) new ResizeObserver(fit).observe(group);
    else window.addEventListener('resize', fit);
    if (window.IntersectionObserver) {
      var seen = new IntersectionObserver(function (entries) {
        if (entries.some(function (e) { return e.isIntersecting; })) { seen.disconnect(); start(); }
      }, { threshold: 0.4 });
      seen.observe(group);
    } else start();
    window.addEventListener('message', function (e) {
      var i = frames.findIndex(function (f) { return f.contentWindow === e.source; });
      if (i < 0 || !e.data) return;
      if (e.data.xiTour === 'sent') {
        sent.push([i, e.data.type]);
        frames.forEach(function (f, j) { if (j !== i) relay(f, e.data.type); });
      } else if (e.data.xiTour === 'ready') {
        sent.forEach(function (s) { if (s[0] !== i) relay(frames[i], s[1]); });
      } else if (e.data.xiTour === 'done' && done.indexOf(i) < 0) {
        done.push(i);
        if (done.length === frames.length) setTimeout(start, 5000);
      }
    });
  });

  // ---- click to interact ---------------------------------------------------
  // A tour iframe ignores the pointer (main.css) so the wheel scrolls the
  // page past it, until a click or keyboard focus makes its screen .is-live.
  // A click elsewhere on the page, or scrolling it out of view, lets go.
  var screens = Array.prototype.slice.call(document.querySelectorAll('.stage-viewport, .phone-screen'));
  var release = function (s) { s.classList.remove('is-live'); };
  screens.forEach(function (s) {
    var frame = s.querySelector('iframe');
    s.addEventListener('pointerdown', function () { s.classList.add('is-live'); });
    frame.addEventListener('focus', function () { s.classList.add('is-live'); });
    if (window.IntersectionObserver) {
      new IntersectionObserver(function (entries) {
        if (!entries[0].isIntersecting) release(s);
      }).observe(s);
    }
  });
  document.addEventListener('pointerdown', function (e) {
    screens.forEach(function (s) { if (!s.contains(e.target)) release(s); });
  });
  document.addEventListener('focusin', function (e) {
    screens.forEach(function (s) { if (!s.contains(e.target)) release(s); });
  });
})();
