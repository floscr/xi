# Web animations

How the web client animates DOM changes, and the pitfalls that cost time
getting there. Reference implementations: the sidebar's delete animation
(`:sidebar/animate-leave` in `xi.web.core`, rules in `style.css`) and row
movement (`xi.web.flip`).

## Two patterns

**Leaving** (an element is about to be removed): don't let the state change
remove it at once. Play the animation first, then run the real change.

1. The user-facing event (`:session/delete`) only emits an effect
   (`[:sidebar/animate-leave {:session-id … :then {…}}]`).
2. The effect finds the element in the DOM, adds a `--leaving` class, and
   dispatches `:then` after the duration (`:session/delete-now`).
3. No element in the DOM (drawer closed, other view) or reduced motion →
   dispatch `:then` immediately.

Class changes made directly on a node survive re-renders: Replicant only
writes attributes whose *vdom* value changed, so a re-render during the
animation (a lobby broadcast, say) leaves the class alone.

**Moving** (siblings shift because something above them changed): FLIP, in
`xi.web.flip`. Rows opt in with a `data-flip` attribute holding a stable key.
`render!` calls `flip/snapshot` before rendering and `flip/play!` after; each
row that moved animates (WAAPI `transform`) from its old spot to its new one.
Rows that have no old position (new in this render) just appear.

- Tag a new kind of row by adding `:data-flip (str "kind:" id)` to its
  attrs. Keys must be unique per row; a repeat (a session listed in two
  groups) is disambiguated by occurrence automatically.
- Not everything should glide: project rows are deliberately untagged
  ("too much"). Tag sparingly.
- Measuring forces layout, so `render!` only snapshots when a slice that
  moves sidebar rows changed (`sidebar-layout-sig-of`). Streamed tokens and
  typing skip it. Add to that signature if a new state slice moves rows.
- Rows with `project-card-trigger--leaving` are skipped; they collapse on
  their own.

## Pitfalls

- **Don't use Replicant's `:replicant/unmounting` for this.** It *replaces*
  the element's whole class list (the base classes, and with them any
  `transition`, are gone), reads `transition-duration` right after, and
  removes the node on the first matching `transitionend` — child
  transitions bubble and count. In practice the transitions were cancelled
  in the same frame and the row vanished instantly (or hung until the
  failsafe timer). Drive the animation yourself (see above).
- **Height can't transition from `auto`.** `interpolate-size:
  allow-keywords` fixes it in Chromium only; **Firefox and Safari ignore it**,
  so `height: 0` snaps and `overflow: hidden` hides the row on the first
  frame (the opacity/transform transitions run, invisibly). Pin the current
  height in px, force a reflow, then set `0px`:

  ```clojure
  (set! (.. el -style -height) (str (.-offsetHeight el) "px"))
  (.-offsetHeight el)                       ; reflow, so the 0 below transitions
  (.add (.-classList el) "…--leaving")
  (set! (.. el -style -height) "0px")
  ```

  The inline height is why the class itself doesn't set `height`.
- **One session, several rows.** The same session can sit in more than one
  sidebar group (Favorites + Recent). Select with `querySelectorAll`, not
  `querySelector`, or the other copy disappears instantly.
- **Optimistic removal needs a tombstone.** A lobby broadcast sent before the
  server finished a delete brings the card back for a frame. Locally deleted
  ids live in `:web/deleted-session-ids` and are filtered out of incoming
  `:lobby/state` until the server stops listing them.
- **Honor `prefers-reduced-motion`.** Check `matchMedia` in the effect/FLIP
  code and skip the animation; CSS-only overrides don't help when the delay
  before the real change is driven from JS.

## Testing

Verify in the isolated demo server (`bb demo`, see [demo.md](demo.md)), not
the real one. Sample the element per animation frame via the chrome tools
(`getComputedStyle(el).opacity/height`, `el.getAnimations()`), and log
`MutationObserver` records to see what Replicant actually rebuilds. Check
Firefox by hand — the chrome tools only drive Chromium, which hides the
`interpolate-size` class of bug.
