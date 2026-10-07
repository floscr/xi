(ns xi.rules.curl
  "Parse a `curl` call into the hosts it requests, for `:host` rules on `:sh`
   calls (`{:tool :sh :cli \"curl\" :host \"100.64.0.2\"}`).

   Only a read-only request parses: every token after `curl` must be an
   http(s) URL or an allowlisted flag — silent/fail/verbose-style switches,
   `-m N`, `-X <METHOD>`, `-o /dev/null`. Anything else (`-o file`, `-O`,
   `-T`, `-K`, `-d @file`, `-H`, proxies, an unknown flag) makes the whole call
   unparseable (nil), so a `:host` rule never matches it and it stays gated.
   Pure — no I/O."
  (:require [clojure.string :as str]))

(def ^:private switch-re
  "Value-less flags, short ones fusable (`-fsSL`)."
  #"-[sSfLvikIg]+|--(?:silent|show-error|fail|location|verbose|include|head|insecure)")

(def ^:private value-flags
  "Flags taking the next token as value → the regex that value must match."
  {"-m"         #"\d+"
   "--max-time" #"\d+"
   "-X"         #"GET|POST|PUT|PATCH|DELETE|HEAD"
   "--request"  #"GET|POST|PUT|PATCH|DELETE|HEAD"
   "-o"         #"/dev/null"
   "--output"   #"/dev/null"})

(def ^:private url-re
  "An http(s) URL, capturing its host. The authority is host[:port] only — no
   userinfo (`localhost@evil.com`), no curl `{a,b}` / `[1-3]` globs (except an
   IPv6 literal), no backslash — so the captured host is the one curl
   contacts, with no parser-disagreement tricks."
  #"(?i)https?://(\[[0-9a-f:.]+\]|[a-z0-9.-]+)(?::\d+)?(?:[/?#][^\s\\]*)?")

(defn hosts
  "The set of (lower-cased) hosts a `curl` argv requests, or nil when it isn't
   a read-only curl request to at least one URL (see the ns doc)."
  [argv]
  (when (= "curl" (first argv))
    (loop [[t & more] (rest argv) acc #{}]
      (cond
        (nil? t)               (not-empty acc)
        (re-matches switch-re t) (recur more acc)
        (contains? value-flags t)
        (when (and (some? (first more)) (re-matches (value-flags t) (first more)))
          (recur (rest more) acc))
        :else
        (when-let [[_ host] (re-matches url-re t)]
          (recur more (conj acc (str/lower-case host))))))))

(def ^:private plain-command-re
  "A command string with no shell syntax — no quoting, `$`/backtick expansion,
   redirection or control operators — so splitting on spaces yields the argv
   bash runs it with. Glob characters (`?`, `[]`) stay allowed for query
   strings and IPv6 literals: a pathname match keeps the literal prefix up to
   the first glob character (a URL's scheme and host) and never runs
   anything."
  #"[A-Za-z0-9._~:/?#\[\]@+,=%\- ]+")

(defn request-hosts
  "The hosts a `:sh` decision request for `curl` reaches, or nil. Uses the
   literal `:argv` of a clj `(sh …)` call; a background command (which bash
   runs from the `:command` string) is split on spaces only when it carries
   no shell syntax at all."
  [{:keys [tool cli argv command]}]
  (when (and (= :sh tool) (= "curl" cli))
    (hosts (or argv
               (when (and command (re-matches plain-command-re (str command)))
                 (str/split (str/trim command) #" +"))))))
