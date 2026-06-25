(ns xi.url
  "URL detection utilities — recognizing bare http(s):// URLs embedded in
   text. Pure and cross-platform (Clojure + ClojureScript). Knows nothing
   about markdown; callers decide what to do with a detected URL.")

(def ^:private schemes ["https://" "http://"])

(defn scheme-at
  "Returns the URL scheme prefix (\"https://\" or \"http://\") that `s` starts
   with at index `idx`, or nil."
  [^String s idx]
  (some (fn [p]
          (let [end (+ idx (count p))]
            (when (and (<= end (count s)) (= (subs s idx end) p))
              p)))
        schemes))

(defn- boundary-char?
  "Characters that terminate a bare URL while scanning."
  [ch]
  (or (= ch \space) (= ch \tab) (= ch \newline) (= ch \return)
      (= ch \<) (= ch \>) (= ch \") (= ch \`) (= ch \|) (= ch \\)))

;; Trailing punctuation trimmed from the end of a bare URL (GFM-style).
;; Closing parens are handled separately so balanced parens are kept.
(def ^:private trailing-punct
  #{\. \, \; \: \! \? \' \" \] \} \* \_ \~})

(defn url-at
  "If a bare http(s):// URL begins at index `idx` in `s`, returns
   `[url end]` where `end` is the exclusive end index after GFM-style
   trailing-punctuation trimming (balanced parens preserved). Otherwise nil.

   Does not consider whether the URL is embedded in a surrounding word —
   callers apply that policy (see `scheme-at`)."
  [^String s idx]
  (when-let [prefix (scheme-at s idx)]
    (let [len (count s)
          min-end (+ idx (count prefix))
          raw-end (loop [i min-end]
                    (if (or (>= i len) (boundary-char? (.charAt s i)))
                      i
                      (recur (inc i))))]
      (when (> raw-end min-end)
        (let [end (loop [e raw-end]
                    (if (<= e min-end)
                      e
                      (let [ch (.charAt s (dec e))]
                        (cond
                          ;; Trim a trailing ')' only when unbalanced, so URLs
                          ;; like …/Foo_(bar) keep their closing paren.
                          (= ch \))
                          (let [sub (subs s idx e)
                                opens (count (filter #(= % \() sub))
                                closes (count (filter #(= % \)) sub))]
                            (if (> closes opens) (recur (dec e)) e))
                          (contains? trailing-punct ch) (recur (dec e))
                          :else e))))]
          (when (> end min-end)
            [(subs s idx end) end]))))))
