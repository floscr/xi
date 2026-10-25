(ns xi.server.static-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.server.static :as static]))

(deftest etag-test
  (testing "one tag per file version, another for its gzipped form"
    (is (= "W/\"ff-1\"" (static/etag 255 1 false)))
    (is (= "W/\"ff-1-gz\"" (static/etag 255 1 true)))
    (is (not= (static/etag 255 1 false) (static/etag 256 1 false)))
    (is (not= (static/etag 255 1 false) (static/etag 255 2 false)))))

(deftest not-modified-test
  (let [tag   (static/etag 10 1700000000000 true)
        mtime 1700000000000]
    (testing "If-None-Match: the same tag, in a list, strong or weak, or *"
      (is (static/not-modified? {:if-none-match tag} tag mtime))
      (is (static/not-modified? {:if-none-match (str "\"x\", " tag)} tag mtime))
      (is (static/not-modified? {:if-none-match (subs tag 2)} tag mtime))
      (is (static/not-modified? {:if-none-match "*"} tag mtime))
      (is (not (static/not-modified? {:if-none-match (static/etag 10 1700000000000 false)} tag mtime)))
      (is (not (static/not-modified? {:if-none-match (static/etag 11 1700000000000 true)} tag mtime))))
    (testing "If-None-Match wins over If-Modified-Since"
      (is (not (static/not-modified? {:if-none-match "\"old\""
                                      :if-modified-since (.toUTCString (js/Date. mtime))}
                                     tag mtime))))
    (testing "If-Modified-Since at one-second resolution"
      (is (static/not-modified? {:if-modified-since (.toUTCString (js/Date. mtime))} tag (+ mtime 999)))
      (is (static/not-modified? {:if-modified-since (.toUTCString (js/Date. (+ mtime 60000)))} tag mtime))
      (is (not (static/not-modified? {:if-modified-since (.toUTCString (js/Date. (- mtime 1000)))} tag mtime)))
      (is (not (static/not-modified? {:if-modified-since "not a date"} tag mtime))))
    (testing "no validators: send the file"
      (is (not (static/not-modified? {} tag mtime))))))

(deftest accepts-gzip-test
  (is (static/accepts-gzip? "gzip, deflate, br"))
  (is (static/accepts-gzip? "br;q=1.0, GZIP;q=0.8"))
  (is (not (static/accepts-gzip? "gzip;q=0, br")))
  (is (not (static/accepts-gzip? "br")))
  (is (not (static/accepts-gzip? nil))))

(deftest compressible-test
  (testing "text assets above the floor"
    (is (static/compressible? "/pub/js/main.js" 100000))
    (is (static/compressible? "/pub/css/style.CSS" 5000))
    (is (static/compressible? "/pub/index.html" 2000)))
  (testing "already compressed or tiny"
    (is (not (static/compressible? "/pub/apple-touch-icon.png" 100000)))
    (is (not (static/compressible? "/pub/js/main.js" 100)))
    (is (not (static/compressible? "/pub/noext" 100000)))))
