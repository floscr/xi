(ns xi.ext.treesitter.skeleton-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.ext.treesitter.skeleton :as skeleton]))

(def entries
  [{:section :imports :text "{ foo } from './foo'" :start 1 :end 1}
   {:section :imports :text "type { User } from './types'" :start 2 :end 2}
   {:section :consts :text "MAX_RETRIES = 3" :name "MAX_RETRIES" :start 4 :end 4}
   {:section :fns :text "function greet(name: string): string" :name "greet" :start 13 :end 15}
   {:section :classes :text "class Client" :name "Client" :start 17 :end 27
    :children [{:text "constructor(config: Config)" :name "constructor" :start 20 :end 22}
               {:text "async fetch(path: string)" :name "fetch" :start 24 :end 26}
               "id: string"]}])

(deftest format-skeleton-test
  (let [out (skeleton/format-skeleton entries)]
    (testing "imports collapse under one ranged header"
      (is (str/includes? out "imports: [1-2]"))
      (is (str/includes? out "  { foo } from './foo'")))
    (testing "entries carry ranges"
      (is (str/includes? out "  MAX_RETRIES = 3 [4]"))
      (is (str/includes? out "  function greet(name: string): string [13-15]")))
    (testing "children are indented, ranged when known"
      (is (str/includes? out "    async fetch(path: string) [24-26]"))
      (is (str/includes? out "    id: string")))
    (testing "sections come in order"
      (is (< (str/index-of out "imports:")
             (str/index-of out "consts:")
             (str/index-of out "fns:")
             (str/index-of out "classes:"))))))

(deftest format-skeleton-empty-test
  (is (nil? (skeleton/format-skeleton []))))

(deftest format-range-test
  (is (= "[3]" (skeleton/format-range 3 3)))
  (is (= "[3-9]" (skeleton/format-range 3 9))))

(deftest symbols-test
  (let [syms (skeleton/symbols entries)]
    (testing "top-level names"
      (is (= 13 (get-in syms ["greet" :start])))
      (is (= 27 (get-in syms ["Client" :end]))))
    (testing "members are addressable qualified and bare"
      (is (= 24 (get-in syms ["Client.fetch" :start])))
      (is (= 24 (get-in syms ["fetch" :start]))))
    (testing "string children are not symbols"
      (is (not (contains? syms "id: string"))))))
