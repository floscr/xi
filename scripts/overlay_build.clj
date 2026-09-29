(ns overlay-build
  "Build for the browser overlay scripts (element picker, design mode, style
   editor): squint → esbuild → one minified IIFE, in pure babashka — no node,
   no npm. Same pipeline as the dotfiles' server-lib.frontend:

   - squint.compiler (bb git dep) compiles .cljs → .mjs in-process
   - babashka.esbuild (esbuild via FFI) bundles the entry module

   The intermediate .mjs files go to a temp dir, so a build leaves nothing in
   the repo but the output file."
  (:require [babashka.esbuild :as esbuild]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [squint.compiler :as squint]
            [squint.compiler-common :as squint-common]))

;; Squint's JVM emitter renders regex literals as (str \/ pattern \/), which
;; differs from what the node CLI (JS RegExp.toString()) produced in two ways:
;;   - forward slashes aren't escaped, so #"/a/(.+)" would emit the invalid
;;     JS literal //a/(.+)/
;;   - leading inline flags like #"(?i)foo" (valid in Java) are invalid in a JS
;;     regex body — strip them and append as JS flags instead.
(defmethod squint-common/emit java.util.regex.Pattern [expr _env]
  (let [s (str expr)
        [_ flags body] (re-matches #"(?s)^\(\?([ims]+)\)(.*)" s)
        body (str/replace (or body s) #"(?<!\\)/" "\\\\/")]
    (str \/ body \/ flags)))

(def ^:private squint-repo-root
  "Root of the squint gitlib checkout. It mirrors the npm package layout
   (core.js at the root, src/squint/*.js), so aliasing \"squint-cljs\" here
   resolves the runtime imports squint emits."
  (delay (-> (io/resource "squint/core.js") fs/path fs/parent fs/parent fs/parent str)))

(defn- path->module
  "Module name for a source file: its path relative to the source root,
   / → . and _ → -. Mirrors the squint CLI, which resolves requires by file
   path rather than by the file's own ns form."
  [root file]
  (-> (str (fs/relativize root file))
      (str/replace #"\.clj[cs]$" "")
      (str/replace "/" ".")
      (str/replace "_" "-")))

(defn- scan-sources
  "Map of module-name → source file for the .cljs/.cljc files under `roots`.
   A .cljs file wins over a .cljc sibling."
  [roots]
  (into {}
        (for [root roots
              :let [root (fs/real-path root)]
              ext ["cljc" "cljs"]
              file (fs/glob root (str "**." ext))]
          [(path->module root file) file])))

(defn- compile-all!
  "Squint-compile every source to <out-dir>/<module>.mjs. Requires of known
   modules become sibling imports; anything else resolves to nil so squint
   treats it as a JS global instead of emitting a bogus import."
  [out-dir sources]
  (let [known (set (keys sources))
        resolve-ns (fn [n] (let [n (str n)] (when (known n) (str "./" n ".mjs"))))]
    (doseq [[module file] sources]
      (spit (fs/file out-dir (str module ".mjs"))
            (squint/compile-string (slurp (fs/file file)) {:resolve-ns resolve-ns})))))

(defn build!
  "Build one overlay script.

     :dir     directory holding the entry source; also the first source root
     :entry   entry module name, e.g. \"picker\" for picker.cljs
     :out     output file name inside :dir, e.g. \"picker.js\"
     :paths   extra source roots (e.g. the clj-ui-framework js runtime)
     :task    the bb task name, for the generated-file banner"
  [{:keys [dir entry out paths task]}]
  (let [sources (scan-sources (cons dir paths))
        tmp (fs/create-temp-dir {:prefix "xi-overlay-"})]
    (try
      (compile-all! tmp sources)
      (let [js (-> (esbuild/build {:entry-points [(str (fs/path tmp (str entry ".mjs")))]
                                   :bundle true
                                   :format :iife
                                   :minify true
                                   :alias {"squint-cljs" @squint-repo-root}})
                   :outputs first :contents)
            out-file (fs/file dir out)]
        (spit out-file
              (str "/* GENERATED from " entry ".cljs by `bb " task "` — do not edit. */\n" js))
        (println (format "Built %s (%.1f KiB)" (str out-file) (/ (count js) 1024.0))))
      (finally
        (fs/delete-tree tmp)))))
