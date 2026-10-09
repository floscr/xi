(ns xi.web.theme
  "Custom color themes: a named set of parameters (gray + accent hue and
   chroma, spacing base, type scale, radius) that overrides the generated
   theme's scale variables on <html>, under both the light and the dark
   semantic tokens. The same model as the clj-ui-framework preview's theme
   adapter (dev/theme-adapter.js): the OKLCH steps are the framework's
   tokens.edn, so the default parameters reproduce theme.css exactly.

   The user's value (`normalize`): {:active name :themes {name params}} — both
   keys absent when nothing is set. Per user (xi.user-state :themes), cached
   per browser (xi.web.cache). Handlers in xi.web.core, dialog in
   xi.web.views; everything here is pure."
  (:require [clojure.string :as str]))

(def defaults
  "`:bg-light` / `:bg-dark` are each mode's page background, a `color?`
   string (white, gray-950); `:sidebar-shift` the OKLCH lightness the sidebar
   sits away from it (negative = darker), the same in both modes."
  {:gray-hue      285
   :gray-chroma   1
   :accent-hue    286
   :accent-chroma 1
   :bg-light      "#ffffff"
   :bg-dark       "#0a0a0f"
   :sidebar-shift -0.025
   :size-base     0.25
   :font-base     1
   :font-ratio    1.25
   :radius-scale  1})

(def ranges
  "[min max] per numeric parameter: what `normalize-params` accepts and the
   slider bounds. Chroma and radius are multipliers of the token values."
  {:gray-hue      [0 360]
   :gray-chroma   [0 2]
   :accent-hue    [0 360]
   :accent-chroma [0 2]
   :sidebar-shift [-0.2 0.2]
   :size-base     [0.1 0.5]
   :font-base     [0.75 1.25]
   :font-ratio    [1.05 1.5]
   :radius-scale  [0 2]})

