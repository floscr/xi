(ns xi.tui.keys
  "Keyboard shortcuts for the terminal client, on the shared xi.keys model.

   `decode` turns the raw bytes a terminal sends for one keypress — legacy
   escape sequences and the kitty keyboard protocol's CSI-u form — into a
   canonical chord (\"alt+n\", \"ctrl+o\", \"shift+g\", \"escape\"), so the
   same key names work in config.edn for both clients and extensions can ask
   for any key, not just the ones a hand-written table knew.

   The TUI's effective keymap (xi.keys/effective-keymap for :tui — the
   defaults, the extensions' default keys, the user's config.edn `:keys`) is
   installed once at startup with `set-keymap!` and read by the editor hook
   (xi.client.tui) and the pager components (xi.tui.pager), which each
   resolve keys for the layers active in their context.

   Modes: the editor is `:mode/compose` (keys that type a character are never
   looked up there), a focused pager / dialog / menu is `:mode/navigate`."
  (:require [clojure.string :as str]
            [xi.keys :as keys]
            [xi.tui.ansi :as ansi]))

(def ^:private ESC (str (char 27)))

;; ── Decoding ─────────────────────────────────────────────────────────────────

(def ^:private csi-named
  "CSI final byte / tilde code → named key."
  {"A" "up" "B" "down" "C" "right" "D" "left" "H" "home" "F" "end"
   "1~" "home" "2~" "insert" "3~" "delete" "4~" "end" "5~" "pageup" "6~" "pagedown"
   "7~" "home" "8~" "end"
   "P" "f1" "Q" "f2" "R" "f3" "S" "f4"
   "11~" "f1" "12~" "f2" "13~" "f3" "14~" "f4" "15~" "f5" "17~" "f6" "18~" "f7"
   "19~" "f8" "20~" "f9" "21~" "f10" "23~" "f11" "24~" "f12"})

(def ^:private csi-u-named
  "kitty CSI-u codepoints of the non-printing keys."
  {27 "escape" 13 "enter" 9 "tab" 127 "backspace" 8 "backspace" 32 "space"
   57358 "pageup" 57359 "pagedown" 57360 "home" 57361 "end" 57362 "insert"
   57363 "delete" 57352 "up" 57353 "down" 57354 "left" 57355 "right"
   57364 "f1" 57365 "f2" 57366 "f3" 57367 "f4" 57368 "f5" 57369 "f6"
   57370 "f7" 57371 "f8" 57372 "f9" 57373 "f10" 57374 "f11" 57375 "f12"})

(defn- mods-of
  "kitty modifier parameter (1 + bitfield: 1 shift, 2 alt, 4 ctrl, 8 super)
   → {:shift :alt :ctrl :meta}."
  [n]
  (let [bits (dec (or n 1))]
    {:shift (pos? (bit-and bits 1))
     :alt   (pos? (bit-and bits 2))
     :ctrl  (pos? (bit-and bits 4))
     :meta  (pos? (bit-and bits 8))}))

(defn- printable? [s]
  (and (= 1 (count s))
       (let [c (.charCodeAt s 0)]
         (and (>= c 32) (not= c 127)))))

(defn- control-char
  "A C0 control byte → chord: ^A..^Z are ctrl+a..z (tab, enter, backspace and
   escape keep their own names), the rest the ctrl+punctuation they stand for."
  [code]
  (case code
    0  "ctrl+space"
    8  "backspace"
    9  "tab"
    10 "enter"
    13 "enter"
    27 "escape"
    28 "ctrl+\\"
    29 "ctrl+]"
    30 "ctrl+^"
    31 "ctrl+/"
    127 "backspace"
    (when (<= 1 code 26)
      (str "ctrl+" (char (+ 96 code))))))

