(ns ui.theme-model
  "Parametric themes over the framework's tokens (theme/tokens.edn): a map of
   parameters — gray and accent hue/chroma, the two page colors, status
   colors, size/type/radius scales, font families — plus optional exact
   per-mode palettes (`palette-keys` under :light / :dark) → CSS custom
   properties. Pure and cross-target; ui.themes holds the built-in catalog.

   Two outputs, both set on <html>:
     `root-vars`   the scales and font stacks, shared by both modes
     `mode-vars`   `mode-tokens` ({:light {\"bg-0\" color …} :dark {…}}) as
                   --theme-<token>-<mode>; the generated theme.css reads
                   those before its own mapping (ui.css.gen `themable-tokens`)

   The default parameters reproduce theme.css."
  (:require [clojure.string :as str]))

;; ── Numbers (both targets) ───────────────────────────────────────────────────

(defn- pow [x y] #?(:clj (Math/pow x y) :cljs (js/Math.pow x y)))
(defn- cbrt [x] #?(:clj (Math/cbrt x) :cljs (js/Math.cbrt x)))
(defn- sqrt [x] #?(:clj (Math/sqrt x) :cljs (js/Math.sqrt x)))
(defn- cos [x] #?(:clj (Math/cos x) :cljs (js/Math.cos x)))
(defn- sin [x] #?(:clj (Math/sin x) :cljs (js/Math.sin x)))
(defn- atan2 [y x] #?(:clj (Math/atan2 y x) :cljs (js/Math.atan2 y x)))
(defn- round [x] #?(:clj (Math/round (double x)) :cljs (js/Math.round x)))
(def ^:private pi #?(:clj Math/PI :cljs js/Math.PI))

(defn- finite? [x]
  (and (number? x) #?(:clj (Double/isFinite (double x)) :cljs (js/isFinite x))))

(defn- parse-hex [s] #?(:clj (Integer/parseInt s 16) :cljs (js/parseInt s 16)))
(defn- parse-num [s] #?(:clj (Double/parseDouble s) :cljs (js/parseFloat s)))

(defn- fixed [n places]
  #?(:clj (String/format java.util.Locale/ROOT (str "%." places "f") (object-array [(double n)]))
     :cljs (.toFixed (double n) places)))

(defn- trim-zeros [s]
  (-> s (str/replace #"0+$" "") (str/replace #"\.$" "")))

(defn- rem-value [n] (str (trim-zeros (fixed n 3)) "rem"))

(defn clamp01 [x] (max 0 (min 1 x)))

;; ── Colors ───────────────────────────────────────────────────────────────────
;; Two formats, the color picker's: #rrggbb and oklch(L C H) with L in 0–1.

(defn hex-color? [s]
  (boolean (and (string? s) (re-matches #"#[0-9a-fA-F]{6}" s))))

(defn- parse-oklch [s]
  (when-let [[_ & parts] (and (string? s)
                              (re-matches #"oklch\((\d+(?:\.\d+)?) (\d+(?:\.\d+)?) (\d+(?:\.\d+)?)\)" s))]
    (let [[l c h :as lch] (mapv parse-num parts)]
      (when (and (<= l 1) (<= c 0.5) (<= h 360)) lch))))

(defn color? [s]
  (or (hex-color? s) (some? (parse-oklch s))))

(defn oklch
  "The oklch() string in the generator's format (ui.css.gen)."
  [l c h]
  (str "oklch(" (fixed l 3) " " (fixed c 4) " " (fixed h 1) ")"))

(defn hex->oklch
  "#rrggbb → [l c h] (sRGB → linear → OKLab → polar, Björn Ottosson's
   matrices). A gray gets hue 0 instead of the noise of a zero chroma."
  [hex]
  (let [byte  (fn [i] (/ (parse-hex (subs hex i (+ i 2))) 255))
        lin   (fn [c] (if (<= c 0.04045) (/ c 12.92) (pow (/ (+ c 0.055) 1.055) 2.4)))
        [r g b] (map (comp lin byte) [1 3 5])
        l (cbrt (+ (* 0.4122214708 r) (* 0.5363325363 g) (* 0.0514459929 b)))
        m (cbrt (+ (* 0.2119034982 r) (* 0.6806995451 g) (* 0.1073969566 b)))
        s (cbrt (+ (* 0.0883024619 r) (* 0.2817188376 g) (* 0.6299787005 b)))
        L (+ (* 0.2104542553 l) (* 0.7936177850 m) (* -0.0040720468 s))
        a (+ (* 1.9779984951 l) (* -2.4285922050 m) (* 0.4505937099 s))
        b (+ (* 0.0259040371 l) (* 0.7827717662 m) (* -0.8086757660 s))
        c (sqrt (+ (* a a) (* b b)))]
    (if (< c 1e-4)
      [L 0 0]
      [L c (mod (* (atan2 b a) (/ 180 pi)) 360)])))

(defn color->oklch
  "A `color?` string → [l c h]; nil for anything else."
  [s]
  (cond (hex-color? s) (hex->oklch s)
        (string? s)    (parse-oklch s)))

(defn- lab [[l c h]]
  (let [r (* h (/ pi 180))] [l (* c (cos r)) (* c (sin r))]))

(defn- lch [[l a b]]
  (let [c (sqrt (+ (* a a) (* b b)))]
    [l c (if (< c 1e-4) 0 (mod (* (atan2 b a) (/ 180 pi)) 360))]))

(defn mix
  "Color `a` moved `t` (0–1) toward color `b`, interpolated in OKLab (so a
   gray end doesn't swing the hue) → oklch()."
  [a b t]
  (let [[l1 a1 b1] (lab (color->oklch a))
        [l2 a2 b2] (lab (color->oklch b))
        at (fn [x y] (+ x (* t (- y x))))]
    (apply oklch (lch [(at l1 l2) (at a1 a2) (at b1 b2)]))))

(defn- shift
  "Color `s` with its lightness moved by `dl`."
  [s dl]
  (let [[l c h] (color->oklch s)] (oklch (clamp01 (+ l dl)) c h)))

(defn contrast-text
  "Text on a fill of color `s`: white on a dark or vivid fill, a dark ink in
   its hue on a light one (a pastel accent under white text is unreadable)."
  [s]
  (let [[l c h] (color->oklch s)]
    (if (> l 0.72) (oklch 0.2 (min c 0.03) h) "#ffffff")))

(defn hue-toward
  "Hue `h` moved `share` of the shorter way toward `target`."
  [h target share]
  (let [d (- (mod (+ (- target h) 540) 360) 180)]
    (mod (+ h (* d share)) 360)))

;; ── Scales (tokens.edn) ──────────────────────────────────────────────────────
;; [label lightness chroma]; chroma tapers at the light and dark ends.

(def gray-steps
  [[0 1.000 0.000] [50 0.975 0.003] [100 0.955 0.005] [200 0.915 0.010]
   [300 0.850 0.012] [400 0.690 0.025] [500 0.530 0.035] [600 0.425 0.035]
   [700 0.350 0.035] [800 0.245 0.025] [900 0.190 0.016] [950 0.145 0.011]])

(def accent-steps
  [[50 0.965 0.020] [100 0.925 0.040] [200 0.860 0.075] [300 0.770 0.125]
   [400 0.690 0.170] [500 0.595 0.230] [600 0.505 0.255] [700 0.450 0.245]
   [800 0.395 0.210] [900 0.350 0.175] [950 0.260 0.130]])

(def ^:private success-steps
  [[50 0.980 0.016] [100 0.960 0.038] [200 0.930 0.065] [300 0.885 0.112]
   [400 0.815 0.178] [500 0.705 0.185] [600 0.595 0.150] [700 0.510 0.119]
   [800 0.425 0.090] [900 0.370 0.071] [950 0.270 0.051]])

(def ^:private warning-steps
  [[50 0.985 0.012] [100 0.965 0.032] [200 0.935 0.058] [300 0.890 0.095]
   [400 0.845 0.129] [500 0.790 0.159] [600 0.725 0.153] [700 0.625 0.130]
   [800 0.540 0.107] [900 0.465 0.088] [950 0.355 0.065]])

(def ^:private danger-steps
  [[50 0.970 0.014] [100 0.935 0.032] [200 0.860 0.073] [300 0.765 0.127]
   [400 0.675 0.184] [500 0.610 0.226] [600 0.560 0.220] [700 0.490 0.184]
   [800 0.425 0.153] [900 0.365 0.124] [950 0.255 0.086]])

(def ^:private status-scales
  "[scale-name color-key steps] per status scale; each is anchored on its
   color at the 500 step."
  [["success" :success-color success-steps]
   ["warning" :warning-color warning-steps]
   ["danger"  :danger-color  danger-steps]])

(def ^:private size-steps 16)

(def ^:private font-steps
  [[-2 "xs"] [-1 "sm"] [0 "base"] [1 "md"] [2 "lg"] [3 "xl"] [4 "2xl"] [5 "3xl"]])

(def ^:private radius-px
  [["xs" 3] ["sm" 5] ["md" 8] ["lg" 12] ["control" 9] ["panel" 14] ["surface" 18]])

(def ^:private accent-500-chroma 0.23)
(def ^:private gray-950-chroma 0.011)

(defn- step-color [[_ l c] hue chroma-scale]
  (oklch l (min 0.4 (* c chroma-scale)) hue))

(defn- scale-vars [scale-name hue chroma-scale steps]
  (into {}
        (map (fn [[label :as step]]
               [(str "--" scale-name "-" label) (step-color step hue chroma-scale)]))
        steps))

(defn- anchored-scale-vars
  "The scale whose 500 step is `color`: every other step keeps its lightness
   distance and chroma ratio to the 500 step."
  [scale-name color steps]
  (let [[l c h] (color->oklch color)
        [_ l500 c500] (nth steps 5)]
    (into {}
          (map (fn [[label sl sc]]
                 [(str "--" scale-name "-" label)
                  (oklch (clamp01 (+ sl (- l l500))) (min 0.4 (* sc (/ c c500))) h)]))
          steps)))

;; ── Parameters ───────────────────────────────────────────────────────────────

(def defaults
  "Chroma and radius are multipliers of the token values; `:bg-light` /
   `:bg-dark` are each mode's page color. Font families have no default
   (theme.css's stacks)."
  {:gray-hue      285
   :gray-chroma   1
   :accent-hue    286
   :accent-chroma 1
   :bg-light      "#ffffff"
   :bg-dark       "#0a0a0f"
   :size-base     0.25
   :font-base     1
   :font-ratio    1.25
   :radius-scale  1
   :success-color "oklch(0.705 0.185 152)"
   :warning-color "oklch(0.79 0.159 76)"
   :danger-color  "oklch(0.61 0.226 25)"})

(def ranges
  "[min max] per numeric parameter: what `normalize-params` accepts, and
   slider bounds."
  {:gray-hue      [0 360]
   :gray-chroma   [0 2]
   :accent-hue    [0 360]
   :accent-chroma [0 2]
   :size-base     [0.1 0.5]
   :font-base     [0.75 1.25]
   :font-ratio    [1.05 1.5]
   :radius-scale  [0 2]})

(def color-keys #{:bg-light :bg-dark :success-color :warning-color :danger-color})

(def font-keys #{:font-body :font-mono})

(def modes [:light :dark])

(def palette-keys
  "Exact colors a theme may give per mode (under :light / :dark); each one
   left out stays derived from the parameters.
     :fg :fg-muted :fg-subtle   text (fg-0..2); muted/subtle default to :fg
                                mixed toward the page
     :surface :border           cards and panels (bg-1), lines (border-1)
     :sidebar                   the app's side panel (consumers that have one)
     :bubble                    a filled surface under white text, e.g. chat
                                bubbles (consumers that have one; else
                                accent-600)
     :accent                    accent / accent-strong / fg-on-accent
     :success :warning :danger  the status tokens
     :keyword :string :fn :number :symbol :comment
                                code highlighting, for consumers that color
                                code (not read by `mode-tokens`)"
  [:fg :fg-muted :fg-subtle :surface :border :sidebar :bubble :accent
   :success :warning :danger
   :keyword :string :fn :number :symbol :comment])

(def syntax-keys [:keyword :string :fn :number :symbol :comment])

(def themable-tokens
  "The semantic tokens `mode-tokens` may set, in theme.css's names."
  ["bg-0" "bg-1" "bg-2" "border-0" "border-1" "border-2" "fg-0" "fg-1" "fg-2"
   "accent" "accent-strong" "fg-on-accent" "success" "warning" "danger"])

(defn palette-color
  "Palette color `k` of `mode`, nil when the theme leaves it derived."
  [params mode k]
  (get-in params [mode k]))

;; ── Fonts ────────────────────────────────────────────────────────────────────

(def default-fonts
  {:font-body "ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif"
   :font-mono "ui-monospace, SFMono-Regular, Menlo, Consolas, 'Liberation Mono', monospace"})

(def ^:private generic-families
  #{"serif" "sans-serif" "monospace" "cursive" "fantasy" "system-ui" "math"
    "emoji" "ui-serif" "ui-sans-serif" "ui-monospace" "ui-rounded"})

(def max-font-length 200)

(defn font-families
  "A comma-separated family list (\"Inter, 'IBM Plex Sans'\") → [\"Inter\"
   \"IBM Plex Sans\"]; nil when empty or when it holds anything but family
   names."
  [s]
  (when (and (string? s)
             (<= (count s) max-font-length)
             (not (re-find #"[;{}()<>\\/:!@*=\[\]`\n\r]" s)))
    (let [fams (into []
                     (comp (map #(-> % str/trim (str/replace #"^['\"]|['\"]$" "") str/trim))
                           (remove str/blank?))
                     (str/split s #","))]
      (when (and (seq fams) (not-any? #(re-find #"['\"]" %) fams))
        fams))))

(defn font-stack
  "The CSS value of font `k` (:font-body / :font-mono) for the family list
   `s`: each family quoted, `default-fonts` after them as the fallback."
  [k s]
  (when-let [fams (font-families s)]
    (str/join ", " (concat (map #(if (generic-families %) % (str "\"" % "\"")) fams)
                           [(default-fonts k)]))))

(defn- font-vars [p]
  (into {}
        (keep (fn [k] (when-let [v (font-stack k (get p k))] [(str "--" (name k)) v])))
        font-keys))

;; ── Root properties ──────────────────────────────────────────────────────────

(defn root-vars
  "The scales and font stacks of `params`, name → value."
  [params]
  (let [p (merge defaults params)]
    (merge (scale-vars "gray" (:gray-hue p) (:gray-chroma p) gray-steps)
           (scale-vars "accent" (:accent-hue p) (:accent-chroma p) accent-steps)
           (into {} (mapcat (fn [[scale-name color-key steps]]
                              (anchored-scale-vars scale-name (get p color-key) steps)))
                 status-scales)
           (into {} (map (fn [n] [(str "--size-" n) (rem-value (* (:size-base p) n))]))
                 (range 1 (inc size-steps)))
           (into {} (map (fn [[power label]]
                           [(str "--font-" label)
                            (rem-value (* (:font-base p) (pow (:font-ratio p) power)))]))
                 font-steps)
           (into {} (map (fn [[label px]]
                           [(str "--radius-" label) (str (round (* px (:radius-scale p))) "px")]))
                 radius-px)
           (font-vars p))))

;; ── Per-mode tokens ──────────────────────────────────────────────────────────

(def ^:private surface-steps
  "OKLCH lightness distance of each surface/border token from the page color
   per mode: lighter on a dark page, darker on a light one. With the default
   pages they land on the gray steps theme.css maps those tokens to."
  {:light {"bg-0" 0.025 "bg-1" 0.045 "bg-2" 0.085
           "border-0" 0.085 "border-1" 0.15 "border-2" 0.47}
   :dark  {"bg-0" 0 "bg-1" 0.045 "bg-2" 0.1
           "border-0" 0.1 "border-1" 0.205 "border-2" 0.385}})

(def ^:private text-steps
  "[lightness chroma-share] of each text token on a dark or a light page:
   theme.css's gray step lightness (contrast kept) and a share of the page
   color's chroma."
  {:on-dark  {"fg-0" [0.975 0.15] "fg-1" [0.85 0.35] "fg-2" [0.53 0.5]}
   :on-light {"fg-0" [0.145 0.15] "fg-1" [0.425 0.35] "fg-2" [0.69 0.5]}})

(defn page-color
  "The page color of `mode` (:light / :dark)."
  [params mode]
  (get (merge defaults params) (if (= mode :dark) :bg-dark :bg-light)))

(defn dark-page?
  "Is the page of `mode` dark? A theme may give its light mode a dim page."
  [params mode]
  (< (first (color->oklch (page-color params mode))) 0.5))

(defn- derived-tokens
  "Surfaces step by `mode`'s distances, toward the middle from the page;
   text by the page's own lightness."
  [mode page]
  (let [[l c h] (color->oklch page)
        dark?   (< l 0.5)
        dir     (if dark? 1 -1)]
    (-> {}
        (into (map (fn [[token step]] [token (oklch (clamp01 (+ l (* dir step))) c h)]))
              (surface-steps mode))
        (into (map (fn [[token [tl share]]] [token (oklch tl (* c share) h)]))
              (text-steps (if dark? :on-dark :on-light))))))

(defn- palette-tokens
  "The tokens `palette` (one mode's map) sets exactly, over a page `page`."
  [page {:keys [fg fg-muted fg-subtle surface border accent success warning danger]}]
  (let [dark? (< (first (color->oklch page)) 0.5)]
    (cond-> {}
      fg        (assoc "fg-0" fg
                       "fg-1" (or fg-muted (mix fg page 0.3))
                       "fg-2" (or fg-subtle (mix fg page 0.55)))
      fg-muted  (assoc "fg-1" fg-muted)
      fg-subtle (assoc "fg-2" fg-subtle)
      surface   (assoc "bg-1" surface)
      border    (assoc "border-1" border
                       "border-0" (mix border page 0.4))
      accent    (assoc "accent" accent
                       "accent-strong" (shift accent (if dark? 0.07 -0.07))
                       "fg-on-accent" (contrast-text accent))
      success   (assoc "success" success)
      warning   (assoc "warning" warning)
      danger    (assoc "danger" danger))))

(defn mode-tokens
  "{:light {token color} :dark {…}}: every surface, border and text token in
   the page color's hue (`surface-steps` / `text-steps` away from it), then
   the palette's exact colors over them."
  [params]
  (let [p (merge defaults params)]
    (into {}
          (map (fn [mode]
                 (let [page (page-color p mode)]
                   [mode (merge (derived-tokens mode page)
                                (palette-tokens page (get p mode)))])))
          modes)))

(defn mode-vars
  "`tokens` ({mode {token color}}) as --theme-<token>-<mode> properties."
  [tokens]
  (into {}
        (for [[mode m] tokens
              [token color] m]
          [(str "--theme-" token "-" (name mode)) color])))

(defn css-vars
  "Every property `params` set on <html>; nil for nil params (no theme:
   remove them all, see `var-names`)."
  [params]
  (when params
    (merge (root-vars params) (mode-vars (mode-tokens params)))))

(def ^:private full-palette
  (zipmap palette-keys (repeat "#808080")))

(def var-names
  "Every property a theme may set — the ones to remove when none is active."
  (vec (sort (keys (css-vars (assoc defaults :light full-palette :dark full-palette
                                    :font-body "x" :font-mono "x"))))))

;; ── Swatches ─────────────────────────────────────────────────────────────────

(defn gray-swatches [params]
  (let [p (merge defaults params)]
    (mapv #(step-color % (:gray-hue p) (:gray-chroma p)) (rest gray-steps))))

(defn accent-swatches [params]
  (let [p (merge defaults params)]
    (mapv #(step-color % (:accent-hue p) (:accent-chroma p)) accent-steps)))

(defn accent-color
  "The accent of `mode` (default :light): the palette's, else the scale's
   500 (light) / 400 (dark) step."
  ([params] (accent-color params :light))
  ([params mode]
   (or (palette-color params mode :accent)
       (nth (accent-swatches params) (if (= mode :dark) 4 5)))))

(defn gray-color [params]
  (nth (gray-swatches params) 5))

(defn strip-colors
  "A theme at a glance: light page, its text, dark page, accent."
  [params]
  (let [{:keys [light]} (mode-tokens params)]
    [(page-color params :light) (light "fg-1") (page-color params :dark) (accent-color params)]))

;; ── Deriving parameters from colors ──────────────────────────────────────────

(defn- hundredths [x] (/ (round (* 100 x)) 100.0))

(defn params-from-palette
  "Fill the parameters a palette theme leaves out from its colors: the gray
   scale in the dark page's hue and chroma, the accent scale in the light
   accent's, the status scales on the light status colors. Given parameters
   win."
  [params]
  (let [[_ gc gh] (color->oklch (page-color params :dark))
        accent    (or (palette-color params :light :accent) (palette-color params :dark :accent))
        [_ ac ah] (some-> accent color->oklch)
        status    (fn [k] (or (palette-color params :light k) (palette-color params :dark k)))]
    (merge (cond-> {:gray-hue    (round gh)
                    :gray-chroma (hundredths (min 2 (/ gc gray-950-chroma)))}
             accent (assoc :accent-hue (round ah)
                           :accent-chroma (hundredths (min 2 (/ ac accent-500-chroma))))
             (status :success) (assoc :success-color (status :success))
             (status :warning) (assoc :warning-color (status :warning))
             (status :danger)  (assoc :danger-color (status :danger)))
           params)))

;; ── Validation ───────────────────────────────────────────────────────────────

(defn in-range? [ranges k v]
  (let [[lo hi] (get ranges k)]
    (boolean (and lo (finite? v) (<= lo v hi)))))

(defn normalize-palette
  "The `palette-keys` of `m` holding a `color?`; nil when none."
  [m]
  (not-empty (into {} (filter (fn [[k v]] (and (some #{k} palette-keys) (color? v))))
                   (when (map? m) m))))

(defn normalize-params
  "Keep the known parameters of `m` with valid values: numbers in `ranges`,
   `color?` strings for `color-keys`, family lists (`font-families`) for
   `font-keys`, palettes under :light / :dark. `opts` widens the known
   parameters for a consumer's own ({:ranges :color-keys}, merged in)."
  ([m] (normalize-params m nil))
  ([m opts]
   (let [ranges     (merge ranges (:ranges opts))
         color-keys (into color-keys (:color-keys opts))]
     (into {}
           (keep (fn [[k v]]
                   (cond
                     (some #{k} modes) (some->> (normalize-palette v) (vector k))
                     (font-keys k)     (when (font-families v) [k (str/trim v)])
                     (color-keys k)    (when (color? v) [k v])
                     (in-range? ranges k v) [k v])))
           (when (map? m) m)))))
