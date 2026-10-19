(ns xi.web.appearance-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.appearance :as appearance]))

(deftest defaults-are-super-collapsed
  (testing "no overrides → super collapsed on, tool + thinking blocks collapsed"
    (let [app (appearance/effective nil)]
      (is (true? (:super-collapsed? app)))
      (is (= :collapsed (:tool-blocks app)))
      (is (= :collapsed (:thinking-blocks app)))
      (is (= :summary (:tool-args app)))
      (is (true? (:terminal-hints? app)))))
  (testing "the retired viewer-mode toggle is gone: viewer mode is always on"
    (is (not (contains? (appearance/effective nil) :viewer-mode?)))
    (is (= {} (appearance/normalize {:viewer-mode? false}))
        "a stored override from before the toggle was removed is dropped"))
  (testing "every default key is present"
    (is (= (set (keys appearance/defaults))
           (set (keys (appearance/effective {})))))))

(deftest overrides-win
  (let [app (appearance/effective {:super-collapsed? false :tool-blocks :open
                                   :terminal-hints? false})]
    (is (false? (:super-collapsed? app)))
    (is (false? (:terminal-hints? app)))
    (is (= :open (:tool-blocks app)))
    (is (= :collapsed (:thinking-blocks app)) "untouched keys keep their default")))

(deftest config-layer-sits-between-defaults-and-overrides
  (let [configured {:tool-blocks :open :thinking-blocks :open}]
    (testing "configured values replace the defaults"
      (is (= :open (:tool-blocks (appearance/effective configured nil)))))
    (testing "browser overrides still win over configured values"
      (is (= :collapsed (:tool-blocks (appearance/effective configured {:tool-blocks :collapsed})))))
    (testing "invalid configured values are dropped"
      (is (= :collapsed (:tool-blocks (appearance/effective {:tool-blocks :sideways} nil)))))
    (testing "effective-in reads both layers from state"
      (is (= {:super-collapsed? true
              :tool-blocks :collapsed :tool-blocks-except [] :thinking-blocks :open
              :tool-args :summary :terminal-hints? true}
             (appearance/effective-in {:web/appearance-config configured
                                       :web/appearance {:tool-blocks :collapsed}}))))))

(deftest normalize-drops-garbage
  (testing "unknown keys and disallowed values are dropped"
    (is (= {:tool-blocks :open :tool-args :all}
           (appearance/normalize {:tool-blocks :open
                                  :thinking-blocks :sideways
                                  :super-collapsed? "yes"
                                  :tool-args :all
                                  :bogus 1})))
    (is (= {} (appearance/normalize {:tool-args :everything}))))
  (testing "non-map input is treated as empty"
    (is (= {} (appearance/normalize nil)))
    (is (= {} (appearance/normalize "junk")))
    (is (= {} (appearance/normalize [:tool-blocks :open]))))
  (testing "a bad override never leaks into the effective map"
    (is (= :collapsed (:tool-blocks (appearance/effective {:tool-blocks :sideways}))))))

(deftest block-collapsed
  (let [open-all  {:tool-blocks :open :thinking-blocks :open}
        collapsed {:tool-blocks :collapsed :thinking-blocks :collapsed}
        tool      (fn [name] {:kind :tool-call :tool name})]
    (is (false? (appearance/block-collapsed? open-all (tool "read"))))
    (is (false? (appearance/block-collapsed? open-all {:kind :thinking})))
    (is (true? (appearance/block-collapsed? collapsed (tool "read"))))
    (is (true? (appearance/block-collapsed? collapsed {:kind :thinking})))
    (testing "text / user / status entries are never collapsible"
      (is (false? (appearance/block-collapsed? collapsed {:kind :text})))
      (is (false? (appearance/block-collapsed? collapsed {:kind :user}))))
    (testing "collapsed + except: the listed tools stay open"
      (let [app (assoc collapsed :tool-blocks-except ["edit"])]
        (is (false? (appearance/block-collapsed? app (tool "edit"))))
        (is (false? (appearance/block-collapsed? app (tool "mcp__xi-tools__edit")))
            "matched without the Claude provider's prefix")
        (is (false? (appearance/block-collapsed? app (tool "Edit"))) "case-insensitive")
        (is (true? (appearance/block-collapsed? app (tool "read"))))))
    (testing "an MCP server's tool keeps its server in the key"
      (let [app (assoc collapsed :tool-blocks-except ["mcp__chrome__click"])]
        (is (false? (appearance/block-collapsed? app (tool "mcp__chrome__click"))))
        (is (false? (appearance/block-collapsed? app (tool "mcp__xi-tools__mcp__chrome__click")))
            "the same tool through the Claude provider")
        (is (true? (appearance/block-collapsed? app (tool "mcp__other__click"))))))
    (testing "open + except: only the listed tools collapse"
      (let [app (assoc open-all :tool-blocks-except ["read"])]
        (is (true? (appearance/block-collapsed? app (tool "read"))))
        (is (false? (appearance/block-collapsed? app (tool "edit"))))))
    (testing "the list never touches thinking blocks"
      (is (true? (appearance/block-collapsed? (assoc collapsed :tool-blocks-except ["thinking"])
                                              {:kind :thinking}))))))

