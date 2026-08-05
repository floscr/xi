(ns xi.ext.product-search.geizhals
  "geizhals.at price-comparison search (via the shared headless-Chrome CDP
   client).

   geizhals renders results server-side. A fresh browser profile defaults to
   the gallery view (`.galleryview__item`); we read the product name/URL, the
   lowest price, the offer count, and the star rating. The list view
   (`.listview__item`) is handled as a fallback in case a profile prefers it."
  (:require [clojure.string :as str]
            [xi.ext.product-search.cdp :as cdp]))

(def ^:private HOST "https://geizhals.at")

(def ^:private READY_EXPR
  "document.querySelectorAll('.galleryview__item, .listview__item--product, .listview__item--variant').length")

(def ^:private EXTRACT_JS
  "(() => {
     const clean = s => (s || '').replace(/\\s+/g, ' ').trim();
     const abs = h => { try { return new URL(h, location.href).href; } catch(e){ return h; } };
     let nodes = [...document.querySelectorAll('.galleryview__item')];
     let pre = 'galleryview';
     if (!nodes.length) {
       nodes = [...document.querySelectorAll('.listview__item--product, .listview__item--variant')];
       pre = 'listview';
     }
     const q = (it, sel) => it.querySelector(sel);
     const items = nodes.map(it => {
       const nameA  = q(it, '.' + pre + '__name-link');
       const priceA = q(it, '.' + pre + '__price-link');
       const offerA = q(it, '.' + pre + '__offercount-link, .' + pre + '__offercount');
       const ratingA = q(it, '.' + pre + '__rating-link, .' + pre + '__rating');
       const mpn    = q(it, '.' + pre + '__mpn');
       const rlabel = ratingA ? (ratingA.getAttribute('aria-label') || ratingA.textContent) : '';
       const rm = rlabel.match(/([0-9]+[.,][0-9]+)\\s*von\\s*5/i);
       const cm = rlabel.match(/([0-9.]+)\\s*Bewertung/i);
       const om = clean(offerA ? offerA.textContent : '').match(/([0-9.]+)/);
       return {
         title: clean(nameA ? nameA.textContent : ''),
         url: nameA ? abs(nameA.getAttribute('href')) : null,
         price: clean(priceA ? priceA.textContent : '') || null,
         offers: om ? om[1] : null,
         rating: rm ? rm[1].replace(',', '.') : null,
         reviews: cm ? cm[1] : null,
         mpn: clean(mpn ? mpn.textContent : '') || null
       };
     }).filter(i => i.title);
     return JSON.stringify({items});
   })()")

(defn- format-item [i {:keys [title url price offers rating reviews mpn]}]
  (str/join "\n"
            (cond-> [(str (inc i) ". " title)]
              price   (conj (str "   Bester Preis: " price))
              offers  (conj (str "   Angebote: " offers))
              rating  (conj (str "   Bewertung: " rating "/5"
                                 (when reviews (str " (" reviews " Bewertungen)"))))
              mpn     (conj (str "   MPN: " mpn))
              url     (conj (str "   URL: " url)))))

(defn- format-results [query items]
  (if (empty? items)
    (str "Keine Treffer für \"" query "\" auf geizhals.at.")
    (str "geizhals.at — Suche: \"" query "\" (" (count items) " Treffer)\n\n"
         (str/join "\n\n" (map-indexed format-item items)))))

(defn tool [{:keys [query limit]} _ctx]
  (cdp/search-tool
   {:tool-name  "geizhals_search"
    :query      query
    :limit      limit
    :url        (str HOST "/?fs=" (js/encodeURIComponent query))
    :ready-expr READY_EXPR
    :extract-js EXTRACT_JS
    :format-fn  format-results}))

(def tool-def
  {:name "geizhals_search"
   :description "Search geizhals.at (Austrian price-comparison engine) for products and return matching entries (product name, best price, number of offers, star rating, MPN, product URL).

Use this to compare prices across shops for new hardware/electronics in Austria. Results are live and priced in EUR; the price shown is the lowest across all listed shops. Fetch a product's full offer list by navigating to the returned URL."
   :input_schema {:type "object"
                  :properties {:query {:type "string"
                                       :description "Search query, e.g. \"ssd 1tb nvme\""}
                               :limit {:type "number"
                                       :description "Max entries to return (1-20, default 10)"}}
                  :required ["query"]}})
