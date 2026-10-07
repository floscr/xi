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
  function mapAssocMut(m, k, v) {
    m.set(k, v);
    return m;
  }
  function objAssocMut(m, k, v) {
    m[k] = v;
    return m;
  }
  function getAssocMut(m) {
    switch (typeConst(m)) {
      case MAP_TYPE:
        return mapAssocMut;
      case ARRAY_TYPE:
      case OBJECT_TYPE:
        return objAssocMut;
    }
  }
  function assoc_BANG_(m, k, v, ...kvs) {
    if (kvs.length % 2 !== 0) {
      throw new Error("Illegal argument: assoc expects an odd number of arguments.");
    }
    switch (typeConst(m)) {
      case MAP_TYPE:
        m.set(k, v);
        for (let i = 0; i < kvs.length; i += 2) {
          m.set(kvs[i], kvs[i + 1]);
        }
        break;
      case ARRAY_TYPE:
      case OBJECT_TYPE:
        m[k] = v;
        for (let i = 0; i < kvs.length; i += 2) {
          m[kvs[i]] = kvs[i + 1];
        }
        break;
      default:
        throw new Error(
          `Illegal argument: assoc! expects a Map, Array, or Object as the first argument, but got ${typeof m}.`
        );
    }
    return m;
  }
  function copy(o) {
    switch (typeConst(o)) {
      case MAP_TYPE:
        return new Map(o);
      case SET_TYPE:
        return new o.constructor(o);
      case ARRAY_TYPE:
        return [...o];
      case OBJECT_TYPE:
        return { ...o };
      default:
        throw new Error(`Don't know how to copy object of type ${typeof o}.`);
    }
  }
  function assoc(o, k, v, ...kvs) {
    if (!o) {
      o = {};
    }
    const ret = copy(o);
    assoc_BANG_(ret, k, v, ...kvs);
    return ret;
  }
  var MAP_TYPE = 1;
  var ARRAY_TYPE = 2;
  var OBJECT_TYPE = 3;
  var LIST_TYPE = 4;
  var SET_TYPE = 5;
  var LAZY_ITERABLE_TYPE = 6;
  function emptyOfType(type) {
    switch (type) {
      case MAP_TYPE:
        return /* @__PURE__ */ new Map();
      case ARRAY_TYPE:
        return [];
      case OBJECT_TYPE:
        return {};
      // Object.create?
      case LIST_TYPE:
        return new List();
      case SET_TYPE:
        return /* @__PURE__ */ new Set();
      case LAZY_ITERABLE_TYPE:
        return lazy(function* () {
          return;
        });
    }
    return void 0;
  }
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
  function assoc_in_with(f, fname, o, keys, value) {
    keys = vec(keys);
    o = o || {};
    const baseType = typeConst(o);
    if (baseType !== MAP_TYPE && baseType !== ARRAY_TYPE && baseType !== OBJECT_TYPE)
      throw new Error(
        `Illegal argument: ${fname} expects the first argument to be a Map, Array, or Object.`
      );
    const chain = [o];
    let lastInChain = o;
    for (let i = 0; i < keys.length - 1; i += 1) {
      const k = keys[i];
      let chainValue;
      if (lastInChain instanceof Map) chainValue = lastInChain.get(k);
      else chainValue = lastInChain[k];
      if (!chainValue) {
        chainValue = emptyOfType(baseType);
      }
      chain.push(chainValue);
      lastInChain = chainValue;
    }
    chain.push(value);
    for (let i = chain.length - 2; i >= 0; i -= 1) {
      chain[i] = f(chain[i], keys[i], chain[i + 1]);
    }
    return chain[0];
  }
  function assoc_in(o, keys, value) {
    return assoc_in_with(assoc, "assoc-in", o, keys, value);
  }
  function conj_BANG_set(o, rest) {
    for (const x of rest) {
      o.add(x);
    }
    return o;
  }
  function conj(...xs) {
    if (xs.length === 0) {
      return vector();
    }
    const [_o, ...rest] = xs;
    let o = _o;
    if (o === null || o === void 0) {
      o = list();
    }
    let m, o2;
    switch (typeConst(o)) {
      case SET_TYPE:
        if (o instanceof SortedSet) {
          return conj_BANG_set(new o.constructor(o), rest);
        } else {
          return new o.constructor([...o, ...rest]);
        }
      case LIST_TYPE:
        return new List(...rest.reverse(), ...o);
      case ARRAY_TYPE:
        return [...o, ...rest];
      case MAP_TYPE:
        m = new Map(o);
        for (const x of rest) {
          if (!Array.isArray(x))
            iterable(x).forEach((kv) => {
              m.set(kv[0], kv[1]);
            });
          else m.set(x[0], x[1]);
        }
        return m;
      case LAZY_ITERABLE_TYPE:
        return lazy(function* () {
          yield* rest;
          yield* o;
        });
      case OBJECT_TYPE:
        o2 = { ...o };
        for (const x of rest) {
          if (!Array.isArray(x)) Object.assign(o2, x);
          else o2[x[0]] = x[1];
        }
        return o2;
      default:
        throw new Error(
          "Illegal argument: conj expects a Set, Array, List, Map, or Object as the first argument."
        );
    }
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
  function seq(x) {
    if (x == null) return x;
    const iter = iterable(x);
    if (iter.length === 0 || iter.size === 0) {
      return null;
    }
    const _i = iter[Symbol.iterator]();
    if (_i.next().done) return null;
    return iter;
  }
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
  function vec(x) {
    if (array_QMARK_(x)) {
      return x;
    }
    return [...iterable(x)];
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
  function list(...args) {
    return new List(...args);
  }
  function array_QMARK_(x) {
    return Array.isArray(x);
  }
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
  function empty(coll) {
    const type = typeConst(coll);
    if (type != null) {
      return emptyOfType(type);
    } else {
      throw new Error(`Can't create empty of ${typeof coll}`);
    }
  }
  function get_in(coll, path, orElse) {
    let entry = coll;
    for (const item of path) {
      entry = get(entry, item);
    }
    if (entry === void 0) return orElse;
    return entry;
  }
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
  function empty_QMARK_(coll) {
    return seq(coll) ? false : true;
  }
  function boolean$(x) {
    return !!x;
  }
  function reduce_kv(f, init, m) {
    if (!m) {
      return init;
    }
    var ret = init;
    for (const o of iterable(m)) {
      ret = f(ret, o[0], o[1]);
    }
    return ret;
  }
  function max(x, y, ...more) {
    if (y == void 0) {
      return x;
    }
    return Math.max(x, y, ...more);
  }
  function map_QMARK_(coll) {
    if (coll == null) return false;
    if (isObj(coll)) return true;
    if (coll instanceof Map) return true;
    return false;
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
  function string_QMARK_(s) {
    return typeof s === "string";
  }
  var _metaSym = Symbol("meta");
  function mod(x, y) {
    return (x % y + y) % y;
  }
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
  function update_vals(m, f) {
    const m2 = empty(m);
    const assocFn = getAssocMut(m) || assoc_BANG_;
    reduce_kv(
      (acc, k, v) => {
        return assocFn(acc, k, f(v));
      },
      m2,
      m
    );
    return m2;
  }
  function clj__GT_js_(x, seen) {
    if (seen.has(x)) return x;
    seen.add(x);
    if (map_QMARK_(x)) {
      return update_vals(x, (x2) => clj__GT_js_(x2, seen));
    }
    const tc = typeConst(x);
    if (tc && tc != OBJECT_TYPE) {
      return mapv((x2) => clj__GT_js_(x2, seen), x);
    }
    return x;
  }
  function clj__GT_js(x) {
    return clj__GT_js_(x, /* @__PURE__ */ new Set());
  }

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
    containers4.sort((function(a, b) {
      return item_order(a) - item_order(b);
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
    return (!(v1 == null) ? v1 : (() => {
      const or__23426__auto__2 = el.textContent;
      if (truth_(or__23426__auto__2)) {
        return or__23426__auto__2;
      } else {
        return "";
      }
      ;
    })()).toLowerCase();
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
  var move_group_BANG_ = function(dialog, dir) {
    const vis1 = visible_items(dialog);
    const len2 = vis1.length;
    if (len2 > 0) {
      const group_of3 = (function(el) {
        const or__23426__auto__4 = (() => {
          const and__23442__auto__5 = el;
          if (truth_(and__23442__auto__5)) {
            return el.closest(".command-group");
          } else {
            return and__23442__auto__5;
          }
          ;
        })();
        if (truth_(or__23426__auto__4)) {
          return or__23426__auto__4;
        } else {
          return dialog;
        }
        ;
      });
      const groups6 = [];
      vis1.forEach((function(el) {
        const g7 = group_of3(el);
        if (truth_(groups6.includes(g7))) {
          return null;
        } else {
          return groups6.push(g7);
        }
        ;
      }));
      const glen8 = groups6.length;
      const cur9 = active_item(dialog);
      const gidx10 = groups6.indexOf(group_of3(cur9));
      const next_g11 = dir === "down" ? gidx10 < glen8 - 1 ? gidx10 + 1 : 0 : gidx10 > 0 ? gidx10 - 1 : glen8 - 1;
      const target12 = groups6[next_g11];
      return set_active_BANG_(dialog, vis1.find((function(el) {
        return group_of3(el) === target12;
      })));
    }
    ;
  };
  var quick_key_sets = { "letters": "asdfghlqweryiotzxcvbum", "numbers": "1234567890" };
  var quick_keys = function(dialog) {
    const v1 = dialog.dataset.commandQuickNav;
    if (truth_((() => {
      const and__23442__auto__2 = v1;
      if (truth_(and__23442__auto__2)) {
        return !_EQ_(v1, "");
      } else {
        return and__23442__auto__2;
      }
      ;
    })())) {
      const or__23426__auto__3 = quick_key_sets[v1];
      if (truth_(or__23426__auto__3)) {
        return or__23426__auto__3;
      } else {
        return v1;
      }
      ;
    }
    ;
  };
  var event_key = function(e) {
    const code1 = (() => {
      const or__23426__auto__2 = e.code;
      if (truth_(or__23426__auto__2)) {
        return or__23426__auto__2;
      } else {
        return "";
      }
      ;
    })();
    if (truth_(code1.startsWith("Key"))) {
      return code1.slice(3).toLowerCase();
    } else {
      if (truth_(code1.startsWith("Digit"))) {
        return code1.slice(5);
      } else {
        return null;
      }
    }
    ;
  };
  var hide_hints_BANG_ = function(dialog) {
    if (truth_(dialog.hasAttribute("data-command-hints"))) {
      dialog.removeAttribute("data-command-hints");
      return dialog.querySelectorAll("[data-command-hint]").forEach((function(el) {
        return el.removeAttribute("data-command-hint");
      }));
    }
    ;
  };
  var show_hints_BANG_ = function(dialog) {
    const temp__23062__auto__1 = quick_keys(dialog);
    if (truth_(temp__23062__auto__1)) {
      const ks2 = temp__23062__auto__1;
      hide_hints_BANG_(dialog);
      visible_items(dialog).forEach((function(el, i) {
        if (i < ks2.length) {
          return el.setAttribute("data-command-hint", ks2[i].toUpperCase());
        }
        ;
      }));
      return dialog.setAttribute("data-command-hints", "");
    }
    ;
  };
  var quick_item = function(dialog, e) {
    const temp__23062__auto__1 = quick_keys(dialog);
    if (truth_(temp__23062__auto__1)) {
      const ks2 = temp__23062__auto__1;
      const k3 = event_key(e);
      const i4 = truth_(k3) ? ks2.indexOf(k3) : -1;
      if (i4 >= 0) {
        return visible_items(dialog)[i4];
      }
      ;
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
    const searching_QMARK_4 = !_EQ_(q1, "");
    items(dialog).forEach((function(el) {
      const match5 = searching_QMARK_4 ? item_text(el).includes(q1) : not(el.dataset.commandSearchOnly);
      el.hidden = not(match5);
      if (truth_((() => {
        const or__23426__auto__6 = !searching_QMARK_4;
        if (or__23426__auto__6) {
          return or__23426__auto__6;
        } else {
          return not(match5);
        }
        ;
      })())) {
        return el.style.removeProperty("order");
      } else {
        return el.style.order = match_score(el, q1);
      }
      ;
    }));
    if (searching_QMARK_4) {
      list3.classList.add("command-list--searching");
    } else {
      list3.classList.remove("command-list--searching");
    }
    ;
    Array.from(dialog.querySelectorAll(".command-group")).forEach((function(grp) {
      const visible7 = Array.from(grp.querySelectorAll(".command-item")).filter((function(el) {
        return not(el.hidden);
      }));
      grp.hidden = visible7.length === 0;
      if (truth_(searching_QMARK_4 && visible7.length > 0)) {
        return grp.style.order = visible7.reduce((function(best, el) {
          return Math.min(best, item_order(el));
        }), Infinity);
      } else {
        return grp.style.removeProperty("order");
      }
      ;
    }));
    const vis8 = visible_items(dialog);
    if (vis8.length === 0) {
      list3.classList.add("command-list--empty");
    } else {
      list3.classList.remove("command-list--empty");
    }
    ;
    set_active_BANG_(dialog, vis8.length > 0 ? vis8[0] : null);
    if (truth_(dialog.hasAttribute("data-command-hints"))) {
      return show_hints_BANG_(dialog);
    }
    ;
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
      if (truth_(key2 === "Alt" && quick_keys(dialog1))) {
        return show_hints_BANG_(dialog1);
      } else {
        if (truth_((() => {
          const and__23442__auto__3 = e.altKey;
          if (truth_(and__23442__auto__3)) {
            return not(e.ctrlKey) && (not(e.metaKey) && (not(e.shiftKey) && quick_item(dialog1, e)));
          } else {
            return and__23442__auto__3;
          }
          ;
        })())) {
          e.preventDefault();
          e.stopPropagation();
          return select_BANG_(dialog1, quick_item(dialog1, e));
        } else {
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
                      if (truth_((() => {
                        const and__23442__auto__5 = (() => {
                          const or__23426__auto__4 = key2 === "J";
                          if (or__23426__auto__4) {
                            return or__23426__auto__4;
                          } else {
                            return key2 === "j";
                          }
                          ;
                        })();
                        if (truth_(and__23442__auto__5)) {
                          const and__23442__auto__6 = e.altKey;
                          if (truth_(and__23442__auto__6)) {
                            return e.shiftKey;
                          } else {
                            return and__23442__auto__6;
                          }
                          ;
                        } else {
                          return and__23442__auto__5;
                        }
                        ;
                      })())) {
                        e.preventDefault();
                        return move_group_BANG_(dialog1, "down");
                      } else {
                        if (truth_((() => {
                          const and__23442__auto__8 = (() => {
                            const or__23426__auto__7 = key2 === "K";
                            if (or__23426__auto__7) {
                              return or__23426__auto__7;
                            } else {
                              return key2 === "k";
                            }
                            ;
                          })();
                          if (truth_(and__23442__auto__8)) {
                            const and__23442__auto__9 = e.altKey;
                            if (truth_(and__23442__auto__9)) {
                              return e.shiftKey;
                            } else {
                              return and__23442__auto__9;
                            }
                            ;
                          } else {
                            return and__23442__auto__8;
                          }
                          ;
                        })())) {
                          e.preventDefault();
                          return move_group_BANG_(dialog1, "up");
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
            }
          }
        }
      }
      ;
    }
    ;
  };
  var on_keyup = function(e) {
    if (e.key === "Alt") {
      const temp__23062__auto__1 = open_dialog();
      if (truth_(temp__23062__auto__1)) {
        const dialog2 = temp__23062__auto__1;
        return hide_hints_BANG_(dialog2);
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
      hide_hints_BANG_(t1);
      return clear_viewport_BANG_(t1);
    }
    ;
  };
  var on_blur = function() {
    const temp__23062__auto__1 = open_dialog();
    if (truth_(temp__23062__auto__1)) {
      const dialog2 = temp__23062__auto__1;
      return hide_hints_BANG_(dialog2);
    }
    ;
  };
  var init_BANG_ = function() {
    document.addEventListener("input", on_input, true);
    document.addEventListener("keydown", on_keydown, true);
    document.addEventListener("keyup", on_keyup, true);
    window.addEventListener("blur", on_blur);
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

  // .compiled/dial.mjs
  var tof = function(v) {
    return typeof v;
  };
  var mk = function(tag, class$) {
    const e1 = document.createElement(tag);
    if (truth_(class$)) {
      e1.className = class$;
    }
    ;
    return e1;
  };
  var add_BANG_ = (() => {
    const f8 = (function(var_args) {
      const args91 = [];
      const len__23321__auto__2 = arguments.length;
      let i103 = 0;
      while (true) {
        if (i103 < len__23321__auto__2) {
          args91.push(arguments[i103]);
          let G__4 = i103 + 1;
          i103 = G__4;
          continue;
        }
        ;
        break;
      }
      ;
      const argseq__23513__auto__5 = 1 < args91.length ? args91.slice(1) : null;
      return f8.cljs$core$IFn$_invoke$arity$variadic(arguments[0], argseq__23513__auto__5);
    });
    f8.cljs$core$IFn$_invoke$arity$variadic = (function(parent, children) {
      for (let G__6 of iterable(children)) {
        const c7 = G__6;
        if (truth_(c7)) {
          parent.appendChild(c7);
        }
      }
      ;
      return parent;
    });
    f8.cljs$lang$maxFixedArity = 1;
    return f8;
  })();
  var txt_BANG_ = function(e, s) {
    e.textContent = `${s ?? ""}`;
    return e;
  };
  var on_BANG_ = function(e, ev, f) {
    e.addEventListener(ev, f);
    return e;
  };
  var attr_BANG_ = function(e, k, v) {
    e.setAttribute(k, v);
    return e;
  };
  var clamp = function(v, lo, hi) {
    return Math.max(lo, Math.min(v, hi));
  };
  var path_str = function(path) {
    return path.join(".");
  };
  var fmt_num = function(n) {
    if (not(isFinite(n))) {
      return "0";
    } else {
      return `${Math.round(n * 1e3) / 1e3}`;
    }
    ;
  };
  var round_step = function(v, step) {
    if (truth_((() => {
      const and__23442__auto__1 = step;
      if (truth_(and__23442__auto__1)) {
        return step > 0;
      } else {
        return and__23442__auto__1;
      }
      ;
    })())) {
      return Math.round(v / step) * step;
    } else {
      return v;
    }
    ;
  };
  var infer_range = function(v) {
    if (v < 0) {
      return { "min": v * 3, "max": -v * 3, "step": 1 };
    } else {
      if (v <= 1) {
        return { "min": 0, "max": 1, "step": 0.01 };
      } else {
        if (v <= 10) {
          return { "min": 0, "max": v * 3, "step": 0.1 };
        } else {
          if (v <= 100) {
            return { "min": 0, "max": v * 3, "step": 1 };
          } else {
            if ("else") {
              return { "min": 0, "max": v * 3, "step": 10 };
            } else {
              return null;
            }
          }
        }
      }
    }
    ;
  };
  var infer_step = function(mx) {
    if (mx <= 1) {
      return 0.01;
    } else {
      if (mx <= 10) {
        return 0.1;
      } else {
        if (mx <= 100) {
          return 1;
        } else {
          if ("else") {
            return 10;
          } else {
            return null;
          }
        }
      }
    }
    ;
  };
  var color_re = new RegExp("^\\s*(#([0-9a-fA-F]{3,8})|(rgb|rgba|hsl|hsla|oklch|oklab|color)\\()");
  var color_str_QMARK_ = function(s) {
    return tof(s) === "string" && color_re.test(s);
  };
  var hsv__GT_rgb = function(h, s, v) {
    const c1 = v * s;
    const h_SINGLEQUOTE_2 = mod(h, 360) / 60;
    const x3 = c1 * (1 - Math.abs(mod(h_SINGLEQUOTE_2, 2) - 1));
    const m4 = v - c1;
    const rgb5 = h_SINGLEQUOTE_2 < 1 ? [c1, x3, 0] : h_SINGLEQUOTE_2 < 2 ? [x3, c1, 0] : h_SINGLEQUOTE_2 < 3 ? [0, c1, x3] : h_SINGLEQUOTE_2 < 4 ? [0, x3, c1] : h_SINGLEQUOTE_2 < 5 ? [x3, 0, c1] : "else" ? [c1, 0, x3] : null;
    return [Math.round(255 * (rgb5[0] + m4)), Math.round(255 * (rgb5[1] + m4)), Math.round(255 * (rgb5[2] + m4))];
  };
  var rgb__GT_hsv = function(r, g, b) {
    const r1 = r / 255;
    const g2 = g / 255;
    const b3 = b / 255;
    const mx4 = Math.max(r1, g2, b3);
    const mn5 = Math.min(r1, g2, b3);
    const d6 = mx4 - mn5;
    const h7 = d6 === 0 ? 0 : _EQ_(mx4, r1) ? 60 * mod((g2 - b3) / d6, 6) : _EQ_(mx4, g2) ? 60 * ((b3 - r1) / d6 + 2) : "else" ? 60 * ((r1 - g2) / d6 + 4) : null;
    const h8 = h7 < 0 ? h7 + 360 : h7;
    const s9 = mx4 === 0 ? 0 : d6 / mx4;
    return [h8, s9, mx4];
  };
  var rgb__GT_hsl = function(r, g, b) {
    const r1 = r / 255;
    const g2 = g / 255;
    const b3 = b / 255;
    const mx4 = Math.max(r1, g2, b3);
    const mn5 = Math.min(r1, g2, b3);
    const d6 = mx4 - mn5;
    const l7 = (mx4 + mn5) / 2;
    const h8 = d6 === 0 ? 0 : _EQ_(mx4, r1) ? 60 * mod((g2 - b3) / d6, 6) : _EQ_(mx4, g2) ? 60 * ((b3 - r1) / d6 + 2) : "else" ? 60 * ((r1 - g2) / d6 + 4) : null;
    const h9 = h8 < 0 ? h8 + 360 : h8;
    const s10 = d6 === 0 ? 0 : d6 / (1 - Math.abs(2 * l7 - 1));
    return [h9, s10, l7];
  };
  var color_probe = null;
  var parse_rgba = function(s) {
    const el1 = (() => {
      const or__23426__auto__2 = color_probe;
      if (truth_(or__23426__auto__2)) {
        return or__23426__auto__2;
      } else {
        const e3 = mk("div", null);
        e3.style.display = "none";
        document.body.appendChild(e3);
        color_probe = e3;
        return e3;
      }
      ;
    })();
    el1.style.color = "";
    el1.style.color = `${s ?? ""}`;
    if (!_EQ_("", el1.style.color)) {
      const cs4 = getComputedStyle(el1).color;
      const m5 = cs4.match(new RegExp("rgba?\\(([^)]+)\\)"));
      if (truth_(m5)) {
        const parts6 = m5[1].split(new RegExp("[ ,/]+"));
        const a7 = parts6.length > 3 ? parseFloat(parts6[3]) : 1;
        return [parseFloat(parts6[0]), parseFloat(parts6[1]), parseFloat(parts6[2]), truth_(isFinite(a7)) ? a7 : 1];
      }
      ;
    }
    ;
  };
  var to_hex2 = function(n) {
    return clamp(Math.round(n), 0, 255).toString(16).padStart(2, "0");
  };
  var compose_color = function(h, s, v, a, fmt) {
    const rgb1 = hsv__GT_rgb(h, s, v);
    const r2 = rgb1[0];
    const g3 = rgb1[1];
    const b4 = rgb1[2];
    const G__125 = fmt;
    switch (G__125) {
      case "rgb":
        if (a < 0.999) {
          return `${"rgba("}${r2 ?? ""}${", "}${g3 ?? ""}${", "}${b4 ?? ""}${", "}${fmt_num(a) ?? ""}${")"}`;
        } else {
          return `${"rgb("}${r2 ?? ""}${", "}${g3 ?? ""}${", "}${b4 ?? ""}${")"}`;
        }
        ;
        break;
      case "hsl":
        const hsl7 = rgb__GT_hsl(r2, g3, b4);
        const hh8 = Math.round(hsl7[0]);
        const ss9 = Math.round(hsl7[1] * 100);
        const ll10 = Math.round(hsl7[2] * 100);
        if (a < 0.999) {
          return `${"hsla("}${hh8 ?? ""}${", "}${ss9 ?? ""}${"%, "}${ll10 ?? ""}${"%, "}${fmt_num(a) ?? ""}${")"}`;
        } else {
          return `${"hsl("}${hh8 ?? ""}${", "}${ss9 ?? ""}${"%, "}${ll10 ?? ""}${"%)"}`;
        }
        ;
        break;
      default:
        return `${"#"}${to_hex2(r2) ?? ""}${to_hex2(g3) ?? ""}${to_hex2(b4) ?? ""}${(a < 0.999 ? to_hex2(a * 255) : "") ?? ""}`;
    }
    ;
  };
  var humanize = function(k) {
    const s1 = `${k ?? ""}`.replace(new RegExp("([a-z0-9])([A-Z])", "g"), "$1 $2").replace(new RegExp("[_\\-]", "g"), " ").trim();
    if (s1.length === 0) {
      return s1;
    } else {
      return `${s1.charAt(0).toUpperCase() ?? ""}${s1.slice(1) ?? ""}`;
    }
    ;
  };
  var normalize_options = function(opts) {
    return mapv((function(o) {
      if (tof(o) === "object") {
        return { "value": o["value"], "label": (() => {
          const or__23426__auto__1 = o["label"];
          if (truth_(or__23426__auto__1)) {
            return or__23426__auto__1;
          } else {
            return o["value"];
          }
          ;
        })() };
      } else {
        return { "value": o, "label": o };
      }
      ;
    }), opts);
  };
  var parse_node = function(k, v, path) {
    if (truth_(Array.isArray(v))) {
      const step1 = v.length > 3 ? v[3] : infer_step(v[2]);
      return { "ctype": "slider", "key": k, "path": path, "label": humanize(k), "default": v[0], "min": v[1], "max": v[2], "step": step1 };
    } else {
      if (tof(v) === "number") {
        const r2 = infer_range(v);
        return { "ctype": "slider", "key": k, "path": path, "label": humanize(k), "default": v, "min": r2["min"], "max": r2["max"], "step": r2["step"] };
      } else {
        if (tof(v) === "boolean") {
          return { "ctype": "toggle", "key": k, "path": path, "label": humanize(k), "default": v };
        } else {
          if (tof(v) === "string") {
            if (truth_(color_str_QMARK_(v))) {
              return { "ctype": "color", "key": k, "path": path, "label": humanize(k), "default": v };
            } else {
              return { "ctype": "text", "key": k, "path": path, "label": humanize(k), "default": v, "placeholder": "" };
            }
          } else {
            if (tof(v) === "object") {
              const t3 = v["type"];
              if (t3 === "text") {
                return { "ctype": "text", "key": k, "path": path, "label": (() => {
                  const or__23426__auto__4 = v["label"];
                  if (truth_(or__23426__auto__4)) {
                    return or__23426__auto__4;
                  } else {
                    return humanize(k);
                  }
                  ;
                })(), "default": (() => {
                  const or__23426__auto__5 = v["default"];
                  if (truth_(or__23426__auto__5)) {
                    return or__23426__auto__5;
                  } else {
                    return "";
                  }
                  ;
                })(), "placeholder": (() => {
                  const or__23426__auto__6 = v["placeholder"];
                  if (truth_(or__23426__auto__6)) {
                    return or__23426__auto__6;
                  } else {
                    return "";
                  }
                  ;
                })() };
              } else {
                if (t3 === "select") {
                  const os7 = normalize_options((() => {
                    const or__23426__auto__8 = v["options"];
                    if (truth_(or__23426__auto__8)) {
                      return or__23426__auto__8;
                    } else {
                      return [];
                    }
                    ;
                  })());
                  return { "ctype": "select", "key": k, "path": path, "label": (() => {
                    const or__23426__auto__9 = v["label"];
                    if (truth_(or__23426__auto__9)) {
                      return or__23426__auto__9;
                    } else {
                      return humanize(k);
                    }
                    ;
                  })(), "options": os7, "default": (() => {
                    const or__23426__auto__10 = v["default"];
                    if (truth_(or__23426__auto__10)) {
                      return or__23426__auto__10;
                    } else {
                      const or__23426__auto__11 = truth_(seq(os7)) ? first(os7)["value"] : null;
                      if (truth_(or__23426__auto__11)) {
                        return or__23426__auto__11;
                      } else {
                        return "";
                      }
                      ;
                    }
                    ;
                  })() };
                } else {
                  if (t3 === "color") {
                    return { "ctype": "color", "key": k, "path": path, "label": (() => {
                      const or__23426__auto__12 = v["label"];
                      if (truth_(or__23426__auto__12)) {
                        return or__23426__auto__12;
                      } else {
                        return humanize(k);
                      }
                      ;
                    })(), "default": (() => {
                      const or__23426__auto__13 = v["default"];
                      if (truth_(or__23426__auto__13)) {
                        return or__23426__auto__13;
                      } else {
                        return "#000000";
                      }
                      ;
                    })() };
                  } else {
                    if (t3 === "image") {
                      const os14 = truth_(v["options"]) ? normalize_options(v["options"]) : null;
                      return { "ctype": "image", "key": k, "path": path, "label": (() => {
                        const or__23426__auto__15 = v["label"];
                        if (truth_(or__23426__auto__15)) {
                          return or__23426__auto__15;
                        } else {
                          return humanize(k);
                        }
                        ;
                      })(), "options": os14, "default": (() => {
                        const or__23426__auto__16 = v["default"];
                        if (truth_(or__23426__auto__16)) {
                          return or__23426__auto__16;
                        } else {
                          const or__23426__auto__17 = truth_(seq(os14)) ? first(os14)["value"] : null;
                          if (truth_(or__23426__auto__17)) {
                            return or__23426__auto__17;
                          } else {
                            return "";
                          }
                          ;
                        }
                        ;
                      })() };
                    } else {
                      if (t3 === "pad") {
                        const ax18 = (function(a, def_) {
                          const tup19 = (() => {
                            const or__23426__auto__20 = v[a];
                            if (truth_(or__23426__auto__20)) {
                              return or__23426__auto__20;
                            } else {
                              return def_;
                            }
                            ;
                          })();
                          return { "default": tup19[0], "min": tup19[1], "max": tup19[2], "step": (() => {
                            const or__23426__auto__21 = tup19[3];
                            if (truth_(or__23426__auto__21)) {
                              return or__23426__auto__21;
                            } else {
                              return (tup19[2] - tup19[1]) / 200;
                            }
                            ;
                          })() };
                        });
                        const default_axis22 = [0, -1, 1, 0.01];
                        const xa23 = ax18("x", default_axis22);
                        const ya24 = ax18("y", default_axis22);
                        const labels25 = (() => {
                          const or__23426__auto__26 = v["labels"];
                          if (truth_(or__23426__auto__26)) {
                            return or__23426__auto__26;
                          } else {
                            return {};
                          }
                          ;
                        })();
                        return { "y": ya24, "path": path, "key": k, "default": { "x": xa23["default"], "y": ya24["default"] }, "y-label": (() => {
                          const or__23426__auto__27 = labels25["y"];
                          if (truth_(or__23426__auto__27)) {
                            return or__23426__auto__27;
                          } else {
                            return "Y";
                          }
                          ;
                        })(), "x-label": (() => {
                          const or__23426__auto__28 = labels25["x"];
                          if (truth_(or__23426__auto__28)) {
                            return or__23426__auto__28;
                          } else {
                            return "X";
                          }
                          ;
                        })(), "label": (() => {
                          const or__23426__auto__29 = v["label"];
                          if (truth_(or__23426__auto__29)) {
                            return or__23426__auto__29;
                          } else {
                            return humanize(k);
                          }
                          ;
                        })(), "ctype": "pad", "x": xa23 };
                      } else {
                        if (truth_((() => {
                          const or__23426__auto__30 = t3 === "spring";
                          if (or__23426__auto__30) {
                            return or__23426__auto__30;
                          } else {
                            return t3 === "easing";
                          }
                          ;
                        })())) {
                          return { "ctype": "transition", "key": k, "path": path, "label": (() => {
                            const or__23426__auto__31 = v["label"];
                            if (truth_(or__23426__auto__31)) {
                              return or__23426__auto__31;
                            } else {
                              return humanize(k);
                            }
                            ;
                          })(), "default": JSON.parse(JSON.stringify(v)) };
                        } else {
                          if (t3 === "action") {
                            return { "ctype": "action", "key": k, "path": path, "label": (() => {
                              const or__23426__auto__32 = v["label"];
                              if (truth_(or__23426__auto__32)) {
                                return or__23426__auto__32;
                              } else {
                                return humanize(k);
                              }
                              ;
                            })() };
                          } else {
                            if ("else") {
                              return { "ctype": "folder", "key": k, "path": path, "label": humanize(k), "collapsed": boolean$(v["_collapsed"]), "children": parse_config(v, path) };
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
              ;
            } else {
              if ("else") {
                return null;
              } else {
                return null;
              }
            }
          }
        }
      }
    }
    ;
  };
  var parse_config = function(config, path) {
    const out1 = [];
    for (let G__2 of iterable(Object.keys(config))) {
      const k3 = G__2;
      if (k3 === "_collapsed") {
      } else {
        const node4 = parse_node(k3, config[k3], conj(path, k3));
        if (truth_(node4)) {
          out1.push(node4);
        }
      }
    }
    ;
    return out1;
  };
  var init_values_BANG_ = function(store, controls) {
    for (let G__1 of iterable(controls)) {
      const c2 = G__1;
      if (get(c2, "ctype") === "folder") {
        init_values_BANG_(store, get(c2, "children"));
      } else {
        if (get(c2, "ctype") === "action") {
        } else {
          if ("else") {
            swap_BANG_(store, assoc_in, get(c2, "path"), get(c2, "default"));
          } else {
          }
        }
      }
    }
    return null;
  };
  var notify_BANG_ = function(panel) {
    const vs1 = deref(get(panel, "store"));
    const temp__23062__auto__2 = get(panel, "onChange");
    if (truth_(temp__23062__auto__2)) {
      const cb3 = temp__23062__auto__2;
      cb3(clj__GT_js(vs1));
    }
    ;
    for (let G__4 of iterable(Array.from(get(panel, "subs")))) {
      const s5 = G__4;
      s5(clj__GT_js(vs1));
    }
    return null;
  };
  var commit_BANG_ = function(panel, path, v) {
    swap_BANG_(get(panel, "store"), assoc_in, path, v);
    notify_BANG_(panel);
    return persist_save_BANG_(panel);
  };
  var refresh_updaters_BANG_ = function(panel) {
    const vs1 = deref(get(panel, "store"));
    const us2 = get(panel, "updaters");
    for (let G__3 of iterable(Object.keys(us2))) {
      const k4 = G__3;
      us2[k4](get_in(vs1, k4.split(".")));
    }
    return null;
  };
  var set_value_BANG_ = function(panel, path, v) {
    swap_BANG_(get(panel, "store"), assoc_in, path, v);
    const temp__23062__auto__1 = get(panel, "updaters")[path_str(path)];
    if (truth_(temp__23062__auto__1)) {
      const u2 = temp__23062__auto__1;
      u2(v);
    }
    ;
    notify_BANG_(panel);
    return persist_save_BANG_(panel);
  };
  var reg_updater_BANG_ = function(panel, path, f) {
    return get(panel, "updaters")[path_str(path)] = f;
  };
  var row = function(label) {
    const r1 = mk("div", "dial-row");
    if (truth_(label)) {
      add_BANG_(r1, txt_BANG_(mk("label", "dial-label"), label));
    }
    ;
    return r1;
  };
  var render_slider = function(panel, c) {
    const map__12 = c;
    const path3 = get(map__12, "path");
    const label4 = get(map__12, "label");
    const min5 = get(map__12, "min");
    const max6 = get(map__12, "max");
    const step7 = get(map__12, "step");
    const r8 = mk("div", "dial-row dial-row--slider");
    const field9 = mk("div", "dial-slider");
    const fill10 = mk("div", "dial-slider-fill");
    const lab11 = mk("span", "dial-slider-label");
    const num12 = mk("input", "dial-num");
    const cur13 = (function() {
      return get_in(deref(get(panel, "store")), path3);
    });
    const paint14 = (function(v) {
      const pct15 = 100 * clamp((v - min5) / (max6 - min5), 0, 1);
      fill10.style.width = `${pct15 ?? ""}${"%"}`;
      return num12.value = fmt_num(v);
    });
    const set_at16 = (function(clientx) {
      const rect17 = field9.getBoundingClientRect();
      const t18 = clamp((clientx - rect17.left) / rect17.width, 0, 1);
      const raw19 = min5 + t18 * (max6 - min5);
      const v20 = clamp(round_step(raw19, step7), min5, max6);
      commit_BANG_(panel, path3, v20);
      return paint14(v20);
    });
    txt_BANG_(lab11, label4);
    attr_BANG_(lab11, "title", label4);
    num12.type = "text";
    attr_BANG_(num12, "inputmode", "decimal");
    attr_BANG_(num12, "spellcheck", "false");
    add_BANG_(field9, fill10, lab11, num12);
    add_BANG_(r8, field9);
    attr_BANG_(field9, "tabindex", "0");
    const drag21 = { "on": false };
    on_BANG_(field9, "pointerdown", (function(e) {
      if (!_EQ_(e.target, num12)) {
        e.preventDefault();
        field9.setPointerCapture(e.pointerId);
        drag21["on"] = true;
        return set_at16(e.clientX);
      }
      ;
    }));
    on_BANG_(field9, "pointermove", (function(e) {
      if (truth_(drag21["on"])) {
        return set_at16(e.clientX);
      }
      ;
    }));
    on_BANG_(field9, "pointerup", (function(e) {
      if (truth_(drag21["on"])) {
        drag21["on"] = false;
        if (truth_(field9.hasPointerCapture(e.pointerId))) {
          field9.releasePointerCapture(e.pointerId);
        }
        ;
        return field9.focus();
      }
      ;
    }));
    on_BANG_(field9, "pointercancel", (function(_) {
      return drag21["on"] = false;
    }));
    on_BANG_(num12, "focus", (function(_) {
      return num12.select();
    }));
    on_BANG_(num12, "change", (function(_) {
      const v22 = parseFloat(num12.value);
      if (truth_(isFinite(v22))) {
        const v223 = clamp(round_step(v22, step7), min5, max6);
        commit_BANG_(panel, path3, v223);
        return paint14(v223);
      } else {
        return paint14(cur13());
      }
      ;
    }));
    on_BANG_(field9, "keydown", (function(e) {
      if (!_EQ_(e.target, num12)) {
        const k24 = e.key;
        const big25 = (() => {
          const or__23426__auto__26 = e.shiftKey;
          if (truth_(or__23426__auto__26)) {
            return or__23426__auto__26;
          } else {
            const or__23426__auto__27 = k24 === "PageUp";
            if (or__23426__auto__27) {
              return or__23426__auto__27;
            } else {
              return k24 === "PageDown";
            }
            ;
          }
          ;
        })();
        const d28 = step7 * (truth_(big25) ? 10 : 1);
        if (truth_((() => {
          const or__23426__auto__29 = k24 === "Enter";
          if (or__23426__auto__29) {
            return or__23426__auto__29;
          } else {
            return k24 === " ";
          }
          ;
        })())) {
          e.preventDefault();
          num12.focus();
          return num12.select();
        } else {
          if (truth_((() => {
            const or__23426__auto__30 = k24 === "ArrowUp";
            if (or__23426__auto__30) {
              return or__23426__auto__30;
            } else {
              const or__23426__auto__31 = k24 === "ArrowRight";
              if (or__23426__auto__31) {
                return or__23426__auto__31;
              } else {
                return k24 === "PageUp";
              }
              ;
            }
            ;
          })())) {
            e.preventDefault();
            const v32 = clamp(cur13() + d28, min5, max6);
            commit_BANG_(panel, path3, v32);
            return paint14(v32);
          } else {
            if (truth_((() => {
              const or__23426__auto__33 = k24 === "ArrowDown";
              if (or__23426__auto__33) {
                return or__23426__auto__33;
              } else {
                const or__23426__auto__34 = k24 === "ArrowLeft";
                if (or__23426__auto__34) {
                  return or__23426__auto__34;
                } else {
                  return k24 === "PageDown";
                }
                ;
              }
              ;
            })())) {
              e.preventDefault();
              const v35 = clamp(cur13() - d28, min5, max6);
              commit_BANG_(panel, path3, v35);
              return paint14(v35);
            } else {
              if (k24 === "Home") {
                e.preventDefault();
                commit_BANG_(panel, path3, min5);
                return paint14(min5);
              } else {
                if (k24 === "End") {
                  e.preventDefault();
                  commit_BANG_(panel, path3, max6);
                  return paint14(max6);
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
    }));
    paint14(cur13());
    reg_updater_BANG_(panel, path3, paint14);
    return r8;
  };
  var render_toggle = function(panel, c) {
    const map__12 = c;
    const path3 = get(map__12, "path");
    const label4 = get(map__12, "label");
    const r5 = row(label4);
    const wrap6 = mk("label", "switch dial-switch");
    const input7 = mk("input", "switch-input");
    const track8 = mk("span", "switch-track");
    const thumb9 = mk("span", "switch-thumb");
    const paint10 = (function(v) {
      input7.checked = boolean$(v);
      if (truth_(v)) {
        return track8.classList.add("switch-track--checked");
      } else {
        return track8.classList.remove("switch-track--checked");
      }
      ;
    });
    attr_BANG_(input7, "type", "checkbox");
    add_BANG_(track8, thumb9);
    add_BANG_(wrap6, input7, track8);
    on_BANG_(input7, "change", (function(_) {
      const v11 = input7.checked;
      commit_BANG_(panel, path3, v11);
      return paint10(v11);
    }));
    add_BANG_(r5, wrap6);
    paint10(get_in(deref(get(panel, "store")), path3));
    reg_updater_BANG_(panel, path3, paint10);
    return r5;
  };
  var render_text = function(panel, c) {
    const map__12 = c;
    const path3 = get(map__12, "path");
    const label4 = get(map__12, "label");
    const placeholder5 = get(map__12, "placeholder");
    const r6 = row(label4);
    const ta7 = mk("textarea", "dial-text");
    attr_BANG_(ta7, "rows", "1");
    if (truth_(seq(placeholder5))) {
      attr_BANG_(ta7, "placeholder", placeholder5);
    }
    ;
    on_BANG_(ta7, "input", (function(_) {
      ta7.style.height = "auto";
      ta7.style.height = `${Math.min(120, ta7.scrollHeight) ?? ""}px`;
      return commit_BANG_(panel, path3, ta7.value);
    }));
    add_BANG_(r6, ta7);
    ta7.value = (() => {
      const or__23426__auto__8 = get_in(deref(get(panel, "store")), path3);
      if (truth_(or__23426__auto__8)) {
        return or__23426__auto__8;
      } else {
        return "";
      }
      ;
    })();
    reg_updater_BANG_(panel, path3, (function(v) {
      return ta7.value = (() => {
        const or__23426__auto__9 = v;
        if (truth_(or__23426__auto__9)) {
          return or__23426__auto__9;
        } else {
          return "";
        }
        ;
      })();
    }));
    return r6;
  };
  var render_select = function(panel, c) {
    const map__12 = c;
    const path3 = get(map__12, "path");
    const label4 = get(map__12, "label");
    const options5 = get(map__12, "options");
    const r6 = row(label4);
    const wrap7 = mk("div", "select dial-select");
    const trigger8 = mk("button", "select-trigger");
    const valspan9 = mk("span", "select-value");
    const opt_for10 = (function(v) {
      return options5.find((function(o) {
        return _EQ_(o["value"], v);
      }));
    });
    const set_lbl11 = (function(v) {
      const o12 = opt_for10(v);
      return txt_BANG_(valspan9, truth_(o12) ? o12["label"] : (() => {
        const or__23426__auto__13 = v;
        if (truth_(or__23426__auto__13)) {
          return or__23426__auto__13;
        } else {
          return "";
        }
        ;
      })());
    });
    attr_BANG_(trigger8, "type", "button");
    attr_BANG_(trigger8, "role", "combobox");
    attr_BANG_(trigger8, "aria-haspopup", "listbox");
    attr_BANG_(trigger8, "aria-expanded", "false");
    if (truth_(empty_QMARK_(options5))) {
      trigger8.disabled = true;
    }
    ;
    add_BANG_(trigger8, valspan9);
    add_BANG_(wrap7, trigger8);
    add_BANG_(r6, wrap7);
    const cur14 = get_in(deref(get(panel, "store")), path3);
    attr_BANG_(trigger8, "data-select-value", (() => {
      const or__23426__auto__15 = cur14;
      if (truth_(or__23426__auto__15)) {
        return or__23426__auto__15;
      } else {
        return "";
      }
      ;
    })());
    set_lbl11(cur14);
    on_BANG_(trigger8, "click", (function(_) {
      const temp__23062__auto__16 = window.__uiSelect;
      if (truth_(temp__23062__auto__16)) {
        const f17 = temp__23062__auto__16;
        return f17(trigger8, options5, (function(v) {
          attr_BANG_(trigger8, "data-select-value", v);
          set_lbl11(v);
          return commit_BANG_(panel, path3, v);
        }));
      }
      ;
    }));
    reg_updater_BANG_(panel, path3, (function(v) {
      attr_BANG_(trigger8, "data-select-value", (() => {
        const or__23426__auto__18 = v;
        if (truth_(or__23426__auto__18)) {
          return or__23426__auto__18;
        } else {
          return "";
        }
        ;
      })());
      return set_lbl11(v);
    }));
    return r6;
  };
  var render_color = function(panel, c) {
    const map__12 = c;
    const path3 = get(map__12, "path");
    const label4 = get(map__12, "label");
    const r5 = mk("div", "dial-row dial-row--color");
    const wrap6 = mk("div", "dial-color");
    const formats7 = mk("div", "dial-color-formats");
    const plane8 = mk("div", "dial-color-plane");
    const marker9 = mk("span", "dial-color-marker");
    const tracks10 = mk("div", "dial-color-tracks");
    const huerow11 = mk("label", "dial-color-track-row");
    const hue12 = mk("input", "dial-color-track dial-color-hue");
    const oprow13 = mk("label", "dial-color-track-row");
    const op14 = mk("input", "dial-color-track dial-color-opacity");
    const txtf15 = mk("input", "dial-color-input");
    const st16 = { "h": 265, "s": 0.6, "v": 0.9, "a": 1, "fmt": "hex" };
    const fmt_btns17 = {};
    const cur18 = (function() {
      return get_in(deref(get(panel, "store")), path3);
    });
    const emit19 = (function() {
      return compose_color(st16["h"], st16["s"], st16["v"], st16["a"], st16["fmt"]);
    });
    const detect_fmt20 = (function(s) {
      const s21 = `${s ?? ""}`.trim().toLowerCase();
      if (truth_(s21.startsWith("hsl"))) {
        return "hsl";
      } else {
        if (truth_(s21.startsWith("rgb"))) {
          return "rgb";
        } else {
          if (truth_(s21.startsWith("#"))) {
            return "hex";
          } else {
            if ("else") {
              return st16["fmt"];
            } else {
              return null;
            }
          }
        }
      }
      ;
    });
    const adopt22 = (function(s) {
      const rgba23 = parse_rgba(s);
      if (truth_(rgba23)) {
        const hsv24 = rgb__GT_hsv(rgba23[0], rgba23[1], rgba23[2]);
        if (hsv24[1] > 1e-4) {
          st16["h"] = hsv24[0];
        }
        ;
        st16["s"] = hsv24[1];
        st16["v"] = hsv24[2];
        return st16["a"] = rgba23[3];
      }
      ;
    });
    const paint_ui25 = (function() {
      const h26 = st16["h"];
      const s27 = st16["s"];
      const v28 = st16["v"];
      const a29 = st16["a"];
      const rgb30 = hsv__GT_rgb(h26, s27, v28);
      const hue_col31 = `${"hsl("}${Math.round(h26) ?? ""}${", 100%, 50%)"}`;
      const solid32 = `${"rgb("}${rgb30[0] ?? ""}${", "}${rgb30[1] ?? ""}${", "}${rgb30[2] ?? ""}${")"}`;
      plane8.style.background = `${"linear-gradient(to top, #000, rgba(0,0,0,0)),"}${"linear-gradient(to right, #fff, "}${hue_col31}${")"}`;
      marker9.style.left = `${100 * s27}${"%"}`;
      marker9.style.top = `${100 * (1 - v28)}${"%"}`;
      marker9.style.background = solid32;
      hue12.value = `${h26 ?? ""}`;
      op14.value = `${a29 ?? ""}`;
      op14.style.setProperty("--dial-color-solid", solid32);
      for (let G__33 of iterable(["hex", "rgb", "hsl"])) {
        const f34 = G__33;
        const b35 = fmt_btns17[f34];
        if (truth_(b35)) {
          attr_BANG_(b35, "data-active", _EQ_(f34, st16["fmt"]) ? "true" : "false");
        }
      }
      return null;
    });
    const set_fmt36 = (function(f) {
      st16["fmt"] = f;
      const v37 = emit19();
      commit_BANG_(panel, path3, v37);
      txtf15.value = v37;
      return paint_ui25();
    });
    const push38 = (function() {
      const v39 = emit19();
      commit_BANG_(panel, path3, v39);
      txtf15.value = v39;
      return paint_ui25();
    });
    const plane_at40 = (function(e) {
      const rect41 = plane8.getBoundingClientRect();
      const sx42 = clamp((e.clientX - rect41.left) / rect41.width, 0, 1);
      const sy43 = clamp((e.clientY - rect41.top) / rect41.height, 0, 1);
      st16["s"] = sx42;
      st16["v"] = 1 - sy43;
      return push38();
    });
    const paint44 = (function(v) {
      const v45 = (() => {
        const or__23426__auto__46 = v;
        if (truth_(or__23426__auto__46)) {
          return or__23426__auto__46;
        } else {
          return "#000000";
        }
        ;
      })();
      adopt22(v45);
      st16["fmt"] = detect_fmt20(v45);
      txtf15.value = v45;
      return paint_ui25();
    });
    for (let G__47 of iterable([["hex", "Hex"], ["rgb", "RGB"], ["hsl", "HSL"]])) {
      const pair48 = G__47;
      const f49 = pair48[0];
      const b50 = mk("button", "dial-color-format");
      b50.type = "button";
      txt_BANG_(b50, pair48[1]);
      fmt_btns17[f49] = b50;
      on_BANG_(b50, "click", (function(_) {
        return set_fmt36(f49);
      }));
      add_BANG_(formats7, b50);
    }
    ;
    attr_BANG_(plane8, "tabindex", "0");
    add_BANG_(plane8, marker9);
    const dragging51 = { "on": false };
    on_BANG_(plane8, "pointerdown", (function(e) {
      e.preventDefault();
      plane8.setPointerCapture(e.pointerId);
      dragging51["on"] = true;
      return plane_at40(e);
    }));
    on_BANG_(plane8, "pointermove", (function(e) {
      if (truth_(dragging51["on"])) {
        return plane_at40(e);
      }
      ;
    }));
    on_BANG_(plane8, "pointerup", (function(_) {
      return dragging51["on"] = false;
    }));
    on_BANG_(plane8, "pointercancel", (function(_) {
      return dragging51["on"] = false;
    }));
    hue12.type = "range";
    hue12.min = "0";
    hue12.max = "360";
    hue12.step = "1";
    op14.type = "range";
    op14.min = "0";
    op14.max = "1";
    op14.step = "0.01";
    add_BANG_(huerow11, txt_BANG_(mk("span", null), "Hue"), hue12);
    add_BANG_(oprow13, txt_BANG_(mk("span", null), "Opacity"), op14);
    on_BANG_(hue12, "input", (function(_) {
      st16["h"] = parseFloat(hue12.value);
      return push38();
    }));
    on_BANG_(op14, "input", (function(_) {
      st16["a"] = parseFloat(op14.value);
      return push38();
    }));
    txtf15.type = "text";
    attr_BANG_(txtf15, "spellcheck", "false");
    on_BANG_(txtf15, "change", (function(_) {
      const v52 = txtf15.value;
      if (truth_(color_str_QMARK_(v52))) {
        adopt22(v52);
        st16["fmt"] = detect_fmt20(v52);
      }
      ;
      commit_BANG_(panel, path3, v52);
      return paint_ui25();
    }));
    add_BANG_(tracks10, huerow11, oprow13);
    add_BANG_(wrap6, formats7, plane8, tracks10, txtf15);
    if (truth_(label4)) {
      add_BANG_(r5, txt_BANG_(mk("label", "dial-label"), label4));
    }
    ;
    add_BANG_(r5, wrap6);
    paint44(cur18());
    reg_updater_BANG_(panel, path3, paint44);
    return r5;
  };
  var render_image = function(panel, c) {
    const map__12 = c;
    const path3 = get(map__12, "path");
    const label4 = get(map__12, "label");
    const options5 = get(map__12, "options");
    const r6 = row(label4);
    const wrap7 = mk("div", "dial-image");
    const grid8 = mk("div", "dial-image-grid");
    const drop9 = mk("label", "dial-image-drop");
    const file10 = mk("input", null);
    const cur11 = (function() {
      return get_in(deref(get(panel, "store")), path3);
    });
    const mark12 = (function(v) {
      for (let G__13 of iterable(Array.from(grid8.children))) {
        const ch14 = G__13;
        if (_EQ_(ch14.getAttribute("data-value"), v)) {
          ch14.classList.add("is-active");
        } else {
          ch14.classList.remove("is-active");
        }
      }
      return null;
    });
    const read_BANG_15 = (function(f) {
      if (truth_(f)) {
        const rd16 = new FileReader();
        rd16.onload = (function(_) {
          const v17 = rd16.result;
          commit_BANG_(panel, path3, v17);
          return mark12(v17);
        });
        return rd16.readAsDataURL(f);
      }
      ;
    });
    file10.type = "file";
    attr_BANG_(file10, "accept", "image/*");
    file10.style.display = "none";
    if (truth_(seq(options5))) {
      for (let G__18 of iterable(options5)) {
        const o19 = G__18;
        const b20 = mk("button", "dial-image-opt");
        attr_BANG_(b20, "type", "button");
        attr_BANG_(b20, "data-value", o19["value"]);
        attr_BANG_(b20, "title", o19["label"]);
        b20.style.backgroundImage = `${"url("}${JSON.stringify(o19["value"]) ?? ""}${")"}`;
        on_BANG_(b20, "click", (function(_) {
          const v21 = o19["value"];
          commit_BANG_(panel, path3, v21);
          return mark12(v21);
        }));
        add_BANG_(grid8, b20);
      }
    }
    ;
    txt_BANG_(drop9, "Drop / upload");
    add_BANG_(drop9, file10);
    on_BANG_(file10, "change", (function(_) {
      return read_BANG_15(file10.files[0]);
    }));
    on_BANG_(drop9, "dragover", (function(e) {
      e.preventDefault();
      return drop9.classList.add("is-over");
    }));
    on_BANG_(drop9, "dragleave", (function(_) {
      return drop9.classList.remove("is-over");
    }));
    on_BANG_(drop9, "drop", (function(e) {
      e.preventDefault();
      drop9.classList.remove("is-over");
      return read_BANG_15(e.dataTransfer.files[0]);
    }));
    add_BANG_(wrap7, grid8, drop9);
    add_BANG_(r6, wrap7);
    mark12(cur11());
    reg_updater_BANG_(panel, path3, (function(v) {
      return mark12(v);
    }));
    return r6;
  };
  var render_pad = function(panel, c) {
    const map__12 = c;
    const path3 = get(map__12, "path");
    const x4 = get(map__12, "x");
    const y5 = get(map__12, "y");
    const x_label6 = get(map__12, "x-label");
    const y_label7 = get(map__12, "y-label");
    const r8 = row(get(c, "label"));
    const area9 = mk("div", "dial-pad");
    const dot10 = mk("div", "dial-pad-dot");
    const meta11 = mk("div", "dial-pad-meta");
    const cur12 = (function() {
      return get_in(deref(get(panel, "store")), path3);
    });
    const to_pct13 = (function(v, ax) {
      return clamp((v - ax["min"]) / (ax["max"] - ax["min"]), 0, 1);
    });
    const paint14 = (function(val) {
      const px15 = 100 * to_pct13(val["x"], x4);
      const py16 = 100 * (1 - to_pct13(val["y"], y5));
      dot10.style.left = `${px15 ?? ""}${"%"}`;
      dot10.style.top = `${py16 ?? ""}${"%"}`;
      return txt_BANG_(meta11, `${x_label6 ?? ""}${" "}${fmt_num(val["x"]) ?? ""}${"   "}${y_label7 ?? ""}${" "}${fmt_num(val["y"]) ?? ""}`);
    });
    const set_at17 = (function(cx, cy) {
      const rect18 = area9.getBoundingClientRect();
      const tx19 = clamp((cx - rect18.left) / rect18.width, 0, 1);
      const ty20 = clamp((cy - rect18.top) / rect18.height, 0, 1);
      const vx21 = clamp(round_step(x4["min"] + tx19 * (x4["max"] - x4["min"]), x4["step"]), x4["min"], x4["max"]);
      const vy22 = clamp(round_step(y5["min"] + (1 - ty20) * (y5["max"] - y5["min"]), y5["step"]), y5["min"], y5["max"]);
      const v23 = { "x": vx21, "y": vy22 };
      commit_BANG_(panel, path3, v23);
      return paint14(v23);
    });
    add_BANG_(area9, dot10);
    add_BANG_(r8, area9, meta11);
    attr_BANG_(area9, "tabindex", "0");
    const dragging24 = { "on": false };
    on_BANG_(area9, "pointerdown", (function(e) {
      area9.setPointerCapture(e.pointerId);
      dragging24["on"] = true;
      return set_at17(e.clientX, e.clientY);
    }));
    on_BANG_(area9, "pointermove", (function(e) {
      if (truth_(dragging24["on"])) {
        return set_at17(e.clientX, e.clientY);
      }
      ;
    }));
    on_BANG_(area9, "pointerup", (function(_) {
      return dragging24["on"] = false;
    }));
    on_BANG_(area9, "dblclick", (function(_) {
      commit_BANG_(panel, path3, get(c, "default"));
      return paint14(get(c, "default"));
    }));
    paint14(cur12());
    reg_updater_BANG_(panel, path3, paint14);
    return r8;
  };
  var cubic_bezier = function(p1x, p1y, p2x, p2y, t) {
    const mt1 = 1 - t;
    const y2 = 3 * mt1 * mt1 * t * p1y + 3 * mt1 * t * t * p2y + t * t * t;
    return { "x": t, "y": y2 };
  };
  var sample_spring = function(visual_dur, bounce, t) {
    const zeta1 = clamp(1 - bounce, 0.05, 1);
    const omega2 = 2 * Math.PI / Math.max(0.05, visual_dur);
    if (zeta1 < 1) {
      const wd3 = omega2 * Math.sqrt(1 - zeta1 * zeta1);
      return 1 - Math.exp(-zeta1 * omega2 * t) * (Math.cos(wd3 * t) + zeta1 * omega2 / wd3 * Math.sin(wd3 * t));
    } else {
      return 1 - Math.exp(-omega2 * t) * (1 + omega2 * t);
    }
    ;
  };
  var render_transition = function(panel, c) {
    const map__12 = c;
    const path3 = get(map__12, "path");
    const r4 = row(get(c, "label"));
    const wrap5 = mk("div", "dial-transition");
    const modes6 = mk("div", "dial-seg");
    const canvas7 = mk("canvas", "dial-curve");
    const fields8 = mk("div", "dial-fields");
    const cur9 = (function() {
      return get_in(deref(get(panel, "store")), path3);
    });
    const get_mode10 = (function() {
      const v11 = cur9();
      if (v11["type"] === "easing") {
        return "easing";
      } else {
        if (truth_(v11["stiffness"])) {
          return "physics";
        } else {
          if ("else") {
            return "time";
          } else {
            return null;
          }
        }
      }
      ;
    });
    const draw12 = (function() {
      const ctx13 = canvas7.getContext("2d");
      const w14 = 220;
      const h15 = 90;
      const accent16 = getComputedStyle(document.documentElement).getPropertyValue("--accent").trim();
      canvas7.width = w14;
      canvas7.height = h15;
      ctx13.clearRect(0, 0, w14, h15);
      ctx13.lineWidth = 2;
      ctx13.strokeStyle = truth_(seq(accent16)) ? accent16 : "#7c5cfc";
      ctx13.beginPath();
      const v17 = cur9();
      const mode18 = get_mode10();
      const n1319 = 61;
      let i20 = 0;
      for (; i20 < n1319; i20++) {
        (() => {
          const t21 = i20 / 60;
          const y22 = mode18 === "easing" ? (() => {
            const e23 = (() => {
              const or__23426__auto__24 = v17["ease"];
              if (truth_(or__23426__auto__24)) {
                return or__23426__auto__24;
              } else {
                return [0.25, 0.1, 0.25, 1];
              }
              ;
            })();
            return cubic_bezier(e23[0], e23[1], e23[2], e23[3], t21)["y"];
          })() : mode18 === "physics" ? sample_spring(0.5, 0.2, t21 * 1) : "else" ? (() => {
            const vd25 = (() => {
              const or__23426__auto__26 = v17["visualDuration"];
              if (truth_(or__23426__auto__26)) {
                return or__23426__auto__26;
              } else {
                return 0.4;
              }
              ;
            })();
            return sample_spring(vd25, (() => {
              const or__23426__auto__27 = v17["bounce"];
              if (truth_(or__23426__auto__27)) {
                return or__23426__auto__27;
              } else {
                return 0.2;
              }
              ;
            })(), t21 * (vd25 * 2));
          })() : null;
          const px28 = t21 * w14;
          const py29 = h15 - clamp(y22, -0.2, 1.4) * (h15 * 0.7) - h15 * 0.1;
          if (i20 === 0) {
            return ctx13.moveTo(px28, py29);
          } else {
            return ctx13.lineTo(px28, py29);
          }
          ;
        })();
      }
      ;
      return ctx13.stroke();
    });
    const num_field30 = (function(key_, lbl, mn, mx, st) {
      const fw31 = mk("label", "dial-field");
      const inp32 = mk("input", null);
      inp32.type = "number";
      inp32.min = `${mn ?? ""}`;
      inp32.max = `${mx ?? ""}`;
      inp32.step = `${st ?? ""}`;
      inp32.value = `${(() => {
        const or__23426__auto__33 = cur9()[key_];
        if (truth_(or__23426__auto__33)) {
          return or__23426__auto__33;
        } else {
          return "";
        }
        ;
      })() ?? ""}`;
      add_BANG_(fw31, txt_BANG_(mk("span", null), lbl), inp32);
      on_BANG_(inp32, "input", (function(_) {
        const v34 = JSON.parse(JSON.stringify(cur9()));
        v34[key_] = parseFloat(inp32.value);
        commit_BANG_(panel, path3, v34);
        return draw12();
      }));
      return { "el": fw31, "inp": inp32 };
    });
    const rebuild35 = (function() {
      fields8.innerHTML = "";
      const mode36 = get_mode10();
      const v37 = cur9();
      if (mode36 === "easing") {
        add_BANG_(fields8, num_field30("duration", "Duration", 0, 5, 0.05)["el"]);
      } else {
        if (mode36 === "physics") {
          for (let G__38 of iterable([num_field30("stiffness", "Stiffness", 1, 500, 1), num_field30("damping", "Damping", 1, 60, 1), num_field30("mass", "Mass", 0.1, 5, 0.1)])) {
            const f39 = G__38;
            add_BANG_(fields8, f39["el"]);
          }
        } else {
          if ("else") {
            for (let G__40 of iterable([num_field30("visualDuration", "Duration", 0.05, 2, 0.01), num_field30("bounce", "Bounce", 0, 1, 0.01)])) {
              const f41 = G__40;
              add_BANG_(fields8, f41["el"]);
            }
          } else {
          }
        }
      }
      ;
      return draw12();
    });
    const set_mode42 = (function(m) {
      const v43 = JSON.parse(JSON.stringify(cur9()));
      if (m === "easing") {
        v43["type"] = "easing";
        if (truth_(v43["ease"])) {
        } else {
          v43["ease"] = [0.25, 0.1, 0.25, 1];
        }
        ;
        if (truth_(v43["duration"])) {
        } else {
          v43["duration"] = 0.3;
        }
      } else {
        if (m === "physics") {
          v43["type"] = "spring";
          v43["stiffness"] = (() => {
            const or__23426__auto__44 = v43["stiffness"];
            if (truth_(or__23426__auto__44)) {
              return or__23426__auto__44;
            } else {
              return 200;
            }
            ;
          })();
          v43["damping"] = (() => {
            const or__23426__auto__45 = v43["damping"];
            if (truth_(or__23426__auto__45)) {
              return or__23426__auto__45;
            } else {
              return 25;
            }
            ;
          })();
          v43["mass"] = (() => {
            const or__23426__auto__46 = v43["mass"];
            if (truth_(or__23426__auto__46)) {
              return or__23426__auto__46;
            } else {
              return 1;
            }
            ;
          })();
        } else {
          if ("else") {
            v43["type"] = "spring";
            delete v43["stiffness"];
            delete v43["damping"];
            delete v43["mass"];
            v43["visualDuration"] = (() => {
              const or__23426__auto__47 = v43["visualDuration"];
              if (truth_(or__23426__auto__47)) {
                return or__23426__auto__47;
              } else {
                return 0.4;
              }
              ;
            })();
            v43["bounce"] = (() => {
              const or__23426__auto__48 = v43["bounce"];
              if (truth_(or__23426__auto__48)) {
                return or__23426__auto__48;
              } else {
                return 0.2;
              }
              ;
            })();
          } else {
          }
        }
      }
      ;
      commit_BANG_(panel, path3, v43);
      return rebuild35();
    });
    for (let G__49 of iterable([["easing", "Easing"], ["time", "Time"], ["physics", "Physics"]])) {
      const pair50 = G__49;
      const b51 = mk("button", "dial-seg-btn");
      attr_BANG_(b51, "type", "button");
      txt_BANG_(b51, pair50[1]);
      on_BANG_(b51, "click", (function(_) {
        return set_mode42(pair50[0]);
      }));
      add_BANG_(modes6, b51);
    }
    ;
    add_BANG_(wrap5, modes6, canvas7, fields8);
    add_BANG_(r4, wrap5);
    rebuild35();
    reg_updater_BANG_(panel, path3, (function(_) {
      return rebuild35();
    }));
    return r4;
  };
  var render_action = function(panel, c) {
    const map__12 = c;
    const path3 = get(map__12, "path");
    const label4 = get(map__12, "label");
    const r5 = mk("div", "dial-row dial-row--action");
    const btn6 = mk("button", "dial-action");
    attr_BANG_(btn6, "type", "button");
    txt_BANG_(btn6, label4);
    on_BANG_(btn6, "click", (function(_) {
      const temp__23062__auto__7 = get(panel, "onAction");
      if (truth_(temp__23062__auto__7)) {
        const f8 = temp__23062__auto__7;
        return f8(path_str(path3));
      }
      ;
    }));
    add_BANG_(r5, btn6);
    return r5;
  };
  var render_folder = function(panel, c) {
    const wrap1 = mk("div", "dial-folder");
    const head2 = mk("button", "dial-folder-head");
    const body3 = mk("div", "dial-folder-body");
    const chev4 = mk("span", "dial-chevron");
    const open5 = atom(not(get(c, "collapsed")));
    const sync6 = (function() {
      if (truth_(deref(open5))) {
        return wrap1.classList.remove("is-collapsed");
      } else {
        return wrap1.classList.add("is-collapsed");
      }
      ;
    });
    attr_BANG_(head2, "type", "button");
    add_BANG_(head2, chev4, txt_BANG_(mk("span", null), get(c, "label")));
    on_BANG_(head2, "click", (function(_) {
      swap_BANG_(open5, not);
      return sync6();
    }));
    for (let G__7 of iterable(get(c, "children"))) {
      const child8 = G__7;
      add_BANG_(body3, render_control(panel, child8));
    }
    ;
    add_BANG_(wrap1, head2, body3);
    sync6();
    return wrap1;
  };
  var render_control = function(panel, c) {
    const t1 = get(c, "ctype");
    if (t1 === "slider") {
      return render_slider(panel, c);
    } else {
      if (t1 === "toggle") {
        return render_toggle(panel, c);
      } else {
        if (t1 === "text") {
          return render_text(panel, c);
        } else {
          if (t1 === "select") {
            return render_select(panel, c);
          } else {
            if (t1 === "color") {
              return render_color(panel, c);
            } else {
              if (t1 === "image") {
                return render_image(panel, c);
              } else {
                if (t1 === "pad") {
                  return render_pad(panel, c);
                } else {
                  if (t1 === "transition") {
                    return render_transition(panel, c);
                  } else {
                    if (t1 === "action") {
                      return render_action(panel, c);
                    } else {
                      if (t1 === "folder") {
                        return render_folder(panel, c);
                      } else {
                        if ("else") {
                          return mk("div", null);
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
    ;
  };
  var storage_for = function(panel) {
    const p1 = get(panel, "persist");
    if (truth_((() => {
      const and__23442__auto__2 = p1;
      if (truth_(and__23442__auto__2)) {
        return p1["storage"] === "sessionStorage";
      } else {
        return and__23442__auto__2;
      }
      ;
    })())) {
      return sessionStorage;
    } else {
      return localStorage;
    }
    ;
  };
  var storage_key = function(panel) {
    const p1 = get(panel, "persist");
    const or__23426__auto__2 = (() => {
      const and__23442__auto__3 = p1;
      if (truth_(and__23442__auto__3)) {
        return p1["key"];
      } else {
        return and__23442__auto__3;
      }
      ;
    })();
    if (truth_(or__23426__auto__2)) {
      return or__23426__auto__2;
    } else {
      return `${"dialkit:"}${get(panel, "id") ?? ""}`;
    }
    ;
  };
  var persist_save_BANG_ = function(panel) {
    if (truth_(get(panel, "persist"))) {
      return (() => {
        try {
          const payload1 = { "values": clj__GT_js(deref(get(panel, "store"))), "presets": get(panel, "presets"), "active": get(panel, "activePreset") };
          return storage_for(panel).setItem(storage_key(panel), JSON.stringify(payload1));
        } catch (_2) {
          return null;
        }
      })();
    }
    ;
  };
  var persist_load_BANG_ = function(panel) {
    if (truth_(get(panel, "persist"))) {
      return (() => {
        try {
          const raw1 = storage_for(panel).getItem(storage_key(panel));
          if (truth_(raw1)) {
            const data2 = JSON.parse(raw1);
            if (truth_(data2["values"])) {
              reset_BANG_(get(panel, "store"), data2["values"]);
            }
            ;
            if (truth_(data2["presets"])) {
              panel["presets"] = data2["presets"];
            }
            ;
            if (truth_(data2["active"])) {
              return panel["activePreset"] = data2["active"];
            }
            ;
          }
          ;
        } catch (_3) {
          return null;
        }
      })();
    }
    ;
  };
  var root_el = atom(null);
  var panels = atom({});
  var drag_state = { "on": false, "x": 0, "y": 0, "sx": 0, "sy": 0 };
  var apply_root_position_BANG_ = function(el, pos) {
    const s1 = el.style;
    const bottom_QMARK_2 = (() => {
      const or__23426__auto__3 = pos === "bottom-right";
      if (or__23426__auto__3) {
        return or__23426__auto__3;
      } else {
        return pos === "bottom-left";
      }
      ;
    })();
    const left_QMARK_4 = (() => {
      const or__23426__auto__5 = pos === "top-left";
      if (or__23426__auto__5) {
        return or__23426__auto__5;
      } else {
        return pos === "bottom-left";
      }
      ;
    })();
    s1.top = "auto";
    s1.bottom = "auto";
    s1.left = "auto";
    s1.right = "auto";
    if (truth_(bottom_QMARK_2)) {
      s1.bottom = "12px";
    } else {
      s1.top = "12px";
    }
    ;
    if (truth_(left_QMARK_4)) {
      return s1.left = "12px";
    } else {
      return s1.right = "12px";
    }
    ;
  };
  var ensure_root_BANG_ = function() {
    const or__23426__auto__1 = deref(root_el);
    if (truth_(or__23426__auto__1)) {
      return or__23426__auto__1;
    } else {
      const el2 = mk("div", "dialkit-root");
      attr_BANG_(el2, "data-theme", "system");
      apply_root_position_BANG_(el2, "top-right");
      document.body.appendChild(el2);
      reset_BANG_(root_el, el2);
      window.addEventListener("pointermove", (function(e) {
        if (truth_(drag_state["on"])) {
          const dx3 = e.clientX - drag_state["sx"];
          const dy4 = e.clientY - drag_state["sy"];
          el2.style.right = "auto";
          el2.style.bottom = "auto";
          el2.style.left = `${drag_state["x"] + dx3}px`;
          return el2.style.top = `${drag_state["y"] + dy4}px`;
        }
        ;
      }));
      window.addEventListener("pointerup", (function(_) {
        return drag_state["on"] = false;
      }));
      return el2;
    }
    ;
  };
  var start_drag_BANG_ = function(e) {
    const el1 = deref(root_el);
    const rect2 = el1.getBoundingClientRect();
    drag_state["on"] = true;
    drag_state["sx"] = e.clientX;
    drag_state["sy"] = e.clientY;
    drag_state["x"] = rect2.left;
    return drag_state["y"] = rect2.top;
  };
  var build_body_BANG_ = function(panel) {
    const body1 = get(panel, "bodyEl");
    body1.innerHTML = "";
    panel["updaters"] = {};
    for (let G__2 of iterable(get(panel, "controls"))) {
      const c3 = G__2;
      add_BANG_(body1, render_control(panel, c3));
    }
    return null;
  };
  var select_version_BANG_ = function(panel, id) {
    panel["activePreset"] = id;
    if (truth_(id)) {
      const p1 = get(panel, "presets").find((function(x) {
        return _EQ_(x["id"], id);
      }));
      if (truth_(p1)) {
        reset_BANG_(get(panel, "store"), p1["values"]);
      }
    } else {
      reset_BANG_(get(panel, "store"), get(panel, "baseValues"));
    }
    ;
    refresh_updaters_BANG_(panel);
    notify_BANG_(panel);
    persist_save_BANG_(panel);
    return render_versions_BANG_(panel);
  };
  var save_version_BANG_ = function(panel) {
    const id1 = `v${Date.now() ?? ""}`;
    const n2 = `${"Version "}${2 + get(panel, "presets").length}`;
    const preset3 = { "id": id1, "name": n2, "values": clj__GT_js(deref(get(panel, "store"))) };
    get(panel, "presets").push(preset3);
    return select_version_BANG_(panel, id1);
  };
  var render_versions_BANG_ = function(panel) {
    const sel1 = get(panel, "versionSel");
    sel1.innerHTML = "";
    const o02 = mk("option", null);
    o02.value = "";
    txt_BANG_(o02, "Version 1");
    add_BANG_(sel1, o02);
    for (let G__3 of iterable(get(panel, "presets"))) {
      const p4 = G__3;
      const o5 = mk("option", null);
      o5.value = p4["id"];
      txt_BANG_(o5, p4["name"]);
      add_BANG_(sel1, o5);
    }
    ;
    return sel1.value = (() => {
      const or__23426__auto__6 = get(panel, "activePreset");
      if (truth_(or__23426__auto__6)) {
        return or__23426__auto__6;
      } else {
        return "";
      }
      ;
    })();
  };
  var copy_config_BANG_ = function(panel) {
    const vals1 = JSON.stringify(clj__GT_js(deref(get(panel, "store"))), null, 2);
    const text2 = `${'// DialKit values for "'}${get(panel, "name") ?? ""}${'"\n'}${"// Replace your config defaults with these tuned values:\n"}${vals1 ?? ""}`;
    if (truth_(navigator.clipboard)) {
      navigator.clipboard.writeText(text2);
    }
    ;
    const temp__23062__auto__3 = window.__uiToast;
    if (truth_(temp__23062__auto__3)) {
      const t4 = temp__23062__auto__3;
      return t4("Copied values to clipboard", { "variant": "success" });
    }
    ;
  };
  var make_panel = function(name, config, opts) {
    const id1 = (() => {
      const or__23426__auto__2 = opts["id"];
      if (truth_(or__23426__auto__2)) {
        return or__23426__auto__2;
      } else {
        return `dial-${Math.random().toString(36).slice(2, 8) ?? ""}`;
      }
      ;
    })();
    const controls3 = parse_config(config, []);
    const store4 = atom({});
    const panel5 = { "open": not(opts["defaultCollapsed"]), "store": store4, "activePreset": null, "name": name, "persist": (() => {
      const p6 = opts["persist"];
      if (p6 === true) {
        return {};
      } else {
        if (tof(p6) === "object") {
          return p6;
        } else {
          if ("else") {
            return null;
          } else {
            return null;
          }
        }
      }
      ;
    })(), "controls": controls3, "icon": (() => {
      const or__23426__auto__7 = opts["icon"];
      if (truth_(or__23426__auto__7)) {
        return or__23426__auto__7;
      } else {
        return "\u2699";
      }
      ;
    })(), "onAction": opts["onAction"], "presets": [], "id": id1, "position": (() => {
      const or__23426__auto__8 = opts["position"];
      if (truth_(or__23426__auto__8)) {
        return or__23426__auto__8;
      } else {
        return "top-right";
      }
      ;
    })(), "subs": [], "onChange": opts["onChange"], "updaters": {} };
    init_values_BANG_(store4, controls3);
    panel5["baseValues"] = clj__GT_js(deref(store4));
    persist_load_BANG_(panel5);
    return panel5;
  };
  var mount_panel_BANG_ = function(panel, opts) {
    const root1 = ensure_root_BANG_();
    const pos2 = get(panel, "position");
    const side3 = truth_((() => {
      const or__23426__auto__4 = pos2 === "top-left";
      if (or__23426__auto__4) {
        return or__23426__auto__4;
      } else {
        return pos2 === "bottom-left";
      }
      ;
    })()) ? "pos-left" : "pos-right";
    const card5 = mk("div", `${"dial-panel "}${side3 ?? ""}`);
    const head6 = mk("div", "dial-panel-head");
    const title7 = mk("div", "dial-panel-title");
    const tools8 = mk("div", "dial-panel-tools");
    const vsel9 = mk("select", "dial-version");
    const addb10 = mk("button", "dial-tool");
    const copyb11 = mk("button", "dial-tool");
    const resetb12 = mk("button", "dial-tool");
    const collb13 = mk("button", "dial-tool");
    const iconb14 = mk("button", "dial-panel-icon");
    const body15 = mk("div", "dial-panel-body");
    apply_root_position_BANG_(root1, pos2);
    txt_BANG_(title7, get(panel, "name"));
    panel["bodyEl"] = body15;
    panel["versionSel"] = vsel9;
    panel["cardEl"] = card5;
    attr_BANG_(iconb14, "type", "button");
    attr_BANG_(iconb14, "title", `${"Expand "}${get(panel, "name") ?? ""}`);
    txt_BANG_(iconb14, get(panel, "icon"));
    for (let G__16 of iterable([[addb10, "+", "Save version"], [copyb11, "\u29C9", "Copy values"], [resetb12, "\u21BA", "Reset"], [collb13, "\u2013", "Minimize"]])) {
      const spec17 = G__16;
      const b18 = spec17[0];
      attr_BANG_(b18, "type", "button");
      attr_BANG_(b18, "title", spec17[2]);
      txt_BANG_(b18, spec17[1]);
    }
    ;
    on_BANG_(vsel9, "change", (function(_) {
      return select_version_BANG_(panel, (() => {
        const v19 = vsel9.value;
        if (truth_(seq(v19))) {
          return v19;
        }
        ;
      })());
    }));
    on_BANG_(addb10, "click", (function(_) {
      return save_version_BANG_(panel);
    }));
    on_BANG_(copyb11, "click", (function(_) {
      return copy_config_BANG_(panel);
    }));
    on_BANG_(resetb12, "click", (function(_) {
      return select_version_BANG_(panel, null);
    }));
    on_BANG_(collb13, "click", (function(_) {
      card5.classList.add("is-iconified");
      return panel["open"] = false;
    }));
    on_BANG_(iconb14, "click", (function(_) {
      card5.classList.remove("is-iconified");
      return panel["open"] = true;
    }));
    on_BANG_(head6, "pointerdown", (function(e) {
      if (truth_((() => {
        const or__23426__auto__20 = _EQ_(e.target, head6);
        if (or__23426__auto__20) {
          return or__23426__auto__20;
        } else {
          return _EQ_(e.target, title7);
        }
        ;
      })())) {
        return start_drag_BANG_(e);
      }
      ;
    }));
    add_BANG_(tools8, vsel9, addb10, copyb11, resetb12, collb13);
    add_BANG_(head6, title7, tools8);
    add_BANG_(card5, head6, body15, iconb14);
    add_BANG_(root1, card5);
    build_body_BANG_(panel);
    render_versions_BANG_(panel);
    if (truth_(opts["defaultCollapsed"])) {
      card5.classList.add("is-iconified");
      panel["open"] = false;
    }
    ;
    return card5;
  };
  var setvals_walk_BANG_ = function(panel, prefix, o) {
    for (let G__1 of iterable(Object.keys(o))) {
      const k2 = G__1;
      const v3 = o[k2];
      const path4 = conj(prefix, k2);
      if (truth_(tof(v3) === "object" && (not(Array.isArray(v3)) && not(get(panel, "updaters")[path_str(path4)])))) {
        setvals_walk_BANG_(panel, path4, v3);
      } else {
        set_value_BANG_(panel, path4, v3);
      }
    }
    return null;
  };
  var controller = function(panel) {
    return { "setOpen": (function(o) {
      panel["open"] = o;
      const temp__23062__auto__1 = get(panel, "cardEl");
      if (truth_(temp__23062__auto__1)) {
        const card2 = temp__23062__auto__1;
        if (truth_(o)) {
          card2.classList.remove("is-iconified");
        } else {
          card2.classList.add("is-iconified");
        }
      }
      ;
      return void 0;
    }), "destroy": (function() {
      const temp__23062__auto__3 = get(panel, "cardEl");
      if (truth_(temp__23062__auto__3)) {
        const card4 = temp__23062__auto__3;
        card4.remove();
      }
      ;
      delete deref(panels)[get(panel, "id")];
      return void 0;
    }), "setValue": (function(p, v) {
      set_value_BANG_(panel, p.split("."), v);
      return void 0;
    }), "getOpen": (function() {
      return get(panel, "open");
    }), "id": get(panel, "id"), "setValues": (function(obj) {
      setvals_walk_BANG_(panel, [], obj);
      return void 0;
    }), "resetValues": (function() {
      select_version_BANG_(panel, null);
      return void 0;
    }), "getValues": (function() {
      return clj__GT_js(deref(get(panel, "store")));
    }), "subscribe": (function(cb, immediate) {
      get(panel, "subs").push(cb);
      if (!(immediate === false)) {
        cb(clj__GT_js(deref(get(panel, "store"))));
      }
      ;
      return function() {
        const i5 = get(panel, "subs").indexOf(cb);
        if (i5 >= 0) {
          return get(panel, "subs").splice(i5, 1);
        }
        ;
      };
    }) };
  };
  var use_dial = function(name, config, opts) {
    const opts1 = (() => {
      const or__23426__auto__2 = opts;
      if (truth_(or__23426__auto__2)) {
        return or__23426__auto__2;
      } else {
        return {};
      }
      ;
    })();
    const panel3 = make_panel(name, config, opts1);
    const ctrl4 = controller(panel3);
    deref(panels)[get(panel3, "id")] = panel3;
    panel3["ctrl"] = ctrl4;
    Object.defineProperty(ctrl4, "values", { "get": (function() {
      return clj__GT_js(deref(get(panel3, "store")));
    }) });
    if (!(opts1["enabled"] === false)) {
      mount_panel_BANG_(panel3, opts1);
    }
    ;
    return ctrl4;
  };
  var panel_by_id = function(id) {
    return deref(panels)[id];
  };
  var dial_store = { "setPanelOpen": (function(id, o) {
    const temp__23062__auto__1 = panel_by_id(id);
    if (truth_(temp__23062__auto__1)) {
      const p2 = temp__23062__auto__1;
      return get(p2, "ctrl")["setOpen"](o);
    }
    ;
  }), "togglePanelOpen": (function(id) {
    const temp__23062__auto__3 = panel_by_id(id);
    if (truth_(temp__23062__auto__3)) {
      const p4 = temp__23062__auto__3;
      return get(p4, "ctrl")["setOpen"](not(get(p4, "open")));
    }
    ;
  }), "getPanelOpen": (function(id) {
    const temp__23062__auto__5 = panel_by_id(id);
    if (truth_(temp__23062__auto__5)) {
      const p6 = temp__23062__auto__5;
      return get(p6, "open");
    }
    ;
  }), "getPresets": (function(id) {
    const temp__23062__auto__7 = panel_by_id(id);
    if (truth_(temp__23062__auto__7)) {
      const p8 = temp__23062__auto__7;
      return get(p8, "presets");
    }
    ;
  }), "getActivePresetId": (function(id) {
    const temp__23062__auto__9 = panel_by_id(id);
    if (truth_(temp__23062__auto__9)) {
      const p10 = temp__23062__auto__9;
      return get(p10, "activePreset");
    }
    ;
  }), "clearActivePreset": (function(id) {
    const temp__23062__auto__11 = panel_by_id(id);
    if (truth_(temp__23062__auto__11)) {
      const p12 = temp__23062__auto__11;
      return select_version_BANG_(p12, null);
    }
    ;
  }), "savePreset": (function(id) {
    const temp__23062__auto__13 = panel_by_id(id);
    if (truth_(temp__23062__auto__13)) {
      const p14 = temp__23062__auto__13;
      return save_version_BANG_(p14);
    }
    ;
  }), "deletePreset": (function(id, pid) {
    const temp__23062__auto__15 = panel_by_id(id);
    if (truth_(temp__23062__auto__15)) {
      const p16 = temp__23062__auto__15;
      p16["presets"] = get(p16, "presets").filter((function(x) {
        return !_EQ_(x["id"], pid);
      }));
      render_versions_BANG_(p16);
      return persist_save_BANG_(p16);
    }
    ;
  }) };
  window["__uiDial"] = use_dial;
  window["DialStore"] = dial_store;

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

  // .compiled/flip.mjs
  var default_duration = 250;
  var default_easing = "cubic-bezier(0.32, 0.72, 0, 1)";
  var reduced_motion_QMARK_ = function() {
    return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  };
  var resolve_els = function(target) {
    if (truth_(string_QMARK_(target))) {
      return Array.from(document.querySelectorAll(target));
    } else {
      if (truth_((() => {
        const c__23404__auto__1 = Element;
        const x__23405__auto__2 = target;
        const ret__23406__auto__3 = x__23405__auto__2 instanceof c__23404__auto__1;
        return ret__23406__auto__3;
      })())) {
        return Array.from(target.children);
      } else {
        if ("else") {
          return Array.from(target);
        } else {
          return null;
        }
      }
    }
    ;
  };
  var capture = function(target) {
    const rects1 = /* @__PURE__ */ new Map();
    for (let G__2 of iterable(resolve_els(target))) {
      const el3 = G__2;
      rects1.set(el3, el3.getBoundingClientRect());
    }
    ;
    return { "target": target, "rects": rects1 };
  };
  var play_moves_BANG_ = function(rects, timing, scale_QMARK_, lift_QMARK_) {
    return rects.forEach((function(rect, el) {
      if (truth_(el.isConnected)) {
        const now1 = el.getBoundingClientRect();
        const dx2 = rect.x - now1.x;
        const dy3 = rect.y - now1.y;
        const sx4 = truth_((() => {
          const and__23442__auto__5 = scale_QMARK_;
          if (truth_(and__23442__auto__5)) {
            return now1.width > 0;
          } else {
            return and__23442__auto__5;
          }
          ;
        })()) ? rect.width / now1.width : 1;
        const sy6 = truth_((() => {
          const and__23442__auto__7 = scale_QMARK_;
          if (truth_(and__23442__auto__7)) {
            return now1.height > 0;
          } else {
            return and__23442__auto__7;
          }
          ;
        })()) ? rect.height / now1.height : 1;
        const moved_QMARK_8 = (() => {
          const or__23426__auto__9 = Math.abs(dx2) >= 0.5;
          if (or__23426__auto__9) {
            return or__23426__auto__9;
          } else {
            const or__23426__auto__10 = Math.abs(dy3) >= 0.5;
            if (or__23426__auto__10) {
              return or__23426__auto__10;
            } else {
              const or__23426__auto__11 = Math.abs(sx4 - 1) >= 5e-3;
              if (or__23426__auto__11) {
                return or__23426__auto__11;
              } else {
                return Math.abs(sy6 - 1) >= 5e-3;
              }
              ;
            }
            ;
          }
          ;
        })();
        if (truth_(moved_QMARK_8)) {
          if (truth_(lift_QMARK_)) {
            el.style.zIndex = "1";
          }
          ;
          const from12 = `${"translate("}${dx2 ?? ""}${"px, "}${dy3 ?? ""}${"px)"}${(truth_(scale_QMARK_) ? `${" scale("}${sx4 ?? ""}${", "}${sy6 ?? ""}${")"}` : "") ?? ""}`;
          const kf13 = { "transform": [from12, "none"] };
          const _14 = truth_(scale_QMARK_) ? kf13["transformOrigin"] = ["0 0", "0 0"] : null;
          const anim15 = el.animate(kf13, timing);
          const unlift16 = (function() {
            return el.style.zIndex = "";
          });
          if (truth_(lift_QMARK_)) {
            anim15.onfinish = unlift16;
            return anim15.oncancel = unlift16;
          }
          ;
        }
        ;
      }
      ;
    }));
  };
  var play_enters_BANG_ = function(target, rects, timing) {
    for (let G__1 of iterable(resolve_els(target))) {
      const el2 = G__1;
      if (truth_(rects.has(el2))) {
      } else {
        el2.animate({ "opacity": [0, 1], "transform": ["scale(0.96)", "none"] }, timing);
      }
    }
    return null;
  };
  var play = (() => {
    const f14 = (function(...args15) {
      const G__161 = args15.length;
      switch (G__161) {
        case 1:
          return f14.cljs$core$IFn$_invoke$arity$1(args15[0]);
          break;
        case 2:
          return f14.cljs$core$IFn$_invoke$arity$2(args15[0], args15[1]);
          break;
        default:
          throw new Error(`${"Invalid arity: "}${args15.length ?? ""}`);
      }
      ;
    });
    f14.cljs$core$IFn$_invoke$arity$1 = (function(snap) {
      return play(snap, null);
    });
    f14.cljs$core$IFn$_invoke$arity$2 = (function(snap, opts) {
      if (truth_(reduced_motion_QMARK_())) {
      } else {
        const opts3 = (() => {
          const or__23426__auto__4 = opts;
          if (truth_(or__23426__auto__4)) {
            return or__23426__auto__4;
          } else {
            return {};
          }
          ;
        })();
        const timing5 = { "duration": (() => {
          const or__23426__auto__6 = opts3["duration"];
          if (truth_(or__23426__auto__6)) {
            return or__23426__auto__6;
          } else {
            return default_duration;
          }
          ;
        })(), "easing": (() => {
          const or__23426__auto__7 = opts3["easing"];
          if (truth_(or__23426__auto__7)) {
            return or__23426__auto__7;
          } else {
            return default_easing;
          }
          ;
        })() };
        const rects8 = snap["rects"];
        play_moves_BANG_(rects8, timing5, truth_(opts3["scale"]) ? true : false, truth_(opts3["lift"]) ? true : false);
        if (truth_(opts3["enter"])) {
          play_enters_BANG_(snap["target"], rects8, timing5);
        }
      }
      ;
      return null;
    });
    f14.cljs$lang$maxFixedArity = 2;
    return f14;
  })();
  var wrap = (() => {
    const f17 = (function(...args18) {
      const G__191 = args18.length;
      switch (G__191) {
        case 2:
          return f17.cljs$core$IFn$_invoke$arity$2(args18[0], args18[1]);
          break;
        case 3:
          return f17.cljs$core$IFn$_invoke$arity$3(args18[0], args18[1], args18[2]);
          break;
        default:
          throw new Error(`${"Invalid arity: "}${args18.length ?? ""}`);
      }
      ;
    });
    f17.cljs$core$IFn$_invoke$arity$2 = (function(target, mutate) {
      return wrap(target, mutate, null);
    });
    f17.cljs$core$IFn$_invoke$arity$3 = (function(target, mutate, opts) {
      const snap3 = capture(target);
      mutate();
      return play(snap3, opts);
    });
    f17.cljs$lang$maxFixedArity = 3;
    return f17;
  })();
  window.__uiFlip = { "capture": capture, "play": play, "wrap": wrap };

  // .compiled/gestures.mjs
  var press_ms = 500;
  var press_visual_ms = 250;
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
      clearTimeout(get(p2, "visual-timer"));
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
        const G__202 = e.target;
        if (G__202 == null) {
          return null;
        } else {
          return G__202.closest(selector);
        }
        ;
      })();
      if (truth_(temp__23062__auto__1)) {
        const el3 = temp__23062__auto__1;
        cancel_BANG_();
        const x4 = e.clientX;
        const y5 = e.clientY;
        return reset_BANG_(press, { "el": el3, "x": x4, "y": y5, "visual-timer": setTimeout((function() {
          return el3.classList.add(press_class);
        }), press_visual_ms), "timer": setTimeout((function() {
          clearTimeout(get(deref(press), "visual-timer"));
          reset_BANG_(press, null);
          el3.classList.add(press_class);
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
        clearTimeout(get(p2, "visual-timer"));
        get(p2, "el").classList.add(press_class);
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

  // .compiled/lightbox.mjs
  var overlay_sel = ".lightbox-overlay";
  var image_sel = ".lightbox-image";
  var max_scale = 5;
  var pinch_min = 0.75;
  var pinch_max = 6;
  var double_tap_scale = 2.5;
  var double_tap_ms = 300;
  var tap_slop_px = 10;
  var double_tap_slop_px = 30;
  var identity_zoom = { "s": 1, "tx": 0, "ty": 0 };
  var pointers = /* @__PURE__ */ new Map();
  var gesture = atom(null);
  var suppress_click_QMARK_2 = atom(false);
  var last_tap = atom(null);
  var clamp2 = function(lo, hi, v) {
    return Math.min(hi, Math.max(lo, v));
  };
  var rel = function(x, y) {
    return [x - window.innerWidth / 2, y - window.innerHeight / 2];
  };
  var zoom_of = function(img) {
    const or__23426__auto__1 = img["__uiZoom"];
    if (truth_(or__23426__auto__1)) {
      return or__23426__auto__1;
    } else {
      return identity_zoom;
    }
    ;
  };
  var zoom_at = function(z, s, px, py) {
    const k1 = s / get(z, "s");
    return assoc(z, "s", s, "tx", px - k1 * (px - get(z, "tx")), "ty", py - k1 * (py - get(z, "ty")));
  };
  var settle = function(img, z) {
    const s1 = clamp2(1, max_scale, get(z, "s"));
    const mx2 = Math.max(0, (s1 * img.offsetWidth - window.innerWidth) / 2);
    const my3 = Math.max(0, (s1 * img.offsetHeight - window.innerHeight) / 2);
    return assoc(z, "s", s1, "tx", clamp2(-mx2, mx2, get(z, "tx")), "ty", clamp2(-my3, my3, get(z, "ty")));
  };
  var apply_zoom_BANG_ = function(img, z, animate_QMARK_) {
    img["__uiZoom"] = z;
    const st1 = img.style;
    st1.transition = truth_(animate_QMARK_) ? "" : "none";
    st1.transform = 1 === get(z, "s") ? "" : `${"translate("}${get(z, "tx") ?? ""}${"px, "}${get(z, "ty") ?? ""}${"px) scale("}${get(z, "s") ?? ""}${")"}`;
    if (get(z, "s") > 1) {
      return img.setAttribute("data-zoomed", "");
    } else {
      return img.removeAttribute("data-zoomed");
    }
    ;
  };
  var reset_zoom_BANG_ = function(img) {
    img["__uiZoom"] = null;
    img.style.transform = "";
    return img.removeAttribute("data-zoomed");
  };
  var current_points = function() {
    return Array.from(pointers.values());
  };
  var rebase_BANG_ = function() {
    const temp__23062__auto__1 = deref(gesture);
    if (truth_(temp__23062__auto__1)) {
      const g2 = temp__23062__auto__1;
      const pts3 = current_points();
      return reset_BANG_(gesture, assoc(g2, "z0", zoom_of(get(g2, "img")), "pts0", pts3, "max-pointers", Math.max(get(g2, "max-pointers"), pts3.length)));
    }
    ;
  };
  var midpoint = function(a, b) {
    return rel((get(a, "x") + get(b, "x")) / 2, (get(a, "y") + get(b, "y")) / 2);
  };
  var distance = function(a, b) {
    return Math.hypot(get(a, "x") - get(b, "x"), get(a, "y") - get(b, "y"));
  };
  var track_BANG_ = function() {
    const map__12 = deref(gesture);
    const img3 = get(map__12, "img");
    const z04 = get(map__12, "z0");
    const pts05 = get(map__12, "pts0");
    const pts6 = current_points();
    if (pts6.length >= 2) {
      const a013 = pts05[0];
      const b014 = pts05[1];
      const a15 = pts6[0];
      const b16 = pts6[1];
      const vec__717 = midpoint(a013, b014);
      const mx018 = nth(vec__717, 0, null);
      const my019 = nth(vec__717, 1, null);
      const vec__1020 = midpoint(a15, b16);
      const mx21 = nth(vec__1020, 0, null);
      const my22 = nth(vec__1020, 1, null);
      const s23 = clamp2(pinch_min, pinch_max, get(z04, "s") * (distance(a15, b16) / Math.max(1, distance(a013, b014))));
      const z24 = zoom_at(z04, s23, mx018, my019);
      return apply_zoom_BANG_(img3, assoc(z24, "tx", get(z24, "tx") + (mx21 - mx018), "ty", get(z24, "ty") + (my22 - my019)), false);
    } else {
      if (get(z04, "s") > 1) {
        const p025 = pts05[0];
        const p26 = pts6[0];
        return apply_zoom_BANG_(img3, assoc(z04, "tx", get(z04, "tx") + (get(p26, "x") - get(p025, "x")), "ty", get(z04, "ty") + (get(p26, "y") - get(p025, "y"))), false);
      }
    }
    ;
  };
  var tap_BANG_ = function(img, x, y) {
    const now1 = Date.now();
    const prev2 = deref(last_tap);
    if (truth_((() => {
      const and__23442__auto__3 = prev2;
      if (truth_(and__23442__auto__3)) {
        return now1 - get(prev2, "t") < double_tap_ms && Math.hypot(x - get(prev2, "x"), y - get(prev2, "y")) < double_tap_slop_px;
      } else {
        return and__23442__auto__3;
      }
      ;
    })())) {
      const z7 = zoom_of(img);
      const vec__48 = rel(x, y);
      const px9 = nth(vec__48, 0, null);
      const py10 = nth(vec__48, 1, null);
      reset_BANG_(last_tap, null);
      return apply_zoom_BANG_(img, get(z7, "s") > 1 ? identity_zoom : settle(img, zoom_at(z7, double_tap_scale, px9, py10)), true);
    } else {
      return reset_BANG_(last_tap, { "t": now1, "x": x, "y": y });
    }
    ;
  };
  var on_pointerdown2 = function(e) {
    const t1 = e.target;
    const overlay2 = (() => {
      const G__213 = t1;
      if (G__213 == null) {
        return null;
      } else {
        return G__213.closest(overlay_sel);
      }
      ;
    })();
    if (truth_((() => {
      const and__23442__auto__4 = overlay2;
      if (truth_(and__23442__auto__4)) {
        return not(t1.closest(".lightbox-close")) && (() => {
          const or__23426__auto__5 = !("mouse" === e.pointerType);
          if (or__23426__auto__5) {
            return or__23426__auto__5;
          } else {
            return e.button === 0;
          }
          ;
        })();
      } else {
        return and__23442__auto__4;
      }
      ;
    })())) {
      const temp__23062__auto__6 = overlay2.querySelector(image_sel);
      if (truth_(temp__23062__auto__6)) {
        const img7 = temp__23062__auto__6;
        if (truth_((() => {
          const or__23426__auto__8 = pointers.size === 0;
          if (or__23426__auto__8) {
            return or__23426__auto__8;
          } else {
            return !_EQ_(img7, get(deref(gesture), "img"));
          }
          ;
        })())) {
          pointers.clear();
          reset_BANG_(suppress_click_QMARK_2, false);
          reset_BANG_(gesture, { "img": img7, "moved": false, "on-image": img7.contains(t1), "max-pointers": 0 });
        }
        ;
        const x9 = e.clientX;
        const y10 = e.clientY;
        pointers.set(e.pointerId, { "x": x9, "y": y10, "x0": x9, "y0": y10 });
        if ("mouse" === e.pointerType) {
          e.preventDefault();
        }
        ;
        return rebase_BANG_();
      }
      ;
    }
    ;
  };
  var on_pointermove3 = function(e) {
    const id1 = e.pointerId;
    if (truth_(pointers.has(id1))) {
      const p2 = pointers.get(id1);
      const x3 = e.clientX;
      const y4 = e.clientY;
      pointers.set(id1, assoc(p2, "x", x3, "y", y4));
      if (Math.hypot(x3 - get(p2, "x0"), y4 - get(p2, "y0")) > tap_slop_px) {
        swap_BANG_(gesture, assoc, "moved", true);
      }
      ;
      return track_BANG_();
    }
    ;
  };
  var on_pointer_end2 = function(e) {
    const id1 = e.pointerId;
    if (truth_(pointers.has(id1))) {
      pointers.delete(id1);
      if (pointers.size > 0) {
        return rebase_BANG_();
      } else {
        const map__23 = deref(gesture);
        const img4 = get(map__23, "img");
        const moved5 = get(map__23, "moved");
        const on_image6 = get(map__23, "on-image");
        const max_pointers7 = get(map__23, "max-pointers");
        reset_BANG_(gesture, null);
        if (truth_(moved5)) {
          reset_BANG_(suppress_click_QMARK_2, true);
        }
        ;
        if (truth_(not(moved5) && (() => {
          const and__23442__auto__8 = on_image6;
          if (truth_(and__23442__auto__8)) {
            return 1 === max_pointers7 && "pointerup" === e.type;
          } else {
            return and__23442__auto__8;
          }
          ;
        })())) {
          return tap_BANG_(img4, e.clientX, e.clientY);
        } else {
          return apply_zoom_BANG_(img4, settle(img4, zoom_of(img4)), true);
        }
        ;
      }
      ;
    }
    ;
  };
  var on_wheel = function(e) {
    const temp__23062__auto__1 = (() => {
      const G__222 = e.target;
      if (G__222 == null) {
        return null;
      } else {
        return G__222.closest(overlay_sel);
      }
      ;
    })();
    if (truth_(temp__23062__auto__1)) {
      const overlay3 = temp__23062__auto__1;
      const temp__23062__auto__4 = overlay3.querySelector(image_sel);
      if (truth_(temp__23062__auto__4)) {
        const img5 = temp__23062__auto__4;
        e.preventDefault();
        const z9 = zoom_of(img5);
        const dy10 = e.deltaY * (1 === e.deltaMode ? 16 : 1);
        const k11 = Math.exp(-dy10 * (truth_(e.ctrlKey) ? 0.01 : 2e-3));
        const vec__612 = rel(e.clientX, e.clientY);
        const px13 = nth(vec__612, 0, null);
        const py14 = nth(vec__612, 1, null);
        return apply_zoom_BANG_(img5, settle(img5, zoom_at(z9, clamp2(1, max_scale, get(z9, "s") * k11), px13, py14)), false);
      }
      ;
    }
    ;
  };
  var on_click_capture2 = function(e) {
    if (truth_(deref(suppress_click_QMARK_2))) {
      reset_BANG_(suppress_click_QMARK_2, false);
      e.preventDefault();
      return e.stopPropagation();
    }
    ;
  };
  var on_load_capture = function(e) {
    const t1 = e.target;
    if (truth_((() => {
      const and__23442__auto__2 = t1.matches;
      if (truth_(and__23442__auto__2)) {
        return t1.matches(image_sel);
      } else {
        return and__23442__auto__2;
      }
      ;
    })())) {
      return reset_zoom_BANG_(t1);
    }
    ;
  };
  var on_dragstart = function(e) {
    const t1 = e.target;
    if (truth_((() => {
      const and__23442__auto__2 = t1;
      if (truth_(and__23442__auto__2)) {
        const and__23442__auto__3 = t1.closest;
        if (truth_(and__23442__auto__3)) {
          return t1.closest(overlay_sel);
        } else {
          return and__23442__auto__3;
        }
        ;
      } else {
        return and__23442__auto__2;
      }
      ;
    })())) {
      return e.preventDefault();
    }
    ;
  };
  if (truth_(window["__uiLightboxZoom"])) {
  } else {
    window["__uiLightboxZoom"] = true;
    document.addEventListener("pointerdown", on_pointerdown2, true);
    document.addEventListener("pointermove", on_pointermove3, true);
    document.addEventListener("pointerup", on_pointer_end2, true);
    document.addEventListener("pointercancel", on_pointer_end2, true);
    document.addEventListener("click", on_click_capture2, true);
    document.addEventListener("wheel", on_wheel, { "capture": true, "passive": false });
    document.addEventListener("load", on_load_capture, true);
    document.addEventListener("dragstart", on_dragstart, true);
  }

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

  // .compiled/number_field.mjs
  var num = function(v, fallback) {
    const n1 = parseFloat(v);
    if (truth_(isNaN(n1))) {
      return fallback;
    } else {
      return n1;
    }
    ;
  };
  var closest_field = function(el) {
    if (truth_((() => {
      const and__23442__auto__1 = el;
      if (truth_(and__23442__auto__1)) {
        return el.closest;
      } else {
        return and__23442__auto__1;
      }
      ;
    })())) {
      return el.closest("[data-ui-number-field]");
    }
    ;
  };
  var disabled_QMARK_ = function(field) {
    return field.hasAttribute("data-ui-number-disabled");
  };
  var field_input = function(field) {
    return field.querySelector("input");
  };
  var round6 = function(n) {
    return Math.round(n * 1e6) / 1e6;
  };
  var step_input_BANG_ = function(input, dir) {
    if (truth_((() => {
      const and__23442__auto__1 = input;
      if (truth_(and__23442__auto__1)) {
        return not(input.disabled);
      } else {
        return and__23442__auto__1;
      }
      ;
    })())) {
      const raw_step2 = num(input.step, 1);
      const step3 = raw_step2 > 0 ? raw_step2 : 1;
      const cur4 = num(input.value, 0);
      const min_STAR_5 = !_EQ_("", input.min) ? num(input.min, null) : null;
      const max_STAR_6 = !_EQ_("", input.max) ? num(input.max, null) : null;
      const next7 = round6(cur4 + dir * step3);
      const next8 = (() => {
        const G__239 = next7;
        const G__2310 = !(min_STAR_5 == null) ? Math.max(G__239, min_STAR_5) : G__239;
        if (!(max_STAR_6 == null)) {
          return Math.min(G__2310, max_STAR_6);
        } else {
          return G__2310;
        }
        ;
      })();
      input.value = `${next8 ?? ""}`;
      input.dispatchEvent(new Event("input", { "bubbles": true }));
      return input.dispatchEvent(new Event("change", { "bubbles": true }));
    }
    ;
  };
  var on_wheel2 = function(e) {
    const temp__23062__auto__1 = closest_field(e.target);
    if (truth_(temp__23062__auto__1)) {
      const field2 = temp__23062__auto__1;
      if (truth_(disabled_QMARK_(field2))) {
        return null;
      } else {
        const temp__23062__auto__3 = field_input(field2);
        if (truth_(temp__23062__auto__3)) {
          const input4 = temp__23062__auto__3;
          e.preventDefault();
          return step_input_BANG_(input4, e.deltaY < 0 ? 1 : -1);
        }
        ;
      }
      ;
    }
    ;
  };
  var on_click2 = function(e) {
    const temp__23062__auto__1 = (() => {
      const and__23442__auto__2 = e.target;
      if (truth_(and__23442__auto__2)) {
        const and__23442__auto__3 = e.target.closest;
        if (truth_(and__23442__auto__3)) {
          return e.target.closest("[data-ui-number-step]");
        } else {
          return and__23442__auto__3;
        }
        ;
      } else {
        return and__23442__auto__2;
      }
      ;
    })();
    if (truth_(temp__23062__auto__1)) {
      const btn4 = temp__23062__auto__1;
      const temp__23062__auto__5 = closest_field(btn4);
      if (truth_(temp__23062__auto__5)) {
        const field6 = temp__23062__auto__5;
        if (truth_(disabled_QMARK_(field6))) {
          return null;
        } else {
          return step_input_BANG_(field_input(field6), num(btn4.getAttribute("data-ui-number-step"), 0));
        }
        ;
      }
      ;
    }
    ;
  };
  var init_BANG_4 = function() {
    document.addEventListener("wheel", on_wheel2, { "passive": false });
    return document.addEventListener("click", on_click2);
  };
  init_BANG_4();

  // .compiled/panels.mjs
  var easing = "cubic-bezier(0.32, 0.72, 0, 1)";
  var duration = 250;
  var key_step = 10;
  var key_step_fast = 50;
  var pan_threshold = 3;
  var axes_config = { "horizontal": { "client": "clientWidth", "extent": "width", "cursor": "col-resize", "grow": "ArrowRight", "shrink": "ArrowLeft", "sepOrient": "vertical" }, "vertical": { "client": "clientHeight", "extent": "height", "cursor": "row-resize", "grow": "ArrowDown", "shrink": "ArrowUp", "sepOrient": "horizontal" } };
  var clamp3 = function(v, lo, hi) {
    return Math.min(Math.max(v, lo), hi);
  };
  var round2 = function(v) {
    return Math.round(v * 100) / 100;
  };
  var reduced_motion_QMARK_2 = function() {
    return matchMedia("(prefers-reduced-motion: reduce)").matches;
  };
  var has_attr_QMARK_ = function(el, attr) {
    return !(el == null) && (!(el.hasAttribute == null) && el.hasAttribute(attr));
  };
  var fill_node_QMARK_ = function(el) {
    return has_attr_QMARK_(el, "data-ui-panels-fill");
  };
  var separator_node_QMARK_ = function(el) {
    return has_attr_QMARK_(el, "data-ui-panels-separator");
  };
  var fill_after_QMARK_ = function(el) {
    let n1 = el.nextElementSibling;
    while (true) {
      if (n1 == null) {
        return false;
      } else {
        if (truth_(fill_node_QMARK_(n1))) {
          return true;
        } else {
          if ("else") {
            let G__2 = n1.nextElementSibling;
            n1 = G__2;
            continue;
          } else {
            return null;
          }
        }
      }
      ;
      ;
      break;
    }
    ;
  };
  var extent_key = function(group) {
    return group.axes["extent"];
  };
  var group_extent = function(group) {
    return group.el[group.axes["client"]];
  };
  var parse_size = function(s) {
    if (truth_(!(s == null) && !_EQ_(s, ""))) {
      const pct1 = s.endsWith("%");
      const v2 = parseFloat(s);
      if (truth_(isNaN(v2))) {
        return null;
      } else {
        return { "pct": pct1, "value": v2 };
      }
      ;
    }
    ;
  };
  var to_pixels = function(parsed, total) {
    if (truth_(parsed.pct)) {
      return parsed.value / 100 * total;
    } else {
      return parsed.value;
    }
    ;
  };
  var measure = function(panel) {
    const total1 = group_extent(panel.group);
    if (truth_(panel.pct)) {
      return Math.round(panel.value / 100 * total1);
    } else {
      return panel.value;
    }
    ;
  };
  var current_extent = function(panel) {
    return panel.el.getBoundingClientRect()[extent_key(panel.group)];
  };
  var size_report = function(panel) {
    if (truth_(panel.pct)) {
      return `${panel.value ?? ""}${"%"}`;
    } else {
      return panel.value;
    }
    ;
  };
  var lock_state = { "count": 0, "saved": null };
  var lock_body_BANG_ = function(cursor, on_escape) {
    const style1 = document.body.style;
    const onkey2 = (function(e) {
      if (e.key === "Escape") {
        e.preventDefault();
        return on_escape();
      }
      ;
    });
    if (lock_state.count === 0) {
      lock_state["saved"] = { "cursor": style1.cursor, "userSelect": style1.userSelect, "webkitUserSelect": style1.webkitUserSelect };
    }
    ;
    lock_state["count"] = lock_state.count + 1;
    style1.cursor = cursor;
    style1.userSelect = "none";
    style1.webkitUserSelect = "none";
    addEventListener("keydown", onkey2, true);
    return function() {
      lock_state["count"] = lock_state.count - 1;
      if (lock_state.count === 0) {
        const saved3 = lock_state.saved;
        style1.cursor = saved3.cursor;
        style1.userSelect = saved3.userSelect;
        style1.webkitUserSelect = saved3.webkitUserSelect;
      }
      ;
      return removeEventListener("keydown", onkey2, true);
    };
  };
  var persist_BANG_ = function(panel) {
    if (truth_(panel.persist)) {
      return (() => {
        try {
          return localStorage.setItem(`${"ui-panels:"}${panel.persist ?? ""}`, JSON.stringify({ "size": size_report(panel), "collapsed": truth_(panel.collapsed) ? true : false }));
        } catch (_1) {
          return null;
        }
      })();
    }
    ;
  };
  var restore_persisted = function(key) {
    return (() => {
      try {
        const temp__23062__auto__1 = localStorage.getItem(`${"ui-panels:"}${key ?? ""}`);
        if (truth_(temp__23062__auto__1)) {
          const raw2 = temp__23062__auto__1;
          return JSON.parse(raw2);
        }
        ;
      } catch (_3) {
        return null;
      }
    })();
  };
  var emit_BANG_ = function(panel, type, detail) {
    return panel.el.dispatchEvent(new CustomEvent(type, { "bubbles": true, "detail": detail }));
  };
  var emit_resize_BANG_ = function(panel) {
    return emit_BANG_(panel, "ui-panels-resize", { "size": size_report(panel), "pixels": measure(panel) });
  };
  var reflect_collapsed_BANG_ = function(panel) {
    if (truth_(panel.collapsed)) {
      return panel.el.setAttribute("data-collapsed", "true");
    } else {
      return panel.el.removeAttribute("data-collapsed");
    }
    ;
  };
  var jump_size_BANG_ = function(panel, px) {
    return panel.el.style[extent_key(panel.group)] = `${px ?? ""}px`;
  };
  var jump_content_BANG_ = function(panel, px) {
    const temp__23062__auto__1 = panel.content;
    if (truth_(temp__23062__auto__1)) {
      const content2 = temp__23062__auto__1;
      return content2.style[extent_key(panel.group)] = `${px ?? ""}px`;
    }
    ;
  };
  var available = function(panel) {
    const group1 = panel.group;
    const k2 = extent_key(group1);
    const fill3 = group1.fill;
    const base4 = !(fill3 == null) ? parseFloat(getComputedStyle(fill3)[k2]) : 0;
    let i5 = 0;
    let room6 = base4;
    while (true) {
      if (i5 < group1.panels.length) {
        const p7 = group1.panels[i5];
        let G__8 = i5 + 1;
        let G__9 = room6 + (current_extent(p7) - (p7 === panel ? 0 : p7.target));
        i5 = G__8;
        room6 = G__9;
        continue;
      } else {
        return Math.max(0, room6);
      }
      ;
      ;
      break;
    }
    ;
  };
  var bounds = function(panel) {
    const room1 = available(panel);
    const total2 = group_extent(panel.group);
    const minv3 = Math.min(!(panel.minSize == null) ? to_pixels(panel.minSize, total2) : 0, room1);
    const maxv4 = !(panel.maxSize == null) ? to_pixels(panel.maxSize, total2) : room1;
    return { "min": minv3, "max": Math.max(minv3, Math.min(maxv4, room1)) };
  };
  var grow_sign = function(panel) {
    if (truth_(panel.end)) {
      return 1;
    } else {
      return -1;
    }
    ;
  };
  var sync_aria_BANG_ = function(panel) {
    const b1 = bounds(panel);
    const t2 = Math.round(panel.target);
    return panel.grips.forEach((function(grip) {
      grip.setAttribute("aria-valuenow", `${t2 ?? ""}`);
      grip.setAttribute("aria-valuetext", `${t2 ?? ""}${" pixels"}`);
      grip.setAttribute("aria-valuemin", `${Math.round(b1.min) ?? ""}`);
      if (!(panel.maxSize == null)) {
        return grip.setAttribute("aria-valuemax", `${Math.round(b1.max) ?? ""}`);
      } else {
        return grip.removeAttribute("aria-valuemax");
      }
      ;
    }));
  };
  var any_folding_QMARK_ = function(group) {
    return group.panels.some((function(p) {
      if (truth_(p.folding)) {
        return true;
      } else {
        return false;
      }
      ;
    }));
  };
  var freeze_fill_BANG_ = function(panel) {
    const group1 = panel.group;
    const fill2 = group1.fill;
    const inner3 = group1.fillInner;
    if (!(inner3 == null)) {
      fill2.style.justifyContent = truth_(panel.end) ? "flex-end" : "flex-start";
      fill2.style.overflow = "clip";
      return inner3.style[extent_key(group1)] = `${Math.max(0, available(panel) - panel.target) ?? ""}px`;
    }
    ;
  };
  var set_folding_BANG_ = function(panel, folding) {
    if (!_EQ_(truth_(panel.folding) ? true : false, truth_(folding) ? true : false)) {
      panel["folding"] = folding;
      if (truth_(folding)) {
        panel.el.setAttribute("data-folding", "true");
      } else {
        panel.el.removeAttribute("data-folding");
      }
      ;
      if (truth_(folding)) {
        return null;
      } else {
        const group1 = panel.group;
        const fill2 = group1.fill;
        const inner3 = group1.fillInner;
        if (truth_(!(inner3 == null) && not(any_folding_QMARK_(group1)))) {
          inner3.style[extent_key(group1)] = "100%";
          return fill2.style.overflow = "";
        }
        ;
      }
      ;
    }
    ;
  };
  var fold_BANG_ = function(panel, to) {
    const el1 = panel.el;
    const k2 = extent_key(panel.group);
    const from3 = current_extent(panel);
    set_folding_BANG_(panel, true);
    freeze_fill_BANG_(panel);
    const temp__23062__auto__4 = panel.anim;
    if (truth_(temp__23062__auto__4)) {
      const anim5 = temp__23062__auto__4;
      anim5.cancel();
    }
    ;
    jump_size_BANG_(panel, to);
    const content_from6 = truth_(to > 0 && from3 > 0) ? (() => {
      const temp__23062__auto__7 = panel.content;
      if (truth_(temp__23062__auto__7)) {
        const c8 = temp__23062__auto__7;
        return c8.getBoundingClientRect()[k2];
      }
      ;
    })() : null;
    if (to > 0) {
      jump_content_BANG_(panel, to);
    }
    ;
    if (truth_((() => {
      const or__23426__auto__9 = reduced_motion_QMARK_2();
      if (truth_(or__23426__auto__9)) {
        return or__23426__auto__9;
      } else {
        return _EQ_(from3, to);
      }
      ;
    })())) {
      return set_folding_BANG_(panel, false);
    } else {
      const kf10 = {};
      const _11 = kf10[k2] = [`${from3 ?? ""}px`, `${to ?? ""}px`];
      const anim12 = el1.animate(kf10, { "duration": duration, "easing": easing });
      panel["anim"] = anim12;
      if (truth_(!(content_from6 == null) && (!(panel.content == null) && !_EQ_(content_from6, to)))) {
        const ckf13 = {};
        ckf13[k2] = [`${content_from6 ?? ""}px`, `${to ?? ""}px`];
        panel.content.animate(ckf13, { "duration": duration, "easing": easing });
      }
      ;
      anim12.onfinish = (function() {
        panel["anim"] = null;
        return set_folding_BANG_(panel, false);
      });
      return anim12.oncancel = (function() {
        return panel["anim"] = null;
      });
    }
    ;
  };
  var retarget_BANG_ = function(panel) {
    const m1 = measure(panel);
    const value2 = truth_(panel.collapsed) ? 0 : m1;
    if (truth_(current_extent(panel) === 0 && not(panel.dragging))) {
      jump_content_BANG_(panel, m1);
    }
    ;
    if (!_EQ_(value2, panel.target)) {
      panel["target"] = value2;
      if (truth_(panel.dragging)) {
      } else {
        if (_EQ_(current_extent(panel), value2)) {
          set_folding_BANG_(panel, false);
          jump_size_BANG_(panel, value2);
          if (value2 > 0) {
            jump_content_BANG_(panel, m1);
          }
        } else {
          fold_BANG_(panel, value2);
        }
      }
    }
    ;
    return sync_aria_BANG_(panel);
  };
  var set_collapsed_BANG_ = function(panel, collapsed) {
    if (!_EQ_(truth_(collapsed) ? true : false, truth_(panel.collapsed) ? true : false)) {
      panel["collapsed"] = collapsed;
      reflect_collapsed_BANG_(panel);
      retarget_BANG_(panel);
      emit_BANG_(panel, "ui-panels-collapse", { "collapsed": truth_(collapsed) ? true : false });
      return persist_BANG_(panel);
    }
    ;
  };
  var apply_size_BANG_ = function(panel, px) {
    const b1 = bounds(panel);
    const v2 = clamp3(px, b1.min, b1.max);
    const total3 = group_extent(panel.group);
    if (truth_((() => {
      const and__23442__auto__4 = panel.collapsed;
      if (truth_(and__23442__auto__4)) {
        return v2 > 0;
      } else {
        return and__23442__auto__4;
      }
      ;
    })())) {
      panel["collapsed"] = false;
      reflect_collapsed_BANG_(panel);
      emit_BANG_(panel, "ui-panels-collapse", { "collapsed": false });
    }
    ;
    if (truth_(panel.pct)) {
      panel["value"] = total3 > 0 ? round2(100 * (v2 / total3)) : 0;
    } else {
      panel["value"] = v2;
    }
    ;
    retarget_BANG_(panel);
    emit_resize_BANG_(panel);
    return persist_BANG_(panel);
  };
  var release_BANG_ = function(panel) {
    const temp__23062__auto__1 = panel.unlock;
    if (truth_(temp__23062__auto__1)) {
      const unlock2 = temp__23062__auto__1;
      unlock2();
      return panel["unlock"] = null;
    }
    ;
  };
  var set_resizing_attrs_BANG_ = function(panel, on) {
    const f1 = (function(el) {
      if (truth_(on)) {
        return el.setAttribute("data-resizing", "true");
      } else {
        return el.removeAttribute("data-resizing");
      }
      ;
    });
    f1(panel.el);
    return panel.grips.forEach(f1);
  };
  var stop_dragging_BANG_ = function(panel) {
    release_BANG_(panel);
    panel["dragging"] = false;
    return set_resizing_attrs_BANG_(panel, false);
  };
  var drag_collapse_BANG_ = function(panel, collapsed) {
    panel["collapsed"] = collapsed;
    reflect_collapsed_BANG_(panel);
    panel["target"] = truth_(collapsed) ? 0 : measure(panel);
    return emit_BANG_(panel, "ui-panels-collapse", { "collapsed": truth_(collapsed) ? true : false });
  };
  var drag_cancel_BANG_ = function(panel) {
    if (truth_(panel.dragging)) {
      const s1 = panel.session;
      jump_size_BANG_(panel, s1.start);
      jump_content_BANG_(panel, s1.start > 0 ? s1.start : measure(panel));
      if (!_EQ_(truth_(s1.sessionCollapsed) ? true : false, truth_(s1.wasCollapsed) ? true : false)) {
        drag_collapse_BANG_(panel, s1.wasCollapsed);
      }
      ;
      return stop_dragging_BANG_(panel);
    }
    ;
  };
  var drag_start_BANG_ = function(panel, cursor) {
    const b1 = bounds(panel);
    const s2 = panel.session;
    const group3 = panel.group;
    const cur4 = current_extent(panel);
    const temp__23062__auto__5 = panel.anim;
    if (truth_(temp__23062__auto__5)) {
      const anim6 = temp__23062__auto__5;
      anim6.cancel();
      jump_size_BANG_(panel, cur4);
    }
    ;
    s2["min"] = b1.min;
    s2["max"] = b1.max;
    s2["sign"] = grow_sign(panel);
    s2["start"] = cur4;
    s2["sessionCollapsed"] = truth_(panel.collapsed) ? true : false;
    s2["wasCollapsed"] = truth_(panel.collapsed) ? true : false;
    release_BANG_(panel);
    panel["unlock"] = lock_body_BANG_(!(cursor == null) ? cursor : group3.axes["cursor"], (function() {
      return drag_cancel_BANG_(panel);
    }));
    set_folding_BANG_(panel, false);
    panel["dragging"] = true;
    return set_resizing_attrs_BANG_(panel, true);
  };
  var drag_move_BANG_ = function(panel, dx, dy) {
    if (truth_(panel.dragging)) {
      const s1 = panel.session;
      const group2 = panel.group;
      const offset3 = extent_key(group2) === "width" ? dx : dy;
      const pixels4 = s1.start + offset3 * s1.sign;
      const collapse5 = (() => {
        const and__23442__auto__6 = panel.collapsible;
        if (truth_(and__23442__auto__6)) {
          return pixels4 < s1.min / 2;
        } else {
          return and__23442__auto__6;
        }
        ;
      })();
      const next7 = truth_(collapse5) ? 0 : Math.round(clamp3(pixels4, s1.min, s1.max));
      if (!_EQ_(truth_(collapse5) ? true : false, truth_(s1.sessionCollapsed) ? true : false)) {
        drag_collapse_BANG_(panel, collapse5);
      }
      ;
      jump_size_BANG_(panel, next7);
      if (truth_(collapse5)) {
      } else {
        jump_content_BANG_(panel, next7);
      }
      ;
      return s1["sessionCollapsed"] = collapse5;
    }
    ;
  };
  var drag_end_BANG_ = function(panel) {
    if (truth_(panel.dragging)) {
      const s1 = panel.session;
      stop_dragging_BANG_(panel);
      if (truth_(s1.sessionCollapsed)) {
        persist_BANG_(panel);
      } else {
        const px2 = current_extent(panel);
        const total3 = group_extent(panel.group);
        if (truth_(panel.pct)) {
          panel["value"] = total3 > 0 ? round2(100 * (px2 / total3)) : 0;
        } else {
          panel["value"] = px2;
        }
        ;
        panel["target"] = measure(panel);
        emit_resize_BANG_(panel);
        persist_BANG_(panel);
      }
      ;
      const cur4 = current_extent(panel);
      if (!_EQ_(cur4, panel.target)) {
        fold_BANG_(panel, panel.target);
      }
      ;
      return sync_aria_BANG_(panel);
    }
    ;
  };
  var resize_by_key_BANG_ = function(panel, e) {
    const key1 = e.key;
    const axes2 = panel.group.axes;
    if (key1 === "Enter") {
      if (truth_(panel.collapsible)) {
        e.preventDefault();
        return set_collapsed_BANG_(panel, not(panel.collapsed));
      }
    } else {
      const b3 = bounds(panel);
      const fast4 = (() => {
        const or__23426__auto__5 = e.shiftKey;
        if (truth_(or__23426__auto__5)) {
          return or__23426__auto__5;
        } else {
          const or__23426__auto__6 = key1 === "PageUp";
          if (or__23426__auto__6) {
            return or__23426__auto__6;
          } else {
            return key1 === "PageDown";
          }
          ;
        }
        ;
      })();
      const step7 = (truth_(fast4) ? key_step_fast : key_step) * grow_sign(panel);
      const t8 = panel.target;
      const next9 = key1 === "End" ? b3.max : key1 === "Home" ? b3.min : key1 === "PageDown" ? t8 + step7 : key1 === "PageUp" ? t8 - step7 : _EQ_(key1, axes2["grow"]) ? t8 + step7 : _EQ_(key1, axes2["shrink"]) ? t8 - step7 : "else" ? null : null;
      if (!(next9 == null)) {
        e.preventDefault();
        return apply_size_BANG_(panel, clamp3(next9, b3.min, b3.max));
      }
      ;
    }
    ;
  };
  var reset_size_BANG_ = function(panel) {
    if (truth_(panel.collapsed)) {
      set_collapsed_BANG_(panel, false);
    }
    ;
    const total1 = group_extent(panel.group);
    const parsed2 = !(panel.defaultSize == null) ? panel.defaultSize : panel.initial;
    if (!(parsed2 == null)) {
      return apply_size_BANG_(panel, to_pixels(parsed2, total1));
    }
    ;
  };
  var attach_grip_BANG_ = function(grip, get_panel) {
    const state1 = { "pressed": null, "dragging": false, "dragged": false };
    const settle2 = (function() {
      state1["pressed"] = null;
      return state1["dragging"] = false;
    });
    grip.addEventListener("pointerdown", (function(e) {
      if (!(get_panel() == null)) {
        state1["dragged"] = false;
        state1["pressed"] = { "x": e.clientX, "y": e.clientY };
        return grip.setPointerCapture(e.pointerId);
      }
      ;
    }));
    grip.addEventListener("pointermove", (function(e) {
      const temp__23062__auto__3 = state1.pressed;
      if (truth_(temp__23062__auto__3)) {
        const pr4 = temp__23062__auto__3;
        const temp__23062__auto__5 = get_panel();
        if (truth_(temp__23062__auto__5)) {
          const p6 = temp__23062__auto__5;
          const dx7 = e.clientX - pr4.x;
          const dy8 = e.clientY - pr4.y;
          if (truth_(not(state1.dragging) && Math.hypot(dx7, dy8) >= pan_threshold)) {
            state1["dragging"] = true;
            state1["dragged"] = true;
            drag_start_BANG_(p6, null);
          }
          ;
          if (truth_(state1.dragging)) {
            return drag_move_BANG_(p6, dx7, dy8);
          }
          ;
        }
        ;
      }
      ;
    }));
    grip.addEventListener("pointerup", (function(_) {
      if (truth_(state1.dragging)) {
        const temp__23062__auto__9 = get_panel();
        if (truth_(temp__23062__auto__9)) {
          const p10 = temp__23062__auto__9;
          drag_end_BANG_(p10);
        }
      }
      ;
      return settle2();
    }));
    grip.addEventListener("pointercancel", (function(_) {
      if (truth_(state1.dragging)) {
        const temp__23062__auto__11 = get_panel();
        if (truth_(temp__23062__auto__11)) {
          const p12 = temp__23062__auto__11;
          drag_cancel_BANG_(p12);
        }
      }
      ;
      return settle2();
    }));
    grip.addEventListener("dblclick", (function(_) {
      if (truth_(state1.dragged)) {
        return null;
      } else {
        const temp__23062__auto__13 = get_panel();
        if (truth_(temp__23062__auto__13)) {
          const p14 = temp__23062__auto__13;
          return reset_size_BANG_(p14);
        }
        ;
      }
      ;
    }));
    return grip.addEventListener("keydown", (function(e) {
      const temp__23062__auto__15 = get_panel();
      if (truth_(temp__23062__auto__15)) {
        const p16 = temp__23062__auto__15;
        return resize_by_key_BANG_(p16, e);
      }
      ;
    }));
  };
  var separator_panel = function(group, slot) {
    return group.bySide[truth_(fill_after_QMARK_(slot)) ? "start" : "end"];
  };
  var place_all_BANG_ = function(group) {
    const gel1 = group.el;
    const children2 = Array.from(gel1.children);
    const fill3 = children2.find((function(c) {
      return fill_node_QMARK_(c);
    }));
    const by_side4 = {};
    group["fill"] = !(fill3 == null) ? fill3 : null;
    group["fillInner"] = truth_(!(fill3 == null) && fill3.hasAttribute("data-pin")) ? fill3.querySelector(":scope > .ui-panels-fill-inner") : null;
    group.panels.forEach((function(p) {
      const el5 = p.el;
      const end6 = fill_after_QMARK_(el5);
      const neighbour7 = truth_(end6) ? el5.nextElementSibling : el5.previousElementSibling;
      const bare8 = not(separator_node_QMARK_(neighbour7));
      p["end"] = end6;
      el5.setAttribute("data-edge", truth_(end6) ? "end" : "start");
      if (bare8) {
        el5.setAttribute("data-bare", "true");
      } else {
        el5.removeAttribute("data-bare");
      }
      ;
      by_side4[truth_(end6) ? "start" : "end"] = p;
      return p["grips"] = !(p.edge == null) ? [p.edge] : [];
    }));
    group["bySide"] = by_side4;
    children2.forEach((function(c) {
      if (truth_(separator_node_QMARK_(c))) {
        const temp__23062__auto__9 = c["__uiSepGrip"];
        if (truth_(temp__23062__auto__9)) {
          const grip10 = temp__23062__auto__9;
          const temp__23062__auto__11 = separator_panel(group, c);
          if (truth_(temp__23062__auto__11)) {
            const p12 = temp__23062__auto__11;
            return p12.grips.push(grip10);
          }
          ;
        }
        ;
      }
      ;
    }));
    return group.panels.forEach((function(p) {
      return sync_aria_BANG_(p);
    }));
  };
  var observe_panel_attrs_BANG_ = function(panel) {
    const el1 = panel.el;
    const mo2 = new MutationObserver((function(muts) {
      return muts.forEach((function(m) {
        const attr3 = m.attributeName;
        if (attr3 === "data-collapsed") {
          const want4 = el1.hasAttribute("data-collapsed");
          if (!_EQ_(truth_(want4) ? true : false, truth_(panel.collapsed) ? true : false)) {
            return set_collapsed_BANG_(panel, want4);
          }
          ;
        } else {
          if (attr3 === "data-size") {
            const temp__23062__auto__5 = parse_size(el1.getAttribute("data-size"));
            if (truth_(temp__23062__auto__5)) {
              const parsed6 = temp__23062__auto__5;
              if (truth_((() => {
                const or__23426__auto__7 = !_EQ_(parsed6.value, panel.value);
                if (or__23426__auto__7) {
                  return or__23426__auto__7;
                } else {
                  return !_EQ_(truth_(parsed6.pct) ? true : false, truth_(panel.pct) ? true : false);
                }
                ;
              })())) {
                panel["pct"] = parsed6.pct;
                panel["value"] = parsed6.value;
                return retarget_BANG_(panel);
              }
              ;
            }
            ;
          } else {
            return null;
          }
        }
        ;
      }));
    }));
    mo2.observe(el1, { "attributes": true, "attributeFilter": ["data-collapsed", "data-size"] });
    return panel["attrObserver"] = mo2;
  };
  var create_panel_BANG_ = function(group, el) {
    const content1 = el.querySelector(":scope > .ui-panels-content");
    const edge2 = el.querySelector(":scope > .ui-panels-edge");
    const parsed3 = parse_size(el.getAttribute("data-size"));
    const persist_key4 = el.getAttribute("data-persist-key");
    const saved5 = !(persist_key4 == null) ? restore_persisted(persist_key4) : null;
    const saved_size6 = !(saved5 == null) ? parse_size(`${saved5.size ?? ""}`) : null;
    const active7 = !(saved_size6 == null) ? saved_size6 : parsed3;
    const collapsed8 = !(saved5 == null) ? truth_(saved5.collapsed) ? true : false : el.hasAttribute("data-collapsed");
    const panel9 = { "folding": false, "edge": edge2, "el": el, "group": group, "collapsed": collapsed8, "content": content1, "collapsible": el.hasAttribute("data-collapsible"), "value": !(active7 == null) ? active7.value : 0, "grips": [], "persist": persist_key4, "dragging": false, "minSize": parse_size(el.getAttribute("data-min-size")), "pct": !(active7 == null) ? active7.pct : false, "anim": null, "initial": parsed3, "unlock": null, "defaultSize": parse_size(el.getAttribute("data-default-size")), "target": 0, "end": false, "maxSize": parse_size(el.getAttribute("data-max-size")), "session": {} };
    el["__uiPanel"] = panel9;
    group.panels.push(panel9);
    const m10 = measure(panel9);
    const t11 = truth_(collapsed8) ? 0 : m10;
    panel9["target"] = t11;
    reflect_collapsed_BANG_(panel9);
    jump_size_BANG_(panel9, t11);
    jump_content_BANG_(panel9, m10);
    if (!(edge2 == null)) {
      edge2.setAttribute("aria-orientation", group.axes["sepOrient"]);
      attach_grip_BANG_(edge2, (function() {
        return panel9;
      }));
    }
    ;
    observe_panel_attrs_BANG_(panel9);
    return panel9;
  };
  var attach_separator_BANG_ = function(group, slot) {
    const grip1 = slot.querySelector(":scope > .ui-panels-grip");
    const grip2 = !(grip1 == null) ? grip1 : slot;
    slot["__uiSepGrip"] = grip2;
    grip2.setAttribute("aria-orientation", group.axes["sepOrient"]);
    return attach_grip_BANG_(grip2, (function() {
      return separator_panel(group, slot);
    }));
  };
  var bind_children_BANG_ = function(group) {
    return Array.from(group.el.children).forEach((function(c) {
      if (truth_((() => {
        const and__23442__auto__1 = has_attr_QMARK_(c, "data-ui-panels-panel");
        if (truth_(and__23442__auto__1)) {
          return c["__uiPanel"] == null;
        } else {
          return and__23442__auto__1;
        }
        ;
      })())) {
        return create_panel_BANG_(group, c);
      } else {
        if (truth_((() => {
          const and__23442__auto__2 = separator_node_QMARK_(c);
          if (truth_(and__23442__auto__2)) {
            return c["__uiSepGrip"] == null;
          } else {
            return and__23442__auto__2;
          }
          ;
        })())) {
          return attach_separator_BANG_(group, c);
        } else {
          return null;
        }
      }
      ;
    }));
  };
  var prune_panels_BANG_ = function(group) {
    const gel1 = group.el;
    const kept2 = group.panels.filter((function(p) {
      return p.el.parentElement === gel1;
    }));
    group.panels.forEach((function(p) {
      if (p.el.parentElement === gel1) {
        return null;
      } else {
        release_BANG_(p);
        const temp__23062__auto__3 = p.attrObserver;
        if (truth_(temp__23062__auto__3)) {
          const mo4 = temp__23062__auto__3;
          return mo4.disconnect();
        }
        ;
      }
      ;
    }));
    return group["panels"] = kept2;
  };
  var refit_BANG_ = function(panel) {
    if (truth_((() => {
      const and__23442__auto__1 = panel.pct;
      if (truth_(and__23442__auto__1)) {
        return not(panel.dragging) && not(panel.folding);
      } else {
        return and__23442__auto__1;
      }
      ;
    })())) {
      const m2 = measure(panel);
      const v3 = truth_(panel.collapsed) ? 0 : m2;
      jump_content_BANG_(panel, m2);
      if (!_EQ_(v3, panel.target)) {
        panel["target"] = v3;
        jump_size_BANG_(panel, v3);
        return sync_aria_BANG_(panel);
      }
      ;
    }
    ;
  };
  var init_group_BANG_ = function(gel) {
    if (gel["__uiPanelsGroup"] == null) {
      const orientation1 = (() => {
        const o2 = gel.getAttribute("data-orientation");
        if (o2 === "vertical") {
          return "vertical";
        } else {
          return "horizontal";
        }
        ;
      })();
      const group3 = { "el": gel, "axes": axes_config[orientation1], "panels": [], "bySide": {}, "fill": null, "fillInner": null };
      gel["__uiPanelsGroup"] = group3;
      bind_children_BANG_(group3);
      place_all_BANG_(group3);
      const mo4 = new MutationObserver((function(_) {
        prune_panels_BANG_(group3);
        bind_children_BANG_(group3);
        return place_all_BANG_(group3);
      }));
      mo4.observe(gel, { "childList": true });
      const ro5 = new ResizeObserver((function(_) {
        return group3.panels.forEach((function(p) {
          return refit_BANG_(p);
        }));
      }));
      ro5.observe(gel);
      return group3;
    }
    ;
  };
  var scan_BANG_ = function() {
    return Array.from(document.querySelectorAll("[data-ui-panels-group]")).forEach((function(gel) {
      return init_group_BANG_(gel);
    }));
  };
  var scan_scheduled = atom(null);
  var schedule_scan_BANG_ = function() {
    if (deref(scan_scheduled) == null) {
      return reset_BANG_(scan_scheduled, requestAnimationFrame((function() {
        reset_BANG_(scan_scheduled, null);
        return scan_BANG_();
      })));
    }
    ;
  };
  window["__uiPanels"] = scan_BANG_;
  var start_BANG_ = function() {
    scan_BANG_();
    const mo1 = new MutationObserver((function(_) {
      return schedule_scan_BANG_();
    }));
    return mo1.observe(document.body, { "childList": true, "subtree": true });
  };
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", (function() {
      return start_BANG_();
    }));
  } else {
    start_BANG_();
  }

  // .compiled/popover.mjs
  var gap = 8;
  var edge = 8;
  var clamp4 = function(v, lo, hi) {
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
    content.style.left = `${clamp4(left11, edge, vw9 - cw7 - edge) ?? ""}px`;
    return content.style.top = `${clamp4(top12, edge, vh10 - ch8 - edge) ?? ""}px`;
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
  var init_BANG_5 = function() {
    document.addEventListener("toggle", on_toggle, true);
    window.addEventListener("scroll", reposition_BANG_, true);
    return window.addEventListener("resize", reposition_BANG_);
  };
  init_BANG_5();
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
    const f24 = (function(var_args) {
      const args251 = [];
      const len__23321__auto__2 = arguments.length;
      let i263 = 0;
      while (true) {
        if (i263 < len__23321__auto__2) {
          args251.push(arguments[i263]);
          let G__4 = i263 + 1;
          i263 = G__4;
          continue;
        }
        ;
        break;
      }
      ;
      const argseq__23513__auto__5 = 1 < args251.length ? args251.slice(1) : null;
      return f24.cljs$core$IFn$_invoke$arity$variadic(arguments[0], argseq__23513__auto__5);
    });
    f24.cljs$core$IFn$_invoke$arity$variadic = (function(trigger, args) {
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
    f24.cljs$lang$maxFixedArity = 1;
    return f24;
  })();
  window["__uiSelect"] = open_select;

  // .compiled/theme.mjs
  var storage_key2 = "ui-theme";
  var get_stored = function() {
    return (() => {
      try {
        return localStorage.getItem(storage_key2);
      } catch (_e1) {
        return null;
      }
    })();
  };
  var store_BANG_ = function(mode) {
    return (() => {
      try {
        if (mode === "auto") {
          return localStorage.removeItem(storage_key2);
        } else {
          return localStorage.setItem(storage_key2, mode);
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
    const G__281 = mode;
    switch (G__281) {
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
    const G__292 = mode;
    switch (G__292) {
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
  var notify_BANG_2 = function(mode, effective) {
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
    return notify_BANG_2(m1, resolve_effective(m1));
  };
  var toggle_BANG_ = function() {
    const current1 = get_mode();
    const next_mode2 = (() => {
      const G__303 = current1;
      switch (G__303) {
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
  var init_BANG_6 = function() {
    const mode1 = get_mode();
    apply_theme_BANG_(mode1);
    const mql2 = window.matchMedia("(prefers-color-scheme: dark)");
    return mql2.addEventListener("change", (function(_e) {
      if (get_mode() === "auto") {
        apply_theme_BANG_("auto");
        return notify_BANG_2("auto", resolve_effective("auto"));
      }
      ;
    }));
  };
  window["__uiTheme"] = { "init": init_BANG_6, "set": set_mode_BANG_, "get": get_mode, "effective": get_effective, "toggle": toggle_BANG_, "subscribe": subscribe_BANG_ };

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
  var scan_BANG_2 = function() {
    for (let G__1 of iterable(Array.from(document.querySelectorAll("[data-ui-toast]")))) {
      const el2 = G__1;
      consume_BANG_(el2);
    }
    return null;
  };
  var init_BANG_7 = function() {
    scan_BANG_2();
    const obs1 = new MutationObserver((function(_, _2) {
      return scan_BANG_2();
    }));
    return obs1.observe(document.body, { "childList": true, "subtree": true });
  };
  window["__uiToast"] = show_BANG_;
  if ("loading" === document.readyState) {
    document.addEventListener("DOMContentLoaded", init_BANG_7);
  } else {
    init_BANG_7();
  }

  // .compiled/touch.mjs
  var mq = window.matchMedia("(hover: none)");
  var viewport_overrides = [["width", "device-width"], ["initial-scale", "1.0"], ["maximum-scale", "1.0"], ["user-scalable", "no"]];
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
    const kept5 = remove((function(p__31) {
      const vec__69 = p__31;
      const k10 = nth(vec__69, 0, null);
      const _11 = nth(vec__69, 1, null);
      return contains_QMARK_(override_keys4, k10);
    }), entries1);
    return join(", ", map((function(p__32) {
      const vec__1215 = p__32;
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
