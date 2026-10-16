(ns xi.web.block-nav-test
  (:require [cljs.test :refer [deftest is]]
            [xi.web.block-nav :as nav]))

(deftest arrows-rove-and-stop-at-the-ends
  (is (= 1 (nav/step 3 0 "ArrowDown")))
  (is (= 1 (nav/step 3 0 "ArrowRight")))
  (is (= 2 (nav/step 3 2 "ArrowDown")) "no wrap at the end")
  (is (= 0 (nav/step 3 0 "ArrowUp")) "no wrap at the start")
  (is (= 1 (nav/step 3 2 "ArrowLeft")))
  (is (= 0 (nav/step 3 2 "Home")))
  (is (= 2 (nav/step 3 0 "End"))))

(deftest from-outside-the-controls
  (is (= 0 (nav/step 3 nil "ArrowDown")) "the block itself has focus: first control")
  (is (= 2 (nav/step 3 nil "ArrowUp")) "or the last one")
  (is (nil? (nav/step 0 nil "ArrowDown")) "a block without controls")
  (is (nil? (nav/step 3 1 "a")) "other keys are left alone"))
