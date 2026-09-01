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
