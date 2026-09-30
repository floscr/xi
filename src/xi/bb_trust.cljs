(ns xi.bb-trust
  "bb.edn SHA trust. `bb` runs without an approval dialog when the project's
   bb.edn content-hash is in the trust store. Trust is content-addressed
   (sha256 of bb.edn), so a copied bb.edn is trusted too and an edited one
   auto-revokes until re-trusted.

   Note: this trusts bb.edn's inline tasks/:init/:requires — task code that
   lives in separate files is outside the hash.

   Read by the rules engine (`:bb-trusted` match field, xi.rules.store) and by
   the clj extension (/clj trust-bb, `(sh \"bb\" …)`)."
  (:require [cljs.reader :as reader]
            [xi.paths :as paths]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(defn- trust-path []
  (node-path/join (os/homedir) ".config" "xi" "ext" "bb-trust.edn"))

(defn- read-trust []
  (try
    (when (fs/existsSync (trust-path))
      (reader/read-string (fs/readFileSync (trust-path) "utf8")))
    (catch :default _ nil)))

(defn- write-trust! [m]
  (let [p (trust-path)]
    (fs/mkdirSync (node-path/dirname p) #js {:recursive true})
    (fs/writeFileSync p (str (pr-str m) "\n"))))

(defn find-bb-edn
  "Walk up from cwd to the nearest bb.edn; nil when none is found."
  [cwd]
  (loop [dir (paths/real-resolve (or cwd (.cwd js/process)) ".")]
    (let [f (node-path/join dir "bb.edn")]
      (if (fs/existsSync f)
        f
        (let [parent (node-path/dirname dir)]
          (when (not= parent dir) (recur parent)))))))

(defn- sha
  "Hex sha256 of the nearest bb.edn, or nil when none is found."
  [cwd]
  (when-let [f (find-bb-edn cwd)]
    (-> (.createHash crypto "sha256")
        (.update (fs/readFileSync f))
        (.digest "hex"))))

(defn trusted? [cwd]
  (boolean (when-let [s (sha cwd)]
             (contains? (set (:shas (read-trust))) s))))

(defn trust!
  "Persist the nearest bb.edn's sha to the trust store. Returns {:path :sha}
   or nil when no bb.edn is found."
  [cwd]
  (when-let [f (find-bb-edn cwd)]
    (let [s (sha cwd)]
      (write-trust! (update (or (read-trust) {}) :shas (fnil conj #{}) s))
      {:path f :sha s})))
