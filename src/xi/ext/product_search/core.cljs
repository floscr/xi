(ns xi.ext.product-search.core
  "Product-search extension: shopping / price lookups across amazon.de,
   willhaben.at, and geizhals.at.

   All three tools share one lazily-launched headless Chrome (driven over the
   DevTools Protocol, see xi.ext.product-search.cdp) — several of these sites
   block scripted HTTP or render results client-side, so a real browser is
   required. Chrome is launched on the first search and killed on shutdown.

   Tools:
     amazon_search      search amazon.de
     willhaben_search   search willhaben.at (Austrian classifieds)
     geizhals_search    search geizhals.at (Austrian price comparison)

   Env:
     XI_PRODUCT_SEARCH_CHROME   Chrome/Chromium binary path
                                (legacy XI_AMAZON_CHROME still honored)"
  (:require [xi.ext.product-search.amazon :as amazon]
            [xi.ext.product-search.willhaben :as willhaben]
            [xi.ext.product-search.geizhals :as geizhals]
            [xi.ext.product-search.cdp :as cdp]))

(def extension
  {:id               :product-search
   :tool-definitions [amazon/tool-def willhaben/tool-def geizhals/tool-def]
   :tool-registry    {"amazon_search"    amazon/tool
                      "willhaben_search" willhaben/tool
                      "geizhals_search"  geizhals/tool}
   :on-shutdown      cdp/kill-session!})
