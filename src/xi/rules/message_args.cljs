(ns xi.rules.message-args
  "Option values that are prose to their program — a commit message, a PR
   body — which it stores and never runs. `:command` rules see a `:sh`
   call's argv with these values replaced by `MASK` (xi.rules/command-text),
   so a commit message that says `sudo` is not a sudo call."
  (:require [clojure.string :as str]))

(def MASK "<message>")

(def ^:private git-message
  "`-m` (also bundled after git's value-less short flags, `-am`, or with the
   value attached, `-mfix`) and `--message`."
  {:short #"^-[aqsve]*m(.*)$" :long #{"--message"}})

(def ^:private gh-message
  {:short #"^-[tbn](.*)$" :long #{"--title" "--body" "--notes"}})

(def ^:private specs
  "Program → its subcommand (`:any`: every one) → which options carry a
   message."
  {"git" {"commit" git-message "tag" git-message "merge" git-message
          "stash" git-message "notes" git-message}
   "gh"  {"pr" gh-message "issue" gh-message "release" gh-message}
   "jj"  {:any {:short #"^-m(.*)$" :long #{"--message"}}}})

(def ^:private value-globals
  "Global options before the subcommand that take the next argument."
  {"git" #{"-C" "-c" "--git-dir" "--work-tree" "--namespace" "--config-env"}})

(defn- subcommand-index
  [prog args]
  (loop [i 0]
    (when-let [t (nth args i nil)]
      (cond
        (contains? (value-globals prog) t) (recur (+ i 2))
        (str/starts-with? t "-")           (recur (inc i))
        :else                              i))))

(defn- mask-options
  "`args` with the values of `spec`'s options masked, up to a `--`."
  [{:keys [short long]} args]
  (loop [out [] ts args]
    (if-let [[t & more] (seq ts)]
      (let [long-eq  (some #(when (str/starts-with? t (str % "=")) %) long)
            attached (second (re-matches short t))]
        (cond
          (= "--" t)          (into out ts)
          (or (contains? long t) (= "" attached))
          (if (seq more)
            (recur (conj out t MASK) (rest more))
            (conj out t))
          long-eq             (recur (conj out (str long-eq "=" MASK)) more)
          attached            (recur (conj out (str (subs t 0 (- (count t) (count attached))) MASK))
                                     more)
          :else               (recur (conj out t) more)))
      out)))

(defn mask
  "`argv` (program first) with its message option values replaced by `MASK`;
   unchanged for any other program or subcommand."
  [argv]
  (let [[prog & args] argv
        prog (some-> prog str (str/split #"/") last)
        by-sub (get specs prog)
        i (when by-sub (subcommand-index prog args))
        spec (when by-sub (or (:any by-sub) (when i (get by-sub (nth args i)))))]
    (if-not spec
      argv
      (let [start (if (:any by-sub) 0 (inc i))]
        (into [(first argv)]
              (concat (take start args) (mask-options spec (drop start args))))))))
