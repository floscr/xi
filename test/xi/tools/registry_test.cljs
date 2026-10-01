(ns xi.tools.registry-test
  (:require [cljs.test :refer [deftest is testing async]]
            [xi.tools.registry :as registry]
            [xi.util :as util]))

(defn- total-text [content]
  (->> content
       (filter #(= "text" (:type %)))
       (map #(count (:text %)))
       (reduce + 0)))

(deftest run-tool-caps-oversized-content
  (testing "run-tool trims an oversized tool result under the cap"
    (async done
      (let [big (apply str (repeat (* util/max-tool-result-chars 3) "x"))
            exec (fn [_args _ctx] {:content [{:type "text" :text big}]})]
        (-> (registry/run-tool exec {} {})
            (.then (fn [{:keys [content is-error]}]
                     (is (false? is-error))
                     (is (<= (total-text content) util/max-tool-result-chars))
                     (is (re-find #"Xi truncated" (:text (first content))))
                     (done))))))))

(deftest run-tool-normalizes-errors
  (testing "a throwing exec-fn becomes an error tool result"
    (async done
      (let [exec (fn [_args _ctx] (throw (js/Error. "boom")))]
        (-> (registry/run-tool exec {} {})
            (.then (fn [{:keys [content is-error]}]
                     (is (true? is-error))
                     (is (re-find #"boom" (util/extract-text-content content)))
                     (done))))))))

(deftest run-tool-awaits-promise-results
  (testing "a promise-returning exec-fn is awaited and passed through"
    (async done
      (let [exec (fn [_args _ctx]
                   (js/Promise.resolve {:content [{:type "text" :text "ok"}]}))]
        (-> (registry/run-tool exec {} {})
            (.then (fn [{:keys [content is-error]}]
                     (is (false? is-error))
                     (is (= "ok" (util/extract-text-content content)))
                     (done))))))))

(deftest resolve-tooling-composes-the-advertised-tools
  (let [ext-def  {:name "web_search" :description "s"}
        names    #(mapv :name (:defs (registry/resolve-tooling %)))
        builtins (mapv :name (registry/tool-definitions))]
    (is (= builtins (names {})))
    (is (= (conj builtins "web_search")
           (names {:extra-tool-definitions (fn [] [ext-def])}))
        "extension tools are advertised; the seam may be a fn")
    (is (not-any? #{"bash"} (names {:remove-tools #{"bash"}})))
    (is (= ["web_search"] (names {:extra-tool-definitions [ext-def] :only-tools #{"web_search"}}))
        "an agent profile's allowlist keeps only its tools, builtin or extension")
    (is (= [] (names {:extra-tool-definitions [ext-def] :only-tools #{}}))
        "an empty allowlist advertises nothing")
    (is (= ["ls"] (names {:only-tools #{"ls"}})))
    (is (fn? (get (:registry (registry/resolve-tooling
                              {:extra-tool-registry {"web_search" (fn [_ _] nil)}}))
                  "web_search")))))