(deftest tool-blocks-except
  (testing "normalized to sorted, distinct tool keys"
    (is (= {:tool-blocks-except ["bash" "edit"]}
           (appearance/normalize {:tool-blocks-except ["edit" " Bash " "mcp__xi-tools__edit"]}))))
  (testing "an empty list is kept, so a user can clear a configured one"
    (is (= [] (:tool-blocks-except (appearance/effective {:tool-blocks-except ["edit"]}
                                                         {:tool-blocks-except []})))))
  (testing "garbage is dropped"
    (is (= {} (appearance/normalize {:tool-blocks-except "edit"})))
    (is (= {} (appearance/normalize {:tool-blocks-except ["edit" 1]})))
    (is (= {} (appearance/normalize {:tool-blocks-except [""]}))))
  (testing "toggle-tool adds and removes one key"
    (is (= ["edit" "bash"] (appearance/toggle-tool ["edit"] "bash")))
    (is (= ["edit"] (appearance/toggle-tool ["edit" "bash"] "bash")))
    (is (= ["bash"] (appearance/toggle-tool nil "bash")))))

(def ^:private tools
  [{:name "read" :source :builtin}
   {:name "edit" :source :builtin}
   {:name "clj_outline" :source :extension :ext "clj-surgeon"}
   {:name "gtd_add" :source :user :ext "gtd"}
   {:name "kb_search" :source :user :ext "kb"}
   {:name "mcp__chrome__click" :source :mcp :ext "chrome"}
   {:name "mcp__chrome__fill" :source :mcp :ext "chrome"}])

(defn- titles [sections] (mapv (fn [[t ts]] [t (mapv :key ts)]) sections))

(deftest tool-groups
  (testing "built-in, bundled extensions, then one group per user extension and MCP server"
    (is (= [[{:source :builtin :ext nil} ["edit" "read"]]
            [{:source :extension :ext nil} ["clj_outline"]]
            [{:source :user :ext "gtd"} ["gtd_add"]]
            [{:source :user :ext "kb"} ["kb_search"]]
            [{:source :mcp :ext "chrome"} ["mcp__chrome__click" "mcp__chrome__fill"]]]
           (mapv (fn [[g ts]] [g (mapv :key ts)]) (appearance/tool-groups tools [])))))
  (testing "an MCP tool is labelled without its server prefix"
    (is (= "click" (:label (first (second (last (appearance/tool-groups tools []))))))))
  (testing "a listed tool the server no longer has shows once, as not available"
    (is (= [[{:source :builtin :ext nil} ["read"]]
            [{:source :unavailable :ext nil} ["gone"]]]
           (mapv (fn [[g ts]] [g (mapv :key ts)])
                 (appearance/tool-groups [{:name "read" :source :builtin}] ["gone" "read"])))))
  (testing "an older server's bare names are plain tools, not unavailable ones"
    (is (= [[{:source :other :ext nil} ["read" "view_image"]]]
           (mapv (fn [[g ts]] [g (mapv :key ts)])
                 (appearance/tool-groups ["read" "view_image"] ["view_image"])))))
  (testing "no list yet (the reply is pending)"
    (is (= [] (appearance/tool-groups nil nil)))))

(deftest tool-sections
  (let [listed ["edit" "gtd_add"]]
    (testing "All: the listed tools first, then every tool per source"
      (is (= [["Selected" ["edit" "gtd_add"]]
              ["Built-in" ["edit" "read"]]
              ["Extensions" ["clj_outline"]]
              ["gtd · user extension" ["gtd_add"]]
              ["kb · user extension" ["kb_search"]]
              ["chrome · MCP" ["mcp__chrome__click" "mcp__chrome__fill"]]]
             (titles (appearance/tool-sections tools listed {})))))
    (testing "every entry's :id is unique, so a tool shown twice has two render keys"
      (let [ids (mapcat (fn [[_ ts]] (map :id ts)) (appearance/tool-sections tools listed {}))]
        (is (= (count ids) (count (distinct ids))))))
    (testing "Selected: only the listed tools, in their source groups"
      (is (= [["Built-in" ["edit"]] ["gtd · user extension" ["gtd_add"]]]
             (titles (appearance/tool-sections tools listed {:filter :selected})))))
    (testing "a source filter keeps that source's groups and no Selected section"
      (is (= [["gtd · user extension" ["gtd_add"]] ["kb · user extension" ["kb_search"]]]
             (titles (appearance/tool-sections tools listed {:filter :user})))))
    (testing "the query filters every section by key, case-insensitive; empty ones drop"
      (is (= [["chrome · MCP" ["mcp__chrome__click"]]]
             (titles (appearance/tool-sections tools listed {:query " CLI "})))))
    (testing "hints: the extension of a bundled tool; in Selected, every tool's source"
      (let [sections (into {} (appearance/tool-sections tools listed {}))]
        (is (= ["built-in" "gtd"] (mapv :hint (sections "Selected"))))
        (is (= ["clj-surgeon"] (mapv :hint (sections "Extensions"))))
        (is (= [nil nil] (mapv :hint (sections "Built-in"))))))))

(deftest tool-filter-counts
  (testing "All and Selected always; a source only when it has tools"
    (is (= [[:all "All" 7] [:selected "Selected" 1] [:builtin "Built-in" 2]
            [:extension "Extensions" 1] [:user "User extensions" 2] [:mcp "MCP" 2]]
           (appearance/tool-filter-counts tools ["edit"])))
    (is (= [[:all "All" 1] [:selected "Selected" 0] [:builtin "Built-in" 1]]
           (appearance/tool-filter-counts [{:name "read" :source :builtin}] [])))))

(deftest tool-summary
  (is (nil? (appearance/tool-summary [])))
  (is (= "edit" (appearance/tool-summary ["edit"])))
  (is (= "edit, click" (appearance/tool-summary ["edit" "mcp__chrome__click"])))
  (is (= "bash, edit +2" (appearance/tool-summary ["bash" "edit" "read" "write"]))))

(deftest overridden
  (is (false? (appearance/overridden? nil)))
  (is (false? (appearance/overridden? {})))
  (is (false? (appearance/overridden? {:bogus 1})) "garbage-only overrides count as none")
  (is (true? (appearance/overridden? {:super-collapsed? false}))))
