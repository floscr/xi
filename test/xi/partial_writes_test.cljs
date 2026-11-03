(ns xi.partial-writes-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.partial-writes :as pw]
            [xi.util :as util]))

(deftest line-count
  (is (= 0 (util/line-count "")))
  (is (= 1 (util/line-count "a")))
  (is (= 1 (util/line-count "a\n")))
  (is (= 2 (util/line-count "a\nb")))
  (is (= 3 (util/line-count "a\n\nb\n"))))

(deftest note-says-where-the-content-is
  (testing "saved in place"
    (let [n (pw/note {:path "/p/a.html" :file "/p/a.html" :in-place? true} 640)]
      (is (str/includes? n "/p/a.html"))
      (is (str/includes? n "first 640 lines are saved in that file"))
      (is (str/includes? n "append: true"))))
  (testing "an existing file stays as it was"
    (let [n (pw/note {:path "/p/a.html" :file "/tmp/x/t1.partial" :existed? true} 12)]
      (is (str/includes? n "still has its old content"))
      (is (str/includes? n "/tmp/x/t1.partial"))))
  (testing "a path the rules ask about wasn't written"
    (let [n (pw/note {:path "/etc/a" :file "/tmp/x/t1.partial"} 120)]
      (is (str/includes? n "wasn't written"))
      (is (str/includes? n "copy it to /etc/a")))))
