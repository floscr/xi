(ns xi.api.time
  "The clock for user extensions. A sandbox has no js/ interop, so this is
   the one way to a timestamp — on the server (xi.ext.user) and in a web half
   (xi.web.user-ext.sci), the same function. Not a rules request: reading the
   time is free.

     (now)    → milliseconds since the epoch, like Date.now()
     (today)  → the local date as \"YYYY-MM-DD\"")

(defn now
  "Milliseconds since the Unix epoch."
  []
  (js/Date.now))

(defn today
  "The local date (the machine's time zone) as \"YYYY-MM-DD\"."
  []
  (let [d   (js/Date.)
        pad #(if (< % 10) (str "0" %) (str %))]
    (str (.getFullYear d) "-" (pad (inc (.getMonth d))) "-" (pad (.getDate d)))))
