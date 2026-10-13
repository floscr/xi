(ns xi.web.link-hints-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.web.link-hints :as lh]))

(defn- prefix-free? [ls]
  (not-any? (fn [[a b]] (and (not= a b) (str/starts-with? b a)))
            (for [a ls b ls] [a b])))

(deftest labels-test
  (testing "none for no targets"
    (is (nil? (lh/labels 0))))
  (testing "single letters while they suffice"
    (is (= ["s"] (lh/labels 1)))
    (is (every? #(= 1 (count %)) (lh/labels (count lh/alphabet)))))
  (testing "more targets than letters: distinct, prefix-free, short"
    (doseq [n [15 20 100 300]]
      (let [ls (lh/labels n)]
        (is (= n (count ls) (count (set ls))))
        (is (prefix-free? ls))
        (is (every? #(<= (count %) 3) ls))
        (is (every? #(every? (set lh/alphabet) %) ls))))))
