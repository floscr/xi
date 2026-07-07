(() => {
  // ../../../dev/squint/node_modules/squint-cljs/src/squint/core.js
  function toFn(x) {
    if (x == null) return x;
    if (x instanceof Function) {
      return x;
    }
    const t = typeof x;
    if (t === "string") {
      return (coll, d) => {
        return get(coll, x, d);
      };
    }
    if (t === "object") {
      return (k, d) => {
        return get(x, k, d);
      };
    }
    return x;
  }
  var has = Object.prototype.hasOwnProperty;
  function findKey(iter, tar, key) {
    for (key of iter.keys()) {
      if (dequal(key, tar)) return key;
    }
  }
  function dequal(foo, bar) {
    if (foo === bar) return true;
    var ctor, len, tmp;
    if (foo && bar && (ctor = foo.constructor) === bar.constructor) {
      if (ctor === Array) {
        if ((len = foo.length) === bar.length) {
          while (len-- && dequal(foo[len], bar[len])) ;
        }
        return len === -1;
      }
      if (ctor === Set) {
        if (foo.size !== bar.size) {
          return false;
        }
        for (const elt of foo) {
          tmp = elt;
          if (tmp && typeof tmp === "object") {
            tmp = findKey(bar, tmp);
            if (!tmp) return false;
          }
          if (!bar.has(tmp)) return false;
        }
        return true;
      }
      if (ctor === Map) {
        if (foo.size !== bar.size) {
          return false;
        }
        for (const kv of foo) {
          tmp = kv[0];
          if (tmp && typeof tmp === "object") {
            tmp = findKey(bar, tmp);
            if (!tmp) return false;
          }
          if (!dequal(kv[1], bar.get(tmp))) {
            return false;
          }
        }
        return true;
      }
      if (!ctor || typeof foo === "object") {
        len = 0;
        for (const k in foo) {
          if (has.call(foo, k) && ++len && !has.call(bar, k)) return false;
          if (!(k in bar) || !dequal(foo[k], bar[k])) return false;
        }
        return Object.keys(bar).length === len;
      }
    }
    return false;
  }
  function walkArray(arr, comp) {
    return arr.every(function(x, i) {
      return i === 0 || comp(arr[i - 1], x);
    });
  }
  function _EQ_(...xs) {
    return walkArray(xs, (x, y) => dequal(x, y));
  }
  var MAP_TYPE = 1;
  var ARRAY_TYPE = 2;
  var OBJECT_TYPE = 3;
  var LIST_TYPE = 4;
  var SET_TYPE = 5;
  var LAZY_ITERABLE_TYPE = 6;
  function isObj(coll) {
    return coll.constructor === Object;
  }
  function typeConst(obj) {
    if (obj == null) {
      return void 0;
    }
    if (isObj(obj)) {
      return OBJECT_TYPE;
    }
    if (obj instanceof Map) return MAP_TYPE;
    if (obj instanceof Set) return SET_TYPE;
    if (obj instanceof List) return LIST_TYPE;
    if (Array.isArray(obj)) return ARRAY_TYPE;
    if (obj instanceof LazyIterable) return LAZY_ITERABLE_TYPE;
    if (obj instanceof SortedSet) return SET_TYPE;
    if (obj instanceof Object) return OBJECT_TYPE;
    return void 0;
  }
  function get(coll, key, otherwise = void 0) {
    if (coll == null) {
      return otherwise;
    }
    let v;
    if (isObj(coll)) {
      v = coll[key];
      if (v === void 0) {
        return otherwise;
      } else {
        return v;
      }
    }
    let g;
    switch (typeConst(coll)) {
      case SET_TYPE:
        if (coll.has(key)) v = key;
        break;
      case MAP_TYPE:
        v = coll.get(key);
        break;
      case ARRAY_TYPE:
        v = coll[key];
        break;
      default:
        g = coll["get"];
        if (g instanceof Function) {
          try {
            v = coll.get(key);
            break;
          } catch (e) {
          }
        }
        v = coll[key];
        break;
    }
    return v !== void 0 ? v : otherwise;
  }
  function seqable_QMARK_(x) {
    return x === null || x === void 0 || // we used to check instanceof Object but this returns false for TC39 Records
    // also we used to write `Symbol.iterator in` but this does not work for strings and some other types
    !!x[Symbol.iterator];
  }
  function iterable(x) {
    if (x === null || x === void 0) {
      return [];
    }
    if (seqable_QMARK_(x)) {
      return x;
    }
    if (x instanceof Object) return Object.entries(x);
    throw new TypeError(`${x} is not iterable`);
  }
  var IIterable = Symbol("Iterable");
  function first(coll) {
    const [first2] = iterable(coll);
    return first2;
  }
  var tolr = false;
  var LazyIterable = class {
    constructor(gen) {
      this.gen = gen;
      this.usages = 0;
    }
    [Symbol.iterator]() {
      this.usages++;
      if (this.usages >= 2 && tolr) {
        try {
          throw new Error();
        } catch (e) {
          console.warn("Re-use of lazy value", e.stack);
        }
      }
      return this.gen();
    }
  };
  LazyIterable.prototype[IIterable] = true;
  function lazy(f) {
    return new LazyIterable(f);
  }
  function not(expr) {
    return !truth_(expr);
  }
  var Atom = class {
    constructor(init) {
      this.val = init;
      this._watches = {};
      this._deref = () => this.val;
      this._hasWatches = false;
      this._reset_BANG_ = (x) => {
        const old_val = this.val;
        this.val = x;
        if (this._hasWatches) {
          for (const entry of Object.entries(this._watches)) {
            const k = entry[0];
            const f = entry[1];
            f(k, this, old_val, x);
          }
        }
        return x;
      };
      this._add_watch = (k, fn) => {
        this._watches[k] = fn;
        this._hasWatches = true;
      };
      this._remove_watch = (k) => {
        delete this._watches[k];
      };
    }
  };
  function atom(init) {
    return new Atom(init);
  }
  function deref(ref) {
    return ref._deref();
  }
  function reset_BANG_(atm, v) {
    atm._reset_BANG_(v);
  }
  function swap_BANG_(atm, f, ...args) {
    f = toFn(f);
    const v = f(deref(atm), ...args);
    reset_BANG_(atm, v);
    return v;
  }
  var IApply__apply = Symbol("IApply__apply");
  var List = class extends Array {
    constructor(...args) {
      super();
      this.push(...args);
    }
  };
  function concat1(colls) {
    return lazy(function* () {
      for (const coll of colls) {
        yield* iterable(coll);
      }
    });
  }
  function concat(...colls) {
    return concat1(colls);
  }
  concat[IApply__apply] = (colls) => {
    return concat1(colls);
  };
  function sort(f, coll) {
    if (arguments.length === 1) {
      coll = f;
      f = void 0;
    }
    f = toFn(f);
    coll = iterable(coll);
    const clone = [...coll];
    return clone.sort(f || compare);
  }
  function compare(x, y) {
    if (x === y) {
      return 0;
    } else {
      if (x == null) {
        return -1;
      }
      if (y == null) {
        return 1;
      }
      const tx = typeof x;
      const ty = typeof y;
      if (tx === "number" && ty === "number" || tx === "string" && ty === "string") {
        if (x === y) {
          return 0;
        }
        if (x < y) {
          return -1;
        }
        return 1;
      } else if (Array.isArray(x) && Array.isArray(y)) {
        if (x.length < y.length) {
          return -1;
        } else if (x.length > y.length) {
          return 1;
        } else {
          for (let i = 0; i < x.length; i++) {
            const c = compare(x[i], y[i]);
            if (c != 0) {
              return c;
            }
          }
          return 0;
        }
      } else {
        throw new Error(`comparing ${tx} to ${ty}`);
      }
    }
  }
  function truth_(x) {
    return x != null && x !== false;
  }
  var _metaSym = Symbol("meta");
  var SortedSet = class _SortedSet {
    constructor(xs) {
      const isSorted = xs instanceof _SortedSet;
      if (!isSorted) {
        xs = sort(xs);
      }
      const s = new Set(xs);
      this._elts = [...s];
      this._set = s;
    }
    add(x) {
      if (this._set.has(x)) return this;
      const xs = this._elts;
      let added = false;
      for (let i = 0; i < xs.length; i++) {
        if (compare(x, xs[i]) <= 0) {
          xs.splice(i, 0, x);
          added = true;
          break;
        }
      }
      if (!added) {
        xs.push(x);
        this._set.add(x);
      } else {
        this._set = new Set(xs);
      }
      this.size = xs.length;
      return this;
    }
    delete(x) {
      if (!this._set.has(x)) return this;
      const xs = this._elts;
      const idx = xs.indexOf(x);
      xs.splice(idx, 1);
      this._set = new Set(xs);
      this.size = xs.length;
      return this;
    }
    has(x) {
      return this._set.has(x);
    }
    keys() {
      return this.values();
    }
    values() {
      return this._elts[Symbol.iterator]();
    }
    entries() {
      return this._set.entries();
    }
    forEach(...xs) {
      return this.set.forEach(...xs);
    }
    clear() {
      this._elts = [];
      this._set = new Set(this._elts);
    }
    [Symbol.iterator]() {
      return this.keys();
    }
  };

  // .compiled/command.mjs
  var items = function(dialog) {
    return Array.from(dialog.querySelectorAll(".command-item"));
  };
  var visible_items = function(dialog) {
    return items(dialog).filter((function(el) {
      return not(el.hidden) && not(el.disabled);
    }));
  };
  var item_text = function(el) {
    const v1 = el.dataset.commandValue;
    return (() => {
      const or__23426__auto__2 = v1;
      if (truth_(or__23426__auto__2)) {
        return or__23426__auto__2;
      } else {
        const or__23426__auto__3 = el.textContent;
        if (truth_(or__23426__auto__3)) {
          return or__23426__auto__3;
        } else {
          return "";
        }
        ;
      }
      ;
    })().toLowerCase();
  };
  var active_item = function(dialog) {
    return dialog.querySelector(".command-item--active");
  };
  var set_active_BANG_ = function(dialog, el) {
    const prev1 = active_item(dialog);
    if (truth_(prev1)) {
      prev1.classList.remove("command-item--active");
    }
    ;
    if (truth_(el)) {
      el.classList.add("command-item--active");
      if (truth_(el.scrollIntoView)) {
        return el.scrollIntoView({ "block": "nearest" });
      }
      ;
    }
    ;
  };
  var move_active_BANG_ = function(dialog, dir) {
    const vis1 = visible_items(dialog);
    const len2 = vis1.length;
    if (len2 > 0) {
      const cur3 = active_item(dialog);
      const idx4 = vis1.indexOf(cur3);
      const next_i5 = dir === "down" ? idx4 < len2 - 1 ? idx4 + 1 : 0 : dir === "up" ? idx4 > 0 ? idx4 - 1 : len2 - 1 : dir === "home" ? 0 : dir === "end" ? len2 - 1 : "else" ? idx4 : null;
      return set_active_BANG_(dialog, vis1[next_i5]);
    }
    ;
  };
  var filter_BANG_ = function(dialog, query) {
    const q1 = (() => {
      const or__23426__auto__2 = query;
      if (truth_(or__23426__auto__2)) {
        return or__23426__auto__2;
      } else {
        return "";
      }
      ;
    })().trim().toLowerCase();
    const list3 = dialog.querySelector(".command-list");
    items(dialog).forEach((function(el) {
      const match4 = (() => {
        const or__23426__auto__5 = _EQ_(q1, "");
        if (or__23426__auto__5) {
          return or__23426__auto__5;
        } else {
          return item_text(el).includes(q1);
        }
        ;
      })();
      return el.hidden = not(match4);
    }));
    Array.from(dialog.querySelectorAll(".command-group")).forEach((function(grp) {
      const any6 = Array.from(grp.querySelectorAll(".command-item")).some((function(el) {
        return not(el.hidden);
      }));
      return grp.hidden = not(any6);
    }));
    const vis7 = visible_items(dialog);
    if (vis7.length === 0) {
      list3.classList.add("command-list--empty");
    } else {
      list3.classList.remove("command-list--empty");
    }
    ;
    return set_active_BANG_(dialog, vis7.length > 0 ? vis7[0] : null);
  };
  var find_dialog = function(id) {
    if (truth_(id)) {
      return document.getElementById(id);
    }
    ;
  };
  var open = function(id) {
    const dialog1 = find_dialog(id);
    if (truth_((() => {
      const and__23442__auto__2 = dialog1;
      if (truth_(and__23442__auto__2)) {
        return not(dialog1.open);
      } else {
        return and__23442__auto__2;
      }
      ;
    })())) {
      dialog1.showModal();
      const input3 = dialog1.querySelector(".command-input");
      if (truth_(input3)) {
        input3.value = "";
        input3.focus();
      }
      ;
      return filter_BANG_(dialog1, "");
    }
    ;
  };
  var close = function(id) {
    const dialog1 = find_dialog(id);
    if (truth_((() => {
      const and__23442__auto__2 = dialog1;
      if (truth_(and__23442__auto__2)) {
        return dialog1.open;
      } else {
        return and__23442__auto__2;
      }
      ;
    })())) {
      return dialog1.close();
    }
    ;
  };
  var toggle = function(id) {
    const dialog1 = find_dialog(id);
    if (truth_(dialog1)) {
      if (truth_(dialog1.open)) {
        return dialog1.close();
      } else {
        return open(id);
      }
      ;
    }
    ;
  };
  var select_BANG_ = function(dialog, el) {
    if (truth_((() => {
      const and__23442__auto__1 = el;
      if (truth_(and__23442__auto__1)) {
        return not(el.disabled);
      } else {
        return and__23442__auto__1;
      }
      ;
    })())) {
      dialog.close();
      if (not(el.href)) {
        el.click();
      }
      ;
      if (truth_(el.href)) {
        return window.location = el.href;
      }
      ;
    }
    ;
  };
  var hotkey_match_QMARK_ = function(spec, e) {
    const ekey1 = e.key;
    if (truth_(ekey1)) {
      const parts2 = spec.toLowerCase().split("+");
      const key3 = parts2[parts2.length - 1];
      const mod_QMARK_4 = spec.includes("mod");
      const shift_QMARK_5 = spec.includes("shift");
      const alt_QMARK_6 = spec.includes("alt");
      return _EQ_(ekey1.toLowerCase(), key3) && (_EQ_(mod_QMARK_4, (() => {
        const or__23426__auto__7 = e.metaKey;
        if (truth_(or__23426__auto__7)) {
          return or__23426__auto__7;
        } else {
          return e.ctrlKey;
        }
        ;
      })()) && (_EQ_(shift_QMARK_5, e.shiftKey) && _EQ_(alt_QMARK_6, e.altKey)));
    }
    ;
  };
  var on_global_key = function(e) {
    const dialogs1 = Array.from(document.querySelectorAll(".command-dialog[data-command-hotkey]"));
    return dialogs1.forEach((function(dialog) {
      const spec2 = dialog.dataset.commandHotkey;
      if (truth_((() => {
        const and__23442__auto__3 = spec2;
        if (truth_(and__23442__auto__3)) {
          return hotkey_match_QMARK_(spec2, e);
        } else {
          return and__23442__auto__3;
        }
        ;
      })())) {
        e.preventDefault();
        return toggle(dialog.id);
      }
      ;
    }));
  };
  var open_dialog = function() {
    return document.querySelector(".command-dialog[open]");
  };
  var on_input = function(e) {
    const t1 = e.target;
    if (truth_((() => {
      const and__23442__auto__2 = t1.classList;
      if (truth_(and__23442__auto__2)) {
        return t1.classList.contains("command-input");
      } else {
        return and__23442__auto__2;
      }
      ;
    })())) {
      const dialog3 = t1.closest(".command-dialog");
      if (truth_(dialog3)) {
        return filter_BANG_(dialog3, t1.value);
      }
      ;
    }
    ;
  };
  var on_keydown = function(e) {
    const dialog1 = open_dialog();
    if (truth_(dialog1)) {
      const key2 = e.key;
      if (key2 === "ArrowDown") {
        e.preventDefault();
        return move_active_BANG_(dialog1, "down");
      } else {
        if (key2 === "ArrowUp") {
          e.preventDefault();
          return move_active_BANG_(dialog1, "up");
        } else {
          if (truth_(key2 === "Home" && e.metaKey)) {
            e.preventDefault();
            return move_active_BANG_(dialog1, "home");
          } else {
            if (truth_(key2 === "End" && e.metaKey)) {
              e.preventDefault();
              return move_active_BANG_(dialog1, "end");
            } else {
              if (key2 === "Enter") {
                e.preventDefault();
                return select_BANG_(dialog1, active_item(dialog1));
              } else {
                return null;
              }
            }
          }
        }
      }
      ;
    }
    ;
  };
  var on_click = function(e) {
    const t1 = e.target;
    const dialog2 = truth_(t1.closest) ? t1.closest(".command-dialog") : null;
    if (truth_(dialog2)) {
      const item3 = t1.closest(".command-item");
      if (t1 === dialog2) {
        return dialog2.close();
      } else {
        if (truth_(item3)) {
          if (not(item3.disabled)) {
            return dialog2.close();
          }
        } else {
          return null;
        }
      }
      ;
    }
    ;
  };
  var on_pointermove = function(e) {
    const t1 = e.target;
    if (truth_(t1.closest)) {
      const item2 = t1.closest(".command-item");
      if (truth_((() => {
        const and__23442__auto__3 = item2;
        if (truth_(and__23442__auto__3)) {
          return not(item2.hidden) && not(item2.disabled);
        } else {
          return and__23442__auto__3;
        }
        ;
      })())) {
        const dialog4 = item2.closest(".command-dialog");
        if (truth_(dialog4)) {
          return set_active_BANG_(dialog4, item2);
        }
        ;
      }
      ;
    }
    ;
  };
  var init_BANG_ = function() {
    document.addEventListener("input", on_input, true);
    document.addEventListener("keydown", on_keydown, true);
    document.addEventListener("keydown", on_global_key);
    document.addEventListener("click", on_click);
    return document.addEventListener("pointermove", on_pointermove, true);
  };
  init_BANG_();
  window["__uiCommand"] = { "open": open, "close": close, "toggle": toggle };

  // .compiled/context_menu.mjs
  var get_state = function() {
    const or__23426__auto__1 = window["__uiCtxState"];
    if (truth_(or__23426__auto__1)) {
      return or__23426__auto__1;
    } else {
      const s2 = { "menu": null, "cleanup": null };
      window["__uiCtxState"] = s2;
      return s2;
    }
    ;
  };
  var create_icon = function(paths) {
    const ns_uri1 = "http://www.w3.org/2000/svg";
    const svg2 = document.createElementNS(ns_uri1, "svg");
    svg2.setAttribute("viewBox", "0 0 24 24");
    svg2.setAttribute("width", "16");
    svg2.setAttribute("height", "16");
    svg2.setAttribute("fill", "none");
    svg2.setAttribute("stroke", "currentColor");
    svg2.setAttribute("stroke-width", "2");
    svg2.setAttribute("stroke-linecap", "round");
    svg2.setAttribute("stroke-linejoin", "round");
    svg2.setAttribute("style", "flex-shrink:0");
    paths.forEach((function(d) {
      const p3 = document.createElementNS(ns_uri1, "path");
      p3.setAttribute("d", d);
      return svg2.appendChild(p3);
    }));
    return svg2;
  };
  var dismiss_BANG_ = function() {
    const state1 = get_state();
    if (truth_(state1.menu)) {
      state1.menu.remove();
      state1.menu = null;
    }
    ;
    if (truth_(state1.cleanup)) {
      state1.cleanup();
      return state1.cleanup = null;
    }
    ;
  };
  var execute_item_BANG_ = function(item) {
    const on_click1 = item["on-click"];
    const url2 = item["url"];
    if (truth_(on_click1)) {
      return on_click1();
    } else {
      if (truth_(url2)) {
        return window.location = url2;
      }
    }
    ;
  };
  var show_confirm_BANG_ = function(menu, item) {
    const confirm_val1 = item["confirm"];
    const message2 = confirm_val1 === true ? "Are you sure?" : confirm_val1;
    const _3 = menu.innerHTML = "";
    const msg_el4 = document.createElement("div");
    msg_el4.className = "context-menu-confirm-message";
    msg_el4.textContent = message2;
    menu.appendChild(msg_el4);
    const actions5 = document.createElement("div");
    actions5.className = "context-menu-confirm-actions";
    const cancel_btn6 = document.createElement("button");
    cancel_btn6.className = "context-menu-item";
    cancel_btn6.textContent = "Cancel";
    cancel_btn6.setAttribute("tabindex", "-1");
    cancel_btn6.addEventListener("click", (function(e) {
      e.preventDefault();
      e.stopPropagation();
      return dismiss_BANG_();
    }));
    actions5.appendChild(cancel_btn6);
    const danger7 = item["variant"] === "danger";
    const confirm_btn8 = document.createElement("button");
    confirm_btn8.className = danger7 ? "context-menu-item context-menu-item--danger" : "context-menu-item";
    confirm_btn8.textContent = "Confirm";
    confirm_btn8.setAttribute("tabindex", "-1");
    confirm_btn8.addEventListener("click", (function(e) {
      e.preventDefault();
      e.stopPropagation();
      dismiss_BANG_();
      return execute_item_BANG_(item);
    }));
    actions5.appendChild(confirm_btn8);
    menu.appendChild(actions5);
    const cancel9 = menu.querySelector(".context-menu-item");
    if (truth_(cancel9)) {
      return cancel9.focus();
    }
    ;
  };
  var create_menu = function(items2) {
    const menu1 = document.createElement("div");
    menu1.className = "context-menu";
    menu1.setAttribute("role", "menu");
    items2.forEach((function(item) {
      if (item["type"] === "separator") {
        const sep2 = document.createElement("div");
        sep2.className = "context-menu-separator";
        sep2.setAttribute("role", "separator");
        return menu1.appendChild(sep2);
      } else {
        const url3 = item["url"];
        const tag4 = truth_(url3) ? "a" : "button";
        const el5 = document.createElement(tag4);
        const danger6 = item["variant"] === "danger";
        el5.className = danger6 ? "context-menu-item context-menu-item--danger" : "context-menu-item";
        el5.setAttribute("role", "menuitem");
        el5.setAttribute("tabindex", "-1");
        if (truth_(url3)) {
          el5.setAttribute("href", url3);
        }
        ;
        const paths7 = item["icon-paths"];
        if (truth_((() => {
          const and__23442__auto__8 = paths7;
          if (truth_(and__23442__auto__8)) {
            return paths7.length > 0;
          } else {
            return and__23442__auto__8;
          }
          ;
        })())) {
          el5.appendChild(create_icon(paths7));
        }
        ;
        const span9 = document.createElement("span");
        span9.textContent = item["label"];
        el5.appendChild(span9);
        el5.addEventListener("click", (function(e) {
          e.preventDefault();
          e.stopPropagation();
          if (truth_(item["confirm"])) {
            return show_confirm_BANG_(el5.closest(".context-menu"), item);
          } else {
            dismiss_BANG_();
            return execute_item_BANG_(item);
          }
          ;
        }));
        return menu1.appendChild(el5);
      }
      ;
    }));
    return menu1;
  };
  var position_menu_BANG_ = function(menu, x, y) {
    menu.style.left = `${x ?? ""}px`;
    menu.style.top = `${y ?? ""}px`;
    document.body.appendChild(menu);
    const rect1 = menu.getBoundingClientRect();
    const vw2 = window.innerWidth;
    const vh3 = window.innerHeight;
    const new_x4 = x + rect1.width > vw2 ? vw2 - rect1.width - 8 : x;
    const new_y5 = y + rect1.height > vh3 ? vh3 - rect1.height - 8 : y;
    menu.style.left = `${new_x4 ?? ""}px`;
    return menu.style.top = `${new_y5 ?? ""}px`;
  };
  var focus_first_item_BANG_ = function(menu) {
    const first_item1 = menu.querySelector(".context-menu-item");
    if (truth_(first_item1)) {
      return first_item1.focus();
    }
    ;
  };
  var focus_next_BANG_ = function(menu, direction) {
    const items1 = menu.querySelectorAll(".context-menu-item");
    const active2 = document.activeElement;
    const len3 = items1.length;
    if (len3 > 0) {
      const current_idx4 = (() => {
        const result5 = atom(-1);
        items1.forEach((function(item, i) {
          if (_EQ_(item, active2)) {
            return reset_BANG_(result5, i);
          }
          ;
        }));
        return deref(result5);
      })();
      const next_idx6 = direction === "down" ? current_idx4 < len3 - 1 ? current_idx4 + 1 : 0 : direction === "up" ? current_idx4 > 0 ? current_idx4 - 1 : len3 - 1 : "else" ? current_idx4 : null;
      return items1[next_idx6].focus();
    }
    ;
  };
  var open_context_menu = (() => {
    const f1 = (function(var_args) {
      const args21 = [];
      const len__23321__auto__2 = arguments.length;
      let i33 = 0;
      while (true) {
        if (i33 < len__23321__auto__2) {
          args21.push(arguments[i33]);
          let G__4 = i33 + 1;
          i33 = G__4;
          continue;
        }
        ;
        break;
      }
      ;
      const argseq__23513__auto__5 = 1 < args21.length ? args21.slice(1) : null;
      return f1.cljs$core$IFn$_invoke$arity$variadic(arguments[0], argseq__23513__auto__5);
    });
    f1.cljs$core$IFn$_invoke$arity$variadic = (function(event, args) {
      dismiss_BANG_();
      const items6 = (() => {
        const passed7 = first(args);
        if (truth_(passed7)) {
          return passed7;
        } else {
          const json8 = event.currentTarget.dataset.contextMenu;
          if (truth_(json8)) {
            return JSON.parse(json8);
          }
          ;
        }
        ;
      })();
      const _9 = not(items6) ? console.warn("Context menu: no items provided") : null;
      const menu10 = create_menu(items6);
      const state11 = get_state();
      state11.menu = menu10;
      position_menu_BANG_(menu10, event.clientX, event.clientY);
      focus_first_item_BANG_(menu10);
      const on_click12 = (function(e) {
        if (not(menu10.contains(e.target))) {
          return dismiss_BANG_();
        }
        ;
      });
      const on_key13 = (function(e) {
        const key14 = e.key;
        if (key14 === "Escape") {
          e.preventDefault();
          return dismiss_BANG_();
        } else {
          if (key14 === "ArrowDown") {
            e.preventDefault();
            return focus_next_BANG_(menu10, "down");
          } else {
            if (key14 === "ArrowUp") {
              e.preventDefault();
              return focus_next_BANG_(menu10, "up");
            } else {
              return null;
            }
          }
        }
        ;
      });
      const on_scroll15 = (function(_) {
        return dismiss_BANG_();
      });
      const on_resize16 = (function(_) {
        return dismiss_BANG_();
      });
      const cleanup17 = (function() {
        document.removeEventListener("click", on_click12, true);
        document.removeEventListener("keydown", on_key13, true);
        window.removeEventListener("scroll", on_scroll15, true);
        return window.removeEventListener("resize", on_resize16);
      });
      document.addEventListener("click", on_click12, true);
      document.addEventListener("keydown", on_key13, true);
      window.addEventListener("scroll", on_scroll15, true);
      window.addEventListener("resize", on_resize16);
      return state11.cleanup = cleanup17;
    });
    f1.cljs$lang$maxFixedArity = 1;
    return f1;
  })();
  window["__uiContextMenu"] = open_context_menu;

  // .compiled/popover.mjs
  var gap = 8;
  var edge = 8;
  var clamp = function(v, lo, hi) {
    return Math.max(lo, Math.min(v, hi));
  };
  var align_h = function(tr, cw, align) {
    if (align === "start") {
      return tr.left;
    } else {
      if (align === "end") {
        return tr.right - cw;
      } else {
        if ("else") {
          return tr.left + (tr.width - cw) / 2;
        } else {
          return null;
        }
      }
    }
    ;
  };
  var align_v = function(tr, ch, align) {
    if (align === "start") {
      return tr.top;
    } else {
      if (align === "end") {
        return tr.bottom - ch;
      } else {
        if ("else") {
          return tr.top + (tr.height - ch) / 2;
        } else {
          return null;
        }
      }
    }
    ;
  };
  var position_BANG_ = function(content, trigger) {
    const side1 = (() => {
      const or__23426__auto__2 = content.dataset.popoverSide;
      if (truth_(or__23426__auto__2)) {
        return or__23426__auto__2;
      } else {
        return "bottom";
      }
      ;
    })();
    const align3 = (() => {
      const or__23426__auto__4 = content.dataset.popoverAlign;
      if (truth_(or__23426__auto__4)) {
        return or__23426__auto__4;
      } else {
        return "center";
      }
      ;
    })();
    const tr5 = trigger.getBoundingClientRect();
    const cr6 = content.getBoundingClientRect();
    const cw7 = cr6.width;
    const ch8 = cr6.height;
    const vw9 = window.innerWidth;
    const vh10 = window.innerHeight;
    const left11 = side1 === "left" ? tr5.left - cw7 - gap : side1 === "right" ? tr5.right + gap : "else" ? align_h(tr5, cw7, align3) : null;
    const top12 = side1 === "top" ? tr5.top - ch8 - gap : side1 === "bottom" ? tr5.bottom + gap : "else" ? align_v(tr5, ch8, align3) : null;
    content.style.left = `${clamp(left11, edge, vw9 - cw7 - edge) ?? ""}px`;
    return content.style.top = `${clamp(top12, edge, vh10 - ch8 - edge) ?? ""}px`;
  };
  var current = { "content": null, "trigger": null };
  var reposition_BANG_ = function() {
    if (truth_(current.content)) {
      return position_BANG_(current.content, current.trigger);
    }
    ;
  };
  var on_toggle = function(e) {
    const content1 = e.target;
    if (truth_((() => {
      const and__23442__auto__2 = content1;
      if (truth_(and__23442__auto__2)) {
        const and__23442__auto__3 = content1.matches;
        if (truth_(and__23442__auto__3)) {
          return content1.matches("[popover].popover-content");
        } else {
          return and__23442__auto__3;
        }
        ;
      } else {
        return and__23442__auto__2;
      }
      ;
    })())) {
      if (e.newState === "open") {
        const id4 = content1.id;
        const trigger5 = truth_(id4) ? document.querySelector(`${'[popovertarget="'}${id4 ?? ""}${'"]'}`) : null;
        if (truth_(trigger5)) {
          current.content = content1;
          current.trigger = trigger5;
          return position_BANG_(content1, trigger5);
        }
        ;
      } else {
        if (current.content === content1) {
          current.content = null;
          return current.trigger = null;
        }
      }
      ;
    }
    ;
  };
  var init_BANG_2 = function() {
    document.addEventListener("toggle", on_toggle, true);
    window.addEventListener("scroll", reposition_BANG_, true);
    return window.addEventListener("resize", reposition_BANG_);
  };
  init_BANG_2();
  window["__uiPopover"] = { "reposition": reposition_BANG_ };

  // .compiled/theme.mjs
  var storage_key = "ui-theme";
  var get_stored = function() {
    return (() => {
      try {
        return localStorage.getItem(storage_key);
      } catch (_e1) {
        return null;
      }
    })();
  };
  var store_BANG_ = function(mode) {
    return (() => {
      try {
        if (mode === "auto") {
          return localStorage.removeItem(storage_key);
        } else {
          return localStorage.setItem(storage_key, mode);
        }
        ;
      } catch (_e1) {
        return null;
      }
    })();
  };
  var system_prefers_dark_QMARK_ = function() {
    return window.matchMedia("(prefers-color-scheme: dark)").matches;
  };
  var resolve_effective = function(mode) {
    const G__51 = mode;
    switch (G__51) {
      case "light":
        return "light";
        break;
      case "dark":
        return "dark";
        break;
      default:
        if (truth_(system_prefers_dark_QMARK_())) {
          return "dark";
        } else {
          return "light";
        }
    }
    ;
  };
  var suppress_transitions_BANG_ = function() {
    const el1 = document.documentElement;
    el1.setAttribute("data-no-transitions", "");
    el1.offsetHeight;
    return requestAnimationFrame((function() {
      return requestAnimationFrame((function() {
        return el1.removeAttribute("data-no-transitions");
      }));
    }));
  };
  var apply_theme_BANG_ = function(mode) {
    const el1 = document.documentElement;
    suppress_transitions_BANG_();
    const G__62 = mode;
    switch (G__62) {
      case "light":
        return el1.setAttribute("data-theme", "light");
        break;
      case "dark":
        return el1.setAttribute("data-theme", "dark");
        break;
      default:
        return el1.removeAttribute("data-theme");
    }
    ;
  };
  var subscribers = atom([]);
  var notify_BANG_ = function(mode, effective) {
    const subs1 = deref(subscribers);
    return subs1.forEach((function(f) {
      return f({ "mode": mode, "effective": effective });
    }));
  };
  var get_mode = function() {
    const or__23426__auto__1 = get_stored();
    if (truth_(or__23426__auto__1)) {
      return or__23426__auto__1;
    } else {
      return "auto";
    }
    ;
  };
  var get_effective = function() {
    return resolve_effective(get_mode());
  };
  var set_mode_BANG_ = function(mode) {
    const m1 = truth_(get(/* @__PURE__ */ new Set(["auto", "light", "dark"]), mode)) ? mode : "auto";
    store_BANG_(m1);
    apply_theme_BANG_(m1);
    return notify_BANG_(m1, resolve_effective(m1));
  };
  var toggle_BANG_ = function() {
    const current1 = get_mode();
    const next_mode2 = (() => {
      const G__73 = current1;
      switch (G__73) {
        case "auto":
          return "light";
          break;
        case "light":
          return "dark";
          break;
        case "dark":
          return "auto";
          break;
        default:
          return "auto";
      }
      ;
    })();
    set_mode_BANG_(next_mode2);
    return next_mode2;
  };
  var subscribe_BANG_ = function(f) {
    swap_BANG_(subscribers, (function(subs) {
      return subs.concat([f]);
    }));
    return function() {
      return swap_BANG_(subscribers, (function(subs) {
        return subs.filter((function(s) {
          return !_EQ_(s, f);
        }));
      }));
    };
  };
  var init_BANG_3 = function() {
    const mode1 = get_mode();
    apply_theme_BANG_(mode1);
    const mql2 = window.matchMedia("(prefers-color-scheme: dark)");
    return mql2.addEventListener("change", (function(_e) {
      if (get_mode() === "auto") {
        apply_theme_BANG_("auto");
        return notify_BANG_("auto", resolve_effective("auto"));
      }
      ;
    }));
  };
  window["__uiTheme"] = { "init": init_BANG_3, "set": set_mode_BANG_, "get": get_mode, "effective": get_effective, "toggle": toggle_BANG_, "subscribe": subscribe_BANG_ };
})();
