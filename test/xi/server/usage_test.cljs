(ns xi.server.usage-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.server.usage :as usage]))

(def ^:private integrate #'usage/integrate)

(def sources [{:id :claude} {:id :pool}])

(defn- reading [id used] {:id id :windows [{:id "w" :used used}]})

(deftest integrate-merges-sources
  (let [st (integrate {:by-source {} :history {}}
                      [[:claude [(reading "claude/a" 10)]]
                       [:pool [(reading "claude/a" 99) (reading "claude/b" 20)]]]
                      sources 1000)]
    (testing "built-in sources come first and win a duplicate id"
      (is (= [(reading "claude/a" 10) (reading "claude/b" 20)] (:readings st))))
    (is (= {"claude/a" {"w" [[1000 10]]} "claude/b" {"w" [[1000 20]]}} (:history st)))
    (is (= 1000 (:fetched-at st)))
    (testing "a source that failed (nil) keeps its last readings"
      (let [st' (integrate st [[:claude [(reading "claude/a" 11)]] [:pool nil]] sources 2000)]
        (is (= [(reading "claude/a" 11) (reading "claude/b" 20)] (:readings st')))))
    (testing "a source that answered with nothing drops its cards"
      (let [st' (integrate st [[:claude [(reading "claude/a" 11)]] [:pool []]] sources 2000)]
        (is (= [(reading "claude/a" 11)] (:readings st')))))))
