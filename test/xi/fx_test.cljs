(ns xi.fx-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.fx :as fx]))

(defn- edit-call [path]
  {:kind :tool-call :tool "edit" :arguments {:path path}})

(deftest session-edited-files-scopes-to-cwd
  (testing "keeps files inside cwd, relative to cwd"
    (let [room {:history [(edit-call "/repo/src/a.cljs")
                          (edit-call "/repo/src/nested/b.cljs")]}]
      (is (= ["src/a.cljs" "src/nested/b.cljs"]
             (fx/session-edited-files room "/repo")))))

  (testing "drops files written outside cwd"
    (let [room {:history [(edit-call "/repo/src/a.cljs")
                          (edit-call "/tmp/bench5.clj")]}]
      (is (= ["src/a.cljs"]
             (fx/session-edited-files room "/repo")))))

  (testing "de-duplicates repeated edits to the same file"
    (let [room {:history [(edit-call "/repo/src/a.cljs")
                          (edit-call "/repo/src/a.cljs")]}]
      (is (= ["src/a.cljs"]
             (fx/session-edited-files room "/repo"))))))
