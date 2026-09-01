(ns xi.ext.treesitter.core-test
  "Tool-gate + read_source behavior. Skipped when the native CLI is absent."
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.ext.treesitter.core :as core]
            [xi.ext.treesitter.parse :as p]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(defn- big-ts-source []
  ;; Bodies must be big enough that the outline is a real saving (<50% of the
  ;; file) — the gate deliberately passes through when it isn't.
  (apply str
         "import { x } from './x';\n"
         (for [i (range 40)]
           (str "export function fn" i "(a: number): number {\n"
                "  const x0 = a + " i ";\n"
                "  const x1 = x0 * 2;\n"
                "  const x2 = x1 + 1;\n"
                "  const x3 = x2 * 2;\n"
                "  const x4 = x3 + 1;\n"
                "  return x4 + " i ";\n"
                "}\n"))))

(defn- write-tmp [name content]
  (let [path (node-path/join (os/tmpdir) name)]
    (fs/writeFileSync path content)
    path))

(defn- gate-result-text [gated]
  (-> gated :result :content first :text))

(deftest gate-intercepts-large-source-test
  (async done
    (if-not (p/available?)
      (do (is true "skipped") (done))
      (let [ext (core/create nil)
            gate (:tool-gate ext)
            path (write-tmp "xi-ts-core-test.ts" (big-ts-source))]
        (-> (js/Promise.resolve (gate {:name "read" :arguments {:path path}} {:cwd nil}))
            (.then (fn [gated]
                     (testing "large source file read → outline"
                       (is (:intercepted gated))
                       (let [text (gate-result-text gated)]
                         (is (str/includes? text "fns:"))
                         (is (str/includes? text "export function fn0(a: number): number [2-9]"))
                         (is (str/includes? text "read_source"))))
                     (done)))
            (.catch (fn [e] (is false (str e)) (done))))))))

(deftest gate-passes-through-test
  (async done
    (if-not (p/available?)
      (do (is true "skipped") (done))
      (let [ext (core/create nil)
            gate (:tool-gate ext)
            big (write-tmp "xi-ts-core-test.ts" (big-ts-source))
            small (write-tmp "xi-ts-core-small.ts" "export const x = 1;\n")
            md (write-tmp "xi-ts-core-test.md" (apply str (repeat 300 "line\n")))
            offset-call {:name "read" :arguments {:path big :offset 10 :limit 5}}
            small-call {:name "read" :arguments {:path small}}
            md-call {:name "read" :arguments {:path md}}]
        (-> (js/Promise.all
             #js [(js/Promise.resolve (gate offset-call {:cwd nil}))
                  (js/Promise.resolve (gate small-call {:cwd nil}))
                  (js/Promise.resolve (gate md-call {:cwd nil}))])
            (.then (fn [[o s m]]
                     (testing "offset/limit reads pass through"
                       (is (= offset-call o)))
                     (testing "small files pass through"
                       (is (= small-call s)))
                     (testing "unsupported extensions pass through"
                       (is (= md-call m)))
                     (done)))
            (.catch (fn [e] (is false (str e)) (done))))))))

(deftest read-source-test
  (async done
    (if-not (p/available?)
      (do (is true "skipped") (done))
      (let [ext (core/create nil)
            read-source (get-in ext [:tool-registry "read_source"])
            path (write-tmp "xi-ts-core-test.ts" (big-ts-source))]
        (-> (js/Promise.resolve (read-source {:path path :symbol "fn3"} {:cwd nil}))
            (.then (fn [result]
                     (let [text (-> result :content first :text)]
                       (testing "symbol mode returns the literal definition"
                         (is (str/includes? text "lines 26-33"))
                         (is (str/includes? text "return x4 + 3;"))))
                     (js/Promise.resolve (read-source {:path path :symbol "nope"} {:cwd nil}))))
            (.then (fn [result]
                     (testing "unknown symbol errors with the available names"
                       (is (:is-error result))
                       (is (str/includes? (-> result :content first :text) "fn0")))
                     (js/Promise.resolve (read-source {:path path :start_line 2 :end_line 5} {:cwd nil}))))
            (.then (fn [result]
                     (testing "line-range mode"
                       (let [text (-> result :content first :text)]
                         (is (str/includes? text "export function fn0"))
                         (is (str/includes? text "lines 2-5"))))
                     (done)))
            (.catch (fn [e] (is false (str e)) (done))))))))
