(ns xi.ext.chrome-mcp.scope-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.ext.chrome-mcp.scope :as scope]))

(deftest strip-chrome-suffix-test
  (is (= "Shovels" (scope/strip-chrome-suffix "Shovels - Google Chrome")))
  (is (= "Foo - Bar" (scope/strip-chrome-suffix "Foo - Bar - Google Chrome")))
  (is (= "Foo" (scope/strip-chrome-suffix "Foo - Chromium")))
  (is (= "No suffix" (scope/strip-chrome-suffix "No suffix")))
  (is (nil? (scope/strip-chrome-suffix nil))))

(deftest parse-pages-test
  (let [text (str "## Pages\n"
                  "6: Shovels | building data (https://app.shovels.ai/)\n"
                  "7: Figma Export (http://localhost:3001/x) [selected]\n"
                  "9: Isolated (https://ex.com/) isolatedContext=abc")
        pages (scope/parse-pages text)]
    (is (= 3 (count pages)))
    (is (= {:id 6 :title "Shovels | building data" :url "https://app.shovels.ai/" :selected? false}
           (nth pages 0)))
    (is (= {:id 7 :title "Figma Export" :url "http://localhost:3001/x" :selected? true}
           (nth pages 1)))
    (is (= "Isolated" (:title (nth pages 2))))
    (is (= "https://ex.com/" (:url (nth pages 2))))))

(deftest parse-pages-no-url-test
  (testing "untitled pages print as a bare URL (no parens) — parsed as :url"
    (is (= [{:id 3 :title "about:blank" :url "about:blank" :selected? false}]
           (scope/parse-pages "3: about:blank")))
    (is (= [{:id 4 :title "chrome://newtab/" :url "chrome://newtab/" :selected? false}]
           (scope/parse-pages "4: chrome://newtab/"))))
  (testing "a plain non-URL title still parses with :url nil"
    (is (= [{:id 5 :title "Loading page" :url nil :selected? false}]
           (scope/parse-pages "5: Loading page")))))

