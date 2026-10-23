(ns xi.web.diff-jump-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.diff :as diff]
            [xi.web.diff-jump :as jump]))

(def ^:private text
  (str "diff --git a/src/app/tokens.cljs b/src/app/tokens.cljs\n"
       "--- a/src/app/tokens.cljs\n"
       "+++ b/src/app/tokens.cljs\n"
       "@@ -1,6 +1,8 @@\n"
       " (ns app.tokens)\n"
       "-(defn read-token [t] (:v t))\n"
       "+(defn read-token [t] (quote-font (:v t)))\n"
       "+\n"
       "+(defn- reference-updates [sets]\n"
       "+  (map read-token sets))\n"
       " (defn set-sets [a b]\n"
       "   (reference-updates b))\n"
       "diff --git a/src/app/ui.js b/src/app/ui.js\n"
       "--- a/src/app/ui.js\n"
       "+++ b/src/app/ui.js\n"
       "@@ -10,2 +10,4 @@\n"
       " export const withoutToken = (sets) => sets;\n"
       "+  resolveDraft(sets) {\n"
       "+    return <Toggle on={withoutToken(sets)} />;\n"
       " }\n"))

(def ^:private rows (diff/diff-rows (diff/parse-diff-text text)))

(defn- at [hit] (select-keys hit [:filename :side :line]))

(deftest symbol-definitions-win
  (testing "an added definition"
    (is (= {:filename "src/app/tokens.cljs" :side :new :line 4}
           (at (jump/find-in-rows rows "reference-updates")))))
  (testing "a definition kept as context beats the added call"
    (is (= {:filename "src/app/tokens.cljs" :side :new :line 6}
           (at (jump/find-in-rows rows "set-sets")))))
  (testing "the added definition over the removed one"
    (is (= {:filename "src/app/tokens.cljs" :side :new :line 2}
           (at (jump/find-in-rows rows "read-token")))))
  (testing "a namespaced chip finds the leaf's definition"
    (is (= {:filename "src/app/tokens.cljs" :side :new :line 4}
           (at (jump/find-in-rows rows "tokens/reference-updates")))))
  (testing "a JS method head and an arrow-function const"
    (is (= {:filename "src/app/ui.js" :side :new :line 11}
           (at (jump/find-in-rows rows "resolveDraft"))))
    (is (= {:filename "src/app/ui.js" :side :new :line 10}
           (at (jump/find-in-rows rows "withoutToken")))))
  (testing "a call's parens and an element's brackets are dropped"
    (is (= {:filename "src/app/ui.js" :side :new :line 11}
           (at (jump/find-in-rows rows "resolveDraft(sets)"))))
    (is (= {:filename "src/app/ui.js" :side :new :line 10}
           (at (jump/find-in-rows rows "<withoutToken>"))))
    (is (= {:filename "src/app/ui.js" :side :new :line 12}
           (at (jump/find-in-rows rows "<Toggle>")))
        "a JSX element is found at its opening bracket")))

(deftest mentions-and-misses
  (testing "no definition: the first changed line mentioning it"
    (is (= {:filename "src/app/tokens.cljs" :side :new :line 2}
           (at (jump/find-in-rows rows "quote-font")))))
  (testing "a symbol is matched whole"
    (is (nil? (jump/find-in-rows rows "read-tok")))
    (is (= {:filename "src/app/tokens.cljs" :side :new :line 4}
           (at (jump/find-in-rows rows "sets")))
        "a parameter is a mention, found on the first changed line"))
  (testing "nothing in the diff"
    (is (nil? (jump/find-in-rows rows "unrelated-thing")))
    (is (nil? (jump/find-in-rows rows "")))))

(deftest path-chips
  (is (= ["src/app/ui.js" 12] (jump/path-ref "src/app/ui.js:12")))
  (is (= ["ui.js" 12] (jump/path-ref "ui.js:12-14")))
  (is (nil? (jump/path-ref "set-sets")))
  (testing "a path chip is exact and matches the file by suffix"
    (is (= {:filename "src/app/ui.js" :side :new :line 12}
           (at (jump/find-in-rows rows "app/ui.js:12")))))
  (testing "a line the diff doesn't show"
    (is (nil? (jump/find-in-rows rows "src/app/ui.js:99")))))

(deftest split-rows
  (let [rows (diff/as-layout rows :split)]
    (is (= {:filename "src/app/tokens.cljs" :side :new :line 4}
           (at (jump/find-in-rows rows "reference-updates"))))
    (is (some? (:sel-idx (jump/find-in-rows rows "reference-updates"))))))

(deftest buffers-in-front-first
  (let [room {:ui {:active-buffer "diff:staged"
                   :buffers {"diff:git"    {:kind :diff :text text :opened-at 2}
                             "diff:staged" {:kind :diff :text text :opened-at 1}
                             "diff:ansi"   {:kind :diff :engine :difft :text "x" :opened-at 3}
                             "file:a"      {:kind :file :opened-at 4}}}}]
    (is (= ["diff:staged" "diff:git"] (map first (jump/diff-buffers room)))
        "the one in front, then newest; difft ANSI text has no rows")
    (is (= "diff:staged" (:buffer-id (jump/target room "set-sets" :stacked))))
    (is (nil? (jump/target room "nope" :stacked)))))
