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
            [xi.ext.clj :as clj-ext]
            ["node:os" :as os]))

(defn- ev [code]
  (-> (clj-ext/reply->result
       (clj-ext/eval-message #js {:id 0 :kind "clj" :code code
                                  :roomId "sandbox-test"
                                  :allowed #js [] :allowedCommands #js []
                                  :allowedWrites #js [] :allowedReads #js []
                                  :allowedBg #js [] :cwd (os/tmpdir)})
       nil)
      :content first :text))

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
                  "((.. Math -constructor -constructor) \"return 1\")"]]
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
    (is (= "=> :ok" (ev "(try (throw (ex-info \"x\" {})) (catch :default e :ok))"))))
  (testing "construction still works through the :constructor fns"
    (is (= "=> \"boom\"" (ev "(ex-message (Exception. \"boom\"))")))
    (is (= "=> \"x\"" (ev "(ex-message (js/Error. \"x\"))")))
    (is (re-find #"Assert failed" (ev "(assert (= 1 2))")))
    (is (= "=> true" (ev "(inst? (Date.))")))
    (is (= "=> 0" (ev "(.getTime (Date. 0))")))
    (is (= "=> \"1970-01-01T00:00:01.000Z\"" (ev "(str (Instant. 1000))")))))
