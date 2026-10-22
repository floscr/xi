(ns xi.markdown.code-kind-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.markdown.code-kind :as kind]))

(deftest classify-test
  (testing "literals"
    (doseq [s ["%" "/100" "\"50%\"" "0.5" "12px" ">=" "[\"font-weight\"]" "nil" "'a'"]]
      (is (= :literal (kind/classify s)) s)))
  (testing "paths need an extension, a :line suffix or a trailing slash"
    (doseq [s ["package.json" "frontend/pnpm-workspace.yaml:6" "src/a.cljs:10-20"
               "frontend/packages/tokenscript/" "~/.config/xi/config.edn"]]
      (is (= :path (kind/classify s)) s)))
  (testing "sigils"
    (doseq [s [":opacity" ":error.style-dictionary/value-with-percent" "@atom" "--full" "$HOME"]]
      (is (= :sigil (kind/classify s)) s)))
  (testing "everything else is an identifier, including ambiguous a/b"
    (doseq [s ["resolve-value" "sm/max-safe-int" "app.plugins.tokens/get-resolved-value"
               "symbol->value" "line-height-ratio false" "bb test"]]
      (is (= :ident (kind/classify s)) s))))

(deftest split-prefix-test
  (is (= ["frontend/src/" "tokenscript.cljs"] (kind/split-prefix "frontend/src/tokenscript.cljs")))
  (is (= ["frontend/packages/" "tokenscript/"] (kind/split-prefix "frontend/packages/tokenscript/")))
  (is (= ["app.plugins.tokens/" "get-resolved-value"] (kind/split-prefix "app.plugins.tokens/get-resolved-value")))
  (is (= [":error.style-dictionary/" "value-with-percent"] (kind/split-prefix ":error.style-dictionary/value-with-percent")))
  (is (= ["NumberWithUnit." "Percent"] (kind/split-prefix "NumberWithUnit.Percent")))
  (is (= [":" "opacity"] (kind/split-prefix ":opacity")))
  (testing "no split"
    (is (= ["" "package.json"] (kind/split-prefix "package.json")))
    (is (= ["" "resolve-value"] (kind/split-prefix "resolve-value")))
    (is (= ["" "bb test --full"] (kind/split-prefix "bb test --full")))
    (is (= ["" "/"] (kind/split-prefix "/")))
    (is (= ["" "v1.2.3"] (kind/split-prefix "v1.2.3")))))
