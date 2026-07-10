(ns xi.tui.path-complete-test
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [xi.tui.path-complete :as pc]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def ^:private tmp (atom nil))

(defn- mk [& segs]
  (apply path/join @tmp segs))

(use-fixtures :once
  {:before
   (fn []
     (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "pc-test-"))]
       (reset! tmp dir)
       (fs/mkdirSync (mk "src"))
       (fs/mkdirSync (mk "src" "core"))
       (fs/mkdirSync (mk "src" "commands"))
       (fs/writeFileSync (mk "src" "cli.cljs") "")
       (fs/writeFileSync (mk "README.md") "")
       (fs/writeFileSync (mk ".hidden") "")))
   :after
   (fn [] (fs/rmSync @tmp #js {:recursive true :force true}))})

(deftest empty-and-nil
  (testing "blank/nil token completes nothing"
    (is (nil? (pc/complete "" @tmp)))
    (is (nil? (pc/complete nil @tmp)))))

(deftest single-match-inline
  (testing "unique prefix completes the remaining chars"
    (is (= {:action :insert :text "EADME.md"} (pc/complete "R" @tmp))))
  (testing "unique dir gets a trailing slash"
    ;; only entry starting with 's' is the src/ directory
    (is (= {:action :insert :text "rc/"} (pc/complete "s" @tmp)))))

(deftest common-prefix-extension
  (testing "multiple matches sharing a longer prefix extend inline"
    ;; src/com -> commands/ is the only match past the shared 'com'
    (is (= {:action :insert :text "mands/"} (pc/complete "src/com" @tmp))))
  (testing "matches with no shared prefix past the base open a menu"
    ;; src/c -> cli.cljs, commands/, core/ share only 'c' (== base)
    (is (= {:action :menu
            :items [{:label "cli.cljs" :insert "li.cljs"}
                    {:label "commands/" :insert "ommands/"}
                    {:label "core/" :insert "ore/"}]}
           (pc/complete "src/c" @tmp)))))

(deftest directory-listing-extends-shared-prefix
  (testing "listing a dir extends by the entries' shared prefix first"
    ;; src/ -> cli.cljs, commands/, core/ all start with 'c' (shell-style)
    (is (= {:action :insert :text "c"} (pc/complete "src/" @tmp)))))

(deftest hidden-files-excluded
  (testing "dotfiles are hidden when the base does not start with '.'"
    ;; base 'R' must not surface .hidden; only README.md matches
    (is (= {:action :insert :text "EADME.md"} (pc/complete "R" @tmp)))))

(deftest hidden-visible-with-dot
  (testing "a leading dot reveals dotfiles alongside the ./ and ../ relative dirs"
    (is (= {:action :menu
            :items [{:label "../" :insert "./"}
                    {:label "./" :insert "/"}
                    {:label ".hidden" :insert "hidden"}]}
           (pc/complete "." @tmp)))))

(deftest relative-dir-expansion
  (testing "a bare '..' expands to '../'"
    (is (= {:action :insert :text "/"} (pc/complete ".." @tmp))))
  (testing "a nested '..' segment expands to '../'"
    (is (= {:action :insert :text "/"} (pc/complete "src/.." @tmp))))
  (testing "a trailing-slash relative path lists the target dir"
    ;; src/../ resolves back to tmp, listing README.md and src/
    (is (= {:action :menu
            :items [{:label "README.md" :insert "README.md"}
                    {:label "src/" :insert "src/"}]}
           (pc/complete "src/../" @tmp)))))

(deftest no-match
  (testing "no filesystem match completes nothing"
    (is (nil? (pc/complete "zzz" @tmp)))))
