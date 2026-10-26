(ns xi.rules.migrate-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.rules.migrate :as migrate]
            [xi.rules.store :as store]))

(defn- file [rules]
  (str "{:type :xi/rules\n :version 1\n :rules\n " rules "}\n"))

(deftest migrate-data-renames-the-bb-tool
  (let [{:keys [data changes]}
        (migrate/migrate-data
         {:type :xi/rules :version 1
          :rules [{:match {:tool :bb :task {"bb" "test"}} :action {:type :allow}}
                  {:match {:tool #{:sh :bb}} :action {:type :ask}}
                  {:on-block {:tool-name "bb" :bb-trusted false} :do {:type :deny}}
                  {:match {:tool :read} :action {:type :allow}}]
          :defaults [:xi.rules.defaults/everything
                     {:match {:tool #{:bb :task}} :action {:type :ask}}]})]
    (is (= [{:match {:tool :task :task {"bb" "test"}} :action {:type :allow}}
            {:match {:tool #{:sh :task}} :action {:type :ask}}
            {:on-block {:tool-name "run_task" :task-file-trusted false} :do {:type :deny}}
            {:match {:tool :read} :action {:type :allow}}]
           (:rules data)))
    (is (= [:xi.rules.defaults/everything {:match {:tool #{:task}} :action {:type :ask}}]
           (:defaults data)))
    (is (= ["rules #1: :tool :bb → :task"
            "rules #2: :tool :bb → :task"
            "rules #3: :tool-name \"bb\" → \"run_task\""
            "rules #3: :bb-trusted → :task-file-trusted"
            "defaults #2: :tool :bb → :task"]
           changes))))

(deftest a-current-file-has-no-changes
  (is (empty? (migrate/changes {:type :xi/rules :version 1
                                :rules [{:match {:tool :task :task-file-trusted true}
                                         :action {:type :allow}}]})))
  (is (nil? (migrate/migrate-text (file "[{:match {:tool :task} :action {:type :allow}}]")
                                  store/read-rule-edn))))

(deftest migrate-text-keeps-comments-and-layout
  (let [text (file (str ";; tasks are fine to run\n"
                        " [{:match  {:tool #{:bb :task} ; both runners\n"
                        "            :bb-trusted true\n"
                        "            :command #\"\\btest\\b\"}\n"
                        "   :action {:type :allow}}\n"
                        "  {:match {:tool-name #{\"bb\" \"read\"}} :action {:type :ask}}]"))
        {new-text :text :keys [changes error]} (migrate/migrate-text text store/read-rule-edn)]
    (is (nil? error))
    (is (= 3 (count changes)))
    (is (str/includes? new-text ";; tasks are fine to run"))
    (is (str/includes? new-text "; both runners"))
    (is (str/includes? new-text ":task-file-trusted true"))
    (is (not (re-find #":bb(?![\w-])" new-text)))
    (is (empty? (migrate/changes (store/read-rule-edn new-text))))
    (testing "the rewritten file loads"
      (is (nil? (:error (store/parse-rules-config (store/read-rule-edn new-text))))))))

(deftest the-store-refuses-a-file-with-old-names
  (let [{:keys [error]} (store/parse-rules-config
                         (store/read-rule-edn
                          (file "[{:match {:bb-trusted false} :action {:type :allow}}]")))]
    (is (str/includes? error ":bb-trusted → :task-file-trusted"))
    (is (str/includes? error "xi migrate"))))