(defn decode
  "Raw terminal input for one keypress → canonical chord, or nil when it is
   not a single key (a paste, an unknown sequence, several keys at once)."
  [data]
  (when (string? data)
    (let [n (count data)]
      (cond
        (zero? n) nil

        ;; plain character or C0 control byte
        (= n 1)
        (if (printable? data)
          (keys/chord {:key data})
          (control-char (.charCodeAt data 0)))

        ;; kitty CSI-u: ESC [ code[:shifted[:base]] ; mods[:event] u
        (re-matches #"\u001b\[(\d+)(?::\d*)*(?:;(\d+)(?::\d+)?)?u" data)
        (let [[_ code mods] (re-matches #"\u001b\[(\d+)(?::\d*)*(?:;(\d+)(?::\d+)?)?u" data)
              code (js/parseInt code 10)
              m    (mods-of (when mods (js/parseInt mods 10)))
              key  (or (get csi-u-named code)
                       (when (and (>= code 32) (not= code 127))
                         (js/String.fromCodePoint code)))]
          (when key (keys/chord (assoc m :key key))))

        ;; CSI with modifiers: ESC [ 1 ; mods X  /  ESC [ n ; mods ~
        (re-matches #"\u001b\[(\d+);(\d+)([A-Z~])" data)
        (let [[_ a mods fin] (re-matches #"\u001b\[(\d+);(\d+)([A-Z~])" data)
              m   (mods-of (js/parseInt mods 10))
              key (get csi-named (if (= fin "~") (str a "~") fin))]
          (when key (keys/chord (assoc m :key key))))

        ;; legacy CSI / SS3: arrows, home/end, tilde keys, shift+tab
        (re-matches #"\u001b[\[O](\d*[A-Z~])" data)
        (let [[_ code] (re-matches #"\u001b[\[O](\d*[A-Z~])" data)]
          (cond
            (= code "Z") "shift+tab"
            (= code "M") "shift+enter"
            :else (some-> (get csi-named code) (#(keys/chord {:key %})))))

        ;; alt + key: ESC prefix on a single character / control byte
        (and (= n 2) (= (first data) ESC))
        (let [c (subs data 1)]
          (cond
            ;; an uppercase letter after ESC is Alt+Shift+letter
            (printable? c) (keys/chord {:key c :alt true
                                        :shift (boolean (re-matches #"[A-Z]" c))})
            :else (when-let [base (control-char (.charCodeAt c 0))]
                    (if (str/starts-with? base "ctrl+")
                      (str "ctrl+alt+" (subs base 5))
                      (str "alt+" base)))))

        :else nil))))

;; ── Keymap ───────────────────────────────────────────────────────────────────

;; The TUI's effective keymap (`set-keymap!`); the defaults until installed.
(defonce keymap (atom (keys/effective-keymap {:surface :tui})))

(defonce ^:private actions
  ;; id → {:label …}: the catalog plus the extension actions (for listings)
  (atom keys/catalog))

(defn set-keymap!
  "Install the effective TUI keymap from the user's config.edn `:keys`
   (`user-keys`, may be nil) and the extensions' actions
   (xi.keys/ext-keybinding->action maps, for their default keys and labels)."
  [user-keys ext-actions]
  (reset! actions (into keys/catalog (map (fn [a] [(:id a) (select-keys a [:label])])) ext-actions))
  (reset! keymap (keys/effective-keymap {:surface :tui
                                         :extensions [(keys/ext-keymap ext-actions)]
                                         :user user-keys})))

(defn lookup
  "xi.keys/lookup against the installed keymap."
  ([layers pending chord] (keys/lookup @keymap layers pending chord))
  ([layers pending chord enabled?] (keys/lookup @keymap layers pending chord enabled?)))

(def pager-layers
  "The layers every pager resolves in after its own buffer layer."
  [:buffer/pager :mode/navigate :global])

;; ── Help bars ────────────────────────────────────────────────────────────────

(defn help-bar
  "A pager help toolbar from the installed keymap: `entries` are
   `[[action-id …] label]` pairs; each action's first bound key (in `layers`)
   is shown in compact form, several joined with `/`, and entries whose
   actions have no key are left out."
  [layers entries]
  (let [dim (partial ansi/fg :dim)
        acc (partial ansi/fg :accent)]
    (->> entries
         (keep (fn [[ids label]]
                 (let [ks (keep #(keys/shortcut @keymap layers % {:compact? true}) ids)]
                   (when (seq ks)
                     (str (acc (str/join "/" ks)) (dim (str ":" label)))))))
         (str/join (dim "  ")))))

;; ── Listing ──────────────────────────────────────────────────────────────────

(defn listing-text
  "The keyboard-shortcuts buffer text: every layer of the installed keymap
   with a bound action, `active` layers first, user changes marked with `*`."
  [active]
  (let [base     (keys/effective-keymap {:surface :tui})
        sections (keys/listing @keymap base @actions active)
        width    (->> sections (mapcat :rows) (map (comp count :display)) (reduce max 8))]
    (str/join
     "\n"
     (concat
      (mapcat (fn [{:keys [label active? rows]}]
                (concat
                 [(str (ansi/fg :bold label)
                       (when-not active? (ansi/fg :dim "  (not active here)")))]
                 (for [{:keys [display label custom?]} rows]
                   (str "  " (ansi/fg :accent (.padEnd display width)) "  " label
                        (when custom? (ansi/fg :dim "  *"))))
                 [""]))
              sections)
      [(ansi/fg :dim "Change them under :keys in ~/.config/xi/config.edn (* = yours).")]))))
