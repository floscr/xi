(ns xi.sandbox.sci-test
  (:require [cljs.test :refer [deftest is testing]]
            [sci.core :as sci]
            [xi.sandbox.sci :as sandbox]))

(defn- ev
  ([src] (ev {} src))
  ([opts src] (sci/eval-string* (sandbox/init opts) src)))

(deftest parse-fns-are-in-every-sandbox
  (is (= [42 nil 2.5 true] (ev "[(parse-long \"42\") (parse-long \"4x\") (parse-double \"2.5\") (parse-boolean \"true\")]")))
  (is (uuid? (ev "(parse-uuid \"6f1c4e5a-1b2c-4d3e-8f9a-0b1c2d3e4f5a\")")))
  (testing "a caller's own clojure.core entries win and keep the defaults"
    (is (= ["mine" 7] (ev {:namespaces {'clojure.core {'format (fn [& _] "mine")}}}
                          "[(format \"%s\" 1) (parse-long \"7\")]")))))

(deftest jvm-number-statics-are-strict
  (is (= [42 -7 255 2.5 1.0E3] (ev "[(Long/parseLong \"42\") (Integer/parseInt \"-7\")
                                     (Long/parseLong \"ff\" 16) (Double/parseDouble \"2.5\")
                                     (Double/parseDouble \"1e3\")]")))
  (doseq [src ["(Long/parseLong \"42abc\")" "(Long/parseLong \" 42\")" "(Long/parseLong \"\")"
               "(Long/parseLong \"9\" 8)" "(Long/parseLong \"1\" 37)"
               "(Long/parseLong \"99999999999999999999\")" "(Long/parseLong nil)"
               "(Double/parseDouble \"1.5abc\")" "(Double/parseDouble nil)"]]
    (is (thrown-with-msg? js/Error #"For input string" (ev src)) src)))
