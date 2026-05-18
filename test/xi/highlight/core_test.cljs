(ns xi.highlight.core-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.highlight.core :as hl]
            [xi.highlight.grammars :as grammars]
            [xi.highlight.theme :as theme]))

(deftest tokenize-clojure-basic
  (testing "tokenizes a simple Clojure expression"
    (let [tokens (hl/tokenize (grammars/get-grammar "clojure") "(defn foo [x] x)")]
      (is (seq tokens) "should produce tokens")
      (is (every? #(and (:type %) (:value %)) tokens)
          "every token has :type and :value")))

  (testing "covers basic token types"
    (let [tokens (hl/tokenize (grammars/get-grammar "clojure") "(defn foo [x] (+ x 42))")]
      (is (some #(= :punctuation (:type %)) tokens) "has punctuation")
      (is (some #(= :number (:type %)) tokens) "has number")
      (is (some #(= :keyword-decl (:type %)) tokens) "has keyword-decl for defn")))

  (testing "tokenizes comments"
    (let [tokens (hl/tokenize (grammars/get-grammar "clojure") ";; hello")]
      (is (= 1 (count (filter #(= :comment (:type %)) tokens))))
      (is (= ";; hello" (:value (first (filter #(= :comment (:type %)) tokens)))))))

  (testing "tokenizes strings"
    (let [tokens (hl/tokenize (grammars/get-grammar "clojure") "\"hello world\"")]
      (is (some #(= :string (:type %)) tokens))))

  (testing "tokenizes Clojure keywords as string-symbol (chroma convention)"
    (let [tokens (hl/tokenize (grammars/get-grammar "clojure") ":foo")]
      (is (some #(= :string-symbol (:type %)) tokens)))))

(deftest tokenize-json
  (testing "tokenizes JSON values"
    (let [tokens (hl/tokenize (grammars/get-grammar "json") "{\"name\": \"xi\", \"version\": 1}")]
      (is (some #(= :string (:type %)) tokens) "has strings")
      (is (some #(= :number (:type %)) tokens) "has number"))))

(deftest tokenize-bash
  (testing "tokenizes a bash command"
    (let [tokens (hl/tokenize (grammars/get-grammar "bash") "echo \"hello\" | grep foo")]
      (is (some #(= :name-builtin (:type %)) tokens) "has builtins")
      (is (seq tokens) "produces tokens"))))

(deftest merge-adjacent-test
  (testing "merges consecutive same-type tokens"
    (let [tokens [{:type :text :value "a"} {:type :text :value "b"} {:type :keyword :value "c"}]
          merged (hl/merge-adjacent tokens)]
      (is (= 2 (count merged)))
      (is (= "ab" (:value (first merged))))
      (is (= "c" (:value (second merged))))))

  (testing "returns nil for empty input"
    (is (nil? (hl/merge-adjacent [])))))

(deftest colorize-roundtrip
  (testing "colorize produces non-empty output for highlighted code"
    (let [tokens (-> (hl/tokenize (grammars/get-grammar "clojure") "(defn foo [x] x)")
                     hl/merge-adjacent)
          result (theme/colorize tokens)]
      (is (string? result))
      (is (pos? (count result))))))

(deftest grammar-registry
  (testing "get-grammar resolves standard names"
    (is (some? (grammars/get-grammar "clojure")))
    (is (some? (grammars/get-grammar "clj")))
    (is (some? (grammars/get-grammar "bash")))
    (is (some? (grammars/get-grammar "json")))
    (is (some? (grammars/get-grammar "python")))
    (is (some? (grammars/get-grammar "javascript")))
    (is (some? (grammars/get-grammar "nix")))
    (is (some? (grammars/get-grammar "yaml"))))

  (testing "returns nil for unknown languages"
    (is (nil? (grammars/get-grammar "this-definitely-does-not-exist-xyz")))
    (is (nil? (grammars/get-grammar nil)))))

(deftest many-grammars-available
  (testing "registry has many languages"
    (is (> (count grammars/registry) 200)
        "should have 200+ language entries in registry")))
