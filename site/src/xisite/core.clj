(ns xisite.core
  "Site-wide constants.")

(def site-url "https://xi.florianschroedl.com")
(def site-title "Xi")
(def site-description
  "An AI harness for the terminal and the browser, with Clojure scripting access and permission gating via rules. Configurable extensions and MCP support.")

(def repo-url "https://github.com/floscr/xi")

;; Where the reference docs live on the forge, for guide links that point at
;; ../something.md (outside docs/guide).
(def reference-docs-url (str repo-url "/blob/master/docs/"))

(def ^:dynamic *dev*
  "True under the dev server: live reload script is injected."
  false)

(defn absolute-url [path]
  (str site-url path))
