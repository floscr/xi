(ns xi.api.json
  "JSON for user extensions. The sandbox has no `js/JSON` (no raw js/ access),
   and http/sh hand back strings, so parsing and building payloads goes
   through here. Pure: no capability, no rules request.")

(defn parse
  "JSON string → Clojure data, object keys as keywords. Throws on invalid
   JSON; pass {:keywordize? false} to keep string keys."
  ([s] (parse s nil))
  ([s {:keys [keywordize?] :or {keywordize? true}}]
   (js->clj (js/JSON.parse (str s)) :keywordize-keys keywordize?)))

(defn stringify
  "Clojure data → JSON string; {:pretty? true} indents by two spaces."
  ([x] (stringify x nil))
  ([x {:keys [pretty?]}]
   (if pretty?
     (js/JSON.stringify (clj->js x) nil 2)
     (js/JSON.stringify (clj->js x)))))

(defn pretty
  "Re-indent a JSON string by two spaces, keeping its key order (a round trip
   through Clojure maps would not). Throws on invalid JSON."
  [s]
  (js/JSON.stringify (js/JSON.parse (str s)) nil 2))
