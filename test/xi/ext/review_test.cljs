(ns xi.ext.review-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.ext.review :as review]))

(def ^:private resolve-target #'review/resolve-target)
(def ^:private build-review-prompt #'review/build-review-prompt)
(def ^:private builtin-review-guidance #'review/builtin-review-guidance)
(def ^:private review-command (get-in review/extension [:commands 0 :handler]))

(deftest resolve-target-maps-args-to-diff-spec
  (testing "no args → working tree + staged vs HEAD, incl. untracked"
    (let [{:keys [diff-args desc include-untracked?]} (resolve-target nil)]
      (is (= ["diff" "HEAD" "--stat"] diff-args))
      (is (str/includes? desc "uncommitted"))
      (is (true? include-untracked?))))
  (testing "blank args behave like no args"
    (is (= ["diff" "HEAD" "--stat"] (:diff-args (resolve-target "   ")))))
  (testing "\"staged\" → cached diff"
    (let [{:keys [diff-args desc include-untracked?]} (resolve-target "staged")]
      (is (= ["diff" "--cached" "--stat"] diff-args))
      (is (str/includes? desc "staged"))
      (is (false? include-untracked?))))
  (testing "a ref → PR-style <ref>...HEAD diff"
    (let [{:keys [diff-args desc include-untracked?]} (resolve-target "main")]
      (is (= ["diff" "main...HEAD" "--stat"] diff-args))
      (is (str/includes? desc "main...HEAD"))
      (is (false? include-untracked?)))))

(deftest build-review-prompt-embeds-methodology-and-diff
  (let [prompt (build-review-prompt "staged changes vs HEAD" "src/foo.cljs | 3 +--" nil)]
    (testing "embeds the ported methodology (phases + severity labels)"
      (is (str/includes? prompt "Four-Phase Process"))
      (is (str/includes? prompt "[blocking]"))
      (is (str/includes? prompt "Severity Labels")))
    (testing "embeds the target description and the diff overview"
      (is (str/includes? prompt "staged changes vs HEAD"))
      (is (str/includes? prompt "src/foo.cljs | 3 +--")))
    (testing "omits the project-guidance section when guidance is nil"
      (is (not (str/includes? prompt "Project-Specific Review Guidance"))))))

(deftest build-review-prompt-includes-project-guidance-when-present
  (let [prompt (build-review-prompt "changes" "a.clj | 1 +"
                                    "### Clojure / ClojureScript\n- purity")]
    (testing "guidance is embedded under its own section header"
      (is (str/includes? prompt "## Project-Specific Review Guidance"))
      (is (str/includes? prompt "### Clojure / ClojureScript")))))

(deftest builtin-review-guidance-loads-by-marker-file
  (testing "a clojure marker (this repo has deps.edn/bb.edn) loads the clojure checklist"
    (let [guidance (builtin-review-guidance ".")]
      (is (some? guidance))
      (is (some #(str/includes? % "Clojure / ClojureScript") guidance))))
  (testing "a dir with no known markers loads nothing"
    (is (nil? (builtin-review-guidance "/nonexistent-xyzzy-dir")))))

(deftest review-command-is-pure-and-defers-to-effect
  (let [{:keys [effects]} (review-command {} {:room-id "r1" :args "staged"})]
    (is (= [[:review/start {:room-id "r1" :args "staged"}]] effects))))
