(ns xi.ext.treesitter.core-test
  "read override + read_source behavior. Skipped when the native CLI is absent."
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

(defn- result-text [result]
  (-> result :content first :text))

(deftest read-outlines-large-source-test
  (async done
    (if-not (p/available?)
      (do (is true "skipped") (done))
      (let [ext  (core/create nil)
            read (get-in ext [:tool-registry "read"])
            path (write-tmp "xi-ts-core-test.ts" (big-ts-source))]
        (-> (js/Promise.resolve (read {:path path} {:cwd nil}))
            (.then (fn [result]
                     (testing "large source file read → outline"
                       (let [text (result-text result)]
                         (is (str/includes? text "fns:"))
                         (is (str/includes? text "export function fn0(a: number): number [2-9]"))
                         (is (str/includes? text "read_source"))))
                     (done)))
            (.catch (fn [e] (is false (str e)) (done))))))))

(deftest read-falls-through-to-builtin-test
  (async done
    (if-not (p/available?)
      (do (is true "skipped") (done))
      (let [ext   (core/create nil)
            read  (get-in ext [:tool-registry "read"])
            big   (write-tmp "xi-ts-core-test.ts" (big-ts-source))
            small (write-tmp "xi-ts-core-small.ts" "export const x = 1;\n")
            md    (write-tmp "xi-ts-core-test.md" (apply str (repeat 300 "line\n")))]
        (-> (js/Promise.all
             #js [(js/Promise.resolve (read {:path big :offset 10 :limit 5} {:cwd nil}))
                  (js/Promise.resolve (read {:path small} {:cwd nil}))
                  (js/Promise.resolve (read {:path md} {:cwd nil}))])
            (.then (fn [[o s m]]
                     (testing "offset/limit reads get literal lines, not the outline"
                       (is (not (str/includes? (result-text o) "Structural outline")))
                       (is (str/includes? (result-text o) "const x")))
                     (testing "small files are read whole"
                       (is (str/includes? (result-text s) "export const x = 1;")))
                     (testing "unsupported extensions are read whole"
                       (is (not (str/includes? (result-text m) "Structural outline")))
                       (is (str/includes? (result-text m) "line")))
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
