(ns xi.ext.extensions-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.ext.extensions :as extensions]
            [xi.ext.manager :as manager]))

(defn- make [reload!]
  (extensions/create {:manager (manager/create) :reload! reload!}))

(defn- call-tool [ext]
  ((get-in ext [:tool-registry "ext_reload"]) {} {}))

(deftest no-manager-no-extension
  (is (nil? (extensions/create {}))))

(deftest ext-reload-is-an-agent-tool
  (let [ext (make (fn [] {:loaded []}))]
    (is (= ["ext_reload"] (map :name (:tool-definitions ext))))
    (is (fn? (get-in ext [:tool-registry "ext_reload"])))))

(deftest ext-reload-reports-loaded-extensions
  (let [ext    (make (fn [] {:loaded [:notes :claude-swap] :rejected [] :skipped ["x.cljs"]}))
        result (call-tool ext)
        text   (get-in result [:content 0 :text])]
    (is (false? (:is-error result)))
    (is (str/includes? text "loaded: notes, claude-swap"))
    (is (str/includes? text "not enabled"))
    (is (str/includes? text "x.cljs"))))

(deftest ext-reload-surfaces-eval-errors
  (testing "a rejected file is an error result carrying the reason"
    (let [ext    (make (fn [] {:loaded [] :rejected [{:file "/e/notes.cljs" :error "eval error: boom"}]}))
          result (call-tool ext)]
      (is (true? (:is-error result)))
      (is (str/includes? (get-in result [:content 0 :text]) "notes.cljs — eval error: boom")))))