(def color-keys
  "The parameters that hold a `color?` string (a color picker value)."
  #{:bg-light :bg-dark})

(defn hex-color? [s]
  (boolean (and (string? s) (re-matches #"#[0-9a-fA-F]{6}" s))))

(defn- parse-oklch
  "`oklch(L C H)` as the color picker writes it (L 0–1) → [l c h]."
  [s]
  (when-let [[_ & parts] (and (string? s)
                              (re-matches #"oklch\((\d+(?:\.\d+)?) (\d+(?:\.\d+)?) (\d+(?:\.\d+)?)\)" s))]
    (let [[l c h :as lch] (mapv js/parseFloat parts)]
      (when (and (<= l 1) (<= c 0.5) (<= h 360)) lch))))

(defn color?
  "A background color: #rrggbb or oklch(L C H), the picker's two formats."
  [s]
  (or (hex-color? s) (some? (parse-oklch s))))

(def presets
  "Color starting points for the editor; each sets only the color keys. The
   dark background is the preset's own gray-950."
  [{:name "Purple"  :gray-hue 285 :gray-chroma 1   :accent-hue 286 :accent-chroma 1    :bg-light "#ffffff" :bg-dark "#0a0a0f"}
   {:name "Blue"    :gray-hue 255 :gray-chroma 0.5 :accent-hue 255 :accent-chroma 0.87 :bg-light "#ffffff" :bg-dark "#090a0c"}
   {:name "Neutral" :gray-hue 0   :gray-chroma 0   :accent-hue 255 :accent-chroma 0.87 :bg-light "#ffffff" :bg-dark "#0a0a0a"}
   {:name "Warm"    :gray-hue 60  :gray-chroma 0.7 :accent-hue 50  :accent-chroma 0.85 :bg-light "#ffffff" :bg-dark "#0d0907"}
   {:name "Rose"    :gray-hue 0   :gray-chroma 0.5 :accent-hue 350 :accent-chroma 0.85 :bg-light "#ffffff" :bg-dark "#0c090a"}
   {:name "Emerald" :gray-hue 165 :gray-chroma 0.5 :accent-hue 165 :accent-chroma 0.75 :bg-light "#ffffff" :bg-dark "#080b09"}])

(def max-themes 20)
(def max-name-length 40)

;; ── Scales (tokens.edn) ──────────────────────────────────────────────────────
;; [label lightness chroma]; chroma tapers at the light and dark ends.

(def ^:private gray-steps
  [[50 0.975 0.003] [100 0.955 0.005] [200 0.915 0.010] [300 0.850 0.012]
   [400 0.690 0.025] [500 0.530 0.035] [600 0.425 0.035] [700 0.350 0.035]
   [800 0.245 0.025] [900 0.190 0.016] [950 0.145 0.011]])

(def ^:private accent-steps
  [[50 0.965 0.020] [100 0.925 0.040] [200 0.860 0.075] [300 0.770 0.125]
   [400 0.690 0.170] [500 0.595 0.230] [600 0.505 0.255] [700 0.450 0.245]
   [800 0.395 0.210] [900 0.350 0.175] [950 0.260 0.130]])

(def ^:private size-steps 16)

(def ^:private font-steps
  [[-2 "xs"] [-1 "sm"] [0 "base"] [1 "md"] [2 "lg"] [3 "xl"] [4 "2xl"] [5 "3xl"]])

(def ^:private radius-px
  [["xs" 4] ["sm" 6] ["md" 10] ["lg" 16]])

(defn- fixed [n places] (.toFixed (double n) places))

(defn- trim-zeros [s]
  (-> s (str/replace #"0+$" "") (str/replace #"\.$" "")))

(defn- rem-value [n] (str (trim-zeros (fixed n 3)) "rem"))

(defn oklch
  "The oklch() string in the generator's format (ui.css.gen)."
  [l c h]
  (str "oklch(" (fixed l 3) " " (fixed c 4) " " (fixed h 1) ")"))

(defn- step-color [[_ l c] hue chroma-scale]
  (oklch l (min 0.4 (* c chroma-scale)) hue))

(defn- scale-vars [scale-name hue chroma-scale steps]
  (into {}
        (map (fn [[label :as step]]
               [(str "--" scale-name "-" label) (step-color step hue chroma-scale)]))
        steps))

(defn- clamp01 [x] (max 0 (min 1 x)))

(defn hex->oklch
  "#rrggbb → [l c h] (sRGB → linear → OKLab → polar, the Björn Ottosson
   matrices). A gray gets hue 0 instead of the noise of a zero chroma."
  [hex]
  (let [byte  (fn [i] (/ (js/parseInt (subs hex i (+ i 2)) 16) 255))
        lin   (fn [c] (if (<= c 0.04045) (/ c 12.92) (js/Math.pow (/ (+ c 0.055) 1.055) 2.4)))
        [r g b] (map (comp lin byte) [1 3 5])
        l (js/Math.cbrt (+ (* 0.4122214708 r) (* 0.5363325363 g) (* 0.0514459929 b)))
        m (js/Math.cbrt (+ (* 0.2119034982 r) (* 0.6806995451 g) (* 0.1073969566 b)))
        s (js/Math.cbrt (+ (* 0.0883024619 r) (* 0.2817188376 g) (* 0.6299787005 b)))
        L (+ (* 0.2104542553 l) (* 0.7936177850 m) (* -0.0040720468 s))
        a (+ (* 1.9779984951 l) (* -2.4285922050 m) (* 0.4505937099 s))
        b (+ (* 0.0259040371 l) (* 0.7827717662 m) (* -0.8086757660 s))
        c (js/Math.sqrt (+ (* a a) (* b b)))]
    (if (< c 1e-4)
      [L 0 0]
      [L c (mod (* (js/Math.atan2 b a) (/ 180 js/Math.PI)) 360)])))

(defn color->oklch [s]
  (if (hex-color? s) (hex->oklch s) (parse-oklch s)))

(defn backgrounds
  "{:light [page sidebar] :dark [page sidebar]} — the two surfaces per mode
   as oklch(): the page is the chosen color, the sidebar the same color
   `:sidebar-shift` away in lightness."
  [params]
  (let [{:keys [bg-light bg-dark sidebar-shift]} (merge defaults params)
        pair (fn [color]
               (let [[l c h] (color->oklch color)]
                 [(oklch l c h) (oklch (clamp01 (+ l sidebar-shift)) c h)]))]
    {:light (pair bg-light)
     :dark  (pair bg-dark)}))

(defn- background-vars
  "Per-mode properties; style.css picks the pair of the current mode for
   --chat-bg / --sidebar-bg (inline properties cannot differ by mode)."
  [params]
  (let [{[light-bg light-sb] :light [dark-bg dark-sb] :dark} (backgrounds params)]
    {"--theme-bg-light"      light-bg
     "--theme-sidebar-light" light-sb
     "--theme-bg-dark"       dark-bg
     "--theme-sidebar-dark"  dark-sb}))

(defn css-vars
  "The CSS custom properties `params` set on <html>, name → value; nil for
   nil params (the default theme: every managed property is removed)."
  [params]
  (when params
    (let [p (merge defaults params)]
      (merge (scale-vars "gray" (:gray-hue p) (:gray-chroma p) gray-steps)
             (scale-vars "accent" (:accent-hue p) (:accent-chroma p) accent-steps)
             (background-vars p)
             (into {} (map (fn [n] [(str "--size-" n) (rem-value (* (:size-base p) n))]))
                   (range 1 (inc size-steps)))
             (into {} (map (fn [[power label]]
                             [(str "--font-" label)
                              (rem-value (* (:font-base p) (js/Math.pow (:font-ratio p) power)))]))
                   font-steps)
             (into {} (map (fn [[label px]]
                             [(str "--radius-" label)
                              (str (js/Math.round (* px (:radius-scale p))) "px")]))
                   radius-px)))))

(def var-names
  "Every property a theme manages — the ones to remove when none is active."
  (vec (sort (keys (css-vars defaults)))))

(defn gray-swatches [params]
  (let [p (merge defaults params)]
    (mapv #(step-color % (:gray-hue p) (:gray-chroma p)) gray-steps)))

(defn accent-swatches [params]
  (let [p (merge defaults params)]
    (mapv #(step-color % (:accent-hue p) (:accent-chroma p)) accent-steps)))

(defn accent-color
  "The 500 accent stop of `params`, for a chip dot."
  [params]
  (nth (accent-swatches params) 5))

(defn hue-gradient []
  (str "linear-gradient(to right, "
       (str/join "," (map #(str "oklch(0.65 0.15 " % ")") [0 60 120 180 240 300 360]))
       ")"))

(defn chroma-gradient [hue]
  (str "linear-gradient(to right, oklch(0.5 0 " hue "), oklch(0.5 0.15 " hue "))"))

(defn preset-active?
  "Do `params` carry exactly the preset's colors?"
  [preset params]
  (let [p (merge defaults params)]
    (every? (fn [[k v]] (= v (get p k))) (dissoc preset :name))))

;; ── The user's value ─────────────────────────────────────────────────────────

(defn- in-range? [k v]
  (let [[lo hi] (get ranges k)]
    (boolean (and lo (number? v) (js/isFinite v) (<= lo v hi)))))

(defn normalize-params
  "Keep the known parameters of `m` whose values are in range (numbers) or
   a `color?` (`color-keys`)."
  [m]
  (into {} (filter (fn [[k v]] (if (color-keys k) (color? v) (in-range? k v))))
        (when (map? m) m)))

(defn valid-name? [s]
  (and (string? s)
       (<= 1 (count (str/trim s)) max-name-length)))

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
        active (let [a (:active themes)] (when (contains? custom a) a))]
    (cond-> {}
      (seq custom) (assoc :themes custom)
      active       (assoc :active active))))

(defn active-params
  "Parameters of the active theme, nil when the default is in use."
  [themes]
  (when-let [a (:active themes)]
    (get-in themes [:themes a])))

(defn active-vars [themes]
  (css-vars (active-params themes)))

(defn custom-themes
  "[[name params] …] by name, for the picker."
  [themes]
  (sort-by key (:themes themes)))

;; ── Transitions (xi.web.core handlers) ───────────────────────────────────────

(defn select
  "Make `name` the active theme; nil for the default."
  [themes name]
  (normalize (assoc themes :active name)))

(defn remove-theme [themes name]
  (normalize (update themes :themes dissoc name)))

(defn new-name
  "The first of \"Custom\", \"Custom 2\", … not taken."
  [themes]
  (let [taken (set (keys (:themes themes)))]
    (->> (map (fn [n] (if (= n 1) "Custom" (str "Custom " n))) (iterate inc 1))
         (remove taken)
         first)))

(defn draft-for
  "The editor's draft {:original :name :params}: a copy of theme `name`, or a
   new theme from the defaults when `name` is nil. `:original` is the name
   the draft came from (nil for a new one), which Save replaces."
  [themes name]
  (if-let [params (get-in themes [:themes name])]
    {:original name :name name :params (merge defaults params)}
    {:original nil :name (new-name themes) :params defaults}))

(defn draft-savable?
  "A valid name that no other theme already uses, and room for a new one."
  [themes {:keys [original name]}]
  (let [name   (some-> name str/trim)
        others (dissoc (:themes themes) original)]
    (boolean (and (valid-name? name)
                  (not (contains? others name))
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
