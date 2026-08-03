(ns xi.ext.config
  "Generic per-extension config/secret loader.

   Any extension can have a dotenv-style file at

     ~/.config/xi/ext/<id>.env

   holding secrets (API keys, tokens) that must never be committed. The file
   lives outside the repo, so it can't be checked in by accident; the repo's
   .gitignore also covers stray *.env files as a backstop.

   Format: `KEY=VALUE` lines; blank lines and `#` comments are ignored;
   surrounding single/double quotes on the value are stripped.

     # ~/.config/xi/ext/render.env
     RENDER_API_KEY=rnd_abc123

   Usage:
     (load-config :render)              => {\"RENDER_API_KEY\" \"rnd_abc123\"}
     (get-value   :render \"RENDER_API_KEY\") => \"rnd_abc123\"

   `get-value` lets a shell `export` override the file, so the same key can be
   supplied either way."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(defn config-dir [] (path/join (os/homedir) ".config" "xi" "ext"))
(defn config-file [id] (path/join (config-dir) (str (name id) ".env")))

(defn- strip-quotes [v]
  (if (and (>= (count v) 2)
           (or (and (str/starts-with? v "\"") (str/ends-with? v "\""))
               (and (str/starts-with? v "'")  (str/ends-with? v "'"))))
    (subs v 1 (dec (count v)))
    v))

(defn parse-dotenv
  "Parse dotenv text into a string->string map."
  [text]
  (into {}
        (keep (fn [line]
                (let [l (str/trim line)]
                  (when-not (or (str/blank? l) (str/starts-with? l "#"))
                    (when-let [idx (str/index-of l "=")]
                      (let [k (str/trim (subs l 0 idx))
                            v (strip-quotes (str/trim (subs l (inc idx))))]
                        (when-not (str/blank? k)
                          [k v])))))))
        (str/split-lines (or text ""))))

(defn load-config
  "Read extension `id`'s dotenv file into a string->string map ({} when the
   file is absent or unreadable)."
  [id]
  (let [f (config-file id)]
    (try
      (if (fs/existsSync f)
        (parse-dotenv (str (fs/readFileSync f "utf8")))
        {})
      (catch :default _ {}))))

(defn get-value
  "Resolve a single config KEY for extension `id`. A non-blank `process.env`
   entry of the same name wins over the file (so a shell export can override);
   returns `default` (nil) when neither has it."
  ([id k] (get-value id k nil))
  ([id k default]
   (let [env (aget js/process.env k)]
     (if (and env (not (str/blank? env)))
       env
       (get (load-config id) k default)))))
