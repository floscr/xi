(ns xi.ext.product-search.amazon
  "amazon.de product search (via the shared headless-Chrome CDP client).

   Amazon fully blocks scripted HTTP requests (202/503, empty bodies) and even
   the Jina reader only gets Amazon's bot page, so a real browser is required.
   Loads `https://www.amazon.de/s?k=<query>` and extracts the result cards."
  (:require [clojure.string :as str]
            [xi.ext.product-search.cdp :as cdp]))

(def ^:private HOST "https://www.amazon.de")

;; Ready once search-result cards exist, or a CAPTCHA form appears (truthy so we
;; stop waiting and let EXTRACT_JS report the captcha).
(def ^:private READY_EXPR
  "(()=>{if(document.querySelector('form[action*=\"validateCaptcha\"]'))return 'captcha';return document.querySelectorAll('div[data-component-type=\"s-search-result\"]').length})()")

(def ^:private EXTRACT_JS
  "(() => {
     if (document.querySelector('form[action*=\"validateCaptcha\"]')) {
       return JSON.stringify({captcha: true});
     }
     const clean = s => (s || '').replace(/\\s+/g, ' ').trim();
     const cards = [...document.querySelectorAll('div[data-asin][data-component-type=\"s-search-result\"]')]
       .filter(c => c.getAttribute('data-asin'));
     const items = cards.map(c => {
       const asin = c.getAttribute('data-asin');
       // Newer amazon.de layout keeps the full product title in the
       // title-recipe block (brand line + title, optionally a 'Sponsored' line).
       const recipe = (c.querySelector('[data-cy=\"title-recipe\"]') || {}).innerText || '';
       const sponsored = /^\\s*(Sponsored|Gesponsert)/i.test(recipe);
       let title = clean(recipe.replace(/^\\s*(Sponsored|Gesponsert)\\s*/i, ''));
       if (!title) { const h2 = c.querySelector('h2'); title = clean(h2 ? h2.innerText : ''); }
       const price = clean((c.querySelector('.a-price .a-offscreen') || {}).innerText) || null;
       const iconAlt = (c.querySelector('.a-icon-alt') || {}).innerText || '';
       const rm = iconAlt.match(/([0-9]+[.,][0-9]+)\\s*(?:out of|von)/i);
       const rating = rm ? rm[1].replace(',', '.') : null;
       const revText = (c.querySelector('[data-cy=\"reviews-block\"]') || {}).innerText || '';
       const revm = revText.match(/\\(([0-9.,]+[KMkm]?)\\)/);
       const reviews = revm ? revm[1] : null;
       const prime = !!c.querySelector('.a-icon-prime, [aria-label*=\"Prime\"]');
       return {asin, title, price, rating, reviews, prime, sponsored};
     }).filter(i => i.title);
     return JSON.stringify({items});
   })()")

(defn- format-item [i {:keys [asin title price rating reviews prime sponsored]}]
  (str/join "\n"
            (cond-> [(str (inc i) ". " title (when sponsored "  [Gesponsert]"))]
              price          (conj (str "   Preis: " price))
              rating         (conj (str "   Bewertung: " rating "/5"
                                        (when reviews (str " (" reviews " Rezensionen)"))))
              (and (not rating) reviews) (conj (str "   Rezensionen: " reviews))
              prime          (conj "   Prime: ja")
              asin           (conj (str "   ASIN: " asin))
              asin           (conj (str "   URL: " HOST "/dp/" asin)))))

(defn- format-results [query items]
  (if (empty? items)
    (str "Keine Treffer für \"" query "\" auf amazon.de.")
    (str "amazon.de — Suche: \"" query "\" (" (count items) " Treffer)\n\n"
         (str/join "\n\n" (map-indexed format-item items)))))

(defn tool [{:keys [query limit]} _ctx]
  (cdp/search-tool
   {:tool-name  "amazon_search"
    :query      query
    :limit      limit
    :url        (str HOST "/s?k=" (js/encodeURIComponent query))
    :ready-expr READY_EXPR
    :extract-js EXTRACT_JS
    :format-fn  format-results}))

(def tool-def
  {:name "amazon_search"
   :description "Search amazon.de for products and return matching entries (title, price, rating, review count, Prime, ASIN, product URL).

Use this for shopping / price-lookup tasks on the German Amazon marketplace. Results are live and locale-scoped to amazon.de (prices in EUR). Fetch a specific product's details by navigating to the returned URL (https://www.amazon.de/dp/<ASIN>)."
   :input_schema {:type "object"
                  :properties {:query {:type "string"
                                       :description "Search query, e.g. \"usb c kabel 2m\""}
                               :limit {:type "number"
                                       :description "Max entries to return (1-20, default 10)"}}
                  :required ["query"]}})
