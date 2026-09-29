(ns xi.tools.truncate-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.tools.truncate :as trunc]))

(deftest note-test
  (testing "names the spill file and how to inspect it"
    (let [n (trunc/note {:keep :head :shown 10 :total 50
                         :path "/tmp/xi-output/clj-1.log" :how "use (grep re f)."})]
      (is (str/includes? n "showing first 10 of 50 chars"))
      (is (str/includes? n "/tmp/xi-output/clj-1.log"))
      (is (str/includes? n "use (grep re f)."))))
  (testing "tail keep + failed spill omits the pointer"
    (let [n (trunc/note {:keep :tail :shown 10 :total 50 :path nil :how "x"})]
      (is (str/includes? n "showing last 10"))
      (is (not (str/includes? n "saved to"))))))

(deftest truncate-test
  (with-redefs [trunc/spill! (fn [prefix _] (str "/tmp/xi-output/" prefix ".log"))]
    (testing "short output passes through untouched"
      (is (= "abc" (trunc/truncate "abc" 10 {}))))
    (testing "head keep: prefix of the text, then the note"
      (let [r (trunc/truncate "0123456789ABCDEF" 10 {:prefix "clj" :how "h"})]
        (is (str/starts-with? r "0123456789\n… [truncated"))
        (is (str/includes? r "/tmp/xi-output/clj.log"))
        (is (not (str/includes? r "ABCDEF")))))
    (testing "tail keep: the note first, then the end of the text"
      (let [r (trunc/truncate "0123456789ABCDEF" 6 {:keep :tail :prefix "bash" :how "h"})]
        (is (str/starts-with? r "[truncated: showing last 6 of 16"))
        (is (str/ends-with? r "\nABCDEF"))
        (is (str/includes? r "/tmp/xi-output/bash.log"))))))
