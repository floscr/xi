(ns xi.web.tour.stubs.user-ext
  "Stands in for xi.web.user-ext in the :tour build (shadow-cljs.edn
   :ns-aliases): a tour loads no user extensions, so the SCI module, the
   module loader and everything SCI keeps alive drop out of the bundle.")

(def handlers {})

(defn fx [_] {})

(defn request-tap [_] (fn [_ _] nil))
