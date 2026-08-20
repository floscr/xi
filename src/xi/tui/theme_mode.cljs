(ns xi.tui.theme-mode
  "Detect the terminal's light/dark theme so tool/code blocks can follow it.

   Xi's prose already 'reacts' passively — uncolored text inherits the
   terminal's default fg/bg, which flips when the terminal theme flips. Only the
   blocks hardcode colors, so we need an explicit signal for them.

   Signal precedence:
     1. `XI_THEME_MODE` env var (`light`/`dark`) — explicit override.
     2. The dotfiles `theme-mode` state file
        (`$XDG_STATE_HOME/theme-mode/mode`, default `~/.local/state/theme-mode/mode`),
        containing `light` or `dark`. This is the same file nvim/emacs/etc.
        read to switch live.
     3. Default `:dark`.

   `refresh!` re-reads the signal (cheaply, TTL-cached) and pushes the result
   into `xi.highlight.theme` so every block-painting caller reflects it. Call it
   once per render frame."
  (:require ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            [clojure.string :as str]
            [xi.highlight.theme :as hl-theme]))

(defn- env [k] (aget js/process.env k))

(defn- state-file []
  (let [xdg (env "XDG_STATE_HOME")
        base (if (not-empty xdg) xdg (path/join (os/homedir) ".local" "state"))]
    (path/join base "theme-mode" "mode")))

(defn- read-file-mode []
  (try
    (-> (fs/readFileSync (state-file) "utf8") str/trim str/lower-case)
    (catch :default _ nil)))

(defn- detect
  "Resolve the current mode to `:light` or `:dark` from env/state file."
  []
  (let [v (or (some-> (env "XI_THEME_MODE") str/trim str/lower-case not-empty)
              (read-file-mode))]
    (if (= "light" v) :light :dark)))

;; TTL cache so streaming renders (many per second) don't stat the file every
;; frame, while a theme switch still lands within ~half a second.
(def ^:private ttl-ms 500)
(defonce ^:private cache (atom {:mode nil :at 0}))

(defn current
  "Return the cached current mode (`:light`/`:dark`), re-detecting past the TTL."
  []
  (let [now (js/Date.now)
        {:keys [mode at]} @cache]
    (if (and mode (< (- now at) ttl-ms))
      mode
      (let [m (detect)]
        (reset! cache {:mode m :at now})
        m))))

(defn refresh!
  "Re-detect the mode and push it into `xi.highlight.theme`.
   Returns the current mode. Call once per render frame."
  []
  (let [m (current)]
    (hl-theme/set-mode! m)
    m))

(defn watch!
  "Watch the theme-mode state file and call `(on-change)` when it changes, so
   the TUI can re-theme live when the OS theme flips (super+i etc.) without a
   new xi event. Watches the parent directory (robust against atomic rename
   writes) and debounces bursts. Returns a stop fn; no-ops if unwatchable."
  [on-change]
  (try
    (let [file (state-file)
          dir  (path/dirname file)
          base (path/basename file)
          debounce (atom nil)
          fire (fn []
                 ;; bust the TTL cache so the next detect is fresh
                 (reset! cache {:mode nil :at 0})
                 (on-change))
          handler (fn [_ fname]
                    (when (or (nil? fname) (= (str fname) base))
                      (when-let [t @debounce] (js/clearTimeout t))
                      (reset! debounce (js/setTimeout fire 60))))
          watcher (fs/watch dir #js {:persistent false} handler)]
      (fn [] (try (.close watcher) (catch :default _ nil))))
    (catch :default _ (fn [] nil))))
