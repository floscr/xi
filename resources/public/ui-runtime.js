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
  function contains_QMARK_(coll, v) {
    switch (typeConst(coll)) {
      case SET_TYPE:
      case MAP_TYPE:
        return coll.has(v);
      case void 0:
        return false;
      default:
        return v in coll;
    }
  }
  function nth(coll, idx, orElse) {
    if (coll) {
      var elt = void 0;
      if (Array.isArray(coll)) {
        elt = coll[idx];
      } else {
        const iter = iterable(coll);
        let i = 0;
        for (const value of iter) {
          if (i++ == idx) {
            elt = value;
            break;
          }
        }
      }
      if (elt !== void 0) {
        return elt;
      }
    }
    return orElse;
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
  function _iterator(coll) {
    return coll[Symbol.iterator]();
  }
  var es6_iterator = _iterator;
  function first(coll) {
    const [first2] = iterable(coll);
    return first2;
  }
  function second(coll) {
    const [_, v] = iterable(coll);
    return v;
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
  function map(f, ...colls) {
    f = toFn(f);
    switch (colls.length) {
      case 0:
        return (rf) => {
          return (...args) => {
            switch (args.length) {
              case 0: {
                return rf();
              }
              case 1: {
                return rf(args[0]);
              }
              case 2: {
                return rf(args[0], f(args[1]));
              }
              default: {
                return rf(args[0], f(...args.slice(1)));
              }
            }
          };
        };
      case 1:
        return lazy(function* () {
          for (const x of iterable(colls[0])) {
            yield f(x);
          }
        });
      default:
        return lazy(function* () {
          const iters = colls.map((coll) => es6_iterator(iterable(coll)));
          while (true) {
            const args = [];
            for (const i of iters) {
              const nextVal = i.next();
              if (nextVal.done) {
                return;
              }
              args.push(nextVal.value);
            }
            yield f(...args);
          }
        });
    }
  }
  function filter1(pred) {
    return (rf) => {
      return (...args) => {
        switch (args.length) {
          case 0:
            return rf();
          case 1:
            return rf(args[0]);
          case 2: {
            const result = args[0];
            const input = args[1];
            if (truth_(pred(input))) {
              return rf(result, input);
            } else return result;
          }
        }
      };
    };
  }
  function filter(pred, coll) {
    if (arguments.length === 1) {
      return filter1(pred);
    }
    pred = toFn(pred);
    return lazy(function* () {
      for (const x of iterable(coll)) {
        if (truth_(pred(x))) {
          yield x;
        }
      }
    });
  }
  function remove(pred, coll) {
    if (arguments.length === 1) {
      return filter1(complement(pred));
    }
    return filter(complement(pred), coll);
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
  function vector(...args) {
    return args;
  }
  function mapv(...args) {
    if (args.length === 2) {
      const [_f, coll] = args;
      const f = toFn(_f);
      const iter = iterable(coll);
      if (Array.isArray(iter)) {
        const ret2 = new Array(iter.length);
        for (var i = 0; i < iter.length; i++) {
          ret2[i] = f(iter[i]);
        }
        return ret2;
      } else {
        var ret = [];
        for (const x of iter) {
          ret.push(f(x));
        }
        return ret;
      }
    }
    return [...map(...args)];
  }
  function set(coll) {
    return new Set(iterable(coll));
  }
  var IApply__apply = Symbol("IApply__apply");
  function complement(f) {
    f = toFn(f);
    return (...args) => not(f(...args));
  }
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
  function max(x, y, ...more) {
    if (y == void 0) {
      return x;
    }
    return Math.max(x, y, ...more);
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
  function number_QMARK_(x) {
    return typeof x == "number";
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
  var item_order = function(el) {
    return Number((() => {
      const or__23426__auto__1 = el.style.order;
      if (truth_(or__23426__auto__1)) {
        return or__23426__auto__1;
      } else {
        return 0;
      }
      ;
    })());
  };
  var visible_items = function(dialog) {
    const vis1 = items(dialog).filter((function(el) {
      return not(el.hidden) && not(el.disabled);
    }));
    const container2 = (function(el) {
      const or__23426__auto__3 = el.closest(".command-group");
      if (truth_(or__23426__auto__3)) {
        return or__23426__auto__3;
      } else {
        return dialog;
      }
      ;
    });
    const containers4 = [];
    vis1.forEach((function(el) {
      const c5 = container2(el);
      if (truth_(containers4.includes(c5))) {
        return null;
      } else {
        return containers4.push(c5);
      }
      ;
    }));
    return containers4.flatMap((function(c) {
      return vis1.filter((function(el) {
        return c === container2(el);
      })).sort((function(a, b) {
        return item_order(a) - item_order(b);
      }));
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
  var item_label = function(el) {
    const lbl1 = el.querySelector(".command-item-label");
    return (() => {
      const or__23426__auto__2 = (() => {
        const and__23442__auto__3 = lbl1;
        if (truth_(and__23442__auto__3)) {
          return lbl1.textContent;
        } else {
          return and__23442__auto__3;
        }
        ;
      })();
      if (truth_(or__23426__auto__2)) {
        return or__23426__auto__2;
      } else {
        const or__23426__auto__4 = el.textContent;
        if (truth_(or__23426__auto__4)) {
          return or__23426__auto__4;
        } else {
          return "";
        }
        ;
      }
      ;
    })().toLowerCase();
  };
  var match_score = function(el, q) {
    const label1 = item_label(el);
    if (_EQ_(label1, q)) {
      return 0;
    } else {
      if (truth_(label1.startsWith(q))) {
        return 1;
      } else {
        if (truth_(label1.split(/[^a-z0-9]+/).some((function(w) {
          return w.startsWith(q);
        })))) {
          return 2;
        } else {
          if (truth_(label1.includes(q))) {
            return 3;
          } else {
            if ("else") {
              return 4;
            } else {
              return null;
            }
          }
        }
      }
    }
    ;
  };
  var active_item = function(dialog) {
    return dialog.querySelector(".command-item--active");
  };
  var set_active_BANG_ = (() => {
    const f1 = (function(...args2) {
      const G__31 = args2.length;
      switch (G__31) {
        case 2:
          return f1.cljs$core$IFn$_invoke$arity$2(args2[0], args2[1]);
          break;
        case 3:
          return f1.cljs$core$IFn$_invoke$arity$3(args2[0], args2[1], args2[2]);
          break;
        default:
          throw new Error(`${"Invalid arity: "}${args2.length ?? ""}`);
      }
      ;
    });
    f1.cljs$core$IFn$_invoke$arity$2 = (function(dialog, el) {
      return set_active_BANG_(dialog, el, true);
    });
    f1.cljs$core$IFn$_invoke$arity$3 = (function(dialog, el, scroll_QMARK_) {
      const prev3 = active_item(dialog);
      if (truth_(prev3)) {
        prev3.classList.remove("command-item--active");
      }
      ;
      if (truth_(el)) {
        el.classList.add("command-item--active");
        if (truth_((() => {
          const and__23442__auto__4 = scroll_QMARK_;
          if (truth_(and__23442__auto__4)) {
            return el.scrollIntoView;
          } else {
            return and__23442__auto__4;
          }
          ;
        })())) {
          return el.scrollIntoView({ "block": "nearest" });
        }
        ;
      }
      ;
    });
    f1.cljs$lang$maxFixedArity = 3;
    return f1;
  })();
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
      el.hidden = not(match4);
      if (truth_((() => {
        const or__23426__auto__6 = _EQ_(q1, "");
        if (or__23426__auto__6) {
          return or__23426__auto__6;
        } else {
          return not(match4);
        }
        ;
      })())) {
        return el.style.removeProperty("order");
      } else {
        return el.style.order = match_score(el, q1);
      }
      ;
    }));
    Array.from(dialog.querySelectorAll(".command-group")).forEach((function(grp) {
      const any7 = Array.from(grp.querySelectorAll(".command-item")).some((function(el) {
        return not(el.hidden);
      }));
      return grp.hidden = not(any7);
    }));
    const vis8 = visible_items(dialog);
    if (vis8.length === 0) {
      list3.classList.add("command-list--empty");
    } else {
      list3.classList.remove("command-list--empty");
    }
    ;
    return set_active_BANG_(dialog, vis8.length > 0 ? vis8[0] : null);
  };
  var find_dialog = function(id) {
    if (truth_(id)) {
      return document.getElementById(id);
    }
    ;
  };
  var observe_list_BANG_ = function(dialog) {
    const temp__23062__auto__1 = dialog["__cmdListObs"];
    if (truth_(temp__23062__auto__1)) {
      const prev2 = temp__23062__auto__1;
      prev2.disconnect();
    }
    ;
    const list3 = dialog.querySelector(".command-list");
    if (truth_(list3)) {
      const obs4 = new MutationObserver((function(_, _5) {
        const input6 = dialog.querySelector(".command-input");
        return filter_BANG_(dialog, truth_(input6) ? input6.value : "");
      }));
      obs4.observe(list3, { "childList": true, "subtree": true });
      return dialog["__cmdListObs"] = obs4;
    }
    ;
  };
  var viewport = function() {
    return window.visualViewport;
  };
  var clear_viewport_BANG_ = function(dialog) {
    const s1 = dialog.style;
    s1.removeProperty("--command-top");
    return s1.removeProperty("--command-max-h");
  };
  var sync_viewport_BANG_ = function(dialog) {
    const vv1 = viewport();
    if (truth_((() => {
      const and__23442__auto__2 = dialog;
      if (truth_(and__23442__auto__2)) {
        return vv1;
      } else {
        return and__23442__auto__2;
      }
      ;
    })())) {
      const h3 = vv1.height;
      const off4 = vv1.offsetTop;
      const kb5 = window.innerHeight - h3;
      if (truth_((() => {
        const or__23426__auto__6 = kb5 > 120;
        if (or__23426__auto__6) {
          return or__23426__auto__6;
        } else {
          return off4 > 1;
        }
        ;
      })())) {
        const top_gap7 = Math.max(12, Math.round(h3 * 0.08));
        const bot_gap8 = Math.max(12, Math.round(h3 * 0.06));
        const max_h9 = Math.max(160, h3 - top_gap7 - bot_gap8);
        const s10 = dialog.style;
        s10.setProperty("--command-top", `${off4 + top_gap7}px`);
        return s10.setProperty("--command-max-h", `${max_h9 ?? ""}px`);
      } else {
        return clear_viewport_BANG_(dialog);
      }
      ;
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
      filter_BANG_(dialog1, "");
      observe_list_BANG_(dialog1);
      return sync_viewport_BANG_(dialog1);
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
          if (truth_(key2 === "n" && e.ctrlKey)) {
            e.preventDefault();
            return move_active_BANG_(dialog1, "down");
          } else {
            if (truth_(key2 === "p" && e.ctrlKey)) {
              e.preventDefault();
              return move_active_BANG_(dialog1, "up");
            } else {
              if (truth_(key2 === "j" && e.ctrlKey)) {
                e.preventDefault();
                return move_active_BANG_(dialog1, "down");
              } else {
                if (truth_(key2 === "k" && e.ctrlKey)) {
                  e.preventDefault();
                  return move_active_BANG_(dialog1, "up");
                } else {
                  if (truth_(key2 === "j" && e.altKey)) {
                    e.preventDefault();
                    return move_active_BANG_(dialog1, "down");
                  } else {
                    if (truth_(key2 === "k" && e.altKey)) {
                      e.preventDefault();
                      return move_active_BANG_(dialog1, "up");
                    } else {
                      if (truth_(key2 === "n" && e.altKey)) {
                        e.preventDefault();
                        return move_active_BANG_(dialog1, "down");
                      } else {
                        if (truth_(key2 === "p" && e.altKey)) {
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
                    }
                  }
                }
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
    if (!(e.pointerType === "touch")) {
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
            return set_active_BANG_(dialog4, item2, false);
          }
          ;
        }
        ;
      }
      ;
    }
    ;
  };
  var on_viewport_change = function() {
    const temp__23062__auto__1 = open_dialog();
    if (truth_(temp__23062__auto__1)) {
      const dialog2 = temp__23062__auto__1;
      return sync_viewport_BANG_(dialog2);
    }
    ;
  };
  var on_dialog_close = function(e) {
    const t1 = e.target;
    if (truth_((() => {
      const and__23442__auto__2 = t1;
      if (truth_(and__23442__auto__2)) {
        const and__23442__auto__3 = t1.classList;
        if (truth_(and__23442__auto__3)) {
          return t1.classList.contains("command-dialog");
        } else {
          return and__23442__auto__3;
        }
        ;
      } else {
        return and__23442__auto__2;
      }
      ;
    })())) {
      return clear_viewport_BANG_(t1);
    }
    ;
  };
  var init_BANG_ = function() {
    document.addEventListener("input", on_input, true);
    document.addEventListener("keydown", on_keydown, true);
    document.addEventListener("keydown", on_global_key);
    document.addEventListener("click", on_click);
    document.addEventListener("pointermove", on_pointermove, true);
    document.addEventListener("close", on_dialog_close, true);
    const temp__23062__auto__1 = viewport();
    if (truth_(temp__23062__auto__1)) {
      const vv2 = temp__23062__auto__1;
      vv2.addEventListener("resize", on_viewport_change);
      return vv2.addEventListener("scroll", on_viewport_change);
    }
    ;
  };
  init_BANG_();
  window["__uiCommand"] = { "open": open, "close": close, "toggle": toggle };

  // .compiled/context_menu.mjs
  var get_state = function() {
    const or__23426__auto__1 = window["__uiCtxState"];
    if (truth_(or__23426__auto__1)) {
      return or__23426__auto__1;
    } else {
      const s2 = { "menu": null, "cleanup": null, "growthObserver": null };
      window["__uiCtxState"] = s2;
      return s2;
    }
    ;
  };
  var create_icon = function(elements) {
    const ns_uri1 = "http://www.w3.org/2000/svg";
    const svg2 = document.createElementNS(ns_uri1, "svg");
    svg2.setAttribute("viewBox", "0 0 24 24");
    svg2.setAttribute("width", "18");
    svg2.setAttribute("height", "18");
    svg2.setAttribute("fill", "none");
    svg2.setAttribute("stroke", "currentColor");
    svg2.setAttribute("stroke-width", "1.6");
    svg2.setAttribute("stroke-linecap", "round");
    svg2.setAttribute("stroke-linejoin", "round");
    svg2.setAttribute("style", "flex-shrink:0");
    elements.forEach((function(el) {
      const tag3 = el[0];
      const attrs4 = el[1];
      const node5 = document.createElementNS(ns_uri1, tag3);
      attrs4.forEach((function(pair) {
        return node5.setAttribute(pair[0], pair[1]);
      }));
      return svg2.appendChild(node5);
    }));
    return svg2;
  };
  var dismiss_BANG_ = function() {
    const state1 = get_state();
    if (truth_(state1.menu)) {
      state1.menu.remove();
      state1.menu = null;
      document.dispatchEvent(new CustomEvent("clj-ui-menu-dismiss"));
    }
    ;
    if (truth_(state1.growthObserver)) {
      state1.growthObserver.disconnect();
      state1.growthObserver = null;
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
  var clamp_to_viewport_BANG_ = function(menu) {
    const x1 = parseFloat(menu.style.left);
    const y2 = parseFloat(menu.style.top);
    const w3 = menu.offsetWidth;
    const h4 = menu.offsetHeight;
    const vw5 = window.innerWidth;
    const vh6 = window.innerHeight;
    if (x1 + w3 > vw5 - 8) {
      menu.style.left = `${max(8, vw5 - w3 - 8) ?? ""}px`;
    }
    ;
    if (y2 + h4 > vh6 - 8) {
      return menu.style.top = `${max(8, vh6 - h4 - 8) ?? ""}px`;
    }
    ;
  };
  var position_menu_BANG_ = function(menu, x, y) {
    menu.style.left = `${x ?? ""}px`;
    menu.style.top = `${y ?? ""}px`;
    document.body.appendChild(menu);
    clamp_to_viewport_BANG_(menu);
    const mo1 = new MutationObserver((function(_) {
      return clamp_to_viewport_BANG_(menu);
    }));
    mo1.observe(menu, { "childList": true, "subtree": true });
    return get_state().growthObserver = mo1;
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
    const f4 = (function(var_args) {
      const args51 = [];
      const len__23321__auto__2 = arguments.length;
      let i63 = 0;
      while (true) {
        if (i63 < len__23321__auto__2) {
          args51.push(arguments[i63]);
          let G__4 = i63 + 1;
          i63 = G__4;
          continue;
        }
        ;
        break;
      }
      ;
      const argseq__23513__auto__5 = 1 < args51.length ? args51.slice(1) : null;
      return f4.cljs$core$IFn$_invoke$arity$variadic(arguments[0], argseq__23513__auto__5);
    });
    f4.cljs$core$IFn$_invoke$arity$variadic = (function(event, args) {
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
          e.preventDefault();
          e.stopPropagation();
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
    f4.cljs$lang$maxFixedArity = 1;
    return f4;
  })();
  window["__uiContextMenu"] = open_context_menu;

  // .compiled/drop_zone.mjs
  var closest_zone = function(el) {
    if (truth_((() => {
      const and__23442__auto__1 = el;
      if (truth_(and__23442__auto__1)) {
        return el.closest;
      } else {
        return and__23442__auto__1;
      }
      ;
    })())) {
      return el.closest("[data-ui-drop-zone]");
    }
    ;
  };
  var emit_files_BANG_ = function(zone, files) {
    if (files.length > 0) {
      return zone.dispatchEvent(new CustomEvent("ui:drop-zone-files", { "bubbles": true, "detail": { "files": files } }));
    }
    ;
  };
  var on_dragover = function(e) {
    const temp__23062__auto__1 = closest_zone(e.target);
    if (truth_(temp__23062__auto__1)) {
      const zone2 = temp__23062__auto__1;
      e.preventDefault();
      return zone2.classList.add("drop-zone-active");
    }
    ;
  };
  var on_dragleave = function(e) {
    const temp__23062__auto__1 = closest_zone(e.target);
    if (truth_(temp__23062__auto__1)) {
      const zone2 = temp__23062__auto__1;
      if (truth_((() => {
        const and__23442__auto__3 = e.relatedTarget;
        if (truth_(and__23442__auto__3)) {
          return zone2.contains(e.relatedTarget);
        } else {
          return and__23442__auto__3;
        }
        ;
      })())) {
        return null;
      } else {
        return zone2.classList.remove("drop-zone-active");
      }
      ;
    }
    ;
  };
  var on_drop = function(e) {
    const temp__23062__auto__1 = closest_zone(e.target);
    if (truth_(temp__23062__auto__1)) {
      const zone2 = temp__23062__auto__1;
      e.preventDefault();
      zone2.classList.remove("drop-zone-active");
      return emit_files_BANG_(zone2, Array.from(e.dataTransfer.files));
    }
    ;
  };
  var on_change = function(e) {
    const input1 = e.target;
    if (truth_((() => {
      const and__23442__auto__2 = input1;
      if (truth_(and__23442__auto__2)) {
        const and__23442__auto__3 = input1.matches;
        if (truth_(and__23442__auto__3)) {
          return input1.matches('input[type="file"]');
        } else {
          return and__23442__auto__3;
        }
        ;
      } else {
        return and__23442__auto__2;
      }
      ;
    })())) {
      const temp__23062__auto__4 = closest_zone(input1);
      if (truth_(temp__23062__auto__4)) {
        const zone5 = temp__23062__auto__4;
        return emit_files_BANG_(zone5, Array.from(input1.files));
      }
      ;
    }
    ;
  };
  var init_BANG_2 = function() {
    document.addEventListener("dragover", on_dragover);
    document.addEventListener("dragleave", on_dragleave);
    document.addEventListener("drop", on_drop);
    return document.addEventListener("change", on_change);
  };
  init_BANG_2();

  // .compiled/gestures.mjs
  var press_ms = 500;
  var slop_px = 10;
  var selector = ".context-menu-trigger, [data-long-press]";
  var press_class = "clj-ui-pressing";
  var press = atom(null);
  var suppress_click_QMARK_ = atom(false);
  var held = atom(null);
  var clear_press_visual_BANG_ = function(el) {
    if (truth_(el)) {
      return el.classList.remove(press_class);
    }
    ;
  };
  var clear_held_BANG_ = function() {
    const temp__23062__auto__1 = deref(held);
    if (truth_(temp__23062__auto__1)) {
      const el2 = temp__23062__auto__1;
      clear_press_visual_BANG_(el2);
      return reset_BANG_(held, null);
    }
    ;
  };
  var cancel_BANG_ = function() {
    const temp__23062__auto__1 = deref(press);
    if (truth_(temp__23062__auto__1)) {
      const p2 = temp__23062__auto__1;
      clearTimeout(get(p2, "timer"));
      clear_press_visual_BANG_(get(p2, "el"));
      return reset_BANG_(press, null);
    }
    ;
  };
  var dispatch_contextmenu_BANG_ = function(el, x, y) {
    return el.dispatchEvent(new MouseEvent("contextmenu", { "bubbles": true, "cancelable": true, "view": window, "clientX": x, "clientY": y }));
  };
  var on_pointerdown = function(e) {
    reset_BANG_(suppress_click_QMARK_, false);
    clear_held_BANG_();
    if (e.pointerType === "touch") {
      const temp__23062__auto__1 = (() => {
        const G__82 = e.target;
        if (G__82 == null) {
          return null;
        } else {
          return G__82.closest(selector);
        }
        ;
      })();
      if (truth_(temp__23062__auto__1)) {
        const el3 = temp__23062__auto__1;
        cancel_BANG_();
        const x4 = e.clientX;
        const y5 = e.clientY;
        el3.classList.add(press_class);
        return reset_BANG_(press, { "el": el3, "x": x4, "y": y5, "timer": setTimeout((function() {
          reset_BANG_(press, null);
          reset_BANG_(held, el3);
          reset_BANG_(suppress_click_QMARK_, true);
          return dispatch_contextmenu_BANG_(el3, x4, y5);
        }), press_ms) });
      }
      ;
    }
    ;
  };
  var on_pointermove2 = function(e) {
    const temp__23062__auto__1 = deref(press);
    if (truth_(temp__23062__auto__1)) {
      const p2 = temp__23062__auto__1;
      if (truth_((() => {
        const or__23426__auto__3 = Math.abs(e.clientX - get(p2, "x")) > slop_px;
        if (or__23426__auto__3) {
          return or__23426__auto__3;
        } else {
          return Math.abs(e.clientY - get(p2, "y")) > slop_px;
        }
        ;
      })())) {
        return cancel_BANG_();
      }
      ;
    }
    ;
  };
  var on_pointer_end = function(_e) {
    return cancel_BANG_();
  };
  var on_native_contextmenu = function(e) {
    if (truth_(e.isTrusted)) {
      const temp__23062__auto__1 = deref(press);
      if (truth_(temp__23062__auto__1)) {
        const p2 = temp__23062__auto__1;
        clearTimeout(get(p2, "timer"));
        reset_BANG_(press, null);
        return reset_BANG_(held, get(p2, "el"));
      }
      ;
    }
    ;
  };
  var on_click_capture = function(e) {
    if (truth_(deref(suppress_click_QMARK_))) {
      reset_BANG_(suppress_click_QMARK_, false);
      e.preventDefault();
      return e.stopPropagation();
    }
    ;
  };
  document.addEventListener("pointerdown", on_pointerdown, true);
  document.addEventListener("pointermove", on_pointermove2, true);
  document.addEventListener("pointerup", on_pointer_end, true);
  document.addEventListener("pointercancel", on_pointer_end, true);
  document.addEventListener("contextmenu", on_native_contextmenu, true);
  document.addEventListener("click", on_click_capture, true);
  document.addEventListener("clj-ui-menu-dismiss", (function(_) {
    return clear_held_BANG_();
  }));
  window["__uiLongPress"] = dispatch_contextmenu_BANG_;

  // .compiled/masonry.mjs
  var raf = atom(null);
  var observed = /* @__PURE__ */ new Set();
  var ro = new ResizeObserver((function(_) {
    return schedule_BANG_();
  }));
  var native_QMARK_ = function(container) {
    return "masonry" === getComputedStyle(container).gridTemplateRows;
  };
  var clear_BANG_ = function(container) {
    container.style.gridAutoRows = "";
    container.style.removeProperty("row-gap");
    for (let G__1 of iterable(Array.from(container.children))) {
      const item2 = G__1;
      item2.style.gridRowEnd = "";
    }
    return null;
  };
  var layout_BANG_ = function(container) {
    const items1 = Array.from(container.children);
    container.style.gridAutoRows = "0px";
    container.style.setProperty("row-gap", "1px", "important");
    const col_gap2 = parseFloat(getComputedStyle(container).columnGap);
    const col_gap3 = truth_(isNaN(col_gap2)) ? 0 : col_gap2;
    const heights4 = mapv((function(item) {
      return item.offsetHeight;
    }), items1);
    for (let G__5 of iterable(map(vector, items1, heights4))) {
      const vec__69 = G__5;
      const item10 = nth(vec__69, 0, null);
      const h11 = nth(vec__69, 1, null);
      item10.style.gridRowEnd = `${"span "}${Math.round(h11 + col_gap3) ?? ""}`;
    }
    return null;
  };
  var run_BANG_ = function() {
    const els1 = Array.from(document.querySelectorAll(".tile-grid-masonry"));
    for (let G__2 of iterable(els1)) {
      const el3 = G__2;
      if (truth_(observed.has(el3))) {
      } else {
        observed.add(el3);
        ro.observe(el3);
      }
    }
    ;
    for (let G__4 of iterable(Array.from(observed))) {
      const el5 = G__4;
      if (truth_((() => {
        const or__23426__auto__6 = not(el5.isConnected);
        if (or__23426__auto__6) {
          return or__23426__auto__6;
        } else {
          return not(el5.classList.contains("tile-grid-masonry"));
        }
        ;
      })())) {
        observed.delete(el5);
        ro.unobserve(el5);
        if (truth_(el5.isConnected)) {
          clear_BANG_(el5);
        }
      }
    }
    ;
    for (let G__7 of iterable(els1)) {
      const el8 = G__7;
      if (truth_(native_QMARK_(el8))) {
        clear_BANG_(el8);
      } else {
        layout_BANG_(el8);
      }
    }
    return null;
  };
  var schedule_BANG_ = function() {
    if (truth_(deref(raf))) {
      cancelAnimationFrame(deref(raf));
    }
    ;
    return reset_BANG_(raf, requestAnimationFrame((function(_) {
      reset_BANG_(raf, null);
      return run_BANG_();
    })));
  };
  var init_BANG_3 = function() {
    schedule_BANG_();
    const obs1 = new MutationObserver((function(_, _2) {
      return schedule_BANG_();
    }));
    obs1.observe(document.body, { "childList": true, "subtree": true, "attributes": true, "attributeFilter": ["class"] });
    return document.addEventListener("load", (function(e) {
      const target3 = e.target;
      if (truth_("IMG" === target3.tagName && target3.closest(".tile-grid-masonry"))) {
        return schedule_BANG_();
      }
      ;
    }), true);
  };
  window["__uiMasonry"] = schedule_BANG_;
  if ("loading" === document.readyState) {
    document.addEventListener("DOMContentLoaded", init_BANG_3);
  } else {
    init_BANG_3();
  }

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
  var init_BANG_4 = function() {
    document.addEventListener("toggle", on_toggle, true);
    window.addEventListener("scroll", reposition_BANG_, true);
    return window.addEventListener("resize", reposition_BANG_);
  };
  init_BANG_4();
  window["__uiPopover"] = { "reposition": reposition_BANG_ };

  // .compiled/select.mjs
  var get_state2 = function() {
    const or__23426__auto__1 = window["__uiSelectState"];
    if (truth_(or__23426__auto__1)) {
      return or__23426__auto__1;
    } else {
      const s2 = { "menu": null, "trigger": null, "cleanup": null };
      window["__uiSelectState"] = s2;
      return s2;
    }
    ;
  };
  var dismiss_BANG_2 = function() {
    const state1 = get_state2();
    if (truth_(state1.menu)) {
      state1.menu.remove();
      state1.menu = null;
    }
    ;
    if (truth_(state1.trigger)) {
      state1.trigger.setAttribute("aria-expanded", "false");
      state1.trigger = null;
    }
    ;
    if (truth_(state1.cleanup)) {
      state1.cleanup();
      return state1.cleanup = null;
    }
    ;
  };
  var update_trigger_BANG_ = function(trigger, value, label) {
    trigger.dataset.selectValue = value;
    const span1 = trigger.querySelector(".select-value");
    if (truth_(span1)) {
      span1.textContent = label;
      span1.classList.remove("select-value--placeholder");
    }
    ;
    const wrap2 = trigger.parentElement;
    const input3 = truth_(wrap2) ? wrap2.querySelector("input[type=hidden]") : null;
    if (truth_(input3)) {
      input3.value = value;
      return input3.dispatchEvent(new Event("change", { "bubbles": true }));
    }
    ;
  };
  var create_menu2 = function(options, current_value, on_change2, trigger) {
    const menu1 = document.createElement("div");
    menu1.className = "select-menu";
    menu1.setAttribute("role", "listbox");
    options.forEach((function(opt) {
      const value2 = opt["value"];
      const label3 = opt["label"];
      const selected4 = _EQ_(value2, current_value);
      const el5 = document.createElement("button");
      el5.className = selected4 ? "select-option select-option--selected" : "select-option";
      el5.setAttribute("type", "button");
      el5.setAttribute("role", "option");
      el5.setAttribute("tabindex", "-1");
      el5.setAttribute("aria-selected", selected4 ? "true" : "false");
      el5.textContent = label3;
      el5.addEventListener("click", (function(e) {
        e.preventDefault();
        e.stopPropagation();
        if (truth_(on_change2)) {
          dismiss_BANG_2();
          return on_change2(value2);
        } else {
          update_trigger_BANG_(trigger, value2, label3);
          return dismiss_BANG_2();
        }
        ;
      }));
      return menu1.appendChild(el5);
    }));
    return menu1;
  };
  var position_menu_BANG_2 = function(menu, trigger) {
    const r1 = trigger.getBoundingClientRect();
    menu.style.minWidth = `${r1.width ?? ""}px`;
    document.body.appendChild(menu);
    const mr2 = menu.getBoundingClientRect();
    const vw3 = window.innerWidth;
    const vh4 = window.innerHeight;
    const left5 = Math.max(8, Math.min(r1.left, vw3 - mr2.width - 8));
    const below6 = r1.bottom + 4;
    const top7 = below6 + mr2.height > vh4 ? Math.max(8, r1.top - mr2.height - 4) : below6;
    menu.style.left = `${left5 ?? ""}px`;
    return menu.style.top = `${top7 ?? ""}px`;
  };
  var focus_selected_or_first_BANG_ = function(menu) {
    const sel1 = menu.querySelector(".select-option--selected");
    if (truth_(sel1)) {
      return sel1.focus();
    } else {
      const first_opt2 = menu.querySelector(".select-option");
      if (truth_(first_opt2)) {
        return first_opt2.focus();
      }
      ;
    }
    ;
  };
  var focus_next_BANG_2 = function(menu, direction) {
    const items1 = menu.querySelectorAll(".select-option");
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
  var open_select = (() => {
    const f9 = (function(var_args) {
      const args101 = [];
      const len__23321__auto__2 = arguments.length;
      let i113 = 0;
      while (true) {
        if (i113 < len__23321__auto__2) {
          args101.push(arguments[i113]);
          let G__4 = i113 + 1;
          i113 = G__4;
          continue;
        }
        ;
        break;
      }
      ;
      const argseq__23513__auto__5 = 1 < args101.length ? args101.slice(1) : null;
      return f9.cljs$core$IFn$_invoke$arity$variadic(arguments[0], argseq__23513__auto__5);
    });
    f9.cljs$core$IFn$_invoke$arity$variadic = (function(trigger, args) {
      dismiss_BANG_2();
      const options6 = (() => {
        const passed7 = first(args);
        if (truth_(passed7)) {
          return passed7;
        } else {
          const json8 = trigger.dataset.selectOptions;
          if (truth_(json8)) {
            return JSON.parse(json8);
          }
          ;
        }
        ;
      })();
      const on_change9 = second(args);
      const current10 = trigger.dataset.selectValue;
      if (truth_(options6)) {
        const menu11 = create_menu2(options6, current10, on_change9, trigger);
        const state12 = get_state2();
        state12.menu = menu11;
        state12.trigger = trigger;
        trigger.setAttribute("aria-expanded", "true");
        position_menu_BANG_2(menu11, trigger);
        focus_selected_or_first_BANG_(menu11);
        const on_click13 = (function(e) {
          if (not(menu11.contains(e.target))) {
            e.preventDefault();
            e.stopPropagation();
            return dismiss_BANG_2();
          }
          ;
        });
        const on_key14 = (function(e) {
          const key15 = e.key;
          if (key15 === "Escape") {
            e.preventDefault();
            dismiss_BANG_2();
            return trigger.focus();
          } else {
            if (key15 === "ArrowDown") {
              e.preventDefault();
              return focus_next_BANG_2(menu11, "down");
            } else {
              if (key15 === "ArrowUp") {
                e.preventDefault();
                return focus_next_BANG_2(menu11, "up");
              } else {
                if (key15 === "Enter") {
                  const active16 = document.activeElement;
                  if (truth_((() => {
                    const and__23442__auto__17 = active16;
                    if (truth_(and__23442__auto__17)) {
                      return menu11.contains(active16);
                    } else {
                      return and__23442__auto__17;
                    }
                    ;
                  })())) {
                    e.preventDefault();
                    return active16.click();
                  }
                  ;
                } else {
                  if (key15 === "Tab") {
                    return dismiss_BANG_2();
                  } else {
                    return null;
                  }
                }
              }
            }
          }
          ;
        });
        const on_scroll18 = (function(_) {
          return dismiss_BANG_2();
        });
        const on_resize19 = (function(_) {
          return dismiss_BANG_2();
        });
        const cleanup20 = (function() {
          document.removeEventListener("click", on_click13, true);
          document.removeEventListener("keydown", on_key14, true);
          window.removeEventListener("scroll", on_scroll18, true);
          return window.removeEventListener("resize", on_resize19);
        });
        document.addEventListener("click", on_click13, true);
        document.addEventListener("keydown", on_key14, true);
        window.addEventListener("scroll", on_scroll18, true);
        window.addEventListener("resize", on_resize19);
        return state12.cleanup = cleanup20;
      }
      ;
    });
    f9.cljs$lang$maxFixedArity = 1;
    return f9;
  })();
  window["__uiSelect"] = open_select;

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
    const G__131 = mode;
    switch (G__131) {
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
    const G__142 = mode;
    switch (G__142) {
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
      const G__153 = current1;
      switch (G__153) {
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
  var init_BANG_5 = function() {
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
  window["__uiTheme"] = { "init": init_BANG_5, "set": set_mode_BANG_, "get": get_mode, "effective": get_effective, "toggle": toggle_BANG_, "subscribe": subscribe_BANG_ };

  // .compiled/toast.mjs
  var container_id = "ui-toast-container";
  var ensure_container_BANG_ = function() {
    const or__23426__auto__1 = document.getElementById(container_id);
    if (truth_(or__23426__auto__1)) {
      return or__23426__auto__1;
    } else {
      const el2 = document.createElement("div");
      el2.id = container_id;
      el2.className = "toast-container";
      document.body.appendChild(el2);
      return el2;
    }
    ;
  };
  var dismiss_BANG_3 = function(el) {
    if (truth_(el["__uiToastDismissed"])) {
      return null;
    } else {
      el["__uiToastDismissed"] = true;
      el.classList.add("toast-leaving");
      return setTimeout((function() {
        return el.remove();
      }), 300);
    }
    ;
  };
  var show_BANG_ = function(message, opts) {
    const opts1 = (() => {
      const or__23426__auto__2 = opts;
      if (truth_(or__23426__auto__2)) {
        return or__23426__auto__2;
      } else {
        return {};
      }
      ;
    })();
    const variant3 = (() => {
      const or__23426__auto__4 = opts1["variant"];
      if (truth_(or__23426__auto__4)) {
        return or__23426__auto__4;
      } else {
        return "info";
      }
      ;
    })();
    const raw_dur5 = opts1["duration"];
    const duration6 = truth_(number_QMARK_(raw_dur5)) ? raw_dur5 : 5e3;
    const container7 = ensure_container_BANG_();
    const el8 = document.createElement("div");
    el8.className = `${"toast toast-"}${variant3 ?? ""}`;
    el8.textContent = `${message ?? ""}`;
    el8.setAttribute("role", "status");
    el8.addEventListener("click", (function(_) {
      return dismiss_BANG_3(el8);
    }));
    container7.appendChild(el8);
    if (duration6 > 0) {
      setTimeout((function() {
        return dismiss_BANG_3(el8);
      }), duration6);
    }
    ;
    return el8;
  };
  var consume_BANG_ = function(el) {
    const msg1 = el.getAttribute("data-ui-toast");
    const dur2 = el.getAttribute("data-duration");
    el.remove();
    return show_BANG_(msg1, { "variant": (() => {
      const or__23426__auto__3 = el.getAttribute("data-variant");
      if (truth_(or__23426__auto__3)) {
        return or__23426__auto__3;
      } else {
        return "info";
      }
      ;
    })(), "duration": truth_(dur2) ? parseInt(dur2, 10) : null });
  };
  var scan_BANG_ = function() {
    for (let G__1 of iterable(Array.from(document.querySelectorAll("[data-ui-toast]")))) {
      const el2 = G__1;
      consume_BANG_(el2);
    }
    return null;
  };
  var init_BANG_6 = function() {
    scan_BANG_();
    const obs1 = new MutationObserver((function(_, _2) {
      return scan_BANG_();
    }));
    return obs1.observe(document.body, { "childList": true, "subtree": true });
  };
  window["__uiToast"] = show_BANG_;
  if ("loading" === document.readyState) {
    document.addEventListener("DOMContentLoaded", init_BANG_6);
  } else {
    init_BANG_6();
  }

  // ../../../dev/squint/node_modules/squint-cljs/src/squint/string.js
  function join(sep, coll) {
    if (coll === void 0) {
      coll = sep;
      sep = "";
    }
    if (coll instanceof Array) {
      return coll.join(sep);
    }
    let ret = "";
    let addSep = false;
    for (const o of iterable(coll)) {
      if (addSep) ret += sep;
      ret += o;
      addSep = true;
    }
    return ret;
  }

  // .compiled/touch.mjs
  var mq = window.matchMedia("(hover: none)");
  var viewport_overrides = [["width", "device-width"], ["initial-scale", "1.0"], ["maximum-scale", "1.0"], ["user-scalable", "no"], ["viewport-fit", "cover"]];
  var merge_viewport = function(existing) {
    const entries1 = map((function(s) {
      const i2 = s.indexOf("=");
      if (i2 < 0) {
        return [s, null];
      } else {
        return [s.slice(0, i2).trim(), s.slice(i2 + 1).trim()];
      }
      ;
    }), remove((function(s) {
      return _EQ_("", s);
    }), map((function(s) {
      return s.trim();
    }), (() => {
      const or__23426__auto__3 = existing;
      if (truth_(or__23426__auto__3)) {
        return or__23426__auto__3;
      } else {
        return "";
      }
      ;
    })().split(","))));
    const override_keys4 = set(map(first, viewport_overrides));
    const kept5 = remove((function(p__16) {
      const vec__69 = p__16;
      const k10 = nth(vec__69, 0, null);
      const _11 = nth(vec__69, 1, null);
      return contains_QMARK_(override_keys4, k10);
    }), entries1);
    return join(", ", map((function(p__17) {
      const vec__1215 = p__17;
      const k16 = nth(vec__1215, 0, null);
      const v17 = nth(vec__1215, 1, null);
      if (v17 == null) {
        return k16;
      } else {
        return `${k16 ?? ""}${"="}${v17 ?? ""}`;
      }
      ;
    }), concat(kept5, viewport_overrides)));
  };
  var harden_viewport_BANG_ = function() {
    const temp__23007__auto__1 = document.querySelector("meta[name=viewport]");
    if (truth_(temp__23007__auto__1)) {
      const meta_el2 = temp__23007__auto__1;
      return meta_el2.setAttribute("content", merge_viewport(meta_el2.getAttribute("content")));
    } else {
      const m3 = document.createElement("meta");
      m3.setAttribute("name", "viewport");
      m3.setAttribute("content", merge_viewport(null));
      return document.head.appendChild(m3);
    }
    ;
  };
  var sync_BANG_ = function() {
    const touch_QMARK_1 = mq.matches;
    const cl2 = document.documentElement.classList;
    if (truth_(touch_QMARK_1)) {
      cl2.add("clj-ui-touch");
    } else {
      cl2.remove("clj-ui-touch");
    }
    ;
    return touch_QMARK_1;
  };
  window["__uiTouch"] = sync_BANG_;
  if (truth_(sync_BANG_())) {
    harden_viewport_BANG_();
    document.addEventListener("gesturestart", (function(e) {
      return e.preventDefault();
    }));
  }
  mq.addEventListener("change", sync_BANG_);
})();
