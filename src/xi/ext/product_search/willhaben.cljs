(ns xi.ext.product-search.willhaben
  "willhaben.at marketplace search (via the shared headless-Chrome CDP client).

   willhaben is a Next.js app that renders results client-side, but ships the
   full result set as SSR data in the `__NEXT_DATA__` <script> — a far more
   stable extraction target than its hashed styled-components DOM. We read
   `props.pageProps.searchResult.advertSummaryList.advertSummary`, whose entries
   carry a `description` (title) and a named `attributes.attribute` list
   (PRICE_FOR_DISPLAY, SEO_URL, LOCATION, STATE, ADID, …)."
  (:require [clojure.string :as str]
            [xi.ext.product-search.cdp :as cdp]))

(def ^:private HOST "https://www.willhaben.at")
(def ^:private SEARCH_PATH "/iad/kaufen-und-verkaufen/marktplatz")

;; __NEXT_DATA__ is part of the SSR HTML, so it is present as soon as the doc
;; loads — ready as soon as the advert list is populated.
(def ^:private READY_EXPR
  "(()=>{try{const nd=JSON.parse(document.getElementById('__NEXT_DATA__').textContent);return (((((nd.props||{}).pageProps||{}).searchResult||{}).advertSummaryList||{}).advertSummary||[]).length}catch(e){return 0}})()")

(def ^:private EXTRACT_JS
  "(() => {
     try {
       const nd = JSON.parse(document.getElementById('__NEXT_DATA__').textContent);
       const arr = (((((nd.props||{}).pageProps||{}).searchResult||{}).advertSummaryList||{}).advertSummary) || [];
       const attr = (a, name) => {
         const list = ((a.attributes||{}).attribute) || [];
         const f = list.find(x => x.name === name);
         return f && f.values && f.values[0];
       };
       const clean = s => (s || '').replace(/\\s+/g, ' ').trim();
       const items = arr.map(a => {
         const seo = attr(a, 'SEO_URL');
         const loc = [attr(a,'LOCATION'), attr(a,'STATE')].filter(Boolean).join(', ');
         return {
           id: attr(a,'ADID') || a.id,
           title: clean(a.description || attr(a,'HEADING')),
           price: clean(attr(a,'PRICE_FOR_DISPLAY')) || null,
           location: loc || null,
           url: seo ? ('https://www.willhaben.at/iad/' + seo) : null
         };
       }).filter(i => i.title);
       return JSON.stringify({items});
     } catch (e) { return JSON.stringify({error: String(e)}); }
   })()")

(defn- format-item [i {:keys [id title price location url]}]
  (str/join "\n"
            (cond-> [(str (inc i) ". " title)]
              price    (conj (str "   Preis: " price))
              location (conj (str "   Ort: " location))
              id       (conj (str "   ID: " id))
              url      (conj (str "   URL: " url)))))

(defn- format-results [query items]
  (if (empty? items)
    (str "Keine Treffer für \"" query "\" auf willhaben.at.")
    (str "willhaben.at — Suche: \"" query "\" (" (count items) " Treffer)\n\n"
         (str/join "\n\n" (map-indexed format-item items)))))

(defn tool [{:keys [query limit]} _ctx]
  (cdp/search-tool
   {:tool-name  "willhaben_search"
    :query      query
    :limit      limit
    :url        (str HOST SEARCH_PATH "?keyword=" (js/encodeURIComponent query))
    :ready-expr READY_EXPR
    :extract-js EXTRACT_JS
    :format-fn  format-results}))

(def tool-def
  {:name "willhaben_search"
   :description "Search willhaben.at (Austria's largest classifieds marketplace) for used & new listings and return matching entries (title, price, location, ad ID, listing URL).

Use this for second-hand / private-sale / marketplace lookups in Austria. Results are live and priced in EUR. Fetch a listing's details by navigating to the returned URL."
   :input_schema {:type "object"
                  :properties {:query {:type "string"
                                       :description "Search query, e.g. \"fahrrad 28 zoll\""}
                               :limit {:type "number"
                                       :description "Max entries to return (1-20, default 10)"}}
                  :required ["query"]}})
