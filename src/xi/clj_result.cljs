(ns xi.clj-result
  "Parsing + pretty-printing of the clj tool's result text, shared by the
   web and TUI renderers."
  (:require [clojure.string :as str]
            [cljs.reader :as edn]
            [cljs.pprint :as pprint]))

(defn clj-data-value?
  "True when a clj `=>` value looks like a printed clj data structure worth
   syntax-highlighting — it opens with a collection/set/keyword/reader delimiter
   (`[ ( { # :`). Plain command output (multi-line shell text from `(sh …)` /
   `(cat …)`, printed strings) opens with ordinary characters, so it renders as
   plain monospace instead and the clj highlighter doesn't spray colours over
   arbitrary text."
  [s]
  (boolean (re-find #"^\s*[\[({#:]" s)))

(defn parse-clj-result
  "Split a clj tool result string into {:stdout :value :error :loc}.
   Success text is `<stdout>=> <value>`; error text is
   `<stdout>Error: <msg> (line X:Y)`."
  [text is-error]
  (if is-error
    (let [lead?  (str/starts-with? text "Error: ")
          nl-idx (str/index-of text "\nError: ")
          stdout (when nl-idx (subs text 0 nl-idx))
          err    (cond lead?  (subs text (count "Error: "))
                       nl-idx (subs text (+ nl-idx 1 (count "Error: ")))
                       :else  text)
          loc-m  (re-find #"\s*\(line \d+(?::\d+)?\)\s*$" err)
          loc    (when loc-m (str/replace (str/trim loc-m) #"[()]" ""))
          msg    (if loc-m (subs err 0 (- (count err) (count loc-m))) err)]
      {:stdout (not-empty stdout) :error (str/trim msg) :loc loc})
    (let [lead?  (str/starts-with? text "=> ")
          nl-idx (str/index-of text "\n=> ")]
      (cond
        lead?  {:value (subs text 3)}
        nl-idx {:stdout (not-empty (subs text 0 nl-idx))
                :value  (subs text (+ nl-idx 4))}
        :else  {:value text}))))

(defn pretty-edn
  "Best-effort pretty-print of an EDN value string so multi-key maps / nested
   collections render across lines instead of on one wide row. Unknown tagged
   literals (e.g. `#object[...]`) are preserved via a default reader. Returns
   the original text unchanged if it can't be parsed as a single EDN form."
  [text]
  (try
    (let [v (edn/read-string {:default (fn [tag val] (tagged-literal tag val))} text)]
      (str/trim-newline
       (binding [pprint/*print-right-margin* 80]
         (with-out-str (pprint/pprint v)))))
    (catch :default _ text)))

(defn value-display
  "How to show a parsed result's `=>` value: {:text :data?}, or nil when
   there's nothing worth showing (no value, or a bare `nil` after stdout).
   Leading blank lines are dropped; clj data comes back pretty-printed and
   flagged :data? so the caller can syntax-highlight it."
  [{:keys [stdout value]}]
  (when value
    (let [value (str/replace value #"^(?:[ \t]*\r?\n)+" "")
          data? (clj-data-value? value)]
      (when-not (and stdout (= "nil" (str/trim value)))
        {:text  (if data? (pretty-edn value) value)
         :data? data?}))))
