(ns xisite.server
  "Dev server: renders pages per request, serves public/ and the guide's images."
  (:require [babashka.fs :as fs]
            [clojure.stacktrace :as stacktrace]
            [org.httpkit.server :as http]
            [xisite.build :as build]
            [xisite.core :as core]
            [xisite.docs :as docs]
            [xisite.livereload :as livereload]
            [xisite.theme :as theme]))

(def ^:private content-types
  {"html" "text/html; charset=utf-8"
   "css" "text/css; charset=utf-8"
   "js" "text/javascript; charset=utf-8"
   "svg" "image/svg+xml"
   "png" "image/png"
   "jpg" "image/jpeg"
   "webp" "image/webp"
   "txt" "text/plain; charset=utf-8"
   "json" "application/json"})

(defn- static-response [file]
  {:status 200
   ;; CORS: the web client's dev build (:8100) loads tour tapes from here
   ;; (?tape=http://localhost:4322/tours/<tour>.json)
   :headers {"Content-Type" (get content-types (fs/extension file) "application/octet-stream")
             "Access-Control-Allow-Origin" "*"}
   :body (fs/file file)})

(defn- handle [{:keys [uri] :as req}]
  (binding [core/*dev* true]
    (let [static (fs/path "public" (subs uri 1))
          guide-img (when (.startsWith ^String uri "/docs/img/")
                      (fs/path docs/guide-dir "img" (subs uri (count "/docs/img/"))))]
      (cond
        (= uri "/__livereload") (livereload/version-response req)
        (= uri "/css/ui.css") {:status 200
                               :headers {"Content-Type" (content-types "css")}
                               :body (theme/css)}
        (= uri "/js/ui-runtime.js") {:status 200
                                    :headers {"Content-Type" (content-types "js")}
                                    :body (theme/js)}
        (contains? build/tour-files uri)
        (let [src (build/tour-files uri)]
          (if (fs/exists? src)
            (static-response src)
            {:status 404
             :headers {"Content-Type" "text/plain; charset=utf-8"}
             :body (str src " is missing: run `bb tour:build` at the repo root")}))
        (and (not= uri "/") (fs/regular-file? static)) (static-response static)
        (and guide-img (fs/regular-file? guide-img)) (static-response guide-img)
        :else
        (let [pages (build/routes)]
          (if-let [html (or (pages uri) (pages (str uri "/")))]
            {:status 200
             :headers {"Content-Type" "text/html; charset=utf-8"}
             :body html}
            {:status 404
             :headers {"Content-Type" "text/plain; charset=utf-8"}
             :body (str "404 — no route for " uri)}))))))

(defn- wrap-errors [handler]
  (fn [req]
    (try (handler req)
         (catch Exception e
           (binding [*out* *err*] (println e))
           {:status 500
            :headers {"Content-Type" "text/plain; charset=utf-8"}
            :body (str "500 — " (ex-message e) "\n\n"
                       (with-out-str (stacktrace/print-stack-trace e)))}))))

(defn start! [{:keys [port] :or {port 4322}}]
  (let [url (str "http://localhost:" port)]
    (try
      (http/run-server (wrap-errors handle) {:port port})
      (catch java.net.BindException _
        (binding [*out* *err*]
          (println (str "Port " port " is already in use — a dev server is probably already running at " url)))
        (System/exit 1)))
    (livereload/watch!)
    (println (str "Dev server running at " url))))
