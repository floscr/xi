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
