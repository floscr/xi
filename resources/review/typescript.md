### TypeScript / JavaScript

- **Types** — `any` escapes, unsafe `as` casts, non-null `!` assertions hiding
  real nullability, missing discriminated-union exhaustiveness, `null` vs
  `undefined` confusion.
- **Async** — unhandled promise rejections, missing `await`, `forEach` with an
  async callback, sequential `await` in loops that should be `Promise.all`,
  race conditions.
- **React (if present)** — exhaustive-deps on hooks, stale closures, missing
  `useEffect` cleanup, list `key`s, state derived in render vs `useMemo`.
- **Equality & mutation** — `==` vs `===`, accidental mutation of props/state,
  spreads that only shallow-copy.
- **Boundaries** — validate external input (API/user), guard `JSON.parse`,
  injection (XSS via `dangerouslySetInnerHTML`, unsanitized template strings).
- **Reuse** — check for an existing util/component before adding one.
