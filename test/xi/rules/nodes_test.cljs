(ns xi.rules.nodes-test
  (:require [cljs.test :refer [deftest is async]]
            [xi.rules.nodes :as nodes]
            [xi.ext.treesitter.parse :as p]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(defn- tmp-file [name content]
  (let [path (node-path/join (os/tmpdir)
                             (str (.getTime (js/Date.)) "-" (rand-int 1000000) "-" name))]
    (fs/writeFileSync path content)
    path))

(defn- with-runtime
  "Run (f) once the WASM runtime is loaded — nodes-for is synchronous and only
   sees a runtime that has finished loading."
  [f]
  (async done
    (if-not (p/available?)
      (do (is true "skipped") (done))
      (-> (p/ready!)
          (.then (fn [_] (f)))
          (.catch (fn [e] (is false (str e))))
          (.finally done)))))

(deftest write-nodes-top-level-defs
  ;; :write consults the new content's top-level defs (descending into the
  ;; unnamed export wrapper to reach the function_declaration).
  (with-runtime
    (fn []
      (let [path (node-path/join (os/tmpdir) "xi-nodes-write.ts")
            ns   (nodes/nodes-for
                  {:tool :write :path path :effective-cwd (os/tmpdir)
                   :arguments {:content (str "export function fn0(a) { return a; }\n"
                                             "class Foo { bar() { return 1; } }\n")}})]
        (is (some #(and (= "function_declaration" (:type %)) (= "fn0" (:name %))) ns))
        (is (some #(and (= "class_declaration" (:type %)) (= "Foo" (:name %))) ns))))))

(deftest edit-nodes-enclosing-def
  ;; :edit consults the enclosing named node(s) of the edited region.
  (with-runtime
    (fn []
      (let [path (tmp-file "xi-nodes-edit.ts"
                           "export function fn0(a) {\n  return a + 1;\n}\n")
            ns   (nodes/nodes-for
                  {:tool :edit :path path :effective-cwd (os/tmpdir)
                   :arguments {:edits [{:oldText "return a + 1;" :newText "return a + 2;"}]}})]
        (fs/unlinkSync path)
        (is (some #(and (= "function_declaration" (:type %)) (= "fn0" (:name %))) ns))))))

(deftest nodes-for-unsupported-language-nil
  ;; No grammar for .txt → nil, so a :node rule never matches.
  (is (nil? (nodes/nodes-for {:tool :write :path "/tmp/xi-nodes.txt"
                              :effective-cwd (os/tmpdir)
                              :arguments {:content "hello"}}))))
