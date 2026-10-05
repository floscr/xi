(ns xi.env-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.env :as env]))

(deftest plan-sanitize-drops-orphaned-store-vars
  (testing "orphaned /nix/store vars the clean env omits are dropped"
    (let [inherited {"PATH" "/old/bin"
                     "DEPS_CLJ_TOOLS_DIR" "/nix/store/aaa-babashka/clojure_tools"
                     "JAVA_HOME" "/nix/store/bbb-openjdk"
                     "HOME" "/home/x"}
          clean     {"PATH" "/new/bin" "HOME" "/home/x"}
          {:keys [dropped path]} (env/plan-sanitize inherited clean)]
      (is (= ["DEPS_CLJ_TOOLS_DIR" "JAVA_HOME"] dropped))
      (is (= "/new/bin" path)))))

(deftest plan-sanitize-preserves-service-vars
  (testing "orphaned non-store vars (launcher/service) are kept, not dropped"
    (let [inherited {"PATH" "/bin"
                     "XI_HOST" "127.0.0.1"
                     "TMUX" "/tmp/tmux-1000/default,123,0"
                     "DEPS_CLJ_TOOLS_DIR" "/nix/store/aaa/clojure_tools"}
          clean     {"PATH" "/bin"}
          {:keys [dropped path]} (env/plan-sanitize inherited clean)]
      (is (= ["DEPS_CLJ_TOOLS_DIR"] dropped))
      (is (nil? path) "PATH unchanged -> nil"))))

(deftest plan-sanitize-preserves-store-pointing-service-vars
  (testing "launcher/service vars are kept even when they point into /nix/store"
    (let [inherited {"PATH" "/bin"
                     "XI_CHROME_BINARY" "/nix/store/abc-chromium/bin/chromium"
                     "XI_TREESITTER_DIR" "/nix/store/def-xi-treesitter"
                     "DEPS_CLJ_TOOLS_DIR" "/nix/store/aaa/clojure_tools"}
          clean     {"PATH" "/bin"}
          {:keys [dropped]} (env/plan-sanitize inherited clean)]
      (is (= ["DEPS_CLJ_TOOLS_DIR"] dropped)
          "only the non-preserved store orphan is dropped"))))

(deftest plan-sanitize-noop-when-clean-matches
  (testing "nothing to do when clean env introduces no changes"
    (let [inherited {"PATH" "/bin" "HOME" "/home/x"}
          clean     {"PATH" "/bin" "HOME" "/home/x"}
          {:keys [dropped path]} (env/plan-sanitize inherited clean)]
      (is (= [] dropped))
      (is (nil? path)))))

(deftest plan-sanitize-keeps-store-var-that-clean-still-sets
  (testing "a /nix/store var still present in the clean env is not dropped"
    (let [inherited {"PATH" "/bin"
                     "LOCALE_ARCHIVE" "/nix/store/old-glibc/locale-archive"}
          clean     {"PATH" "/bin"
                     "LOCALE_ARCHIVE" "/nix/store/new-glibc/locale-archive"}
          {:keys [dropped]} (env/plan-sanitize inherited clean)]
      (is (= [] dropped)))))
