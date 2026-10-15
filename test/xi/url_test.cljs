(ns xi.url-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.url :as url]))

(deftest scheme-at-test
  (testing "matches http(s) schemes at the given index"
    (is (= "https://" (url/scheme-at "https://x.com" 0)))
    (is (= "http://" (url/scheme-at "see http://x.com" 4)))
    (is (nil? (url/scheme-at "https://x.com" 1)))
    (is (nil? (url/scheme-at "ftp://x.com" 0)))
    (is (nil? (url/scheme-at "nope" 0)))))

(deftest url-at-test
  (testing "extracts the URL and its exclusive end index"
    (is (= ["https://x.com" 13] (url/url-at "https://x.com" 0)))
    (is (= ["http://x.com" 16] (url/url-at "see http://x.com" 4))))

  (testing "stops at whitespace / angle-bracket boundaries"
    (is (= ["https://x.com" 13] (url/url-at "https://x.com more" 0)))
    (is (= ["https://x.com" 13] (url/url-at "https://x.com>tail" 0))))

  (testing "trims trailing sentence punctuation"
    (is (= ["https://x.com" 13] (url/url-at "https://x.com." 0)))
    (is (= ["https://x.com" 13] (url/url-at "https://x.com)" 0))))

  (testing "preserves balanced parens inside the URL"
    (let [s "https://en.wikipedia.org/wiki/Foo_(bar)"]
      (is (= [s (count s)] (url/url-at s 0)))))

  (testing "returns nil when no scheme is present"
    (is (nil? (url/url-at "just text" 0)))
    (is (nil? (url/url-at "https://" 0)))))

(deftest relative-file-link-test
  (testing "resolves against the linking file's directory"
    (is (= "/repo/docs/guide.md" (url/relative-file-link "/repo/docs/README.md" "guide.md")))
    (is (= "/repo/docs/guide.md" (url/relative-file-link "/repo/docs/README.md" "./guide.md")))
    (is (= "/repo/src/a.clj" (url/relative-file-link "/repo/docs/README.md" "../src/a.clj")))
    (is (= "docs/b.md" (url/relative-file-link "docs/a.md" "b.md")))
    (is (= "../b.md" (url/relative-file-link "a.md" "../b.md"))))
  (testing "drops fragment and query, decodes escapes"
    (is (= "/r/x y.md" (url/relative-file-link "/r/a.md" "x%20y.md#section")))
    (is (= "/r/x.md" (url/relative-file-link "/r/a.md" "x.md?plain=1"))))
  (testing "without a base, relative to wherever the caller resolves (the chat's cwd)"
    (is (= "src/a.clj" (url/relative-file-link nil "./src/a.clj"))))
  (testing "drops a :line[:col] suffix, which is not a scheme"
    (is (= "README.md" (url/relative-file-link nil "README.md:12")))
    (is (= "/r/src/a.clj" (url/relative-file-link "/r/x.md" "src/a.clj:3:7")))
    (is (= "/r/a.md" (url/relative-file-link "/r/x.md" "a.md:4#top"))))
  (testing "absolute paths stay absolute"
    (is (= "/etc/x.md" (url/relative-file-link "/r/a.md" "/etc/../etc/x.md"))))
  (testing "non-file links"
    (is (nil? (url/relative-file-link "/r/a.md" "https://example.com/x.md")))
    (is (nil? (url/relative-file-link "/r/a.md" "mailto:a@b.c")))
    (is (nil? (url/relative-file-link "/r/a.md" "tel:123")))
    (is (nil? (url/relative-file-link "/r/a.md" "//cdn.example.com/x.md")))
    (is (nil? (url/relative-file-link "/r/a.md" "#heading")))
    (is (nil? (url/relative-file-link "/r/a.md" "")))
    (is (nil? (url/relative-file-link "/r/a.md" nil)))))
