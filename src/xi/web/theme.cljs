(ns xi.web.theme
  "Color themes: the built-ins (ui.themes) and the user's own, each a map of
   ui.theme-model parameters — scales, page colors, exact per-mode palettes,
   font families — plus Xi's own on top: the sidebar, the markdown paragraph
   gap, the code size, syntax colors and diff bands (style.css reads them as
   --theme-*-<mode>, --paragraph-space, --code-scale).

   The user's value (`normalize`): {:active id :themes {name params}} — both
   keys absent when nothing is set; :active is a custom theme's name or a
   built-in's keyword id. Per user (xi.user-state :themes), cached per
   browser (xi.web.cache). Handlers in xi.web.core, dialog in xi.web.views;
   everything here is pure."
  (:require [clojure.string :as str]
            [ui.theme-model :as model]
            [ui.themes :as builtins]))

(def ^:private own-defaults
  "`:sidebar-shift` the OKLCH lightness the sidebar sits away from the page
   (negative = darker), unless the palette gives a :sidebar;
   `:paragraph-space` the gap between markdown paragraphs, in em;
   `:code-scale` a multiplier on every code font size (style.css --code-scale);
   `:diff-strength` how far diff line backgrounds stand out from the page.
   `:diff-add-color` / `:diff-del-color` (no default: the success / danger
   color) give those backgrounds their hue and chroma."
  {:sidebar-shift   -0.025
   :paragraph-space 0.75
   :code-scale      1
   :diff-strength   1})

(def defaults (merge model/defaults own-defaults))

(def ^:private own-ranges
  {:sidebar-shift   [-0.2 0.2]
   :paragraph-space [0 2]
   :code-scale      [0.7 1.5]
   :diff-strength   [0 2.5]})

(def ranges
  "[min max] per numeric parameter: what `normalize-params` accepts and the
   slider bounds."
  (merge model/ranges own-ranges))

