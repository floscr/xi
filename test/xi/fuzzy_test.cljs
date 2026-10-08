(ns xi.fuzzy-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.fuzzy :as fuzzy]))

(deftest match?-test
  (testing "subsequence, case-insensitive"
    (is (fuzzy/match? "core" "src/xi/web/core.cljs"))
    (is (fuzzy/match? "webcore" "src/xi/web/core.cljs"))
    (is (fuzzy/match? "SXWC" "src/xi/web/core.cljs"))
    (is (not (fuzzy/match? "zzz" "src/xi/web/core.cljs")))
    (is (not (fuzzy/match? "eroc" "core"))))
  (testing "blank query matches everything"
    (is (fuzzy/match? "" "anything"))
    (is (fuzzy/match? "   " "anything")))
  (testing "spaces split independent terms, each matched on its own"
    (is (fuzzy/match? "plan md" "plans/notes.md"))
    (is (fuzzy/match? "md plan" "plans/notes.md"))
    (is (not (fuzzy/match? "plan md" "plans/notes.txt")))))

(deftest score-test
  (testing "nil when not a subsequence"
    (is (nil? (fuzzy/score "zzz" "abc"))))
  (testing "blank query scores 0"
    (is (= 0 (fuzzy/score "" "abc"))))
  (testing "a tighter / earlier match scores lower (better)"
    (is (< (fuzzy/score "core" "core.cljs")
           (fuzzy/score "core" "src/coordinator/legacy_reducer.cljs"))))
  (testing "multi-term queries sum per-term scores; nil when any term misses"
    (is (some? (fuzzy/score "plan md" "plans/notes.md")))
    (is (nil? (fuzzy/score "plan md" "plans/notes.txt")))))

(deftest rank-test
  (let [files ["src/xi/web/core.cljs"
               "src/xi/core/state.cljs"
               "src/xi/core/app.cljs"
               "test/xi/commands_test.cljs"
               "README.md"]]
    (testing "blank query returns input order"
      (is (= files (fuzzy/rank "" files))))
    (testing "ranks the basename hit first"
      (is (= "src/xi/web/core.cljs" (first (fuzzy/rank "core.cljs" files)))))
    (testing "spaced query keeps files matching every term"
      (is (= #{"src/xi/web/core.cljs" "src/xi/core/state.cljs" "src/xi/core/app.cljs"}
             (set (fuzzy/rank "core cljs" files)))))
    (testing "filters out non-matches"
      (is (= [] (fuzzy/rank "zzzq" files))))
    (testing "limit caps results"
      (is (= 2 (count (fuzzy/rank "cljs" files {:limit 2})))))
    (testing "key-fn extracts the string"
      (is (= [{:p "core.cljs"}]
             (fuzzy/rank "core" [{:p "core.cljs"} {:p "readme.md"}]
                         {:key-fn :p}))))))
