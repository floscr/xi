(ns xisite.ui
  "Layout: head, header, footer, page wrapper. Buttons are
   clj-ui-framework components; their tokens and CSS come from /css/ui.css."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [ui.button :as button]
            [xisite.core :as core]
            [xisite.livereload :as livereload]
            [xisite.theme :as theme]))

(def logo-glyph
  "The wordmark's shapes in a 55x40 viewBox, filled with the header logo's
   gradient: reusable on any page, since the header defines it."
  [:g {:fill "url(#logo-gradient)"}
   [:polygon {:points "0,0 9,0 36,40 27,40"}]
   [:polygon {:points "27,0 36,0 9,40 0,40"}]
   [:rect {:x "46" :y "0" :width "9" :height "9"}]
   [:rect {:x "46" :y "15" :width "9" :height "25"}]])

(def ^:private logo
  "The web client's gradient wordmark (xi.web.views/launch-logo); main.css
   turns its stops with the theme's accent like the client's style.css."
  [:svg.logo-mark {:viewBox "0 0 55 40" :width "30" :height "22" :aria-hidden "true"}
   [:defs
    [:linearGradient {:id "logo-gradient" :gradientUnits "userSpaceOnUse"
                      :x1 "0" :y1 "0" :x2 "64" :y2 "40"}
     [:stop.logo-stop-start {:offset "0" :stop-color "#e83fb4"}]
     [:stop.logo-stop-mid {:offset "0.55" :stop-color "#8b4cf0"}]
     [:stop.logo-stop-end {:offset "1" :stop-color "#3b8fd6"}]]]
   logo-glyph])

(def ^:private github-icon
  [:svg {:viewBox "0 0 24 24" :width "16" :height "16" :aria-hidden "true"
         :fill "none" :stroke "currentColor" :stroke-width "2"
         :stroke-linecap "round" :stroke-linejoin "round"}
   [:path {:d "M9 19c-4.3 1.4-4.3-2.5-6-3m12 5v-3.5c0-1 .1-1.4-.5-2c2.8-.3 5.5-1.4 5.5-6a4.6 4.6 0 0 0-1.3-3.2a4.2 4.2 0 0 0-.1-3.2s-1.1-.3-3.5 1.3a12.3 12.3 0 0 0-6.2 0C6.5 2.8 5.4 3.1 5.4 3.1a4.2 4.2 0 0 0-.1 3.2A4.6 4.6 0 0 0 4 9.5c0 4.6 2.7 5.7 5.5 6c-.6.6-.6 1.2-.5 2V21"}]])

(def ^:private npm-icon
  [:svg {:viewBox "0 0 24 24" :width "16" :height "16" :aria-hidden "true"
         :fill "none" :stroke "currentColor" :stroke-width "2"
         :stroke-linecap "round" :stroke-linejoin "round"}
   [:path {:d "M1 8h22v7h-12v2h-4v-2h-6z"}]
   [:path {:d "M7 8v7M14 8v7M17 11v4M4 11v4M11 11v1M20 11v4"}]])

(defn head [{:keys [title description path]}]
  (let [canonical (core/absolute-url path)]
    (list
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width,initial-scale=1"}]
     [:meta {:name "color-scheme" :content "light dark"}]
     ;; before the stylesheets: applies a stored light/dark choice pre-paint
     [:script (h/raw theme/head-script)]
     [:link {:rel "icon" :type "image/svg+xml" :href "/favicon.svg"}]
     [:link {:rel "canonical" :href canonical}]
     [:title title]
     [:meta {:name "description" :content description}]
     [:link {:rel "stylesheet" :href "/css/ui.css"}]
     [:link {:rel "stylesheet" :href "/css/main.css"}]
     [:meta {:property "og:type" :content "website"}]
     [:meta {:property "og:url" :content canonical}]
     [:meta {:property "og:title" :content title}]
     [:meta {:property "og:description" :content description}]
     (when core/*dev*
       [:script (h/raw livereload/script)]))))

(defn- nav-button
  "A `:label` makes it icon-only: the label becomes its aria-label and tooltip."
  [{:keys [href active? label]} & children]
  (apply button/button
         {:variant :ghost :href href
          :class (some->> [(when active? "is-active") (when label "btn-icon")]
                     (remove nil?) seq (str/join " "))
          :attrs (cond-> {}
                   active? (assoc :aria-current "page")
                   label (assoc :aria-label label :title label))}
         children))

(defn header [path]
  (let [docs? (.startsWith ^String path "/docs")
        changelog? (= path "/changelog/")
        blog? (.startsWith ^String path "/blog")]
    [:header.site-header
     [:div.site-header-inner
      [:a.brand {:href "/" :aria-label "Xi home"} logo]
      [:nav.site-nav
       (nav-button {:href "/docs/" :active? (and docs? (not= path "/docs/getting-started/"))} "Docs")
       (nav-button {:href "/docs/getting-started/" :active? (= path "/docs/getting-started/")} "Install")
       (nav-button {:href "/extensions/" :active? (= path "/extensions/")} "Extensions")
       (nav-button {:href "/blog/" :active? blog?} "Blog")
       (nav-button {:href "/changelog/" :active? changelog?} "Changelog")
       (nav-button {:href core/npm-url :label "npm"} npm-icon)
       (nav-button {:href core/repo-url :label "GitHub"} github-icon)]]]))

(defn footer []
  [:footer.site-footer
   [:div.site-footer-inner
    [:span "Xi is MIT licensed. Made by "
     [:a {:href "https://florianschroedl.com" :rel "noopener"} "Florian Schrödl"] "."]
    [:span [:a {:href core/repo-url :rel "noopener"} "Source"] " · "
     [:a {:href core/npm-url :rel "noopener"} "npm"] " · "
     [:a {:href "/docs/"} "Docs"] " · "
     [:a {:href "/extensions/"} "Extensions"] " · "
     [:a {:href "/blog/"} "Blog"] " · "
     [:a {:href "/changelog/"} "Changelog"]]]])

(defn render-page [hiccup]
  (str "<!DOCTYPE html>" (h/html {:mode :html} hiccup)))

(defn layout
  "`scripts`: hiccup for the end of <body>, after the site's own."
  [{:keys [title description path body-class scripts]} & body]
  [:html {:lang "en"}
   [:head (head {:title title :description description :path path})]
   [:body {:class body-class}
    (header path)
    (into [:main] body)
    (footer)
    [:script {:src "/js/ui-runtime.js"}]
    [:script {:src "/js/site.js" :defer true}]
    scripts]])