(def ^:private own-color-keys #{:diff-add-color :diff-del-color})

(def color-keys
  "The parameters that hold a `color?` string (a color picker value)."
  (into model/color-keys own-color-keys))

(def color? model/color?)
(def hex->oklch model/hex->oklch)

(def presets
  "Hue starting points for the editor; each sets only the color keys and
   drops the draft's palettes. The dark background is the preset's own
   gray-950."
  [{:name "Purple"  :gray-hue 285 :gray-chroma 1   :accent-hue 286 :accent-chroma 1    :bg-light "#ffffff" :bg-dark "#0a0a0f"}
   {:name "Blue"    :gray-hue 255 :gray-chroma 0.5 :accent-hue 255 :accent-chroma 0.87 :bg-light "#ffffff" :bg-dark "#090a0c"}
   {:name "Neutral" :gray-hue 0   :gray-chroma 0   :accent-hue 255 :accent-chroma 0.87 :bg-light "#ffffff" :bg-dark "#0a0a0a"}
   {:name "Warm"    :gray-hue 60  :gray-chroma 0.7 :accent-hue 50  :accent-chroma 0.85 :bg-light "#ffffff" :bg-dark "#0d0907"}
   {:name "Rose"    :gray-hue 0   :gray-chroma 0.5 :accent-hue 350 :accent-chroma 0.85 :bg-light "#ffffff" :bg-dark "#0c090a"}
   {:name "Emerald" :gray-hue 165 :gray-chroma 0.5 :accent-hue 165 :accent-chroma 0.75 :bg-light "#ffffff" :bg-dark "#080b09"}])

(def max-themes 20)
(def max-name-length 40)

;; ── Xi's tokens ──────────────────────────────────────────────────────────────

(defn backgrounds
  "{:light [page sidebar] :dark [page sidebar]}: the page color as oklch(),
   the sidebar the palette's :sidebar or the page `:sidebar-shift` away in
   lightness."
  [params]
  (let [p    (merge defaults params)
        pair (fn [mode]
               (let [[l c h] (model/color->oklch (model/page-color p mode))]
                 [(model/oklch l c h)
                  (or (model/palette-color p mode :sidebar)
                      (model/oklch (model/clamp01 (+ l (:sidebar-shift p))) c h))]))]
    {:light (pair :light)
     :dark  (pair :dark)}))

(defn- status-color
  "Status color `k` (:success / :danger) of `mode`: the palette's, else the
   parameter."
  [p mode k]
  (or (model/palette-color p mode k)
      (get p (case k :success :success-color :danger :danger-color))))

(defn diff-color
  "The color the `side` (:add / :del) diff band of `mode` takes its hue and
   chroma from."
  ([params side] (diff-color params side :light))
  ([params side mode]
   (let [p (merge defaults params)]
     (case side
       :add (or (:diff-add-color p) (status-color p mode :success))
       :del (or (:diff-del-color p) (status-color p mode :danger))))))

(defn- status-tints
  "Tokens that carry a status hue on a themed page, where a translucent status
   wash over a colorful surface turns muddy: bands at a block's lightness in
   a hue — diff lines (add-bg / del-bg, `diff-color` at `:diff-strength`) and
   danger-bg (menus' danger hover) — and danger-fg, error text that contrasts
   by lightness when the danger hue is the page's own."
  [p mode l dark?]
  (let [dir  (if dark? 1 -1)
        band (fn [color strength]
               (let [[_ c h] (model/color->oklch color)]
                 (model/oklch (model/clamp01 (+ l (* dir 0.06 strength))) (min (* 0.07 strength) c) h)))
        danger (status-color p mode :danger)
        [_ dc dh] (model/color->oklch danger)]
    {"add-bg"    (band (diff-color p :add mode) (:diff-strength p))
     "del-bg"    (band (diff-color p :del mode) (:diff-strength p))
     "danger-bg" (band danger 1)
     "danger-fg" (model/oklch (if dark? 0.85 0.45) (min 0.12 dc) dh)}))

;; style.css's stock syntax palettes (--hl-*): GitHub-like on a light page,
;; Nord-like on a dark one.
(def ^:private syntax-palettes
  {:on-light {"comment" "#6a737d" "string" "#22863a" "symbol" "#6f42c1" "number" "#6f42c1"
              "keyword" "#d73a49" "builtin" "#005cc5" "fn" "#005cc5" "var" "#24292e"
              "operator" "#d73a49" "reader" "#6f42c1" "punct" "#586069"}
   :on-dark  {"comment" "#6a738d" "string" "#a3be8c" "symbol" "#b48ead" "number" "#b48ead"
              "keyword" "#81a1c1" "builtin" "#88c0d0" "fn" "#88c0d0" "var" "#d8dee9"
              "operator" "#81a1c1" "reader" "#b48ead" "punct" "#d8dee9"}})

(def ^:private syntax-offsets
  "Hue offset from the page per colored token: the palette of a colorful page,
   analogous hues around it. Tokens without one (comment, var, punct) read as
   text: the page hue at their stock chroma."
  {"keyword" 40 "operator" 40 "string" 80
   "builtin" -40 "fn" -40 "symbol" -80 "number" -80 "reader" -80})

(def ^:private syntax-sources
  "--hl-* token → the palette color that sets it exactly (ui.theme-model
   syntax keys), first given wins."
  {"keyword" [:keyword] "operator" [:keyword] "reader" [:keyword]
   "string"  [:string]  "fn" [:fn] "builtin" [:fn]
   "number"  [:number]  "symbol" [:symbol :number]
   "comment" [:comment]})

(defn- derived-syntax
  "The --hl-* tokens on a page of lightness `l`, chroma `c` and hue `h`. Each
   stock token's lightness is rescaled into the range between the page and
   white (dark page) or black (light page) the way the stock palette sits
   against the default page. Its hue blends from the stock one to the page's
   `syntax-offsets` palette as the page gets colorful (chroma 0.02 → 0.05), the
   colored tokens' chroma rising to at least 0.1 with it: a gray page keeps
   the stock palette, a vivid one gets colors that belong to it."
  [l c h dark?]
  (let [ref   (first (model/color->oklch (get defaults (if dark? :bg-dark :bg-light))))
        w     (model/clamp01 (/ (- c 0.02) 0.03))
        place (if dark?
                (fn [tl] (+ l (* (- tl ref) (/ (- 1 l) (- 1 ref)))))
                (fn [tl] (- l (* (- ref tl) (/ l ref)))))]
    (into {}
          (map (fn [[token hex]]
                 (let [[tl tc th] (model/hex->oklch hex)
                       offset (syntax-offsets token)
                       target (mod (+ h (or offset 0)) 360)
                       tc'    (if offset (+ tc (* w (- (max tc 0.1) tc))) tc)]
                   [(str "hl-" token)
                    (model/oklch (model/clamp01 (place tl)) tc' (model/hue-toward th target w))])))
          (syntax-palettes (if dark? :on-dark :on-light)))))

(defn- syntax-colors
  "`derived-syntax`, then the palette's exact colors of `mode` over it; a
   palette with text colors also sets plain code (var) and punctuation."
  [p mode tokens l c h dark?]
  (let [palette (get p mode)]
    (cond-> (into (derived-syntax l c h dark?)
                  (keep (fn [[token ks]]
                          (when-let [color (some #(get palette %) ks)]
                            [(str "hl-" token) color])))
                  syntax-sources)
      (:fg palette) (assoc "hl-var" (tokens "fg-0") "hl-punct" (tokens "fg-1")))))

(defn surfaces
  "{:light {token color} :dark {…}} — ui.theme-model's surface, border,
   text and palette tokens of each mode, plus `status-tints`,
   `syntax-colors` and the palette's user-bubble fill (style.css
   --bubble-bg)."
  [params]
  (let [p      (merge defaults params)
        tokens (model/mode-tokens p)]
    (into {}
          (map (fn [mode]
                 (let [[l c h] (model/color->oklch (model/page-color p mode))
                       dark?   (< l 0.5)
                       t       (tokens mode)]
                   [mode (cond-> (merge t
                                        (status-tints p mode l dark?)
                                        (syntax-colors p mode t l c h dark?))
                           (model/palette-color p mode :bubble)
                           (assoc "bubble" (model/palette-color p mode :bubble)))])))
          model/modes)))

(defn- background-vars
  "Per-mode properties (inline properties cannot differ by mode); style.css
   and theme.css pick the current mode's: --theme-bg-dark,
   --theme-bg-1-dark, --theme-border-0-light, --theme-hl-fn-dark, …."
  [params]
  (let [{[light-bg light-sb] :light [dark-bg dark-sb] :dark} (backgrounds params)]
    (merge {"--theme-bg-light"      light-bg
            "--theme-sidebar-light" light-sb
            "--theme-bg-dark"       dark-bg
            "--theme-sidebar-dark"  dark-sb}
           (model/mode-vars (surfaces params)))))

(defn- em-value [n] (str (str/replace (.toFixed n 3) #"\.?0+$" "") "em"))

(defn css-vars
  "The CSS custom properties `params` set on <html>, name → value; nil for
   nil params (the default theme: every managed property is removed)."
  [params]
  (when params
    (let [p (merge defaults params)]
      (merge (model/root-vars p)
             (background-vars p)
             {"--paragraph-space" (em-value (:paragraph-space p))
              "--code-scale"      (str/replace (.toFixed (:code-scale p) 3) #"\.?0+$" "")}))))

(def var-names
  "Every property a theme may set — the ones to remove when none is active."
  (let [full (zipmap model/palette-keys (repeat "#808080"))]
    (vec (sort (keys (css-vars (assoc defaults :light full :dark full
                                      :font-body "x" :font-mono "x")))))))

(defn gray-swatches [params] (model/gray-swatches (merge defaults params)))
(defn accent-swatches [params] (model/accent-swatches (merge defaults params)))

(defn accent-color
  "The accent of `mode` (default light): the palette's, else the scale's."
  ([params] (accent-color params :light))
  ([params mode] (model/accent-color (merge defaults params) mode)))

(defn gray-color [params] (model/gray-color (merge defaults params)))

(defn strip-colors
  "A theme at a glance, for its row in the picker: light page, its text,
   dark page, accent."
  [params]
  (model/strip-colors (merge defaults params)))

(def ^:private derived-palette-tokens
  "Palette key → the `surfaces` token it is derived as when unset."
  {:fg "fg-0" :fg-muted "fg-1" :fg-subtle "fg-2" :surface "bg-1" :border "border-1"
   :keyword "hl-keyword" :string "hl-string" :fn "hl-fn" :number "hl-number"
   :symbol "hl-symbol" :comment "hl-comment"})

(defn palette-colors
  "{mode {palette-key color}}: every palette color of `params` per mode, the
   set ones as given, the others as derived — what the editor shows."
  [params]
  (let [p    (merge defaults params)
        surf (surfaces p)
        bgs  (backgrounds p)
        root (model/root-vars p)]
    (into {}
          (for [mode model/modes]
            [mode (into {}
                        (for [k model/palette-keys]
                          [k (or (model/palette-color p mode k)
                                 (case k
                                   :sidebar (second (bgs mode))
                                   :accent  (accent-color p mode)
                                   :bubble  (root "--accent-600")
                                   (:success :warning :danger)
                                   (root (str "--" (name k) (if (= mode :dark) "-400" "-500")))
                                   (get-in surf [mode (derived-palette-tokens k)])))]))]))))

(defn hue-gradient []
  (str "linear-gradient(to right, "
       (str/join "," (map #(str "oklch(0.65 0.15 " % ")") [0 60 120 180 240 300 360]))
       ")"))

(defn chroma-gradient [hue]
  (str "linear-gradient(to right, oklch(0.5 0 " hue "), oklch(0.5 0.15 " hue "))"))

(defn preset-active?
  "Do `params` carry exactly the preset's colors (and no palette)?"
  [preset params]
  (let [p (merge defaults params)]
    (and (not-any? p model/modes)
         (every? (fn [[k v]] (= v (get p k))) (dissoc preset :name)))))

;; ── Fonts ────────────────────────────────────────────────────────────────────

(def font-suggestions
  "Families offered in the font fields (installed ones only render; the
   browser's own list joins them, xi.web.views)."
  {:font-body ["Inter" "IBM Plex Sans" "Source Sans 3" "Noto Sans" "Roboto" "Ubuntu"
               "Helvetica Neue" "Charter" "Iowan Old Style" "Georgia" "IBM Plex Serif"
               "IBM Plex Mono" "JetBrains Mono" "system-ui"]
   :font-mono ["JetBrains Mono" "IBM Plex Mono" "Iosevka" "Fira Code" "Cascadia Code"
               "Source Code Pro" "Berkeley Mono" "SF Mono" "Menlo" "Hack" "Ubuntu Mono"
               "DejaVu Sans Mono" "ui-monospace"]})

(defn font-label
  "The first family of a theme's font `k`, or \"System\" when unset."
  [params k]
  (or (first (model/font-families (get params k))) "System"))

;; ── Draft edits ──────────────────────────────────────────────────────────────

(defn set-param
  "`params` with `k` set to `v`; a nil or blank `v` unsets it."
  [params k v]
  (if (or (nil? v) (and (string? v) (str/blank? v)))
    (dissoc params k)
    (assoc params k v)))

(defn set-palette
  "`params` with palette color `k` of `mode` set to `color`, nil unsets it
   (back to derived); an empty palette is dropped."
  [params mode k color]
  (let [palette (not-empty (set-param (get params mode) k color))]
    (if palette (assoc params mode palette) (dissoc params mode))))

(defn apply-preset
  "A hue preset over `params`: its colors, no palettes."
  [params preset]
  (-> params (merge (dissoc preset :name)) (dissoc :light :dark)))

;; ── The user's value ─────────────────────────────────────────────────────────

(defn normalize-params
  "Keep the known parameters of `m` with valid values (ui.theme-model's and
   Xi's own)."
  [m]
  (model/normalize-params m {:ranges own-ranges :color-keys own-color-keys}))

(defn valid-name? [s]
  (and (string? s)
       (<= 1 (count (str/trim s)) max-name-length)))

(defn builtin? [id] (contains? builtins/by-id id))

(def builtin-themes
  "[[id name params] …] in catalog order, for the picker."
  (mapv (fn [{:keys [id name]}] [id name (builtins/params id)]) builtins/catalog))

(def ^:private builtin-names (set (map :name builtins/catalog)))

(defn normalize
  "The user's themes value with garbage dropped: unknown or out-of-range
   parameters, unnamed themes, an :active that names no theme. Empty when
   nothing is left, so an unset user compares equal to the default."
  [themes]
  (let [custom (->> (when (map? themes) (:themes themes))
                    (filter (fn [[n p]] (and (valid-name? n) (map? p))))
                    (sort-by key)
                    (take max-themes)
                    (map (fn [[n p]] [n (normalize-params p)]))
                    (into {}))
        active (let [a (:active themes)]
                 (when (or (contains? custom a) (builtin? a)) a))]
    (cond-> {}
      (seq custom) (assoc :themes custom)
      active       (assoc :active active))))

(defn theme-params
  "Parameters of theme `id`: a built-in's keyword or a custom theme's name."
  [themes id]
  (if (keyword? id)
    (builtins/params id)
    (get-in themes [:themes id])))

(defn theme-label [id]
  (if (keyword? id) (builtins/theme-name id) id))

(defn active-params
  "Parameters of the active theme, nil when the default is in use."
  [themes]
  (some->> (:active themes) (theme-params themes)))

(defn active-vars [themes]
  (css-vars (active-params themes)))

(defn custom-themes
  "[[name params] …] by name, for the picker."
  [themes]
  (sort-by key (:themes themes)))

;; ── Transitions (xi.web.core handlers) ───────────────────────────────────────

(defn select
  "Make theme `id` active (a built-in's keyword, a custom name); nil for the
   default."
  [themes id]
  (normalize (assoc themes :active id)))

(defn remove-theme [themes name]
  (normalize (update themes :themes dissoc name)))

(defn- unique-name
  "`base`, else \"`base` 2\", \"`base` 3\", … — the first no custom or
   built-in theme uses."
  [themes base]
  (let [taken (into builtin-names (keys (:themes themes)))]
    (->> (map (fn [n] (if (= n 1) base (str base " " n))) (iterate inc 1))
         (remove taken)
         first)))

(defn new-name
  "The first of \"Custom\", \"Custom 2\", … not taken."
  [themes]
  (unique-name themes "Custom"))

(defn draft-for
  "The editor's draft {:original :name :params}: a copy of custom theme
   `name`; with nil a new theme from the defaults, with a built-in's keyword
   a new theme copying it. `:original` is the name the draft came from (nil
   for a new one), which Save replaces."
  [themes name]
  (cond
    (keyword? name)
    {:original nil
     :name     (unique-name themes (builtins/theme-name name))
     :params   (merge defaults (builtins/params name))}

    (get-in themes [:themes name])
    {:original name :name name :params (merge defaults (get-in themes [:themes name]))}

    :else
    {:original nil :name (new-name themes) :params defaults}))

(defn draft-savable?
  "A valid name that no other theme (nor a built-in) uses, and room for a
   new one."
  [themes {:keys [original name]}]
  (let [name   (some-> name str/trim)
        others (dissoc (:themes themes) original)]
    (boolean (and (valid-name? name)
                  (not (contains? others name))
                  (not (builtin-names name))
                  (or original (< (count others) max-themes))))))

(defn save
  "Store the draft under its (trimmed) name — replacing `:original` when it
   was renamed — and make it active."
  [themes {:keys [original name params]}]
  (let [name (str/trim name)]
    (-> themes
        (update :themes dissoc original)
        (assoc-in [:themes name] (normalize-params params))
        (assoc :active name)
        normalize)))
