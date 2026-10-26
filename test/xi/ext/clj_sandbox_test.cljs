(ns xi.ext.clj-sandbox-test
  "Sandbox-escape regression tests for the clj tool's SCI context.

   The escapes here all reach js/Function (→ arbitrary code → full host
   access, defeating every path/sh gate). Two classes, two defenses:
     - static-member access on a configured class (`Date/constructor`) does an
       unchecked property read in SCI cljs → closed by `null-proto` class values
     - raw JS-property access (`aget`/`js-obj`/…) and dynamic eval → removed via
       the `:deny` set
   If any of these starts returning a value again, the sandbox is broken."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [xi.ext.clj :as clj-ext]
            ["node:os" :as os]))

(defn- ev* [room-id compute? code]
  (-> (clj-ext/reply->result
       (clj-ext/eval-message #js {:id 0 :kind "clj" :code code
                                  :roomId room-id
                                  :allowed #js [] :allowedCommands #js []
                                  :allowedWrites #js [] :allowedReads #js []
                                  :allowedBg #js [] :cwd (os/tmpdir)
                                  :compute compute?})
       nil)
      :content first :text))

(defn- ev [code] (ev* "sandbox-test" false code))

(defn- blocked?
  "The eval produced an error (a sandbox escape would instead return `=> …`)."
  [code]
  (let [out (ev code)]
    (and (not (nil? out)) (not (re-find #"^=>" out)))))

(deftest static-member-escapes-are-closed
  (testing "Class/constructor can't reach js/Function (null-proto class values)"
    (doseq [code ["((Date/constructor \"return 1\"))"
                  "((Error/constructor \"return 1\"))"
                  "((Exception/constructor \"return 1\"))"
                  "((Throwable/constructor \"return 1\"))"
                  "((Date/constructor.constructor \"return 1\"))"
                  "((Math/constructor.constructor \"return 1\"))"
                  "((Long/constructor.constructor \"return 1\"))"
                  "((Integer/constructor.constructor \"return 1\"))"
                  "((Instant/constructor.constructor \"return 1\"))"
                  "((.. Math -constructor -constructor) \"return 1\")"
                  "((String/constructor \"return 1\"))"
                  "((Base64/constructor.constructor \"return 1\"))"
                  "((.. \"x\" -constructor -constructor) \"return 1\")"
                  "((.. (java.util.Base64/getDecoder) -constructor -constructor) \"return 1\")"]]
      (is (blocked? code) code))))

(deftest raw-js-access-and-eval-are-denied
  (testing "aget/js-obj/eval/… are removed (:deny) — they bypass class gating"
    (doseq [code ["((aget (aget #inst \"2020-01-01\" \"constructor\") \"constructor\") \"return 1\")"
                  "((aget (aget (js-obj) \"constructor\") \"constructor\") \"return 1\")"
                  "(aset (js-obj) \"x\" 1)"
                  "(unchecked-get (js-obj) \"constructor\")"
                  "(js-invoke #inst \"2020-01-01\" \"constructor\")"
                  "(eval '(+ 1 1))"
                  "(load-string \"(+ 1 1)\")"
                  "(eval (read-string \"(sh \\\"id\\\")\"))"
                  "(intern 'user 'x 1)"
                  "((resolve 'js/process))"
                  "(alter-var-root #'clojure.core/+ (constantly -))"]]
      (is (blocked? code) code))))

(deftest js-globals-are-unresolvable
  (doseq [code ["((js/Function \"return 1\"))"
                "(.-pid js/process)"
                "(js/require \"node:fs\")"
                "(aget js/globalThis \"process\")"]]
    (is (blocked? code) code)))

(deftest legitimate-interop-still-works
  (testing "the static members and instance interop the sandbox advertises"
    (is (= "=> 3" (ev "(Math/floor 3.7)")))
    (is (= "=> 3.141592653589793" (ev "Math/PI")))
    (is (= "=> 42" (ev "(Long/parseLong \"42\")")))
    (is (= "=> 7" (ev "(Integer/parseInt \"7\")")))
    (is (= "=> 2.5" (ev "(Double/parseDouble \"2.5\")")))
    (is (= "=> \"1970-01-01T00:00:00.000Z\"" (ev "(str (Instant/ofEpochMilli 0))")))
    (is (= "=> true" (ev "(number? (.getTime #inst \"2020-01-01\"))")))
    (is (= "=> :ok" (ev "(try (throw (ex-info \"x\" {})) (catch :default e :ok))")))
    (is (= "=> :ok" (ev "(try (throw (ex-info \"x\" {})) (catch Exception e :ok))")))
    (is (= "=> :ok" (ev "(try (throw (ex-info \"x\" {})) (catch Throwable e :ok))"))))
  (testing "construction still works through the :constructor fns"
    (is (= "=> \"boom\"" (ev "(ex-message (Exception. \"boom\"))")))
    (is (= "=> \"x\"" (ev "(ex-message (js/Error. \"x\"))")))
    (is (re-find #"Assert failed" (ev "(assert (= 1 2))")))
    (is (= "=> true" (ev "(inst? (Date.))")))
    (is (= "=> 0" (ev "(.getTime (Date. 0))")))
    (is (= "=> \"1970-01-01T00:00:01.000Z\"" (ev "(str (Instant. 1000))")))))

(deftest jvm-base64-idiom-works
  (is (= "=> \"héllo\"" (ev "(String. (.decode (java.util.Base64/getDecoder) \"aMOpbGxv\"))")))
  (is (= "=> \"aMOpbGxv\"" (ev "(.encodeToString (Base64/getEncoder) (.getBytes \"héllo\"))")))
  (is (= "=> [-61 -87]" (ev "(vec (.getBytes \"é\"))")))
  (is (= "=> \"hi\"" (ev "(String. (.decode (Base64/getDecoder) \"aGk=\\n\") \"UTF-8\")")))
  (is (= "=> \"ABC\"" (ev "(.toUpperCase \"abc\")"))))

(deftest jvm-url-encoder-idiom-works
  (is (= "=> \"a+b%26c%3D%C3%A9%21%28%29*\""
         (ev "(java.net.URLEncoder/encode \"a b&c=é!()*\" \"UTF-8\")")))
  (is (= "=> \"a b+é\"" (ev "(URLDecoder/decode \"a+b%2B%C3%A9\" \"UTF-8\")")))
  (is (= "=> \"x%7E\"" (ev "(URLEncoder/encode \"x~\")")))
  (is (blocked? "(URLEncoder/encode \"x\" \"ISO-8859-1\")"))
  (is (blocked? "((URLEncoder/constructor.constructor \"return 1\"))"))
  (is (blocked? "((.. URLEncoder/encode -constructor) \"return 1\")"))
  (is (blocked? "((.. (URLDecoder/decode \"x\") -constructor -constructor) \"return 1\")")))

(deftest compute-mode-has-no-file-program-or-socket-access
  (let [ev (partial ev* "compute-test" true)]
    (testing "every helper that touches files, runs programs or reads the env refuses"
      (doseq [code ["(slurp \"x\")" "(spit \"x\" \"y\")" "(ls)"
                    "(glob \"*\")" "(grep \"a\" \".\")" "(stat \".\")" "(rm \"x\")"
                    "(tmpdir)" "(sh \"ls\")" "(git \"status\")" "(jq \".\" {})"
                    "(env \"HOME\")" "(cwd)" "(ports)" "(which \"ls\")"]]
        (is (re-find #"no file, program or socket access" (ev code)) code)))
    (testing "a refusal names the call and what is available"
      (let [out (ev "(slurp \"x\")")]
        (is (str/includes? out "(slurp …) is not available here") out)
        (is (str/includes? out "Available: basename curl dirname now sleep") out)))
    (testing "the process and socket namespaces refuse the same way"
      (is (str/includes? (ev "(process/start \"ls\")") "(process/start …) is not available here"))
      (is (str/includes? (ev "(socket/connect \"127.0.0.1\" 1)")
                         "(socket/connect …) is not available here")))
    (testing "computation still works, and cat / find are clojure.core's"
      (is (= "=> 3" (ev "(+ 1 2)")))
      (is (= "=> [1 2]" (ev "(into [] cat [[1] [2]])")))
      (is (= "=> [:a 1]" (ev "(find {:a 1} :a)")))
      (is (= "=> \"{\\\"a\\\":1}\"" (ev "(json/write-str {:a 1})")))
      (is (= "=> \"b.txt\"" (ev "(basename \"/a/b.txt\")"))))
    (testing "the room's REPL persists across calls"
      (ev "(def x 41)")
      (is (= "=> 42" (ev "(inc x)")))))
  (testing "a room without :compute keeps the file helpers"
    (is (= "=> true" (ev "(vector? (ls))")))
    (is (= (str "=> " (pr-str (os/tmpdir))) (ev "(cwd)")))))