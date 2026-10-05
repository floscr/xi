(ns xi.ext.treesitter.parse-test
  "The WASM parse layer itself. Unlike the extractor tests these do not skip when
   the runtime is missing: the runtime and grammars are vendored in the repo, so
   their absence is a failure, not a missing optional install."
  (:require [cljs.test :refer [deftest is testing async]]
            [xi.ext.treesitter.parse :as p]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(def ^:private bundled-langs
  ["bash" "clojure" "css" "go" "javascript" "nix" "python" "rust" "tsx" "typescript"])

(defn- write-tmp [name content]
  (let [path (node-path/join (os/tmpdir) name)]
    (fs/writeFileSync path content)
    path))

(defn- run-after-ready [f]
  (async done
    (-> (p/ready!)
        (.then (fn [ok]
                 (is (true? ok) "runtime loaded")
                 (when ok (f))))
        (.catch (fn [e] (is false (str e))))
        (.finally done))))

(deftest vendored-runtime-is-present
  (is (p/available?) "resources/treesitter is found")
  (doseq [l bundled-langs]
    (is (p/grammar? l) (str l ".wasm is vendored"))))

(deftest every-bundled-grammar-parses
  (run-after-ready
   (fn []
     (doseq [[lang ext src] [["bash" "sh" "f() { echo hi; }\n"]
                             ["clojure" "clj" "(defn f [x] x)\n"]
                             ["css" "css" "a { color: red }\n"]
                             ["go" "go" "package main\nfunc main() {}\n"]
                             ["javascript" "js" "function f() {}\n"]
                             ["nix" "nix" "{ a = 1; }\n"]
                             ["python" "py" "def f(x):\n  return x\n"]
                             ["rust" "rs" "fn main() {}\n"]
                             ["tsx" "tsx" "const A = () => <div/>;\n"]
                             ["typescript" "ts" "export function f(x: number) { return x }\n"]]]
       (let [root (p/parse-file-sync lang (write-tmp (str "xi-ts-parse." ext) src))]
         (is (some? root) (str lang " parses"))
         (is (pos? (alength (p/children root))) (str lang " has children")))))))

(deftest tree-shape-matches-the-native-cli-format
  (run-after-ready
   (fn []
     (let [path (write-tmp "xi-ts-shape.py" "def f(x):\n  return x\n")
           buf  (fs/readFileSync path)
           ^js root (p/parse-file-sync "python" path)
           ^js fd   (p/child-of-type root "function_definition")
           ^js name (p/child-by-field fd "name")]
       (is (= "module" (p/node-type root)))
       (is (= 0 (.-sr fd)))
       (is (= 1 (.-er fd)) "a def ends on its last body row")
       (is (= 1 (p/start-line fd)))
       (is (= 2 (p/end-line buf fd)))
       (is (= 2 (.-er root)) "the root runs on to the row after the trailing newline")
       (is (= 2 (p/end-line buf root)) "end-line steps back over that newline")
       (is (= "name" (p/field name)))
       (is (false? (p/anon? name)))
       (is (some p/anon? (array-seq (p/children fd))) "anonymous nodes (`def`, `(`) are kept")))))

(deftest byte-offsets-are-utf8-not-utf16
  ;; web-tree-sitter counts UTF-16 units; the extractors slice a UTF-8 Buffer.
  (run-after-ready
   (fn []
     (let [src  "# héllo ✓ 😀 日本語\ndef grüß(x):\n    return 'ñ' + x\n"
           path (write-tmp "xi-ts-utf8.py" src)
           buf  (fs/readFileSync path)
           root (p/parse-file-sync "python" path)
           fd   (p/child-of-type root "function_definition")]
       (is (not= (.-length buf) (count src)) "fixture really is multi-byte")
       (is (= "grüß" (p/node-text buf (p/child-by-field fd "name"))))
       (is (= "def grüß(x):\n    return 'ñ' + x" (p/node-text buf fd)))
       (is (= (.-length buf) (.-eb root)) "root covers the whole buffer in bytes")))))

(deftest parse-file-promise-api
  (async done
    (let [path (write-tmp "xi-ts-async.rs" "fn main() {}\n")]
      (-> (p/parse-file "rust" path)
          (.then (fn [root] (is (= "source_file" (p/node-type root)))))
          (.then (fn [_] (p/parse-file "rust" "/nonexistent/xi-ts.rs")))
          (.then (fn [_] (is false "a missing file rejects")))
          (.catch (fn [e] (is (some? e) "a missing file rejects")))
          (.finally done)))))

(deftest sync-parse-degrades-to-nil
  (run-after-ready
   (fn []
     (let [path (write-tmp "xi-ts-nil.py" "x = 1\n")]
       (testing "unknown language"
         (is (nil? (p/parse-file-sync "cobol" path))))
       (testing "missing file"
         (is (nil? (p/parse-file-sync "python" "/nonexistent/xi-ts.py"))))))))
