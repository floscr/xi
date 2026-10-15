(ns xi.ext.diff.git-test
  (:require [cljs.test :refer [deftest is]]
            [xi.ext.diff.git :as git]))

(def ^:private path-prefix #'git/path-prefix)

(deftest path-prefix-test
  (is (= "" (path-prefix "/code/xi" "/code/xi")) "the room's own repo keeps root-relative paths")
  (is (= "packages/sub/" (path-prefix "/code/xi" "/code/xi/packages/sub")) "nested repos stay relative")
  (is (= "/code/penpot/" (path-prefix "/code/xi" "/code/penpot")) "sibling repos go absolute")
  (is (= "/code/xi-other/" (path-prefix "/code/xi" "/code/xi-other")) "a shared name prefix is not nesting"))
