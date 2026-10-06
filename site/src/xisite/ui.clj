(ns xisite.ui
  "Layout: head, header, footer, page wrapper. Buttons are
   clj-ui-framework components; their tokens and CSS come from /css/ui.css."
  (:require [hiccup2.core :as h]
            [ui.button :as button]
            [xisite.core :as core]
            [xisite.livereload :as livereload]
            [xisite.theme :as theme]))

(def ^:private logo
  [:svg.logo-mark {:viewBox "0 0 32 32" :width "28" :height "28" :aria-hidden "true"}
   [:rect {:x "1" :y "1" :width "30" :height "30" :rx "8" :fill "currentColor"}]
   [:text {:x "16" :y "22.5" :text-anchor "middle" :font-size "19"
           :font-family "Georgia, 'Times New Roman', serif" :font-style "italic"
           :fill "var(--bg-0)"} "ξ"]])

(def ^:private github-icon
  [:svg {:viewBox "0 0 24 24" :width "16" :height "16" :aria-hidden "true"
         :fill "none" :stroke "currentColor" :stroke-width "2"
         :stroke-linecap "round" :stroke-linejoin "round"}
   [:path {:d "M9 19c-4.3 1.4-4.3-2.5-6-3m12 5v-3.5c0-1 .1-1.4-.5-2c2.8-.3 5.5-1.4 5.5-6a4.6 4.6 0 0 0-1.3-3.2a4.2 4.2 0 0 0-.1-3.2s-1.1-.3-3.5 1.3a12.3 12.3 0 0 0-6.2 0C6.5 2.8 5.4 3.1 5.4 3.1a4.2 4.2 0 0 0-.1 3.2A4.6 4.6 0 0 0 4 9.5c0 4.6 2.7 5.7 5.5 6c-.6.6-.6 1.2-.5 2V21"}]])

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

(defn- nav-button [{:keys [href active?]} & children]
  (apply button/button
         {:variant :ghost :href href
          :class (when active? "is-active")
          :attrs (cond-> {} active? (assoc :aria-current "page"))}
         children))

(defn header [path]
  (let [docs? (.startsWith ^String path "/docs")]
    [:header.site-header
     [:div.site-header-inner
      [:a.brand {:href "/"} logo [:span "xi"]]
      [:nav.site-nav
       (nav-button {:href "/docs/" :active? (and docs? (not= path "/docs/getting-started/"))} "Docs")
       (nav-button {:href "/docs/getting-started/" :active? (= path "/docs/getting-started/")} "Install")
       (nav-button {:href core/repo-url} github-icon [:span "GitHub"])]]]))

(defn footer []
  [:footer.site-footer
   [:div.site-footer-inner
    [:span "Xi is MIT licensed. Made by "
     [:a {:href "https://florianschroedl.com" :rel "noopener"} "Florian Schrödl"] "."]
    [:span [:a {:href core/repo-url :rel "noopener"} "Source"] " · "
     [:a {:href "/docs/"} "Docs"]]]])

(defn render-page [hiccup]
  (str "<!DOCTYPE html>" (h/html {:mode :html} hiccup)))

(defn layout
  [{:keys [title description path body-class]} & body]
  [:html {:lang "en"}
   [:head (head {:title title :description description :path path})]
   [:body {:class body-class}
    (header path)
    (into [:main] body)
    (footer)
    [:script {:src "/js/ui-runtime.js"}]
    [:script {:src "/js/site.js" :defer true}]]])
