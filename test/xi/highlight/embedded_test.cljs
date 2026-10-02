(ns xi.highlight.embedded-test
  (:require [clojure.string :as str]
            [cljs.test :refer [deftest is testing]]
            [xi.highlight.embedded :as embedded]
            [xi.highlight.grammars :as grammars]))

(defn- tokens [src] (embedded/tokenize-clj grammars/get-grammar src))

(defn- joined [toks] (apply str (map :value toks)))

(defn- typed
  "The :type of the token whose value is exactly `value`."
  [toks value]
  (some #(when (= value (:value %)) (:type %)) toks))

(deftest plain-strings-stay-strings
  (let [src "(println \"(def x 1)\")"
        toks (tokens src)]
    (is (= src (joined toks)))
    (is (= :string (typed toks "\"(def x 1)\"")) "not an embedding context")))

(deftest spit-content-highlighted-by-extension
  (let [src "(spit \"/tmp/a.clj\" \"(defn foo [x] 42)\")"
        toks (tokens src)]
    (is (= src (joined toks)) "token values still cover the source exactly")
    (is (= :keyword-decl (some #(when (str/starts-with? (:value %) "defn") (:type %)) toks)))
    (is (= :number (typed toks "42")))
    (is (= :string (typed toks "\"/tmp/a.clj\"")) "the path itself stays a string")))

(deftest spit-content-keeps-escapes-verbatim
  (let [src "(spit \"x.clj\" \"(str \\\"a\\\\b\\\" 1)\\n(inc 2)\")"
        toks (tokens src)]
    (is (= src (joined toks)) "\\\" \\\\ and \\n survive as written")
    (is (= :number (typed toks "2")))))

(deftest spit-multiline-content
  (let [src "(spit \"x.clj\" \"(def a 1)\n(def b 2)\")"
        toks (tokens src)]
    (is (= src (joined toks)))
    (is (= :number (typed toks "2")))))

(deftest spit-unknown-extension-stays-string
  (let [src "(spit \"x.nope\" \"(def a 1)\")"
        toks (tokens src)]
    (is (= src (joined toks)))
    (is (= :string (typed toks "\"(def a 1)\"")))))

(deftest sh-eval-flag-highlights-script
  (testing "bb -e → clojure"
    (let [src "(sh \"bb\" \"-e\" \"(inc 41)\")"
          toks (tokens src)]
      (is (= src (joined toks)))
      (is (= :number (typed toks "41")))))
  (testing "options map before the command is fine"
    (let [src "(sh {:dir \"x\"} \"bb\" \"-e\" \"(inc 41)\")"]
      (is (= :number (typed (tokens src) "41")))))
  (testing "bash -c → bash"
    (let [src "(sh \"bash\" \"-c\" \"echo 1 # hi\")"
          toks (tokens src)]
      (is (= src (joined toks)))
      (is (some #(= :comment (:type %)) toks))))
  (testing "a non-eval flag is not code"
    (let [src "(sh \"bb\" \"-cp\" \"(inc 41)\")"]
      (is (= :string (typed (tokens src) "\"(inc 41)\"")))))
  (testing "an unknown program is not code"
    (let [src "(sh \"echo\" \"-e\" \"(inc 41)\")"]
      (is (= :string (typed (tokens src) "\"(inc 41)\""))))))

(deftest nested-embedding
  (let [src "(spit \"a.clj\" \"(spit \\\"b.clj\\\" \\\"(inc 7)\\\")\")"
        toks (tokens src)]
    (is (= src (joined toks)))
    (is (= :number (typed toks "7")) "clj inside clj inside clj")))
