(ns xi.task-trust
  "Project task file trust (bb.edn, package.json, deno.json — the runners of
   xi.rules.tasks). A task runs without an approval dialog when its project
   file's content hash is in the trust store. Trust is content-addressed
   (sha256 of the file), so a copied file is trusted too and an edited one
   auto-revokes until re-trusted.

   Note: this trusts the file only — task code in other files (bb scripts,
   npm pre/post hooks, node_modules binaries) is outside the hash.

   Read by the rules engine (`:task-file-trusted` match field, xi.rules.store)
   and by the clj extension (/clj trust-tasks, the trust ask's [a]lways)."
  (:require [cljs.reader :as reader]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(defn- config-file [file-name]
  (node-path/join (os/homedir) ".config" "xi" "ext" file-name))

(defn- trust-path [] (config-file "task-trust.edn"))

(defn- read-shas [p]
  (try
    (when (fs/existsSync p)
      (set (:shas (reader/read-string (fs/readFileSync p "utf8")))))
    (catch :default _ nil)))

;; bb-trust.edn is the store from before other runners were trusted; its
;; bb.edn shas still count.
(defn- trusted-shas []
  (into (or (read-shas (trust-path)) #{})
        (read-shas (config-file "bb-trust.edn"))))

(defn- sha [file]
  (-> (.createHash crypto "sha256")
      (.update (fs/readFileSync file))
      (.digest "hex")))

(defn trusted?
  "Whether project file `file` (a path, may be nil) is in the trust store."
  [file]
  (boolean (when (and file (fs/existsSync file))
             (contains? (trusted-shas) (sha file)))))

(defn trust!
  "Persist `file`'s sha to the trust store. Returns {:path :sha}, or nil when
   there is no such file."
  [file]
  (when (and file (fs/existsSync file))
    (let [s (sha file)
          p (trust-path)]
      (fs/mkdirSync (node-path/dirname p) #js {:recursive true})
      (fs/writeFileSync p (str (pr-str {:shas (conj (trusted-shas) s)}) "\n"))
      {:path file :sha s})))
