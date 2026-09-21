(ns xi.dialog-test
  (:require [cljs.test :refer [deftest is]]
            [xi.dialog :as dialog]))

(deftest confirm-options-defaults-to-yes-no
  (is (= [true false]
         (map :value (dialog/confirm-options {:type :confirm :message "?"})))))

(deftest confirm-options-normalizes-keywords
  (let [opts (dialog/confirm-options {:options [:yes :no :allow-repo]})]
    (is (= [true false :repo] (map :value opts)))
    (is (= ["y" "n" "r"] (map :key opts)))
    (is (every? :label opts))))

(deftest confirm-options-drops-unknown-keywords-keeps-maps
  (let [custom {:value :x :key "x" :label "X"}]
    (is (= [custom]
           (dialog/confirm-options {:options [:nope custom]})))))

(deftest form-fields-humanizes-missing-labels
  (is (= [{:name "commit-message" :label "Commit message"}
          {:name "scope" :label "Scope"}]
         (dialog/form-fields {:type :form
                              :fields [{:name "commit-message"}
                                       {:name "scope"}]}))))

(deftest form-fields-keeps-explicit-labels-drops-nameless
  (is (= [{:name "a" :label "Custom"}]
         (dialog/form-fields {:fields [{:name "a" :label "Custom"}
                                       {:label "no name"}]}))))

(deftest resolved-label-from-options
  (let [d {:options [:yes :no :always]}]
    (is (= "Allowed" (dialog/resolved-label d true)))
    (is (= "Denied" (dialog/resolved-label d false)))
    (is (= "Always allowed" (dialog/resolved-label d :always)))
    (is (= "Allowed" (dialog/resolved-label d :unknown-truthy))
        "unknown truthy value falls back to Allowed")))
