(ns xi.web.theme-test
  (:require [cljs.test :refer [deftest is testing]]
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
    (testing "backgrounds: white / gray-950 pages, the sidebar 2.5% darker, gray-tinted"
      (is (= "oklch(1.000 0.0050 285.0)" (get vars "--theme-bg-light")))
      (is (= "oklch(0.975 0.0050 285.0)" (get vars "--theme-sidebar-light")))
      (is (= "oklch(0.145 0.0110 285.0)" (get vars "--theme-bg-dark")))
      (is (= "oklch(0.120 0.0110 285.0)" (get vars "--theme-sidebar-dark"))))
    (testing "every managed property has a value"
      (is (= (set theme/var-names) (set (keys vars))))
      (is (= 54 (count theme/var-names))
          "11 gray + 11 accent + 4 backgrounds + 16 sizes + 8 fonts + 4 radii"))))

(deftest backgrounds-follow-the-gray-and-the-shift
  (let [{:keys [light dark]} (theme/backgrounds {:gray-hue 60 :gray-chroma 2
                                                 :bg-light 0.9 :bg-dark 0 :sidebar-shift 0.1})]
    (is (= ["oklch(0.900 0.0100 60.0)" "oklch(1.000 0.0100 60.0)"] light)
        "a positive shift lightens the sidebar, clamped at white")
    (is (= ["oklch(0.000 0.0220 60.0)" "oklch(0.100 0.0220 60.0)"] dark)))
  (is (= "oklch(0.000 0.0110 285.0)"
         (second (:dark (theme/backgrounds {:bg-dark 0.02 :sidebar-shift -0.2}))))
      "clamped at black"))

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
