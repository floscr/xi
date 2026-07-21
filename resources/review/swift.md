### Swift

- **Optionals & crashes** — force unwraps (`!`), implicitly-unwrapped
  optionals, `try!`, `as!`, and array subscripts that can trap. Prefer
  `guard let`/`if let`, `??`, and `?.`. Flag `fatalError`/`preconditionFailure`
  on reachable paths.
- **Memory & retain cycles** — closures capturing `self` strongly (need
  `[weak self]`/`[unowned self]`), delegate properties that aren't `weak`,
  parent↔child reference cycles, and `unowned` where the referent can outlive
  the closure (crash on access).
- **Concurrency** — data races on shared mutable state, UI mutated off the main
  thread (`@MainActor` / `MainActor.run`), missing `await`, unstructured `Task`
  that's never cancelled, `Sendable` violations, blocking the main thread with
  sync work, and mixing GCD with `async`/`await`.
- **Value vs reference semantics** — `struct` vs `class` choice, unintended
  sharing via reference types, `mutating` methods, and large structs copied on
  every mutation.
- **Error handling** — `try?` silently swallowing errors, over-broad `catch`,
  errors mapped to generic types losing context; prefer typed `Error` enums.
- **SwiftUI (if present)** — `@State` vs `@StateObject` vs `@ObservedObject`
  ownership, missing/unstable `id` in `ForEach`, expensive work in `body`,
  and `@EnvironmentObject` that isn't injected (runtime crash).
- **API design & access control** — default to `private`/`fileprivate`, mark
  classes `final` unless subclassed, avoid leaking implementation types across
  module boundaries, and prefer protocols over concrete types at seams.
- **Reuse** — check for an existing extension/helper before adding one.
