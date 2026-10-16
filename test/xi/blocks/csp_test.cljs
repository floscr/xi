(ns xi.blocks.csp-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.blocks.csp :as csp]))

(deftest default-policy-allows-nothing-external
  (let [p (csp/csp nil)]
    (is (str/includes? p "default-src 'none'"))
    (is (str/includes? p "connect-src 'none'"))
    (is (str/includes? p "frame-src 'none'"))
    (is (str/includes? p "base-uri 'self'"))
    (is (str/includes? p "object-src 'none'"))
    (is (not (str/includes? p "https://")))))

(deftest declared-domains-only-and-nothing-smuggled
  (let [p (csp/csp {:connectDomains ["https://api.example.com" "wss://rt.example.com"]
                    :resourceDomains ["https://*.cdn.example.com" "'unsafe-eval'" "https://x.com; script-src *"]
                    :frameDomains ["javascript:alert(1)"]})]
    (is (str/includes? p "connect-src https://api.example.com wss://rt.example.com"))
    (is (str/includes? p "script-src 'self' 'unsafe-inline' https://*.cdn.example.com;"))
    (is (not (str/includes? p "unsafe-eval")))
    (is (not (str/includes? p "script-src *")))
    (is (str/includes? p "frame-src 'none'"))))

(deftest the-policy-comes-first
  (let [meta? #(str/starts-with? % "<meta http-equiv=\"Content-Security-Policy\"")]
    (testing "after a doctype, before anything the author wrote"
      (let [d (csp/srcdoc "<!doctype html><!-- <head> --><html><head></head></html>" "default-src 'none'")]
        (is (str/starts-with? d "<!doctype html><meta http-equiv="))
        (is (< (str/index-of d "Content-Security-Policy") (str/index-of d "<!--")))))
    (is (meta? (csp/srcdoc "<html><header>x</header></html>" "p")))
    (is (meta? (csp/srcdoc "<p>hi</p>" "p")))
    (testing "the policy text can't break out of its attribute"
      (is (str/includes? (csp/srcdoc "<p/>" "a\"><script>") "content=\"a&quot;>&lt;script>\"")))))
