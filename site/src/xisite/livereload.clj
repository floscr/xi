(ns xisite.livereload
  "Dev-only live reload: a thread watches src/, public/ and the guide,
   reloads changed namespaces, and bumps a counter the browser polls."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def ^:private roots ["src" "public" "../docs/guide"])

(def ^:private watched-exts #{"clj" "css" "js" "svg" "md" "edn" "html"})

(defonce ^:private version (atom 0))

(defn- snapshot []
  (into {}
        (comp (mapcat #(when (fs/exists? %) (file-seq (fs/file %))))
              (filter #(.isFile ^java.io.File %))
              (filter #(watched-exts (fs/extension %)))
              (map (fn [f] [(.getPath ^java.io.File f) (.lastModified ^java.io.File f)])))
        roots))

(defn- path->ns [path]
  (when (and (str/starts-with? path "src/") (= "clj" (fs/extension path)))
    (-> path
        (subs (count "src/"))
        (str/replace #"\.clj$" "")
        (str/replace "/" ".")
        (str/replace "_" "-")
        symbol)))

(defn- reload! [changed]
  (doseq [ns-sym (keep path->ns changed)]
    (try
      (require ns-sym :reload)
      (println "  reloaded" ns-sym)
      (catch Throwable e
        (binding [*out* *err*]
          (println "  reload FAILED for" ns-sym "—" (ex-message e)))))))

(defn watch! []
  (future
    (loop [prev (snapshot)]
      (Thread/sleep 300)
      (let [now (snapshot)
            changed (for [[path mtime] now
                          :when (not= mtime (get prev path))]
                      path)]
        (when (seq changed)
          (println "live-reload:" (str/join ", " (map fs/file-name changed)))
          (reload! changed)
          (swap! version inc))
        (recur now))))
  (println "Live reload watching:" (str/join " " roots)))

(defn version-response [_req]
  {:status 200
   :headers {"Content-Type" "text/plain; charset=utf-8"
             "Cache-Control" "no-cache"}
   :body (str @version)})

(def script
  "(function(){var v=null;function poll(){fetch('/__livereload',{cache:'no-store'}).then(function(r){return r.text();}).then(function(t){if(v!==null&&t!==v){location.reload();return;}v=t;setTimeout(poll,500);}).catch(function(){setTimeout(poll,1000);});}poll();})();")
