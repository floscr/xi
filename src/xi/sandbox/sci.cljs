(ns xi.sandbox.sci
  "The hardened SCI setup every xi sandbox shares — the clj tool and user
   extensions. Use `init` instead of `sci/init` so both get the same
   protection and can't drift apart.

   SCI in ClojureScript needs two extra measures to actually confine code:
     - `:classes` values must be null-prototype objects (`null-proto`): SCI's
       cljs static-member read (`Class/foo`) is unchecked, so a real class
       value would expose its constructor chain. SCI's own default `Error`
       class is overridden the same way.
     - `denied-core` symbols are removed (:deny): raw JS-property access skips
       SCI's instance-interop check, and dynamic eval / var-namespace
       manipulation is re-entry surface sandboxed code never needs.
   Instance interop keeps working for configured class names (SCI keys it on
   the name and reflects on the real object).

   The escape corpus lives in test/xi/ext/clj_sandbox_test.cljs."
  (:require [sci.core :as sci]))

(def denied-core
  "Core symbols removed from every sandbox (see ns doc)."
  '#{aget aset unchecked-get unchecked-set unchecked-get-field
     js-obj js-invoke js-keys js-delete js-in
     eval load-string load-file load load-reader read+string
     intern resolve ns-resolve requiring-resolve find-var
     alter-var-root alter-meta! reset-meta!
     create-ns in-ns remove-ns the-ns find-ns all-ns
     ns-map ns-publics ns-interns ns-unmap ns-name})

(defn null-proto
  "A null-prototype object exposing only `members` (name→value) — the form
   every `:classes` value must take."
  [members]
  (let [o (js/Object.create nil)]
    (doseq [[k v] members] (unchecked-set o (name k) v))
    o))

(defn null-proto-copy
  "null-proto exposing every own property of `src` (e.g. all of js/Math)."
  [src]
  (let [o (js/Object.create nil)]
    (doseq [k (js/Object.getOwnPropertyNames src)]
      (unchecked-set o k (unchecked-get src k)))
    o))

(defn- error-ctor
  "Factory behind `(Exception. msg)` / `(js/Error. msg)` / `assert`. SCI cljs
   constructs via `(or (:constructor opts) (:class opts))`, so construction
   goes through this fn while static access (`Error/constructor`) only ever
   sees the null-proto `:class`."
  ([] (js/Error.))
  ([msg] (js/Error. msg))
  ([msg opts] (js/Error. msg opts)))

(defn- error-class
  "Null-proto static surface for the Error aliases that still works as the
   right-hand side of `instanceof`: SCI's `(catch Exception e …)` compiles to
   `e instanceof <class>`, which needs a callable or a `Symbol.hasInstance`
   method. The symbol-keyed hook isn't reachable by name from sandboxed code,
   so `Exception/constructor` etc. stay closed."
  []
  (let [o (null-proto {})]
    (js/Object.defineProperty o js/Symbol.hasInstance
                              #js {:value (fn [x] (instance? js/Error x))})
    o))

(def ^:private default-class-overrides
  "SCI ships a default cljs `Error` class; replace it (and its aliases) with a
   null-proto static surface plus a constructor fn, so construction,
   `assert` and `(catch Exception e …)` keep working without exposing the
   real class."
  (let [error {:class (error-class) :constructor error-ctor}]
    {'Error error 'js/Error error 'Exception error 'Throwable error}))

(defn init
  "sci/init with the shared hardening applied: `denied-core` merged into
   `:deny`, SCI's default Error class overridden. Callers still supply their
   own `:classes` as null-proto values."
  [opts]
  (sci/init (-> opts
                (update :classes #(merge default-class-overrides %))
                (update :deny (fnil into #{}) denied-core))))
