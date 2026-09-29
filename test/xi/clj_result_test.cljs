(ns xi.clj-result-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.clj-result :as clj-result]))

(deftest parse-clj-result
  (testing "value only"
    (is (= {:value "[1 2]"} (clj-result/parse-clj-result "=> [1 2]" false))))
  (testing "stdout then value"
    (is (= {:stdout "hi" :value "nil"}
           (clj-result/parse-clj-result "hi\n=> nil" false))))
  (testing "error with stdout and location"
    (is (= {:stdout "hi" :error "Boom" :loc "line 2:5"}
           (clj-result/parse-clj-result "hi\nError: Boom (line 2:5)" true)))))

(deftest value-display
  (testing "clj data is pretty-printed and flagged"
    (let [{:keys [text data?]} (clj-result/value-display
                                {:value (pr-str (zipmap (map #(keyword (str "some-long-key-" %)) (range 10))
                                                        (range 10)))})]
      (is data?)
      (is (< 1 (count (re-seq #"\n" text))))))
  (testing "plain text is left alone, minus leading blank lines"
    (is (= {:text "hello world" :data? false}
           (clj-result/value-display {:value "\n  \nhello world"}))))
  (testing "a bare nil after stdout is hidden, but shown on its own"
    (is (nil? (clj-result/value-display {:stdout "out" :value "nil"})))
    (is (= "nil" (:text (clj-result/value-display {:value "nil"})))))
  (testing "no value → nil"
    (is (nil? (clj-result/value-display {:error "x"})))))
