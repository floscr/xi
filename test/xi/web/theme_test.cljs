(ns xi.web.theme-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ui.theme-model :as model]
            [xi.web.theme :as theme]))

(deftest default-params-reproduce-the-generated-theme
  (let [vars (theme/css-vars theme/defaults)]
    (testing "same format and values as ui.css.gen for tokens.edn"
      (is (= "oklch(0.530 0.0350 285.0)" (get vars "--gray-500")))
      (is (= "oklch(0.595 0.2300 286.0)" (get vars "--accent-500")))
      (is (= "0.75rem" (get vars "--font-xs")))
      (is (= "0.875rem" (get vars "--font-base")))
      (is (= "4rem" (get vars "--size-16")))
      (is (= "0.25rem" (get vars "--size-1")))
      (is (= "8px" (get vars "--radius-md")) "tokens.edn's radii")
      (is (= "0.75em" (get vars "--paragraph-space")) "style.css's :root fallback")
      (is (= "1" (get vars "--code-scale")) "style.css's :root fallback"))
    (testing "backgrounds: white / gray-950 pages, the sidebar 2.5% darker"
      (is (= "oklch(1.000 0.0000 0.0)" (get vars "--theme-bg-light")))
      (is (= "oklch(0.975 0.0000 0.0)" (get vars "--theme-sidebar-light")))
      (is (= "oklch(0.147 0.0107 285.0)" (get vars "--theme-bg-dark")))
      (is (= "oklch(0.122 0.0107 285.0)" (get vars "--theme-sidebar-dark"))))
    (testing "every property is a managed one; palettes and fonts add more"
      (is (every? (set theme/var-names) (keys vars)))
      (is (nil? (get vars "--font-body")))
      (is (nil? (get vars "--theme-accent-dark")))
      (is (some #{"--theme-accent-dark"} theme/var-names))
      (is (some #{"--font-mono"} theme/var-names)))))

(deftest hex-to-oklch
  (let [[l c h] (theme/hex->oklch "#ff0000")]
    (is (< 0.627 l 0.629))
    (is (< 0.257 c 0.258))
    (is (< 29.2 h 29.3)))
  (is (= [1 0 0] (mapv #(js/Math.round %) (theme/hex->oklch "#ffffff"))) "white: no hue noise")
  (is (= [0 0 0] (theme/hex->oklch "#000000"))))

(deftest surfaces-follow-the-page-color
  (let [{:keys [light dark]} (theme/surfaces {:bg-light "#ffffff" :bg-dark "oklch(0.3 0.1 25)"})]
    (is (= "oklch(0.345 0.1000 25.0)" (get dark "bg-1")) "a dark page: lighter, in its hue and chroma")
    (is (= "oklch(0.685 0.1000 25.0)" (get dark "border-2")))
    (is (= "oklch(0.955 0.0000 0.0)" (get light "bg-1")) "a light page: darker")
    (is (= "oklch(0.850 0.0350 25.0)" (get dark "fg-1"))
        "text: a fixed lightness for contrast, a share of the page's chroma")
    (is (= "oklch(0.145 0.0000 0.0)" (get light "fg-0")) "dark text on a light page"))
  (is (= "oklch(0.900 0.0000 0.0)"
         (get-in (theme/surfaces {:bg-dark "#ffffff"}) [:dark "bg-2"]))
      "a light color in dark mode still steps toward the middle")
  (is (= "oklch(0.345 0.1000 25.0)"
         (get (theme/css-vars {:bg-dark "oklch(0.3 0.1 25)"}) "--theme-bg-1-dark"))))

(deftest backgrounds-follow-the-color-and-the-shift
  (let [{:keys [light dark]} (theme/backgrounds {:bg-light "#fff4e6" :bg-dark "#000000"
                                                 :sidebar-shift 0.1})]
    (is (= ["oklch(0.972 0.0220 74.1)" "oklch(1.000 0.0220 74.1)"] light)
        "the sidebar keeps the page's chroma and hue; a positive shift lightens it, clamped at white")
    (is (= ["oklch(0.000 0.0000 0.0)" "oklch(0.100 0.0000 0.0)"] dark)))
  (is (= "oklch(0.000 0.0107 285.0)"
         (second (:dark (theme/backgrounds {:sidebar-shift -0.2}))))
      "clamped at black")
  (is (= ["oklch(0.623 0.1880 259.8)" "oklch(0.598 0.1880 259.8)"]
         (:light (theme/backgrounds {:bg-light "oklch(0.623 0.188 259.8)"})))
      "an oklch() color is taken as is"))

(deftest params-change-the-scales
  (let [vars (theme/css-vars {:gray-hue 60 :accent-chroma 2 :size-base 0.3
                              :font-ratio 1.5 :radius-scale 0.5 :paragraph-space 1.2
                              :code-scale 1.25})]
    (is (= "oklch(0.530 0.0350 60.0)" (get vars "--gray-500")) "hue")
    (is (= "oklch(0.595 0.4000 286.0)" (get vars "--accent-500")) "chroma clamps at 0.4")
    (is (= "0.3rem" (get vars "--size-1")))
    (is (= "4.8rem" (get vars "--size-16")))
    (is (= "1rem" (get vars "--font-md")) "md is the ramp's anchor: the base")
    (is (= "1.5rem" (get vars "--font-lg")) "one geometric step up, at the ratio")
    (is (= "3px" (get vars "--radius-sm")))
    (is (= "2px" (get vars "--radius-xs")))
    (is (= "1.2em" (get vars "--paragraph-space")))
    (is (= "1.25" (get vars "--code-scale"))))
  (is (nil? (theme/css-vars nil)) "the default theme sets nothing"))

(defn- lch [s] (mapv js/parseFloat (rest (re-find #"oklch\((\S+) (\S+) (\S+)\)" s))))

(deftest syntax-colors-follow-the-page
  (testing "the default page keeps the stock palette"
    (let [{:keys [dark light]} (theme/surfaces {})
          [sl] (theme/hex->oklch "#a3be8c")]
      (is (< (js/Math.abs (- (first (lch (get dark "hl-string"))) sl)) 0.002))
      (is (< (js/Math.abs (- (first (lch (get light "hl-var")))
                             (first (theme/hex->oklch "#24292e"))))
             0.002))))
  (let [{:keys [dark]} (theme/surfaces {:bg-dark "oklch(0.3 0.1 25)"})
        [string-l string-c string-h] (lch (get dark "hl-string"))]
    (is (every? #(> (first (lch (val %))) 0.55) (filter #(str/starts-with? (key %) "hl-") dark))
        "every token stays well above a lighter page")
    (is (< string-l 1))
    (testing "a vivid page gets hues around its own, no stock blue left"
      (is (= [105 0.1] [string-h string-c]) "strings: page hue + 80")
      (is (= 65 (nth (lch (get dark "hl-keyword")) 2)) "keywords: + 40")
      (is (= 345 (nth (lch (get dark "hl-fn")) 2)) "functions: − 40")
      (is (= 25 (nth (lch (get dark "hl-var")) 2)) "plain code: the page hue"))))

(deftest status-tints-keep-their-hue-on-a-colorful-page
  (let [{:keys [dark light]} (theme/surfaces {:bg-dark "oklch(0.3 0.1 25)"})]
    (is (= "oklch(0.360 0.0700 152.0)" (get dark "add-bg"))
        "a block's lightness, in the success hue rather than a wash over the page")
    (is (= "oklch(0.360 0.0700 25.0)" (get dark "del-bg")))
    (is (= "oklch(0.850 0.1200 25.0)" (get dark "danger-fg")) "light error text on a dark page")
    (is (= "oklch(0.450 0.1200 25.0)" (get light "danger-fg")))))

(deftest diff-bands-follow-their-colors-and-strength
  (testing "unset colors follow success / danger"
    (is (= "oklch(0.705 0.185 152)" (theme/diff-color {} :add)))
    (is (= "#22aa55" (theme/diff-color {:danger-color "#22aa55"} :del))))
  (let [{:keys [dark light]} (theme/surfaces {:bg-dark "oklch(0.3 0.1 25)"
                                              :diff-add-color "oklch(0.6 0.2 250)"
                                              :diff-strength 2})]
    (is (= "oklch(0.420 0.1400 250.0)" (get dark "add-bg"))
        "the picked hue; twice the lightness step and chroma cap")
    (is (= "oklch(0.420 0.1400 25.0)" (get dark "del-bg")) "still the danger hue")
    (is (= "oklch(0.360 0.0700 25.0)" (get dark "danger-bg"))
        "the menus' danger hover keeps the danger band at the stock strength")
    (is (= "oklch(0.880 0.1400 250.0)" (get light "add-bg")) "darker on a light page"))
  (is (= {:diff-add-color "#3366ff" :diff-strength 0.5}
         (theme/normalize-params {:diff-add-color "#3366ff" :diff-del-color "red"
                                  :diff-strength 0.5}))))

(deftest status-scales-follow-their-colors
  (testing "the defaults reproduce theme.css"
    (let [vars (theme/css-vars {})]
      (is (= "oklch(0.705 0.1850 152.0)" (get vars "--success-500")))
      (is (= "oklch(0.845 0.1290 76.0)" (get vars "--warning-400")))
      (is (= "oklch(0.610 0.2260 25.0)" (get vars "--danger-500")))))
  (testing "the picked color is the 500 step; the others keep their distance"
    (let [vars (theme/css-vars {:success-color "oklch(0.6 0.0925 200)"})]
      (is (= "oklch(0.600 0.0925 200.0)" (get vars "--success-500")))
      (is (= "oklch(0.710 0.0890 200.0)" (get vars "--success-400"))
          "0.11 lighter, the chroma at half the token's like the 500 step")
      (is (= "oklch(0.610 0.2260 25.0)" (get vars "--danger-500")) "other scales untouched")))
  (is (= {:danger-color "#22aa55"}
         (theme/normalize-params {:danger-color "#22aa55" :warning-color "green"}))))

(deftest swatches-and-presets
  (is (= 11 (count (theme/gray-swatches {}))))
  (is (= "oklch(0.595 0.2300 286.0)" (theme/accent-color {})))
  (is (theme/preset-active? (first theme/presets) {}) "the defaults are the Purple preset")
  (is (not (theme/preset-active? (second theme/presets) {})))
  (is (theme/preset-active? (second theme/presets)
                            (merge theme/defaults (dissoc (second theme/presets) :name)))))

(deftest normalize-drops-garbage
  (testing "parameters out of range or unknown"
    (is (= {:gray-hue 10}
           (theme/normalize-params {:gray-hue 10 :accent-hue 400 :font-ratio 0.5
                                    :bogus 1 :size-base "x"}))))
  (testing "backgrounds must be #rrggbb or oklch(L C H)"
    (is (= {:bg-light "oklch(0.6 0 0)"}
           (theme/normalize-params {:bg-light "oklch(0.6 0 0)" :bg-dark "oklch(1.2 0.1 30)"}))
        "lightness above 1 is out of range")
    (is (= {} (theme/normalize-params {:bg-dark "oklch(0.6 0.1 30 / 0.5)"})) "no alpha")
    (is (= {:bg-dark "#0A0a0f"}
           (theme/normalize-params {:bg-dark "#0A0a0f" :bg-light "white" :sidebar-shift "#fff"})))
    (is (= {} (theme/normalize-params {:bg-light 1 :bg-dark "#fff" :sidebar-shift "#ffffff"}))))
  (testing "nothing set compares equal to the empty default"
    (is (= {} (theme/normalize nil)))
    (is (= {} (theme/normalize {:active "x"})))
    (is (= {} (theme/normalize {:active nil :themes {}})))
    (is (= {} (theme/normalize "junk"))))
  (testing "an active that names no theme is dropped"
    (is (= {:themes {"a" {}}} (theme/normalize {:active "b" :themes {"a" {}}}))))
  (testing "unnamed themes are dropped, the count is capped"
    (is (= {} (theme/normalize {:themes {"" {} 1 {} "   " {}}})))
    (is (= theme/max-themes
           (count (:themes (theme/normalize
                            {:themes (into {} (map (fn [i] [(str "t" i) {}])
                                                   (range 30)))})))))))

(deftest select-save-remove
  (let [themes (theme/save {} {:original nil :name " Ocean " :params {:accent-hue 200 :bogus 1}})]
    (is (= {:active "Ocean" :themes {"Ocean" {:accent-hue 200}}} themes)
        "the name is trimmed, the params cleaned, the saved theme becomes active")
    (is (= {:accent-hue 200} (theme/active-params themes)))
    (is (= "oklch(0.595 0.2300 200.0)" (get (theme/active-vars themes) "--accent-500")))
    (testing "select the default, then back"
      (let [off (theme/select themes nil)]
        (is (nil? (:active off)))
        (is (nil? (theme/active-vars off)))
        (is (= "Ocean" (:active (theme/select off "Ocean"))))
        (is (nil? (:active (theme/select off "nope"))))))
    (testing "rename replaces the original"
      (is (= {:active "Sea" :themes {"Sea" {:accent-hue 200}}}
             (theme/save themes {:original "Ocean" :name "Sea" :params {:accent-hue 200}}))))
    (testing "remove"
      (is (= {} (theme/remove-theme themes "Ocean")))
      (is (= themes (theme/remove-theme themes "nope"))))))

(deftest drafts
  (let [themes {:active "A" :themes {"A" {:gray-hue 1} "Custom" {}}}]
    (testing "a new draft starts from the defaults with a free name"
      (is (= {:original nil :name "Custom 2" :params theme/defaults}
             (theme/draft-for themes nil))))
    (testing "an existing theme's draft is a full copy"
      (is (= {:original "A" :name "A" :params (assoc theme/defaults :gray-hue 1)}
             (theme/draft-for themes "A"))))
    (testing "savable: a valid, unused name"
      (is (theme/draft-savable? themes {:original nil :name "B"}))
      (is (theme/draft-savable? themes {:original "A" :name "A"}) "keeping its own name")
      (is (not (theme/draft-savable? themes {:original nil :name "A"})) "taken")
      (is (not (theme/draft-savable? themes {:original "Custom" :name "A"})) "renaming onto another")
      (is (not (theme/draft-savable? themes {:original nil :name "  "})))
      (is (not (theme/draft-savable? themes {:original nil :name (apply str (repeat 41 "x"))}))))
    (testing "no room for a new theme past the cap"
      (let [full {:themes (into {} (map (fn [i] [(str "t" i) {}]) (range theme/max-themes)))}]
        (is (not (theme/draft-savable? full {:original nil :name "more"})))
        (is (theme/draft-savable? full {:original "t1" :name "t1 renamed"}))))))

(deftest strip-colors-sum-up-a-theme
  (testing "light page, its muted text, dark page, accent; unset parameters are the defaults"
    (is (= ["#ffffff" (get-in (theme/surfaces {}) [:light "fg-1"]) "#0a0a0f" (theme/accent-color {})]
           (theme/strip-colors nil)))
    (is (= "#123456" (first (theme/strip-colors {:bg-light "#123456"}))))
    (is (= "#fabd2f" (last (theme/strip-colors {:light {:accent "#fabd2f"}}))))
    (is (= "oklch(0.530 0.0350 285.0)" (theme/gray-color {})))))

(deftest palettes-set-exact-colors
  (let [p {:bg-dark "#282828"
           :dark {:fg "#ebdbb2" :accent "#fabd2f" :sidebar "#1d2021" :danger "#fb4934"
                  :keyword "#fb4934" :number "#d3869b"}}
        {:keys [dark light]} (theme/surfaces p)
        vars (theme/css-vars p)]
    (is (= "#ebdbb2" (get dark "fg-0")))
    (is (= "#ebdbb2" (get dark "hl-var")) "plain code follows the palette's text")
    (is (= "#fb4934" (get dark "hl-keyword")))
    (is (= "#fb4934" (get dark "hl-operator")) "operators share the keyword color")
    (is (= "#d3869b" (get dark "hl-symbol")) "symbols fall back to numbers")
    (is (not= "#ebdbb2" (get light "hl-var")) "the light mode stays derived")
    (is (= "#1d2021" (second (:dark (theme/backgrounds p)))) "the sidebar")
    (is (= "#fabd2f" (get vars "--theme-accent-dark")))
    (is (= "#fb4934" (get vars "--theme-danger-dark")))
    (is (= (let [[_ _ h] (model/hex->oklch "#fb4934")] (model/oklch 0.85 0.12 h))
           (get dark "danger-fg"))
        "error text in the palette's danger hue"))
  (testing "the editor shows set colors as given, the rest derived"
    (let [shown (theme/palette-colors {:dark {:fg "#ebdbb2"}})]
      (is (= "#ebdbb2" (get-in shown [:dark :fg])))
      (is (= (get-in (theme/surfaces {}) [:light "fg-0"]) (get-in shown [:light :fg])))
      (is (= (set model/palette-keys) (set (keys (:light shown))))))))

(deftest fonts-set-their-stacks
  (let [vars (theme/css-vars {:font-body "Inter" :font-mono "bad;value"})]
    (is (str/starts-with? (get vars "--font-body") "\"Inter\", "))
    (is (nil? (get vars "--font-mono")) "an invalid list sets nothing"))
  (is (= "Inter" (theme/font-label {:font-body "Inter, sans-serif"} :font-body)))
  (is (= "System" (theme/font-label {} :font-mono)))
  (is (= {:font-body "Inter"} (theme/normalize-params {:font-body " Inter " :font-mono "{x}"}))))

(deftest built-in-themes
  (testing "selected by keyword; their params come from the catalog"
    (let [themes (theme/select {} :gruvbox)]
      (is (= {:active :gruvbox} themes))
      (is (= "#fabd2f" (get (theme/active-vars themes) "--theme-accent-dark")))
      (is (= {} (theme/normalize {:active :no-such-theme})))))
  (testing "every built-in renders"
    (doseq [[id label p] theme/builtin-themes]
      (is (string? label))
      (is (seq (theme/css-vars p)) (name id))))
  (testing "duplicating one starts a new draft named after it"
    (let [{:keys [original name params]} (theme/draft-for {} :nord)]
      (is (nil? original))
      (is (= "Nord 2" name) "a built-in's name is taken")
      (is (= "#88c0d0" (get-in params [:dark :accent])))
      (is (theme/draft-savable? {} {:original nil :name name}))
      (is (not (theme/draft-savable? {} {:original nil :name "Nord"}))))))

(deftest draft-edits
  (is (= {:dark {:fg "#ffffff"}} (theme/set-palette {} :dark :fg "#ffffff")))
  (is (= {} (theme/set-palette {:dark {:fg "#ffffff"}} :dark :fg nil)) "the last color drops the palette")
  (is (= {:a 1} (theme/set-param {:a 1 :font-body "x"} :font-body "  ")) "blank unsets")
  (let [p (theme/apply-preset {:light {:fg "#000000"} :size-base 0.3} (second theme/presets))]
    (is (nil? (:light p)) "a hue preset drops the palettes")
    (is (= 0.3 (:size-base p)))
    (is (theme/preset-active? (second theme/presets) p))))
