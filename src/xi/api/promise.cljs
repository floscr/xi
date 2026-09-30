(ns xi.api.promise
  "Promise helpers for user extensions. The xi.api.fs / .sh / .http calls
   return JS Promises, but the sandbox blocks `.then` interop (Promise isn't a
   configured class), so extensions compose them through these instead:

     (-> (fs/read ctx \"x\")
         (p/then (fn [s] …))
         (p/catch (fn [e] …)))

   The host fns are deliberately NOT named `then`/`catch`: a namespace object
   with a `then` property is a thenable, so anything resolving it as a Promise
   value would call it. `sci-namespace` exposes them under the short names."
  (:refer-clojure :exclude [resolve delay]))

(defn then*   [p f] (.then p f))
(defn catch*  [p f] (.catch p f))
(defn all*    [ps]  (js/Promise.all (into-array ps)))
(defn resolve [v]   (js/Promise.resolve v))
(defn reject  [e]   (js/Promise.reject e))

(defn delay
  "→ Promise that resolves to nil after `ms`."
  [ms]
  (js/Promise. (fn [res _] (js/setTimeout #(res nil) ms))))

(def sci-namespace
  "The `xi.api.promise` namespace as a user extension sees it."
  {'then    then*
   'catch   catch*
   'all     all*
   'resolve resolve
   'reject  reject
   'delay   delay})