(deftest classify-newtab-url-alias-test
  (testing "mcp reports chrome://new-tab-page/ where CDP reports chrome://newtab/
            — alias-normalized so alignment survives and blanks stay correlatable"
    (let [s {:pages [{:id 1 :title "New Tab" :url "chrome://new-tab-page/" :selected? false}
                     {:id 2 :title "about:blank" :url "about:blank" :selected? true}]
             :cdp-targets [{:url "chrome://newtab/" :title "New Tab" :window-id 100}
                           {:url "about:blank" :title "about:blank" :window-id 200}]
             :wm-windows [{:workspace "web" :title "New Tab - Google Chrome"}
                          {:workspace "work" :title "about:blank - Google Chrome"}]
             :launch-workspace "work"}
          r (scope/classify s)]
      (is (= #{2} (:in-workspace r)) "the blank page's window is on 'work'")
      (is (= {1 "web" 2 "work"} (:page->workspace r)))
      (is (true? (:selected-in? r))))))

(def ^:private sample
  {:pages [{:id 6 :title "Shovels | building data" :url "https://app.shovels.ai/" :selected? false}
           {:id 7 :title "Figma Export" :url "http://localhost:3001/x" :selected? true}]
   :cdp-targets [{:url "https://app.shovels.ai/" :title "Shovels | building data" :window-id 1052}
                 {:url "http://localhost:3001/x" :title "Figma Export" :window-id 1056}]
   :wm-windows [{:workspace "shovels" :title "Shovels | building data - Google Chrome"}
                {:workspace "figma" :title "Figma Export - Google Chrome"}]})

(deftest classify-basic-test
  (testing "launch on 'shovels' → only the Shovels page is in-workspace"
    (let [r (scope/classify (assoc sample :launch-workspace "shovels"))]
      (is (= #{6} (:in-workspace r)))
      (is (= #{1052} (:in-workspace-window-ids r))
          "only the CDP window on 'shovels' is reusable for a new tab")
      (is (= 7 (:selected-id r)))
      (is (false? (:selected-in? r)))
      (is (= {6 "shovels" 7 "figma"} (:page->workspace r)))))
  (testing "launch on 'figma' → the selected Figma page is in-workspace"
    (let [r (scope/classify (assoc sample :launch-workspace "figma"))]
      (is (= #{7} (:in-workspace r)))
      (is (true? (:selected-in? r))))))

(deftest classify-background-tab-test
  (testing "a background tab (not the window's active title) is classified via
            a sibling tab in the same CDP window"
    (let [s {:pages [{:id 1 :title "Active Tab" :url "https://a/" :selected? true}
                     {:id 2 :title "Background Tab" :url "https://b/" :selected? false}]
             :cdp-targets [{:url "https://a/" :title "Active Tab" :window-id 500}
                           {:url "https://b/" :title "Background Tab" :window-id 500}]
             ;; wm only sees the active tab's title for the window
             :wm-windows [{:workspace "work" :title "Active Tab - Google Chrome"}]
             :launch-workspace "work"}
          r (scope/classify s)]
      (is (= #{1 2} (:in-workspace r)) "both tabs share the window → both in-workspace"))))

(deftest classify-failsafe-unknown-window-test
  (testing "a page whose window can't be matched to any X11 window is NOT in-workspace"
    (let [s {:pages [{:id 1 :title "Ghost" :url "https://ghost/" :selected? false}]
             :cdp-targets [{:url "https://ghost/" :title "Ghost" :window-id 999}]
             :wm-windows [{:workspace "work" :title "Something Else - Google Chrome"}]
             :launch-workspace "work"}
          r (scope/classify s)]
      (is (= #{} (:in-workspace r)))
      (is (= {1 nil} (:page->workspace r))))))

(deftest classify-owned-window-test
  (testing "a self-created about:blank window is placed on its recorded workspace even without a title match"
    (let [s {:pages [{:id 8 :title "about:blank" :url "about:blank" :selected? false}]
             :cdp-targets [{:url "about:blank" :title "" :window-id 777}]
             :wm-windows [{:workspace "paint" :title "Shovels - Google Chrome"}]
             :launch-workspace "work"
             :owned-window-workspaces {777 "work"}}
          r (scope/classify s)]
      (is (= #{8} (:in-workspace r)) "owned window on 'work' == launch 'work' -> in-workspace")
      (is (= #{777} (:in-workspace-window-ids r))
          "the owned window on 'work' is reusable for a new tab")))
  (testing "an owned window on ANOTHER workspace is NOT ours-here (scoping survives workspace switches)"
    (let [s {:pages [{:id 8 :title "about:blank" :url "about:blank" :selected? false}]
             :cdp-targets [{:url "about:blank" :title "" :window-id 777}]
             :wm-windows []
             :launch-workspace "work"
             :owned-window-workspaces {777 "apply"}}
          r (scope/classify s)]
      (is (= #{} (:in-workspace r)) "owned blank recorded on 'apply', viewing 'work' -> not in-workspace"))))

(deftest classify-duplicate-url-test
  (testing "two tabs with the SAME url in different windows/workspaces are kept
            distinct by positional correlation (URL matching would collapse them)"
    (let [s {:pages [{:id 1 :title "Dash A" :url "https://app/" :selected? false}
                     {:id 2 :title "Dash B" :url "https://app/" :selected? false}]
             :cdp-targets [{:url "https://app/" :title "Dash A" :window-id 100}
                           {:url "https://app/" :title "Dash B" :window-id 200}]
             :wm-windows [{:workspace "work" :title "Dash A - Google Chrome"}
                          {:workspace "paint" :title "Dash B - Google Chrome"}]
             :launch-workspace "work"}
          r (scope/classify s)]
      ;; URL matching alone would map BOTH pages to the first "https://app/"
      ;; target (window 100 -> 'work') and call both in-workspace. Positional
      ;; correlation keeps page 1 -> window 100 ('work') and page 2 -> window
      ;; 200 ('paint') distinct.
      (is (= #{1} (:in-workspace r)) "only the tab in the launch-workspace window")
      (is (= {1 "work" 2 "paint"} (:page->workspace r))))))

(deftest classify-blank-owned-positional-test
  (testing "a self-healed about:blank page (no correlatable URL) is placed by
            positional order and recognized via its owned window id"
    (let [s {:pages [{:id 5 :title "Shovels" :url "https://app.shovels.ai/" :selected? false}
                     {:id 6 :title "about:blank" :url nil :selected? true}]
             :cdp-targets [{:url "https://app.shovels.ai/" :title "Shovels" :window-id 1052}
                           {:url "about:blank" :title "" :window-id 999}]
             ;; wm can't place the blank window (no title); Shovels is elsewhere
             :wm-windows [{:workspace "shovels" :title "Shovels - Google Chrome"}]
             :launch-workspace "work"
             :owned-window-workspaces {999 "work"}}
          r (scope/classify s)]
      (is (= #{6} (:in-workspace r)) "the owned blank is ours; Shovels ('shovels') is not")
      (is (true? (:selected-in? r))))))

(deftest classify-marked-blank-unaligned-test
  (testing "a guard-created marked blank (about:blank#xi-…) is recognized via
            unique-URL correlation even when the mcp page order does not match
            the CDP target order (positional alignment broken) and a bare
            bootstrap about:blank sits next to it"
    (let [s {:pages [{:id 1 :title "Example Domain" :url "https://example.com/" :selected? true}
                     {:id 2 :title "about:blank" :url "about:blank" :selected? false}
                     {:id 3 :title "about:blank#xi-42-1" :url "about:blank#xi-42-1" :selected? false}]
             ;; CDP reports the same targets in a DIFFERENT order
             :cdp-targets [{:url "about:blank" :title "about:blank" :window-id 100}
                           {:url "https://example.com/" :title "Example Domain" :window-id 200}
                           {:url "about:blank#xi-42-1" :title "" :window-id 300}]
             :wm-windows []
             :launch-workspace "__xi-owned-window__"
             :owned-window-workspaces {300 "__xi-owned-window__"}}
          r (scope/classify s)]
      (is (= #{3} (:in-workspace r)) "only the marked blank in the owned window is ours")
      (is (= #{300} (:in-workspace-window-ids r))))))

(deftest classify-duplicate-url-owned-tiebreak-test
  (testing "URL-fallback ties (same URL in a stale foreign window AND the
            agent's own window) map the mcp-selected page to the owned window
            and the non-selected twin away from it"
    (let [s {:pages [{:id 1 :title "Example Domain" :url "https://example.com/" :selected? false}
                     {:id 2 :title "about:blank" :url "about:blank" :selected? false}
                     {:id 5 :title "Example Domain" :url "https://example.com/" :selected? true}]
             ;; order differs from mcp -> positional alignment is out;
             ;; example.com appears in TWO windows (stale 200, owned 300)
             :cdp-targets [{:url "https://example.com/" :title "Example Domain" :window-id 200}
                           {:url "about:blank" :title "about:blank" :window-id 100}
                           {:url "https://example.com/" :title "Example Domain" :window-id 300}]
             :wm-windows []
             :launch-workspace "__xi-owned-window__"
             :owned-window-workspaces {300 "__xi-owned-window__"}}
          r (scope/classify s)]
      (is (= #{5} (:in-workspace r)) "only the selected page maps to the owned window")
      (is (true? (:selected-in? r)))
      (is (nil? (get-in r [:page->workspace 1])) "foreign twin is not attributed to the owned window"))))

(deftest empty-page-url-test
  (is (true? (scope/empty-page-url? "about:blank")))
  (is (true? (scope/empty-page-url? "chrome://new-tab-page/")))
  (is (true? (scope/empty-page-url? "about:blank#xi-42-7")) "marked blanks are still empty pages")
  (is (false? (scope/empty-page-url? "https://example.com/")))
  (is (false? (scope/empty-page-url? nil))))

(deftest filter-list-pages-text-test
  (let [text (str "## Pages\n"
                  "6: Shovels (https://app.shovels.ai/)\n"
                  "7: Figma (http://localhost:3001/x) [selected]")]
    (is (= (str "## Pages\n6: Shovels (https://app.shovels.ai/)")
           (scope/filter-list-pages-text text #{6})))
    (is (= "## Pages" (scope/filter-list-pages-text text #{})))))
