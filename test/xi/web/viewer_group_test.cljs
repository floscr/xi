(ns xi.web.viewer-group-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.viewer-group :as vg]))

(deftest super-collapsible
  (testing "only runs whose blocks are all collapsed fold"
    (is (true? (vg/super-collapsible? [{:collapsed? true} {:collapsed? true}])))
    (is (false? (vg/super-collapsible? [{:collapsed? true} {:collapsed? false}])))
    (is (false? (vg/super-collapsible? [{:collapsed? true} {}])))
    (is (false? (vg/super-collapsible? [])))))

(deftest summarize-run
  (let [s (vg/summarize [{:kind :thinking :name "Thinking"}
                         {:kind :tool-call :name "Read" :detail "a.cljs"}
                         {:kind :tool-call :name "Edit" :detail "b.cljs" :error? true}
                         {:kind :tool-call :name "Bash" :detail "bb test" :running? true}])]
    (is (= 4 (:count s)))
    (is (= 3 (:tool-count s)))
    (is (= 1 (:thinking-count s)))
    (is (= 1 (:error-count s)))
    (is (true? (:running? s)))
    (is (= "Bash" (:name (:latest s))) "latest is the newest block"))
  (testing "a finished run is not running"
    (is (false? (:running? (vg/summarize [{:kind :tool-call :name "Read"}]))))))

(deftest count-labels
  (is (= "1 step" (vg/count-label 1)))
  (is (= "2 steps" (vg/count-label 2))))
