(ns xi.ext.clj-http-test
  (:require [cljs.reader :as reader]
            [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.ext.clj :as clj-ext]
            [xi.ext.clj-http :as http]
            ["node:child_process" :as cp]
            ["node:os" :as os]))

(deftest parse-headers-takes-the-final-response
  (is (= {"content-type" "text/plain"
          "set-cookie"   ["a=1; Path=/" "b=2"]}
         (http/parse-headers
          (str "HTTP/1.1 302 Found\r\nLocation: /x\r\n\r\n"
               "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n"
               "Set-Cookie: a=1; Path=/\r\nSet-Cookie: b=2\r\n\r\n")))))

(defn- argv [req]
  (http/curl-argv (http/normalize "test" req) "OUT" "HDR"))

(deftest curl-argv-reads-no-local-file
  (let [a (argv {:url "http://h/" :method :post :body "@/etc/passwd"})]
    (is (= "-q" (second a)) "~/.curlrc skipped")
    (is (= ["--data-binary" "@-"] (take 2 (drop-while #(not= "--data-binary" %) a)))
        "the body goes over stdin"))
  (is (thrown-with-msg? js/Error #"invalid header name"
                        (argv {:url "http://h/" :headers {"@/etc/passwd" "x"}})))
  (is (thrown-with-msg? js/Error #"newline"
                        (argv {:url "http://h/" :headers {"x" "a\r\nb: c"}})))
  (is (thrown-with-msg? js/Error #"http\(s\)" (argv {:url "file:///etc/passwd"}))))

(deftest curl-argv-method-and-redirects
  (testing "-X only where curl's implied method differs"
    (is (not (some #{"-X"} (argv {:url "http://h/" :method :post :body "x"}))))
    (is (not (some #{"-X"} (argv {:url "http://h/"}))))
    (is (some #{"PUT"} (argv {:url "http://h/" :method :put :body "x"})))
    (is (some #{"--head"} (argv {:url "http://h/" :method :head}))))
  (is (some #{"-L"} (argv {:url "http://h/"})))
  (is (not (some #{"-L"} (argv {:url "http://h/" :follow-redirects false})))))

(deftest normalize-params
  (let [req (http/normalize "test" {:url "http://h/p?a=1" :query-params {:b ["x y" 2]}
                                    :form-params {"pin" 1234} :timeout 500})]
    (is (= "http://h/p?a=1&b=x+y&b=2" (:url req)))
    (is (= "pin=1234" (:body req)))
    (is (= [["content-type" "application/x-www-form-urlencoded"]] (:headers req)))
    (is (= 0.5 (:max-time req)))))

;; ── Against a live server ────────────────────────────────────────────────────

(def ^:private SERVER
  "const http = require('http');
   const s = http.createServer((req, res) => {
     const chunks = [];
     req.on('data', c => chunks.push(c));
     req.on('end', () => {
       const body = Buffer.concat(chunks).toString();
       if (req.url === '/login') {
         res.writeHead(302, {location: '/home', 'set-cookie': ['sid=abc; Path=/', 'theme=dark']});
         return res.end();
       }
       if (req.url === '/home') return res.end('home ' + req.method + ' ' + (req.headers.cookie || ''));
       if (req.url === '/missing') { res.writeHead(404); return res.end('nope'); }
       if (req.url === '/bin') return res.end(Buffer.from([0, 255, 128]));
       res.end(JSON.stringify({method: req.method, url: req.url,
                               ct: req.headers['content-type'] || null, body}));
     });
   });
   s.listen(0, '127.0.0.1', () => console.log(s.address().port));")

(defn- eval-edn
  "Evaluate `code` in a fresh clj sandbox; its printed result read back."
  [code]
  (let [res (clj-ext/reply->result
             (clj-ext/eval-message #js {:id 0 :kind "clj" :code code :roomId "clj-http-test"
                                        :allowed #js [] :allowedCommands #js [] :approved #js []
                                        :allowedWrites #js [] :allowedReads #js [] :allowedBg #js []
                                        :cwd (os/tmpdir)})
             nil)
        text (-> res :content first :text)]
    (if (:is-error res) {:error text} (reader/read-string (str/replace-first text #"^=> " "")))))

(deftest http-client-against-a-server
  (async done
    (let [child (cp/spawn js/process.execPath #js ["-e" SERVER]
                          #js {:stdio #js ["ignore" "pipe" "inherit"]})]
      (.once (.-stdout child) "data"
             (fn [chunk]
               (let [base (str "http://127.0.0.1:" (str/trim (str chunk)))
                     run  #(eval-edn (str/replace % "BASE" base))]
                 (try
                   (testing "form post"
                     (is (= {"method" "POST" "url" "/echo?x=1"
                             "ct" "application/x-www-form-urlencoded" "body" "pin=1234&q=a+b"}
                            (run "(json/read-str (:body (http/post \"BASE/echo\"
                                    {:query-params {:x 1} :form-params {:pin 1234 :q \"a b\"}})))"))))
                   (testing "no redirect: status and the repeated set-cookie"
                     (is (= [302 ["sid=abc; Path=/" "theme=dark"]]
                            (run "(require '[babashka.http-client :as hc])
                                  (let [r (hc/post \"BASE/login\" {:body \"pin=1\" :follow-redirects false})]
                                    [(:status r) (get-in r [:headers \"set-cookie\"])])"))))
                   (testing "a followed POST redirect turns into a GET"
                     (is (= "home GET sid=abc"
                            (run "(:body (http/post \"BASE/login\" {:body \"pin=1\"
                                                                   :headers {\"cookie\" \"sid=abc\"}}))"))))
                   (testing "exceptional status throws unless :throw false"
                     (is (= [404 404 "nope"]
                            (run "[(try (http/get \"BASE/missing\") (catch :default e (:status (ex-data e))))
                                   (:status (http/get \"BASE/missing\" {:throw false}))
                                   (:body (http/request {:uri \"BASE/missing\" :throw false}))]"))))
                   (testing ":as :bytes"
                     (is (= [0 -1 -128] (run "(vec (:body (http/get \"BASE/bin\" {:as :bytes})))"))))
                   (testing "put; curl gets headers and sends \"@…\" bodies literally"
                     (is (= ["PUT" "x" "@/etc/passwd" true]
                            (run "(let [p (json/read-str (:body (http/put \"BASE/echo\" {:body \"x\"})))
                                        c (curl \"BASE/echo\" {:body \"@/etc/passwd\"})]
                                    [(get p \"method\") (get p \"body\")
                                     (get (json/read-str (:body c)) \"body\")
                                     (map? (:headers c))])"))))
                   (finally
                     (.kill child)
                     (done)))))))))
