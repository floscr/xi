(function() {
  if (window.__xiPickerActive) return;
  window.__xiPickerActive = true;
  window.__xiPickerResult = null;
  window.__xiPickerCancelled = false;

  var CFG = window.__XI_PICKER_CFG__ || {};
  var C = CFG.colors;

  var pickedElements = [];

  function esc(s) {
    return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  function getSelector(el) {
    if (el.id) return '#' + CSS.escape(el.id);
    var path = [];
    var cur = el;
    while (cur && cur !== document.body && cur !== document.documentElement) {
      var part = cur.tagName.toLowerCase();
      if (cur.id) { path.unshift('#' + CSS.escape(cur.id)); break; }
      var parent = cur.parentElement;
      if (parent) {
        var same = Array.from(parent.children).filter(function(c) { return c.tagName === cur.tagName; });
        if (same.length > 1) { part += ':nth-of-type(' + (same.indexOf(cur) + 1) + ')'; }
      }
      path.unshift(part);
      cur = cur.parentElement;
    }
    return path.join(' > ') || el.tagName.toLowerCase();
  }

  function getInfo(el) {
    var s = el.tagName.toLowerCase();
    if (el.id) { s += '#' + el.id; }
    else if (el.className && typeof el.className === 'string') {
      var cls = el.className.trim().split(/\s+/).slice(0, 3).join('.');
      if (cls) s += '.' + cls;
    }
    var r = el.getBoundingClientRect();
    return s + '  ' + Math.round(r.width) + '\u00D7' + Math.round(r.height);
  }

  function getStyles(el) {
    var cs = getComputedStyle(el);
    var keys = ['display','position','width','height','margin','padding','color',
      'backgroundColor','fontSize','fontFamily','fontWeight','border','borderRadius',
      'overflow','flexDirection','justifyContent','alignItems','gridTemplateColumns','gap'];
    var out = {};
    for (var i = 0; i < keys.length; i++) {
      var v = cs[keys[i]];
      if (v && v !== 'none' && v !== 'normal' && v !== '0px' && v !== 'auto' &&
          v !== 'visible' && v !== 'static' && v !== 'rgba(0, 0, 0, 0)') {
        out[keys[i]] = v;
      }
    }
    return out;
  }

  // -- UI Elements --

  var hl = document.createElement('div');
  hl.id = '__xi-picker-highlight';
  hl.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483645;' +
    'border:2px solid ' + C.primaryBorder + ';background:' + C.primaryBg +
    ';border-radius:2px;transition:all 50ms ease-out;display:none;';
  document.documentElement.appendChild(hl);

  var tip = document.createElement('div');
  tip.id = '__xi-picker-tooltip';
  tip.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483646;' +
    'background:' + C.bg + ';color:' + C.text +
    ';padding:4px 8px;border-radius:4px;font:12px/1.4 monospace;' +
    'box-shadow:0 2px 8px rgba(0,0,0,0.4);display:none;max-width:400px;' +
    'white-space:nowrap;overflow:hidden;text-overflow:ellipsis;';
  document.documentElement.appendChild(tip);

  var overlay = document.createElement('div');
  overlay.id = '__xi-picker-overlay';
  overlay.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;' +
    'z-index:2147483647;cursor:crosshair;';
  document.documentElement.appendChild(overlay);

  var badge = document.createElement('div');
  badge.id = '__xi-picker-badge';
  badge.style.cssText = 'position:fixed;top:12px;right:12px;z-index:2147483646;' +
    'pointer-events:none;background:' + C.primary + ';color:white;' +
    'padding:4px 12px;border-radius:20px;font:13px/1.4 -apple-system,sans-serif;' +
    'font-weight:600;box-shadow:0 2px 8px rgba(0,0,0,0.3);display:none;';
  document.documentElement.appendChild(badge);

  var banner = document.createElement('div');
  banner.id = '__xi-picker-banner';
  banner.style.cssText = 'position:fixed;top:12px;left:50%;transform:translateX(-50%);' +
    'z-index:2147483646;pointer-events:none;background:' + C.primary + ';color:white;' +
    'padding:8px 16px;border-radius:20px;font:13px/1.4 -apple-system,sans-serif;' +
    'font-weight:600;box-shadow:0 2px 12px rgba(0,0,0,0.4);white-space:nowrap;' +
    'display:flex;align-items:center;gap:8px;';
  banner.innerHTML = '\u{1F3AF} xi picker \u2014 hover &amp; click an element' +
    '<span style="opacity:0.75;font-weight:400;">\u00B7 Esc to cancel</span>';
  document.documentElement.appendChild(banner);

  function showBanner(on) { banner.style.display = on ? 'flex' : 'none'; }

  function updateBadge() {
    if (pickedElements.length > 0) {
      badge.textContent = pickedElements.length + ' picked';
      badge.style.display = 'block';
    } else {
      badge.style.display = 'none';
    }
  }

  var hovered = null;
  var selected = null;
  var panelEl = null;

  function updateHL(el) {
    if (!el) { hl.style.display = 'none'; tip.style.display = 'none'; return; }
    var r = el.getBoundingClientRect();
    hl.style.display = 'block';
    hl.style.top = r.top + 'px';
    hl.style.left = r.left + 'px';
    hl.style.width = r.width + 'px';
    hl.style.height = r.height + 'px';
    tip.style.display = 'block';
    tip.textContent = getInfo(el);
    tip.style.top = (r.top > 33 ? r.top - 28 : r.bottom + 5) + 'px';
    tip.style.left = Math.max(5, r.left) + 'px';
  }

  // -- Event Handlers --

  overlay.addEventListener('mousemove', function(e) {
    if (selected) return;
    overlay.style.pointerEvents = 'none';
    var el = document.elementFromPoint(e.clientX, e.clientY);
    overlay.style.pointerEvents = 'auto';
    if (el && (!el.id || el.id.indexOf('__xi-picker') !== 0)) {
      hovered = el;
      updateHL(el);
    }
  });

  overlay.addEventListener('click', function(e) {
    e.preventDefault();
    e.stopPropagation();
    if (selected || !hovered) return;
    selected = hovered;
    hl.style.borderColor = C.success;
    hl.style.background = C.successBg;
    tip.style.display = 'none';
    showBanner(false);
    showPanel();
  });

  function captureElement(el, msg) {
    var r = el.getBoundingClientRect();
    var html = el.outerHTML;
    if (html.length > CFG.maxHTML) html = html.substring(0, CFG.maxHTML) + '\n<!-- truncated -->';
    return {
      selector: getSelector(el),
      tagName: el.tagName.toLowerCase(),
      message: msg || '',
      outerHTML: html,
      computedStyles: getStyles(el),
      boundingRect: {
        x: r.x + window.scrollX,
        y: r.y + window.scrollY,
        width: r.width,
        height: r.height
      }
    };
  }

  function showPanel() {
    panelEl = document.createElement('div');
    panelEl.id = '__xi-picker-panel';
    panelEl.style.cssText = 'position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);' +
      'z-index:2147483647;background:' + C.bg + ';border:1px solid ' + C.border +
      ';border-radius:12px;padding:20px;width:420px;' +
      'box-shadow:0 8px 32px rgba(0,0,0,0.5);' +
      'font-family:-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif;color:' + C.text + ';';

    var sel = getSelector(selected);
    var info = getInfo(selected);
    var countLabel = pickedElements.length > 0
      ? ' <span style="color:' + C.primary + ';font-size:12px;font-weight:400;">' +
        '(' + pickedElements.length + ' already picked)</span>'
      : '';

    panelEl.innerHTML =
      '<div style="margin-bottom:16px;">' +
        '<div style="font-size:14px;font-weight:600;margin-bottom:8px;color:' + C.accent + '">' +
          '\u{1F3AF} Selected Element' + countLabel + '</div>' +
        '<div style="font:12px/1.4 monospace;padding:8px;background:' + C.bgLight +
          ';border-radius:6px;color:' + C.textMuted + ';word-break:break-all;">' +
          esc(info) + '<br/>' +
          '<span style="color:' + C.textDim + '">' + esc(sel) + '</span>' +
        '</div>' +
      '</div>' +
      '<div style="margin-bottom:16px;">' +
        '<label style="font-size:13px;color:' + C.textMuted + ';display:block;margin-bottom:6px;">' +
          'Message for xi</label>' +
        '<textarea id="__xi-picker-msg" placeholder="What should xi do with this element?"' +
          ' style="width:100%;height:80px;resize:vertical;background:' + C.bgLight +
          ';color:' + C.text + ';border:1px solid ' + C.border +
          ';border-radius:6px;padding:10px;font-size:14px;font-family:inherit;' +
          'outline:none;box-sizing:border-box;"></textarea>' +
      '</div>' +
      '<div style="display:flex;gap:8px;justify-content:flex-end;align-items:center;">' +
        '<span style="font-size:11px;color:' + C.textDim + ';margin-right:auto;">' +
          'Alt+Enter pick more \u00B7 Ctrl+Enter submit \u00B7 Esc back</span>' +
        '<button id="__xi-picker-cancel" style="padding:8px 16px;border-radius:6px;' +
          'border:1px solid ' + C.border + ';background:' + C.bgLight +
          ';color:' + C.textMuted + ';cursor:pointer;font-size:13px;">Cancel</button>' +
        '<button id="__xi-picker-submit" style="padding:8px 16px;border-radius:6px;' +
          'border:none;background:' + C.primary +
          ';color:white;cursor:pointer;font-size:13px;font-weight:600;">Send to xi</button>' +
      '</div>';

    document.documentElement.appendChild(panelEl);

    var msgEl = document.getElementById('__xi-picker-msg');
    if (pickedElements.length === 0 && CFG.prefillMessage) msgEl.value = CFG.prefillMessage;
    setTimeout(function() { msgEl.focus(); }, 50);

    document.getElementById('__xi-picker-cancel').addEventListener('click', doCancel);
    document.getElementById('__xi-picker-submit').addEventListener('click', doSubmit);
    msgEl.addEventListener('keydown', function(e) {
      if (e.key === 'Enter' && e.altKey) { e.preventDefault(); doPickMore(); }
      else if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); doSubmit(); }
    });
  }

  function doPickMore() {
    var msgEl = document.getElementById('__xi-picker-msg');
    var msg = msgEl ? msgEl.value.trim() : '';
    pickedElements.push(captureElement(selected, msg));
    panelEl.remove();
    panelEl = null;
    selected = null;
    hl.style.borderColor = C.primaryBorder;
    hl.style.background = C.primaryBg;
    updateBadge();
    showBanner(true);
  }

  function doSubmit() {
    var msgEl = document.getElementById('__xi-picker-msg');
    var msg = msgEl ? msgEl.value.trim() : '';
    pickedElements.push(captureElement(selected, msg));

    window.__xiPickerResult = {
      elements: pickedElements,
      url: window.location.href
    };
    cleanupDOM();
  }

  function doCancel() {
    cleanupDOM();
    window.__xiPickerCancelled = true;
  }

  function cleanupDOM() {
    var ids = ['__xi-picker-overlay','__xi-picker-highlight','__xi-picker-tooltip','__xi-picker-panel','__xi-picker-badge','__xi-picker-banner'];
    for (var i = 0; i < ids.length; i++) {
      var el = document.getElementById(ids[i]);
      if (el) el.remove();
    }
    window.__xiPickerActive = false;
  }

  // Escape key: panel open -> go back to picking; picking mode -> cancel entirely
  var escHandler = function(e) {
    if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      if (selected && panelEl) {
        panelEl.remove();
        panelEl = null;
        selected = null;
        hl.style.borderColor = C.primaryBorder;
        hl.style.background = C.primaryBg;
        showBanner(true);
      } else {
        document.removeEventListener('keydown', escHandler, true);
        doCancel();
      }
    }
  };
  document.addEventListener('keydown', escHandler, true);
})();
