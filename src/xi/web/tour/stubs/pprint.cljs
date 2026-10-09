(ns xi.web.tour.stubs.pprint
  "Stands in for cljs.pprint in the :tour build (shadow-cljs.edn :ns-aliases):
   the parts xi.error-info and xi.clj-result use, printed on one line.
   Saves ~114 KB of the site's tour bundle.")

(def ^:dynamic *print-right-margin* 72)

(defn pprint [x] (println (pr-str x)))
