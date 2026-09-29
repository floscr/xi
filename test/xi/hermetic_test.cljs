(ns xi.hermetic-test
  "Load-time test-run isolation. The :node-test runner loads every test
   namespace before it runs any test, so the top-level side effects here apply
   to the whole run."
  (:require [cljs.test :refer [deftest is]]
            [xi.rules.store :as rules-store]))

(def ^:private test-global-rules-file
  "A path that never exists, so no global rules apply during tests."
  "/nonexistent/xi-test/rules.edn")

;; Keep the user's real ~/.config/xi/rules.edn out of the tests.
(rules-store/set-global-file! test-global-rules-file)

(deftest global-rules-file-is-isolated
  (is (= test-global-rules-file (rules-store/global-file))))
