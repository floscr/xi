(ns xi.ext.treesitter.langs-test
  "End-to-end extractor tests — spawn the native xi-treesitter CLI on small
   fixture sources. Skipped (pass vacuously) when the CLI/grammars are not
   installed at ~/.config/xi/treesitter."
  (:require [cljs.test :refer [deftest is testing async]]
            [xi.ext.treesitter.parse :as p]
            [xi.ext.treesitter.langs :as langs]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(defn- with-extracted
  "Write `source`, parse as `lang`, extract entries, call (f entries done)."
  [lang ext source f]
  (async done
    (if-not (and (p/available?) (p/grammar? lang))
      (do (is true "treesitter CLI not installed — skipped") (done))
      (let [path (node-path/join (os/tmpdir) (str "xi-ts-test." ext))]
        (fs/writeFileSync path source)
        (-> (p/parse-file lang path)
            (.then (fn [root]
                     (f ((langs/extractor lang) root (fs/readFileSync path)))
                     (done)))
            (.catch (fn [e]
                      (is false (str "parse failed: " e))
                      (done))))))))

(defn- by-name [entries nm]
  (some #(when (= nm (:name %)) %) entries))

(deftest typescript-test
  (with-extracted "typescript" "ts"
    (str "import { foo } from './foo';\n"
         "export const MAX = 3;\n"
         "const load = async (id: string) => fetch(id);\n"
         "export function greet(name: string): string {\n"
         "  return name;\n"
         "}\n"
         "export interface Config {\n"
         "  host: string;\n"
         "  port: number;\n"
         "}\n"
         "export type Result = string | null;\n"
         "export class Client {\n"
         "  private config: Config;\n"
         "  async fetch(path: string): Promise<string> {\n"
         "    return path;\n"
         "  }\n"
         "}\n")
    (fn [entries]
      (testing "sections"
        (is (= [:imports] (distinct (map :section (filter #(= :imports (:section %)) entries)))))
        (is (some #(= "{ foo } from './foo'" (:text %)) entries)))
      (testing "const"
        (let [e (by-name entries "MAX")]
          (is (= :consts (:section e)))
          (is (= "export MAX = 3" (:text e)))))
      (testing "arrow fn lands in :fns"
        (let [e (by-name entries "load")]
          (is (= :fns (:section e)))))
      (testing "function with range"
        (let [e (by-name entries "greet")]
          (is (= :fns (:section e)))
          (is (= 4 (:start e)))
          (is (= 6 (:end e)))))
      (testing "interface members"
        (let [e (by-name entries "Config")]
          (is (= :types (:section e)))
          (is (some #(= "host: string" %) (:children e)))))
      (testing "class methods"
        (let [e (by-name entries "Client")]
          (is (= :classes (:section e)))
          (is (some #(and (map? %) (= "fetch" (:name %))) (:children e))))))))

(deftest python-test
  (with-extracted "python" "py"
    (str "import os\n"
         "from typing import Optional\n"
         "MAX_SIZE = 1024\n"
         "def helper(x: int) -> int:\n"
         "    return x\n"
         "@decorator\n"
         "def decorated():\n"
         "    pass\n"
         "class Worker:\n"
         "    name = 'w'\n"
         "    def run(self, task) -> None:\n"
         "        pass\n")
    (fn [entries]
      (is (some #(= "import os" (:text %)) entries))
      (is (= :consts (:section (by-name entries "MAX_SIZE"))))
      (let [e (by-name entries "helper")]
        (is (= :fns (:section e)))
        (is (= "def helper(x: int) -> int" (:text e))))
      (testing "decorated fn range includes decorator"
        (is (= 6 (:start (by-name entries "decorated")))))
      (let [e (by-name entries "Worker")]
        (is (= :classes (:section e)))
        (is (some #(and (map? %) (= "run" (:name %))) (:children e)))))))

(deftest rust-test
  (with-extracted "rust" "rs"
    (str "use std::fs;\n"
         "const MAX: usize = 10;\n"
         "pub struct Config {\n"
         "    pub host: String,\n"
         "    port: u16,\n"
         "}\n"
         "pub enum Mode { Fast, Slow }\n"
         "pub trait Runner {\n"
         "    fn run(&self) -> bool;\n"
         "}\n"
         "impl Runner for Config {\n"
         "    fn run(&self) -> bool {\n"
         "        true\n"
         "    }\n"
         "}\n"
         "pub fn main() {\n"
         "    println!(\"hi\");\n"
         "}\n")
    (fn [entries]
      (is (some #(= "std::fs" (:text %)) entries))
      (is (= :consts (:section (by-name entries "MAX"))))
      (let [e (by-name entries "Config")]
        (is (= :types (:section e)))
        (is (some #(= "pub host: String" %) (:children e))))
      (is (some #(and (= :types (:section %)) (= "Mode" (:name %))) entries))
      (is (= :traits (:section (by-name entries "Runner"))))
      (testing "impl with method"
        (let [e (some #(when (= :impls (:section %)) %) entries)]
          (is (some? e))
          (is (some #(and (map? %) (= "run" (:name %))) (:children e)))))
      (is (= "pub fn main()" (:text (by-name entries "main")))))))

(deftest go-test
  (with-extracted "go" "go"
    (str "package main\n"
         "import (\n"
         "\t\"fmt\"\n"
         "\t\"os\"\n"
         ")\n"
         "const MaxSize = 1024\n"
         "type Config struct {\n"
         "\tHost string\n"
         "\tPort int\n"
         "}\n"
         "type Runner interface {\n"
         "\tRun() error\n"
         "}\n"
         "func main() {\n"
         "\tfmt.Println(os.Args)\n"
         "}\n"
         "func (c *Config) Addr() string {\n"
         "\treturn c.Host\n"
         "}\n")
    (fn [entries]
      (is (some #(= "\"fmt\"" (:text %)) entries))
      (is (= :consts (:section (by-name entries "MaxSize"))))
      (let [e (by-name entries "Config")]
        (is (= "type Config struct" (:text e)))
        (is (some #(= "Host string" %) (:children e))))
      (is (some #(= "type Runner interface" (:text %)) entries))
      (is (= "func main()" (:text (by-name entries "main"))))
      (is (= "func (c *Config) Addr() string" (:text (by-name entries "Addr")))))))

(deftest bash-test
  (with-extracted "bash" "sh"
    (str "#!/usr/bin/env bash\n"
         "MAX_RETRIES=3\n"
         "cleanup() {\n"
         "  rm -f /tmp/x\n"
         "}\n")
    (fn [entries]
      (is (= :consts (:section (by-name entries "MAX_RETRIES"))))
      (let [e (by-name entries "cleanup")]
        (is (= :fns (:section e)))
        (is (= 3 (:start e)))
        (is (= 5 (:end e)))))))

(deftest nix-test
  (with-extracted "nix" "nix"
    (str "{ pkgs, lib, ... }:\n"
         "let\n"
         "  version = \"1.0\";\n"
         "in {\n"
         "  home.packages = [ pkgs.hello ];\n"
         "  programs.git = {\n"
         "    enable = true;\n"
         "    userName = \"me\";\n"
         "  };\n"
         "}\n")
    (fn [entries]
      (is (some #(= "version" (:name %)) entries))
      (is (some #(= "home.packages" (:name %)) entries))
      (let [e (by-name entries "programs.git")]
        (is (some? e))
        (is (some #(= "enable" %) (:children e)))))))

(deftest clojure-test
  (with-extracted "clojure" "cljc"
    (str "(ns xi.sample\n"
         "  (:require [clojure.string :as str]\n"
         "            [xi.util :as util]))\n"
         "\n"
         "(def max-size 1024)\n"
         "\n"
         "(defonce cache (atom {}))\n"
         "\n"
         "(defn- helper\n"
         "  \"doc\"\n"
         "  [x]\n"
         "  (* x 2))\n"
         "\n"
         "(defn greet\n"
         "  ([name] (greet name \"!\"))\n"
         "  ([name suffix] (str name suffix)))\n"
         "\n"
         "(def ^:private secret 1)\n"
         "\n"
         "(defmulti render :type)\n"
         "(defmethod render :text [m] (:text m))\n"
         "\n"
         "(defprotocol Renderer\n"
         "  (render-it [this opts]))\n"
         "\n"
         "(defrecord Box [w h]\n"
         "  Renderer\n"
         "  (render-it [this opts] nil))\n"
         "\n"
         "(defmacro with-thing [& body]\n"
         "  `(do ~@body))\n"
         "\n"
         "#?(:node\n"
         "   (defn node-only [a] a)\n"
         "   :browser\n"
         "   (defn browser-only [b] b))\n")
    (fn [entries]
      (testing "ns + requires"
        (is (= "(ns xi.sample)" (:text (by-name entries "xi.sample"))))
        (is (some #(and (= :imports (:section %))
                        (= "[clojure.string :as str]" (:text %)))
                  entries)))
      (testing "def / defonce / meta name"
        (is (= :consts (:section (by-name entries "max-size"))))
        (is (= :consts (:section (by-name entries "cache"))))
        (is (= :consts (:section (by-name entries "secret")))))
      (testing "defn- with docstring"
        (let [e (by-name entries "helper")]
          (is (= :fns (:section e)))
          (is (= "(defn- helper [x])" (:text e)))
          (is (= 9 (:start e)))
          (is (= 12 (:end e)))))
      (testing "multi-arity"
        (is (= "(defn greet [name] [name suffix])"
               (:text (by-name entries "greet")))))
      (testing "defmethod is addressable with its dispatch value"
        (is (= "(defmethod render :text [m])"
               (:text (by-name entries "render :text")))))
      (testing "protocol methods"
        (let [e (by-name entries "Renderer")]
          (is (= :types (:section e)))
          (is (some #(and (map? %) (= "render-it" (:name %))) (:children e)))))
      (testing "record fields + methods"
        (let [e (by-name entries "Box")]
          (is (= "(defrecord Box [w h])" (:text e)))
          (is (some #(and (map? %) (= "render-it" (:name %))) (:children e)))))
      (testing "defmacro"
        (is (= :macros (:section (by-name entries "with-thing")))))
      (testing "reader conditional branches"
        (is (= "(defn node-only [a])" (:text (by-name entries "node-only"))))
        (is (= "(defn browser-only [b])" (:text (by-name entries "browser-only"))))))))

(deftest css-test
  (with-extracted "css" "css"
    (str "@import url(\"base.css\");\n"
         "\n"
         ":root {\n"
         "  --accent: #f00;\n"
         "  --gap: 8px;\n"
         "}\n"
         "\n"
         ".button, .button:hover {\n"
         "  color: var(--accent);\n"
         "}\n"
         "\n"
         "#main > .content .title {\n"
         "  font-size: 2rem;\n"
         "}\n"
         "\n"
         "@media (max-width: 600px) {\n"
         "  .button { display: none; }\n"
         "}\n"
         "\n"
         "@keyframes spin {\n"
         "  from { transform: rotate(0); }\n"
         "  to { transform: rotate(360deg); }\n"
         "}\n"
         "\n"
         "@font-face {\n"
         "  font-family: \"Foo\";\n"
         "}\n")
    (fn [entries]
      (testing "import"
        (is (some #(and (= :imports (:section %))
                        (= "@import url(\"base.css\")" (:text %)))
                  entries)))
      (testing "custom properties listed under their rule"
        (let [e (by-name entries ":root")]
          (is (= :rules (:section e)))
          (is (= 3 (:start e)))
          (is (= 6 (:end e)))
          (is (some #(= "--accent: #f00" %) (:children e)))))
      (testing "selector lists and nesting"
        (is (some? (by-name entries ".button, .button:hover")))
        (is (some? (by-name entries "#main > .content .title"))))
      (testing "media query with nested selectors"
        (let [e (some #(when (= "@media (max-width: 600px)" (:text %)) %) entries)]
          (is (some? e))
          (is (some #(and (map? %) (= ".button" (:name %))) (:children e)))))
      (testing "keyframes"
        (is (= "@keyframes spin" (:text (by-name entries "spin")))))
      (testing "at-rule"
        (is (some #(= "@font-face" (:text %)) entries))))))
