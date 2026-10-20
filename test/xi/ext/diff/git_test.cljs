(ns xi.ext.diff.git-test
  (:require [cljs.test :refer [deftest is]]
            [xi.ext.diff.git :as git]))

(def ^:private path-prefix #'git/path-prefix)
(def ^:private existing-dir #'git/existing-dir)

(deftest path-prefix-test
  (is (= "" (path-prefix "/code/xi" "/code/xi")) "the room's own repo keeps root-relative paths")
  (is (= "packages/sub/" (path-prefix "/code/xi" "/code/xi/packages/sub")) "nested repos stay relative")
  (is (= "/code/penpot/" (path-prefix "/code/xi" "/code/penpot")) "sibling repos go absolute")
  (is (= "/code/xi-other/" (path-prefix "/code/xi" "/code/xi-other")) "a shared name prefix is not nesting"))

(deftest existing-dir-test
  (is (= "/tmp" (existing-dir "/tmp/x.txt")))
  (is (= "/tmp" (existing-dir "/tmp/xi-moved-away-7f3a/sub/x.txt"))
      "a file whose directory was moved away resolves to the nearest surviving ancestor")
  (is (= "/" (existing-dir "/xi-gone-7f3a/x.txt"))))
