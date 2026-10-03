(ns xi.tui.theme-mode-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.tui.theme-mode :as theme-mode]))

(deftest resolve-mode-precedence
  (testing "defaults to dark"
    (is (= :dark (theme-mode/resolve-mode nil nil))))
  (testing "the mode set in state applies"
    (is (= :light (theme-mode/resolve-mode nil :light)))
    (is (= :dark (theme-mode/resolve-mode nil :dark))))
  (testing "XI_THEME_MODE wins over state, case and whitespace insensitive"
    (is (= :light (theme-mode/resolve-mode " Light " :dark)))
    (is (= :dark (theme-mode/resolve-mode "dark" :light))))
  (testing "an empty or unknown env value is not an override"
    (is (= :light (theme-mode/resolve-mode "" :light)))
    (is (= :dark (theme-mode/resolve-mode "sepia" :light)))))
