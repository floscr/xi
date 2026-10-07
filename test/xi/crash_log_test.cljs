(ns xi.crash-log-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.crash-log :as crash-log]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(defn- with-log-file [f]
  (let [dir  (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-crash-log-"))
        file (node-path/join dir "nested" "crash.log")]
    (crash-log/set-file! file)
    (try (f file)
         (finally
           (crash-log/set-file! nil)
           (fs/rmSync dir #js {:recursive true :force true})))))

(deftest record-appends-label-and-stack
  (with-log-file
    (fn [file]
      (let [stack (crash-log/record! "tui input" (js/Error. "boom"))]
        (is (str/includes? stack "boom"))
        (crash-log/record! "tui resize" "just a string")
        (let [text (fs/readFileSync file "utf8")]
          (is (str/includes? text "tui input"))
          (is (str/includes? text "boom"))
          (is (str/includes? text "tui resize"))
          (is (str/includes? text "just a string") "anything can be thrown"))))))

(deftest record-never-throws
  (testing "an unwritable log file is not a second crash"
    (let [dir  (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-crash-log-"))
          blocker (node-path/join dir "a-file")]
      (fs/writeFileSync blocker "x")
      ;; a directory can't be made under a regular file (ENOTDIR)
      (crash-log/set-file! (node-path/join blocker "sub" "crash.log"))
      (try (is (str/includes? (crash-log/record! "x" (js/Error. "e")) "e"))
           (finally
             (crash-log/set-file! nil)
             (fs/rmSync dir #js {:recursive true :force true}))))))

(deftest guarded-swallows-and-records
  (with-log-file
    (fn [file]
      (let [seen (atom [])
            f    (crash-log/guarded "tui input"
                                    (fn [x] (swap! seen conj x)
                                      (when (= x :bad) (throw (js/Error. "bad key")))
                                      x))]
        (is (= :ok (f :ok)) "a good call returns its value")
        (is (nil? (f :bad)) "a throwing call returns nil instead of escaping")
        (is (= :after (f :after)) "and the wrapper keeps working")
        (is (= [:ok :bad :after] @seen))
        (is (str/includes? (fs/readFileSync file "utf8") "bad key"))))))
