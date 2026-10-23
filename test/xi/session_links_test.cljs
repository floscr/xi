(ns xi.session-links-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.session-links :as sl]))

(deftest text-urls-test
  (is (= ["https://a.com/x" "http://b.org"]
         (sl/text-urls "see https://a.com/x, and (http://b.org).")))
  (is (= [] (sl/text-urls nil)))
  (is (= [] (sl/text-urls "no links here"))))

(deftest text-files-test
  (testing "inline code paths, line suffix dropped"
    (is (= ["src/xi/foo.cljs" "README.md"]
           (sl/text-files "Changed `src/xi/foo.cljs:12` and `README.md:3`."))))
  (testing "dotted names and urls in code are not files"
    (is (= [] (sl/text-files "`clojure.string` `str/join` `https://x.com/a.md`"))))
  (testing "markdown links to files, not to the web"
    (is (= ["docs/guide/keyboard.md"]
           (sl/text-files "[keys](docs/guide/keyboard.md) [site](https://x.com)")))))

(deftest code-path-test
  (is (= {:path "src/xi/foo.cljs" :line 12} (sl/code-path "src/xi/foo.cljs:12")))
  (is (= {:path "/abs/a.md" :line nil} (sl/code-path "/abs/a.md")))
  (is (= {:path "README.md" :line 3} (sl/code-path "README.md:3")))
  (is (= {:path "~/.config/xi/rules.edn" :line nil} (sl/code-path "~/.config/xi/rules.edn"))
      "a home-relative path stays as written; the server expands it")
  (testing "a bare file name, a dotted symbol or a sentence is not a path"
    (is (nil? (sl/code-path "README.md")))
    (is (nil? (sl/code-path "clojure.string")))
    (is (nil? (sl/code-path "see src/a.cljs now")))
    (is (nil? (sl/code-path nil)))))

(deftest links-test
  (let [history [{:kind :user :text "Look at https://old.example and `src/a.cljs`"}
                 {:kind :tool-call :tool "mcp__xi-tools__read"
                  :arguments {:path "/repo/src/a.cljs"}}
                 {:kind :tool-call :tool "ls" :arguments {:path "/repo/src"}}
                 {:kind :tool-call :tool "mcp__xi-tools__edit"
                  :arguments {:path "/repo/Makefile"}}
                 {:kind :tool-call :tool "fetch"
                  :arguments {:url "https://new.example/page"}}
                 {:kind :thinking :text "https://ignored.example"}
                 {:kind :text :text "Done, see `/elsewhere/b.md`."}]
        {:keys [urls files]} (sl/links history "/repo")]
    (testing "newest first, distinct, thinking skipped"
      (is (= ["https://new.example/page" "https://old.example"] urls)))
    (testing "relative under cwd; dirs skipped; edit tools need no extension"
      (is (= ["/elsewhere/b.md" "Makefile" "src/a.cljs"] files)))))
