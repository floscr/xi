### Clojure / ClojureScript

- **Purity & effects** — is logic kept pure with side effects pushed to the
  edges? Flag I/O, atom swaps, or `println` buried inside otherwise-pure
  functions (in Xi: handlers must be pure, effects run only in the interpreter).
- **nil & destructuring** — `nil` punning, `nil` leaking from `first`/`get`/`seq`,
  keyword-as-fn vs `get` on possibly-nil maps.
- **Laziness** — lazy seqs escaping their scope, holding the head, side effects
  inside `map`/`for` that never realize; use `doseq`/`run!`/`doall` for effects.
- **Threading** — `->` vs `->>` mixups; over-long threads hiding intermediate
  shape changes.
- **Error handling** — prefer `ex-info`/`ex-data` over bare strings; don't
  swallow errors with `(catch :default _ nil)`.
- **ClojureScript interop** — use `(aget obj "k")` for env/JS access,
  watch `clj->js`/`js->clj` round-trips and `#js` literals, and don't drop
  errors from `.then` chains (no `.catch`).
- **Naming** — predicates end in `?`, side-effecting fns end in `!`,
  `->x` for constructors.
- **Reuse** — check `xi.util` / the namespace for an existing helper before
  adding one.
