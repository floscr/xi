(ns xi.client.tui-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.client.tui :as tui]))

(deftest launch-header-minimal
  (testing "minimal opts returns title + help + spacer"
    (let [nodes (tui/launch-header {})]
      (is (= 3 (count nodes)))
      (is (every? #(contains? % :type) nodes))
      (is (= :text (:type (first nodes))))
      (is (= :text (:type (second nodes))))
      (is (= :spacer (:type (nth nodes 2)))))))

(deftest launch-header-with-model-and-cwd
  (testing "model and cwd add text nodes"
    (let [nodes (tui/launch-header {:model "opus" :cwd "/tmp"})]
      (is (= 5 (count nodes)))
      (is (every? #(#{:text :spacer} (:type %)) nodes)))))

(deftest launch-header-with-details
  (testing "details adds one text node per entry"
    (let [nodes (tui/launch-header {:details [{:label "Room" :value "r1"}
                                              {:label "Port" :value "7474"}]})]
      ;; title + 2 details + help + spacer = 5
      (is (= 5 (count nodes))))))

(deftest launch-header-with-agents-files
  (testing "agents-files adds text + spacer"
    (let [nodes (tui/launch-header {:agents-files ["a.md"]})]
      ;; title + help + spacer + agents-text + agents-spacer = 5
      (is (= 5 (count nodes))))))

(deftest launch-header-with-multiple-agents-files
  (testing "plural agents files"
    (let [nodes (tui/launch-header {:agents-files ["a.md" "b.md"]})]
      (is (= 5 (count nodes))))))

(deftest launch-header-full
  (testing "all opts together"
    (let [nodes (tui/launch-header {:model "opus"
                                    :cwd "/tmp"
                                    :agents-files ["a.md"]
                                    :details [{:label "Room" :value "r1"}]})]
      ;; title + model + cwd + 1 detail + help + spacer + agents-text + agents-spacer = 8
      (is (= 8 (count nodes)))
      (is (every? #(#{:text :spacer} (:type %)) nodes)))))
