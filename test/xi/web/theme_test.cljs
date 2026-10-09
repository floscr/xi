(ns xi.web.theme-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.web.theme :as theme]))

(deftest default-params-reproduce-the-generated-theme
  (let [vars (theme/css-vars theme/defaults)]
    (testing "same format and values as ui.css.gen for tokens.edn"
      (is (= "oklch(0.530 0.0350 285.0)" (get vars "--gray-500")))
      (is (= "oklch(0.595 0.2300 286.0)" (get vars "--accent-500")))
      (is (= "0.64rem" (get vars "--font-xs")))
      (is (= "1rem" (get vars "--font-base")))
      (is (= "4rem" (get vars "--size-16")))
      (is (= "0.25rem" (get vars "--size-1")))
      (is (= "10px" (get vars "--radius-md"))))
    (testing "backgrounds: white / gray-950 pages, the sidebar 2.5% darker"
      (is (= "oklch(1.000 0.0000 0.0)" (get vars "--theme-bg-light")))
      (is (= "oklch(0.975 0.0000 0.0)" (get vars "--theme-sidebar-light")))
      (is (= "oklch(0.147 0.0107 285.0)" (get vars "--theme-bg-dark")))
      (is (= "oklch(0.122 0.0107 285.0)" (get vars "--theme-sidebar-dark"))))
    (testing "every managed property has a value"
      (is (= (set theme/var-names) (set (keys vars))))
      (is (= 133 (count theme/var-names))
          "11 gray + 11 accent + 33 status + 4 backgrounds + 46 surfaces + 16 sizes + 8 fonts + 4 radii"))))

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
                              :font-ratio 1.5 :radius-scale 0.5})]
    (is (= "oklch(0.530 0.0350 60.0)" (get vars "--gray-500")) "hue")
    (is (= "oklch(0.595 0.4000 286.0)" (get vars "--accent-500")) "chroma clamps at 0.4")
    (is (= "0.3rem" (get vars "--size-1")))
    (is (= "4.8rem" (get vars "--size-16")))
    (is (= "1.5rem" (get vars "--font-md")))
    (is (= "3px" (get vars "--radius-sm")))
    (is (= "2px" (get vars "--radius-xs"))))
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
