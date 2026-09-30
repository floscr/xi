(ns xi.api-test
  "xi.api.* — the rules-gated capabilities of user extensions. Headless (no
   confirm!) unless a test says otherwise, so every :ask is a refusal."
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [xi.api.fs :as xfs]
            [xi.api.http :as http]
            [xi.api.json :as json]
            [xi.api.sh :as xsh]
            [xi.ext.rules :as rules-ext]
            [xi.rules.store :as store]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(defn- with-data-home
  "Run (f) with XDG_DATA_HOME pointed at a fresh tmp dir; restore after.
   `f` returns a Promise."
  [f done]
  (let [saved (aget js/process.env "XDG_DATA_HOME")
        tmp   (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-api-data-"))]
    (aset js/process.env "XDG_DATA_HOME" tmp)
    (-> (js/Promise.resolve)
        (.then f)
        (.catch (fn [e] (is false (str "unexpected: " (ex-message e)))))
        (.finally (fn []
                    (if saved
                      (aset js/process.env "XDG_DATA_HOME" saved)
                      (js-delete js/process.env "XDG_DATA_HOME"))
                    (done))))))

(def ^:private ctx {:extension "t" :get-state (fn [] {})})

(defn- rejection
  "Promise → the rejection message (or ::resolved)."
  [p]
  (-> p (.then (constantly ::resolved)) (.catch ex-message)))

;; ── fs ───────────────────────────────────────────────────────────────────────

(deftest fs-own-data-dir-is-free
  (async done
    (with-data-home
      (fn []
        (-> (xfs/write ctx "notes/a.txt" "hello")
            (.then (fn [written]
                     (is (str/ends-with? written "/xi/extensions/t/notes/a.txt")
                         "relative paths land in the data dir outside a room")
                     (xfs/read ctx "notes/a.txt")))
            (.then (fn [text]
                     (is (= "hello" text))
                     (xfs/list ctx ".")))
            (.then (fn [entries] (is (= ["notes/"] entries))))))
      done)))

(deftest fs-outside-write-without-anyone-to-ask-is-refused
  (async done
    (let [target (node-path/join (os/homedir) (str "xi-api-test-" (js/Date.now) ".txt"))]
      (with-data-home
        (fn []
          (-> (rejection (xfs/write ctx target "x"))
              (.then (fn [msg]
                       (is (re-find #"needs approval, but there is no one to ask" (str msg)))
                       (is (not (fs/existsSync target)) "nothing was written")))
              (.finally #(when (fs/existsSync target) (fs/unlinkSync target)))))
        done))))

(deftest fs-credential-paths-are-denied
  (async done
    (with-data-home
      (fn []
        (-> (rejection (xfs/read ctx "~/.config/xi/clients.edn"))
            (.then (fn [msg]
                     (is (= "Blocked: extensions may not read or write credential paths." msg))))))
      done)))

(deftest fs-ask-is-raised-for-the-extension
  (async done
    (let [target (node-path/join (os/homedir) (str "xi-api-test-" (js/Date.now) ".txt"))
          asked  (atom nil)
          c      (assoc ctx :confirm! (fn [msg _] (reset! asked msg) (js/Promise.resolve false)))]
      (with-data-home
        (fn []
          (-> (rejection (xfs/write c target "x"))
              (.then (fn [msg]
                       (is (str/starts-with? @asked "[extension t] ")
                           "the dialog names the extension, not the agent")
                       (is (re-find #"denied" msg))
                       (is (not (fs/existsSync target)))))))
        done))))

;; ── sh ───────────────────────────────────────────────────────────────────────

(deftest sh-asks-for-every-command
  (async done
    (with-data-home
      (fn []
        (-> (rejection (xsh/sh ctx "echo" "hi"))
            (.then (fn [msg]
                     (is (re-find #"needs approval" msg)
                         "even a read-only CLI asks — no clj auto-run for extensions")
                     (let [added (atom nil)
                           c     (assoc ctx
                                        :confirm! (fn [_ _] (js/Promise.resolve :always))
                                        :dispatch! #(reset! added %))]
                       (-> (xsh/sh c "echo" "hi")
                           (.then (fn [out]
                                    (is (= "hi" out))
                                    (is (= {:tool :sh :command "echo hi" :extension "t"}
                                           (get-in @added [:rule :match]))
                                        "[a]lways pins the exact command to this extension")))))))))
      done)))

(deftest sh-hardened-denies-still-apply
  (async done
    (with-data-home
      (fn []
        (-> (rejection (xsh/sh (assoc ctx :confirm! (fn [_ _] (js/Promise.resolve true)))
                               "bash" "-c" "echo hi"))
            (.then (fn [msg]
                     (is (not= ::resolved msg) "a shell interpreter is denied even when approving"))))) 
      done)))

(deftest sh-rejects-shell-strings
  (async done
    (-> (rejection (xsh/sh ctx "echo hi"))
        (.then (fn [msg] (is (re-find #"argv-style" msg))))
        (.finally done))))

;; ── http ─────────────────────────────────────────────────────────────────────

(deftest http-is-gated-per-host
  (async done
    (let [asked (atom nil)]
      (-> (rejection (http/fetch ctx "file:///etc/passwd"))
          (.then (fn [msg]
                   (is (re-find #"not an http\(s\) URL" msg))
                   (rejection (http/fetch ctx "https://example.com/x"))))
          (.then (fn [msg]
                   (is (re-find #"needs approval" msg) "headless → refused, no request made")
                   (rejection (http/fetch (assoc ctx :confirm! (fn [m _] (reset! asked m)
                                                                 (js/Promise.resolve false)))
                                          "https://example.com/x"))))
          (.then (fn [msg]
                   (is (re-find #"denied" msg))
                   (is (re-find #"Host: example.com" @asked))))
          (.finally done)))))

(deftest http-url-components
  (is (= "a%20b%26c%3Dd" (http/url-encode "a b&c=d")))
  (is (= "https://x.y/a b" (http/url-decode "https%3A%2F%2Fx.y%2Fa+b")))
  (is (= "100%" (http/url-decode "100%")) "a malformed escape comes back unchanged"))

;; ── json ───────────────────────────────────────────────────────────────────────

(deftest json-round-trips
  (is (= {:a [1 {:b "c"}] :d nil} (json/parse "{\"a\":[1,{\"b\":\"c\"}],\"d\":null}")))
  (is (= {"a/b" 1} (json/parse "{\"a/b\":1}" {:keywordize? false})))
  (is (= "{\"a\":[1,\"x\"]}" (json/stringify {:a [1 "x"]})))
  (is (= "{\n  \"a\": 1\n}" (json/stringify {:a 1} {:pretty? true})))
  (is (= "{\n  \"z\": 1,\n  \"a\": 2\n}" (json/pretty "{\"z\":1,\"a\":2}"))
      "pretty keeps the key order")
  (is (thrown? js/Error (json/parse "nope"))))

;; ── decide! / store ──────────────────────────────────────────────────────────

(deftest decide-shapes
  (async done
    (let [req #(store/request % {:cwd "/tmp"})]
      (-> (rules-ext/decide! (req {:tool :read :path "/tmp/x"}) {})
          (.then (fn [d]
                   (is (= :pass (:decision d)) "no rule → pass")
                   (rules-ext/decide! (req {:tool :write :path "~/.config/xi/rules.edn"}) {})))
          (.then (fn [d]
                   (is (= :deny (:decision d)) "the hard-block runs first")
                   (is (re-find #"hard rule" (:message d)))))
          (.finally done)))))

(deftest hard-block-request-covers-extension-sh
  (is (store/hard-block-request {:tool :sh :command "cp x ~/.config/xi/rules.edn"
                                 :effective-cwd "/tmp"}))
  (is (nil? (store/hard-block-request {:tool :sh :command "cat ~/.config/xi/rules.edn"
                                       :effective-cwd "/tmp"}))
      "reading it is not a write"))
