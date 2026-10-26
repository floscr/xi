(ns xi.ext.clj-http
  "babashka.http-client for the clj tool's sandbox (xi.ext.clj), run on the
   curl CLI; it also backs the (curl …) helper. curl gets no argument that
   reads a local file: ~/.curlrc is skipped (-q), the request body goes over
   stdin and header names are plain tokens (no `-H @file`)."
  (:require [clojure.string :as str]
            [xi.ext.clj-bytes :as bytes]
            [xi.sandbox.url :as url]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

;; babashka.http-client throws on any other status unless :throw false.
(def ^:private UNEXCEPTIONAL
  #{200 201 202 203 204 205 206 207 300 301 302 303 304 307})

(defn- query-string
  "bb's encoding: a sequential value repeats its key."
  [params]
  (str/join "&" (for [[k v] params
                      v (if (sequential? v) v [v])]
                  (str (url/form-encode k) "=" (url/form-encode v)))))

(defn- header-line [[k v]]
  (let [k (if (keyword? k) (name k) (str k))
        v (str v)]
    (when-not (re-matches #"[!#$%&'*+.^_`|~0-9A-Za-z-]+" k)
      (throw (ex-info (str "clj: invalid header name: " (pr-str k)) {})))
    (when (re-find #"[\r\n]" v)
      (throw (ex-info (str "clj: newline in header " k) {})))
    (str k ": " v)))

(defn- has-header? [headers header]
  (some #(= header (str/lower-case (name (first %)))) headers))

(defn parse-headers
  "curl's `-D` dump → the final response's headers as babashka.http-client
   returns them: lower-cased names, a repeated header's values in a vector."
  [dump]
  (let [blocks (->> (str/split dump #"\r?\n\r?\n")
                    (map str/trim)
                    (filter #(str/starts-with? % "HTTP/")))]
    (reduce (fn [m line]
              (if-let [[_ k v] (re-matches #"([^:\s]+):\s*(.*)" line)]
                (update m (str/lower-case k)
                        (fn [old]
                          (cond (nil? old)    v
                                (vector? old) (conj old v)
                                :else         [old v])))
                m))
            {}
            (rest (str/split-lines (or (last blocks) ""))))))

(defn- basic-auth-header [auth]
  (let [[user pass] (if (map? auth) [(:user auth) (:pass auth)] auth)]
    (str "Basic " (.toString (js/Buffer.from (str user ":" pass) "utf8") "base64"))))

(defn normalize
  "A babashka.http-client request map → the {:url :method :headers :body
   :follow? :max-time :as :throw?} curl-argv runs. `who` names the caller in
   errors."
  [who {:keys [url uri method headers body query-params form-params basic-auth
               timeout follow-redirects as throw]}]
  (let [url (str (or url uri))]
    (when-not (re-matches #"(?i)https?://.*" url)
      (throw (ex-info (str "clj: " who " only supports http(s) URLs") {})))
    (when-not (contains? #{nil :string :bytes} as)
      (throw (ex-info (str "clj: " who " supports :as :string and :as :bytes, not "
                           (pr-str as))
                      {})))
    (let [headers (cond-> (vec headers)
                    (and form-params (not (has-header? headers "content-type")))
                    (conj ["content-type" "application/x-www-form-urlencoded"])
                    basic-auth
                    (conj ["authorization" (basic-auth-header basic-auth)]))]
      {:url      (cond-> url
                   (seq query-params)
                   (str (if (str/includes? url "?") "&" "?") (query-string query-params)))
       :method   (keyword (str/lower-case (name (or method :get))))
       :headers  headers
       :body     (cond
                   form-params (query-string form-params)
                   (or (nil? body) (string? body) (array? body) (sequential? body)) body
                   :else (str body))
       :follow?  (not (contains? #{false :never} follow-redirects))
       :max-time (if timeout (/ timeout 1000) 30)
       :as       (or as :string)
       :throw?   (not (false? throw))})))

(defn curl-argv
  "The curl argv for a normalized request, writing the body to `out` and the
   headers to `hdr`. -X only where curl's own choice differs: with -L it would
   also force the method onto a redirect, where a POST must turn into a GET."
  [{:keys [url method headers body follow? max-time]} out hdr]
  (let [implied (if (some? body) :post :get)]
    (cond-> ["curl" "-q" "-sS" "--max-time" (str max-time)
             "-o" out "-D" hdr "-w" "%{http_code}"]
      follow?                      (conj "-L")
      (= :head method)             (conj "--head")
      (not (#{implied :head} method)) (into ["-X" (str/upper-case (name method))])
      (some? body)                 (into ["--data-binary" "@-"])
      :always                      (into (mapcat (fn [h] ["-H" (header-line h)])) headers)
      :always                      (conj url))))

(defn- tmp-file [tag]
  (node-path/join (os/tmpdir) (str "xi-clj-" tag "-" (js/Date.now) "-" (rand-int 1000000))))

(defn- read-and-remove [f encoding]
  (when (fs/existsSync f)
    (try (fs/readFileSync f encoding)
         (finally (fs/rmSync f #js {:force true})))))

(defn- perform!
  "Run a normalized request → {:status :headers :body}. `spawn` is
   (fn [argv input]) → {:exit :out :err}; a body over `max-bytes` throws."
  [{:keys [spawn max-bytes who]} {:keys [method body as] :as req}]
  (let [out (tmp-file "body")
        hdr (tmp-file "head")
        res (spawn (curl-argv req out hdr)
                   (when (some? body) (bytes/->buffer body)))]
    (try
      (let [status (js/parseInt (str/trim (:out res)) 10)]
        (when-not (and (zero? (:exit res)) (pos? status))
          (throw (ex-info (str "clj: " who " failed: " (:err res)) {})))
        (when (and (fs/existsSync out) (> (.-size (fs/statSync out)) max-bytes))
          (throw (ex-info (str "clj: " who " response exceeds " max-bytes
                               " bytes; download it with (sh \"curl\" \"-o\" f url)")
                          {})))
        {:status  status
         :headers (parse-headers (or (read-and-remove hdr "utf8") ""))
         :body    (when-not (= :head method)
                    (let [buf (or (read-and-remove out nil) (js/Buffer.alloc 0))]
                      (if (= :bytes as) (bytes/->bytes buf) (.toString buf "utf8"))))})
      (finally
        (fs/rmSync out #js {:force true})
        (fs/rmSync hdr #js {:force true})))))

(defn- request-fn [ctx]
  (fn [req]
    (let [{:keys [throw?] :as req} (normalize "babashka.http-client" req)
          resp (perform! (assoc ctx :who "babashka.http-client") req)]
      (if (and throw? (not (contains? UNEXCEPTIONAL (:status resp))))
        (throw (ex-info (str "Exceptional status code: " (:status resp)) resp))
        resp))))

(defn curl-fn
  "The (curl url opts) helper: opts {:method :headers :body :max-time
   (seconds)}; follows redirects, never throws on a status."
  [ctx]
  (fn curl*
    ([url] (curl* url nil))
    ([url {:keys [method headers body max-time]}]
     (let [req (normalize "curl" {:url url :method (or method (if (some? body) :post :get))
                                  :headers headers :body body})]
       (perform! (assoc ctx :who "curl")
             (assoc req :max-time (or max-time 30)))))))

(defn sci-namespace
  "babashka.http-client's request fns. ctx: {:spawn :max-bytes}, see perform!."
  [ctx]
  (let [request (request-fn ctx)
        verb    (fn [method]
                  (fn
                    ([url] (request {:url url :method method}))
                    ([url opts] (request (assoc opts :url url :method method)))))]
    {'request request
     'get     (verb :get)
     'head    (verb :head)
     'post    (verb :post)
     'put     (verb :put)
     'patch   (verb :patch)
     'delete  (verb :delete)}))
