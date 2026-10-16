(ns xi.blocks.csp
  "The document an MCP Apps view runs as (xi.ext.blocks.frame): its HTML with
   the Content-Security-Policy its resource declares, as the first element of
   <head>. Pure, so the server and tests share it."
  (:require [clojure.string :as str]))

(defn- domains
  "Declared origins a CSP directive may name: scheme://host[:port][/path],
   wildcard subdomains allowed. Anything else (quotes, keywords, spaces) is
   dropped, so a resource can't smuggle in 'unsafe-eval' or a new directive."
  [xs]
  (->> (if (sequential? xs) xs [])
       (filter #(and (string? %)
                     (re-matches #"(https?|wss?)://[A-Za-z0-9*.\-]+(:\d+)?(/[A-Za-z0-9._~/\-]*)?" %)))
       (str/join " ")))

(defn csp
  "The Content-Security-Policy of a view from its resource's `csp` meta;
   without one, the spec's restrictive default (nothing external)."
  [{:keys [connectDomains resourceDomains frameDomains baseUriDomains]}]
  (let [res (domains resourceDomains)
        with (fn [base extra] (str/trim (str base " " extra)))]
    (str/join "; "
              [(str "default-src 'none'")
               (with "script-src 'self' 'unsafe-inline'" res)
               (with "style-src 'self' 'unsafe-inline'" res)
               (with "img-src 'self' data:" res)
               (with "font-src 'self'" res)
               (with "media-src 'self' data:" res)
               (str "connect-src " (or (not-empty (domains connectDomains)) "'none'"))
               (str "frame-src " (or (not-empty (domains frameDomains)) "'none'"))
               (str "base-uri " (or (not-empty (domains baseUriDomains)) "'self'"))
               "object-src 'none'"
               "form-action 'none'"])))

(defn- attr-escape [s]
  (-> s (str/replace "&" "&amp;") (str/replace "\"" "&quot;") (str/replace "<" "&lt;")))

(defn srcdoc
  "`html` with the CSP meta at its very start (after a leading doctype, so
   the document keeps standards mode). The parser puts it into the implicit
   <head> and it covers everything after it; inserting it later, say after the
   first <head>, would let the author hide that tag in a comment and leave the
   view without a policy."
  [html csp-meta]
  (let [meta (str "<meta http-equiv=\"Content-Security-Policy\" content=\"" (attr-escape csp-meta) "\">")]
    (if-let [doctype (re-find #"(?i)^\s*<!doctype[^>]*>" html)]
      (str doctype meta (subs html (count doctype)))
      (str meta html))))
