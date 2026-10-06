(ns xi.web.user-ext.sci
  "Evaluates user extensions' web halves in the browser — the entry point of
   the lazily loaded `:user-ext` shadow module (SCI only ships to browsers
   whose server actually has user web halves).

   Each bundle ({:id :ns :sources {ns-name source} :tools [name …]}, from the
   server's xi.ext.user/web-bundles) gets its own hardened SCI context
   (xi.sandbox.sci): no js/ access, no aget/eval. Its requires resolve only
   against the bundle's own sources. The exposed host namespaces are pure
   hiccup builders: a curated slice of xi.web.views, the pure diff parser +
   markdown renderer behind them, and the clj-ui-framework components that
   render plain hiccup (ui.command / ui.context-menu are left out — they hand
   data to a JS runtime the sanitizer can't see)."
  (:require [sci.core :as sci]
            [ui.badge]
            [ui.button]
            [ui.empty-state]
            [ui.form]
            [ui.icon]
            [ui.lightbox]
            [ui.sidebar]
            [ui.theme-toggle]
            [ui.toolbar]
            [xi.core.state]
            [xi.diff :as diff]
            [xi.markdown.hiccup :as md]
            [xi.sandbox.sci :as sandbox]
            [xi.web.user-ext.guard :as guard]
            [xi.web.views :as views]))

(def ^:private exposed-namespaces
  {'xi.web.views    {'nav-group      views/nav-group
                     'overflow-menu  views/overflow-menu
                     'spinner        views/spinner
                     'shorten-path   views/shorten-path
                     'diff-rows-view views/diff-rows-view}
   ;; unified diff text → rows for views/diff-rows-view
   'xi.diff         {'parse-diff-text diff/parse-diff-text
                     'diff-rows       diff/diff-rows}
   ;; markdown string → hiccup (sanitized like any other page output)
   'xi.markdown.hiccup {'render md/render}
   'xi.core.state   (sci/copy-ns xi.core.state   (sci/create-ns 'xi.core.state))
   'ui.badge        (sci/copy-ns ui.badge        (sci/create-ns 'ui.badge))
   'ui.button       (sci/copy-ns ui.button       (sci/create-ns 'ui.button))
   'ui.empty-state  (sci/copy-ns ui.empty-state  (sci/create-ns 'ui.empty-state))
   'ui.form         (sci/copy-ns ui.form         (sci/create-ns 'ui.form))
   'ui.icon         (sci/copy-ns ui.icon         (sci/create-ns 'ui.icon))
   'ui.lightbox     (sci/copy-ns ui.lightbox     (sci/create-ns 'ui.lightbox))
   'ui.sidebar      (sci/copy-ns ui.sidebar      (sci/create-ns 'ui.sidebar))
   'ui.theme-toggle (sci/copy-ns ui.theme-toggle (sci/create-ns 'ui.theme-toggle))
   'ui.toolbar      (sci/copy-ns ui.toolbar      (sci/create-ns 'ui.toolbar))})

(defn- eval-bundle
  "The `web-extension` map of one bundle (unwrapped), or throws."
  [{:keys [ns sources]}]
  (let [ctx (sandbox/init
             {:namespaces exposed-namespaces
              :load-fn    (fn [{:keys [namespace]}]
                            (when-let [src (get sources (str namespace))]
                              {:file (str namespace) :source src}))})]
    (sci/eval-string* ctx (get sources ns))
    ;; eval-string* resets *ns*, so read the var qualified
    (try (sci/eval-string* ctx (str ns "/web-extension"))
         (catch :default _ nil))))

(defn load!
  "Evaluate `bundles` → [{:id :web-ext :error}] in order. `taken` holds the
   ids / route segments / page keywords already in use; accepted halves are
   guard/wrap'ed (sanitized pages, filtered dispatch)."
  [bundles {:keys [taken-ids taken-segments taken-pages]}]
  (loop [bs bundles, ids taken-ids, segs taken-segments, pages taken-pages, acc []]
    (if-let [b (first bs)]
      (let [entry (try
                    (let [ext (eval-bundle b)]
                      (cond
                        (and (map? ext) (not= (:id ext) (:id b)))
                        {:id (:id b) :error (str "web-extension :id " (:id ext)
                                                 " must match the extension's :id " (:id b))}
                        :else
                        (if-let [reason (guard/validate ext {:taken-ids ids
                                                             :taken-segments segs
                                                             :taken-pages pages
                                                             :own-tools (:tools b)})]
                          {:id (:id b) :error reason}
                          {:id (:id b) :web-ext (guard/wrap ext)})))
                    (catch :default e
                      {:id (:id b) :error (str "eval error: " (.-message e))}))
            ext   (:web-ext entry)]
        (recur (rest bs)
               (cond-> ids ext (conj (:id ext)))
               (into segs (keys (:routes ext)))
               (into pages (keys (:pages ext)))
               (conj acc entry)))
      acc)))
