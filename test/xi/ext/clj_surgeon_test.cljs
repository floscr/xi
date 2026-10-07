(ns xi.ext.clj-surgeon-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            ["node:child_process" :as cp]
            [xi.ext.clj-surgeon :as surgeon]))

(defn- tool-def [name]
  (->> (:tool-definitions surgeon/extension)
       (filter #(= name (:name %)))
       first))

(deftest clj-replace-docs-say-complete-forms-only
  (let [td (tool-def "clj_replace")]
    (testing "tool description warns against fragments and points at edit"
      (is (str/includes? (:description td) "complete, balanced forms"))
      (is (str/includes? (:description td) "text edit tool")))
    (testing "old_str parameter is documented as one complete form"
      (is (str/includes? (get-in td [:input_schema :properties :old_str :description])
                         "complete, balanced")))))

(deftest system-prompt-routes-fragments-to-edit
  (let [prompt (:system-prompt surgeon/extension)]
    (is (str/includes? prompt "partial fragments"))
    (is (str/includes? prompt "one complete, balanced form"))))

(deftest replace-script-fragment-error-explains-the-limit
  ;; package.json exists and the old-string fails to parse before anything
  ;; is written, so the file is never touched.
  (let [res (.spawnSync cp "bb"
                        #js ["src/xi/ext/clj_surgeon/replace.clj"
                             "package.json" "(foo [a" "(bar)"]
                        #js {:encoding "utf8" :cwd (.cwd js/process)})
        out (str (.-stdout res) (.-stderr res))]
    (is (= 1 (.-status res)))
    (is (str/includes? out "old-string parse error"))
    (is (str/includes? out "ONE complete, balanced form"))
    (is (str/includes? out "`edit` tool"))))
