(ns xi.tui.theme-mode
  "Resolve the terminal's light/dark theme so tool/code blocks can follow it.

   Xi's prose already 'reacts' passively — uncolored text inherits the
   terminal's default fg/bg, which flips when the terminal theme flips. Only the
   blocks hardcode colors, so we need an explicit signal for them.

   Signal precedence:
     1. `XI_THEME_MODE` env var (`light`/`dark`) — explicit override.
     2. The mode in state, set by the `:theme/set` event
        (`{:type :theme/set :mode :light}`). A user extension can dispatch it
        to follow an OS or terminal theme switcher live; see
        docs/guide/extensions-reference.md.
     3. Default `:dark`.

   `refresh!` resolves the signal and pushes the result into
   `xi.highlight.theme` so every block-painting caller reflects it. Call it
   once per render frame with the current state."
  (:require [clojure.string :as str]
            [xi.highlight.theme :as hl-theme]))

(defn resolve-mode
  "Pure: `:light` or `:dark` from the `XI_THEME_MODE` value (string or nil) and
   the mode set in state (`:light`, `:dark` or nil)."
  [env-mode state-mode]
  (let [v (or (some-> env-mode str/trim str/lower-case not-empty)
              (some-> state-mode name))]
    (if (= "light" v) :light :dark)))

(defn refresh!
  "Resolve the mode from the env and `state`, and push it into
   `xi.highlight.theme`. Returns the mode. Call once per render frame."
  [state]
  (let [m (resolve-mode (aget js/process.env "XI_THEME_MODE")
                        (get-in state [:theme :mode]))]
    (hl-theme/set-mode! m)
    m))
