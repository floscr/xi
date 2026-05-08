#!/usr/bin/env bb
;; Evaluate ClojureScript code against the running shadow-cljs :main REPL
;; Usage: bb scripts/repl-eval.bb '(+ 1 2)'
;;        bb scripts/repl-eval.bb --clj '(+ 1 2)'   ;; eval as Clojure (no CLJS switch)

(require '[bencode.core :as bencode])
(import '[java.net Socket]
        '[java.io PushbackInputStream])

(defn bytes->str [x]
  (cond
    (bytes? x) (String. x "UTF-8")
    (map? x) (into {} (map (fn [[k v]] [(bytes->str k) (bytes->str v)])) x)
    (sequential? x) (mapv bytes->str x)
    :else x))

(defn nrepl-messages [s in out code]
  (bencode/write-bencode out {"op" "eval" "code" code})
  (loop [results []]
    (let [resp (bytes->str (bencode/read-bencode in))
          results (conj results resp)]
      (if (some #{"done"} (get resp "status"))
        results
        (recur results)))))

(let [port (Integer/parseInt (str/trim (slurp ".shadow-cljs/nrepl.port")))
      clj-mode? (= "--clj" (first *command-line-args*))
      code (if clj-mode? (second *command-line-args*) (first *command-line-args*))
      s (Socket. "localhost" port)
      in (PushbackInputStream. (.getInputStream s))
      out (.getOutputStream s)]
  ;; Switch to CLJS REPL unless --clj
  (when-not clj-mode?
    (nrepl-messages s in out "(shadow/repl :main)"))
  ;; Eval the code
  (let [msgs (nrepl-messages s in out code)]
    (doseq [m msgs]
      (when-let [v (get m "value")]
        (println v))
      (when-let [o (get m "out")]
        (print o))
      (when-let [e (get m "err")]
        (binding [*out* *err*]
          (print e)))
      (when-let [ex (get m "ex")]
        (binding [*out* *err*]
          (println "ERROR:" ex)))))
  (.close s))
