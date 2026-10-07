(ns xi.rules.curl-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.rules.curl :as curl]))

(deftest hosts
  (testing "every URL's host, lower-cased; ports, paths and queries dropped"
    (is (= #{"localhost"} (curl/hosts ["curl" "http://localhost:8199/a?x=1"])))
    (is (= #{"100.64.0.2" "localhost"}
           (curl/hosts ["curl" "-sS" "http://100.64.0.2:7474/" "HTTPS://LocalHost"])))
    (is (= #{"[::1]"} (curl/hosts ["curl" "--max-time" "5" "http://[::1]:8080"]))))
  (testing "allowlisted flags, value flags with a valid value"
    (is (= #{"h"} (curl/hosts ["curl" "-fsSL" "-X" "POST" "-m" "3" "-o" "/dev/null" "http://h/"])))
    (is (= #{"h"} (curl/hosts ["curl" "--request" "DELETE" "--output" "/dev/null" "http://h"]))))
  (testing "nil: not a read-only request to at least one URL"
    (doseq [argv [["curl"]
                  ["curl" "-s"]
                  ["wget" "http://h/"]
                  ["curl" "-o" "out.txt" "http://h/"]
                  ["curl" "-m" "http://h/"]
                  ["curl" "-X" "TRACE" "http://h/"]
                  ["curl" "-O" "http://h/x"]
                  ["curl" "-H" "X: y" "http://h/"]
                  ["curl" "-d" "@f" "http://h/"]
                  ["curl" "h:8080"]
                  ["curl" "ftp://h/"]
                  ["curl" "http://localhost@evil.com/"]
                  ["curl" "http://{localhost,evil.com}/"]
                  ["curl" "http://evil.com\\@localhost/"]]]
      (is (nil? (curl/hosts argv)) (pr-str argv)))))

(deftest request-hosts
  (testing "a literal :sh argv"
    (is (= #{"h"} (curl/request-hosts {:tool :sh :cli "curl" :argv ["curl" "http://h/?a=1&b=$c"]}))))
  (testing "a background command string, only without shell syntax"
    (is (= #{"h"} (curl/request-hosts {:tool :sh :cli "curl" :command "curl  -s http://h/x?a=1"})))
    (is (nil? (curl/request-hosts {:tool :sh :cli "curl" :command "curl http://h/$(id)"})))
    (is (nil? (curl/request-hosts {:tool :sh :cli "curl" :command "curl 'http://h/'"}))))
  (testing "only :sh calls to curl"
    (is (nil? (curl/request-hosts {:tool :bash :cli "curl" :command "curl http://h/"})))
    (is (nil? (curl/request-hosts {:tool :sh :cli "wget" :command "curl http://h/"})))))
