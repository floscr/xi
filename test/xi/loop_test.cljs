(ns xi.loop-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.loop :as loop]))

(deftest claude-model-prefixes
  (testing "claude- prefix is recognized"
    (is (loop/claude-model? "claude-sonnet-4-20250514"))
    (is (loop/claude-model? "claude-opus-4-6"))
    (is (loop/claude-model? "claude-3-haiku-20240307"))))

(deftest claude-model-anthropic-prefix
  (testing "anthropic/ prefix is recognized"
    (is (loop/claude-model? "anthropic/claude-sonnet-4-20250514"))))

(deftest claude-model-bare-aliases
  (testing "bare aliases are recognized"
    (is (loop/claude-model? "sonnet"))
    (is (loop/claude-model? "opus"))
    (is (loop/claude-model? "haiku"))))

(deftest non-claude-models
  (testing "non-claude models return falsy"
    (is (not (loop/claude-model? "gpt-4")))
    (is (not (loop/claude-model? "llama3")))
    (is (not (loop/claude-model? "qwen3:32b")))
    (is (not (loop/claude-model? "deepseek-r1")))))

(deftest nil-model
  (testing "nil model returns falsy"
    (is (not (loop/claude-model? nil)))))
