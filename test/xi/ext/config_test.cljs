(ns xi.ext.config-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.ext.config :as config]))

(deftest parse-dotenv-reads-key-value-lines
  (testing "basic KEY=VALUE"
    (is (= {"RENDER_API_KEY" "rnd_abc123"}
           (config/parse-dotenv "RENDER_API_KEY=rnd_abc123"))))
  (testing "blank lines and # comments are ignored"
    (is (= {"A" "1" "B" "2"}
           (config/parse-dotenv "# a comment\nA=1\n\n   \n# another\nB=2"))))
  (testing "surrounding single/double quotes are stripped"
    (is (= {"A" "x y" "B" "z"}
           (config/parse-dotenv "A=\"x y\"\nB='z'"))))
  (testing "values may contain = (only the first splits)"
    (is (= {"URL" "https://x/y?a=b"}
           (config/parse-dotenv "URL=https://x/y?a=b"))))
  (testing "surrounding whitespace on key and value is trimmed"
    (is (= {"K" "v"} (config/parse-dotenv "  K  =  v  "))))
  (testing "lines with no = are skipped"
    (is (= {"K" "v"} (config/parse-dotenv "nonsense\nK=v"))))
  (testing "empty / nil input yields {}"
    (is (= {} (config/parse-dotenv "")))
    (is (= {} (config/parse-dotenv nil)))))

(deftest config-file-path-is-per-extension
  (is (re-find #"/\.config/xi/ext/render\.env$" (config/config-file :render)))
  (is (re-find #"/\.config/xi/ext/foo\.env$" (config/config-file "foo"))))

(deftest get-value-prefers-process-env
  (let [k "XI_TEST_CONFIG_KEY_UNIQUE"]
    (testing "a set process.env value wins and needs no file"
      (aset js/process.env k "from-env")
      (is (= "from-env" (config/get-value :nonexistent-ext k)))
      (js-delete js/process.env k))
    (testing "absent everywhere returns the default"
      (is (nil? (config/get-value :nonexistent-ext k)))
      (is (= :d (config/get-value :nonexistent-ext k :d))))))
