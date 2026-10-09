// Tiny, dependency-free behaviour on top of the clj-ui-framework runtime
// (/js/ui-runtime.js, loaded first, provides window.__uiTheme):
//   - the docs sidebar toggle on small screens
//   - the tour stage on the home page: scaling and Replay
//   - the live phones: scaling, starting together, looping.
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

  // ---- tour stage ----------------------------------------------------------
  // The iframe renders the web client at 1280x800 (xisite.pages/stage);
  // scale it to the frame. It loads (and the tour starts) once the stage is
  // mostly in view, and posts {xiTour: 'done'} at its end. A stage with a
  // data-phone-src plays that phone variant on small screens instead: a
  // 390x844 phone frame that loops (no Replay bar).
  var TOUR_WIDTH = 1280;
  var PHONE_WIDTH = 390;
  var STAGE_PHONE_QUERY = '(max-width: 820px)';
  Array.prototype.forEach.call(document.querySelectorAll('.stage-window'), function (win) {
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
})();
