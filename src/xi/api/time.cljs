(ns xi.api.time
  "The clock for user extensions. A sandbox has no js/ interop, so this is
   the one way to a timestamp — on the server (xi.ext.user) and in a web half
   (xi.web.user-ext.sci), the same function. Not a rules request: reading the
   time is free.

     (now)  → milliseconds since the epoch, like Date.now()")

(defn now
  "Milliseconds since the Unix epoch."
  []
  (js/Date.now))
