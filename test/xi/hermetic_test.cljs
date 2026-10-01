(ns xi.hermetic-test
  "Load-time test-run isolation. The :node-test runner loads every test
   namespace before it runs any test, so the top-level side effects here apply
   to the whole run."
  (:require [cljs.test :refer [deftest is]]
            [xi.rules.store :as rules-store]
            [xi.user-config :as user-config]))

(def ^:private test-global-rules-file
  "A path that never exists, so no global rules apply during tests."
  "/nonexistent/xi-test/rules.edn")

(def ^:private test-config-file
  "Likewise for the user config: no extensions, no agent profiles."
  "/nonexistent/xi-test/config.edn")

;; Keep the user's real ~/.config/xi/rules.edn and config.edn out of the tests.
(rules-store/set-global-file! test-global-rules-file)
(user-config/set-config-file! test-config-file)

(deftest global-rules-file-is-isolated
  (is (= test-global-rules-file (rules-store/global-file))))

(deftest user-config-file-is-isolated
  (is (= test-config-file (user-config/config-file)))
  (is (= #{} (user-config/enabled-extensions))))
