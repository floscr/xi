(ns xi.web.user-ext-test
  (:require [cljs.test :refer [deftest is testing]]
            [xi.web.user-ext.guard :as guard]
            [xi.web.user-ext.sci :as user-sci]))

;; ── sanitize ─────────────────────────────────────────────────────────────────

(deftest sanitize-strips-script-capable-markup
  (testing "blocked elements vanish with their children"
    (is (= [:div nil nil [:p "ok"]]
           (guard/sanitize [:div [:script "alert(1)"] [:iframe {:src "x"}] [:p "ok"]])))
    (is (nil? (guard/sanitize [:SCRIPT.x "y"])) "case + class suffix don't sneak past"))
  (testing "string handlers, innerHTML, srcdoc are dropped; :on fns stay"
    (let [f (fn [_])
          [_ attrs] (guard/sanitize [:button {:onclick "alert(1)" :onmouseover "x"
                                              :innerHTML "<img>" :srcdoc "x"
                                              :on {:click f :hover "alert(1)"}
                                              :class "b"}])]
      (is (= {:on {:click f} :class "b"} attrs))))
  (testing "script-capable URLs are removed, ordinary ones kept"
    (is (= [:a {} "x"] (guard/sanitize [:a {:href "javascript:alert(1)"} "x"])))
    (is (= [:a {} "x"] (guard/sanitize [:a {:href " JaVa\tScRiPt:alert(1)"} "x"])))
    (is (= [:a {} "x"] (guard/sanitize [:a {:href "data:text/html,<script>"} "x"])))
    (is (= [:a {:href "https://example.com"} "x"] (guard/sanitize [:a {:href "https://example.com"} "x"])))
    (is (= [:img {:src "data:image/png;base64,AA"}] (guard/sanitize [:img {:src "data:image/png;base64,AA"}])))
    (is (= [:img {}] (guard/sanitize [:img {:src "data:image/svg+xml,<svg onload>"}]))
        "SVG data URLs can carry script"))
  (testing "nested seqs and vectors are walked"
    (is (= [:ul (list [:li "a"] nil)]
           (guard/sanitize [:ul (list [:li "a"] [:script "x"])])))))

;; ── validate ─────────────────────────────────────────────────────────────────

(deftest validate-web-halves
  (let [ok {:id :notes :pages {:notes/list (fn [_ _])} :routes {"notes" {:parse identity}}}]
    (is (nil? (guard/validate ok {})))
    (is (re-find #"namespaced :notes/" (guard/validate (assoc ok :pages {:chat (fn [_ _])}) {}))
        "a user page can't claim a built-in page like :chat")
    (is (re-find #"segment already in use" (guard/validate (assoc ok :routes {"chat" {}}) {})))
    (is (re-find #"segment already in use" (guard/validate ok {:taken-segments #{"notes"}})))
    (is (re-find #"disallowed keys.*handlers" (guard/validate (assoc ok :handlers {}) {})))
    (is (re-find #"already in use" (guard/validate ok {:taken-ids #{:notes}})))))

(deftest validate-sidebar-groups-and-session-menu-items
  (let [group {:id :favs/group :label "Favorites" :where :favorite? :limit 5
               :more {:label "All" :event {:type :ext.favs/open}}}
        item  {:label "Add" :label-on "Remove" :flag :favorite?
               :event {:type :ext.favs/toggle}}
        ok    {:id :favs :sidebar-groups [group] :session-menu-items [item]}]
    (is (nil? (guard/validate ok {})))
    (testing "a group's id is namespaced by the extension, so it can't take a core group's collapsed state"
      (is (re-find #":sidebar-groups must be"
                   (guard/validate (assoc-in ok [:sidebar-groups 0 :id] :recent) {})))
      (is (re-find #":sidebar-groups must be"
                   (guard/validate (assoc-in ok [:sidebar-groups 0 :id] :other/group) {}))))
    (testing "shapes"
      (doseq [bad [(assoc group :label :x) (assoc group :where "favorite?")
                   (assoc group :limit 0) (assoc group :more {:event {}})]]
        (is (re-find #":sidebar-groups must be"
                     (guard/validate (assoc ok :sidebar-groups [bad]) {}))))
      (is (nil? (guard/validate (assoc ok :sidebar-groups [(dissoc group :limit :more)]) {}))
          "limit and more are optional")
      (doseq [bad [(dissoc item :event) (assoc item :label nil) (assoc item :flag "x")
                   (assoc item :label-on 1)]]
        (is (re-find #":session-menu-items must be"
                     (guard/validate (assoc ok :session-menu-items [bad]) {}))))
      (is (re-find #":sidebar-groups must be"
                   (guard/validate (assoc ok :sidebar-groups {:id 1}) {}))))))

(deftest wrapped-sidebar-groups-and-menu-items-only-carry-allowed-events
  (let [ext (guard/wrap
             {:id :favs
              :sidebar-groups
              [{:id :favs/group :label "F" :where :favorite?
                :more {:label "All" :event {:type :route/navigate :page :favs/list}}}
               {:id :favs/other :label "G" :where :favorite?
                :more {:label "Evil" :event {:type :agent/abort}}}]
              :session-menu-items
              [{:label "Add" :event {:type :ext.favs/toggle}}
               {:label "Go" :event {:type :route/navigate :page :favs/list}}
               {:label "Evil" :event {:type :agent/abort}}]})]
    (testing "own events become forwards (the card's :session-id merges into the wrapper); navigation passes; the rest is dropped"
      (is (= [{:label "Add" :event {:type :user-ext/forward :event {:type :ext.favs/toggle}}}
              {:label "Go" :event {:type :route/navigate :page :favs/list}}]
             (:session-menu-items ext))))
    (is (= {:type :route/navigate :page :favs/list}
           (get-in ext [:sidebar-groups 0 :more :event])))
    (is (nil? (get-in ext [:sidebar-groups 1 :more])) "a blocked :more row is dropped, the group stays")
    (testing "the forward reaches the server as the extension's event with the session id"
      (is (= {:type :ext.favs/toggle :session-id "s1" :room-id "r1"}
             (guard/forward-event (assoc (get-in ext [:session-menu-items 0 :event])
                                         :session-id "s1")
                                  "r1"))))))

(deftest tool-views-render-only-the-extensions-own-tools
  (let [ok {:id :notes :tool-views {"notes_add" (fn [_])}}]
    (is (nil? (guard/validate ok {:own-tools ["notes_add"]})))
    (is (re-find #"own tools: write"
                 (guard/validate (assoc-in ok [:tool-views "write"] (fn [_]))
                                 {:own-tools ["notes_add"]}))
        "a web half can't restyle a builtin or another extension's tool")
    (is (re-find #"must be a map" (guard/validate (assoc ok :tool-views [:x]) {})))))

(deftest wrapped-tool-view-is-sanitized-and-falls-back-on-a-throw
  (let [ext (guard/wrap {:id :notes
                         :tool-views {"notes_add" (fn [{:keys [arguments]} slice]
                                                    [:p [:script "x"] (:title arguments) (:n slice)])
                                      "notes_boom" (fn [_ _] (throw (js/Error. "kaput")))}})]
    (is (= [:p nil "hi" 1] ((get-in ext [:tool-views "notes_add"]) {:arguments {:title "hi"}} {:n 1})))
    (is (nil? ((get-in ext [:tool-views "notes_boom"]) {} nil))
        "nil → the tool block shows its plain text result")))

;; ── dispatch filtering + forwarding ──────────────────────────────────────────

(deftest guarded-dispatch
  (let [seen (atom [])
        d    (guard/guard-dispatch :notes #(swap! seen conj %))]
    (d {:type :ext.notes/save :text "x"})
    (d {:type :route/navigate :page :notes/list})
    (d {:type :ui/dialog-response :value true})
    (d {:type :ext.other/steal})
    (is (= [{:type :user-ext/forward :event {:type :ext.notes/save :text "x"}}
            {:type :route/navigate :page :notes/list}]
           @seen))))

(deftest ext-ui-set-writes-the-extensions-own-ui-state
  (let [seen (atom [])
        d    (guard/guard-dispatch :notes #(swap! seen conj %))]
    (d {:type :ext-ui/set :path [:code] :value "abc"})
    (d {:type :ext-ui/set :path [:code] :value nil})
    (d {:type :ext-ui/set :path [] :value "x"})
    (d {:type :ext-ui/set :path [:a] :value {:evil true}})
    (d {:type :ext-ui/set :path "code" :value "x"})
    (is (= [{:type :user-ext/ui-set :ext-id :notes :path [:code] :value "abc"}
            {:type :user-ext/ui-set :ext-id :notes :path [:code] :value nil}]
           @seen)
        "the id is the extension's own; empty paths and non-scalar values are dropped")))

(deftest bound-input-reads-and-writes-the-extensions-ui-state
  (let [seen (atom [])
        ext  (guard/wrap {:id :notes
                          :pages {:notes/p (fn [_ _] [:input {:bind [:code] :type "text"}])}})
        page (get-in ext [:pages :notes/p])
        st   {:user-ext/ui {:notes {:code "hello"} :other {:code "secret"}}}
        [_ attrs] (page st #(swap! seen conj %))]
    (is (= "hello" (:value attrs)) "reads its own slice, never another extension's")
    (is (not (contains? attrs :bind)))
    ((get-in attrs [:on :input]) #js {:target #js {:value "hello!"}})
    (is (= [{:type :user-ext/ui-set :ext-id :notes :path [:code] :value "hello!"}] @seen))
    (testing "unset state is an empty input; a bad path just drops the attribute"
      (is (= "" (:value (second (page {} identity)))))
      (let [bad (guard/wrap {:id :notes :pages {:notes/p (fn [_ _] [:input {:bind "nope"}])}})]
        (is (= [:input {}] ((get-in bad [:pages :notes/p]) {} identity)))))))

(deftest forward-event-adds-room-and-menu-ctx
  (is (= {:type :ext.notes/save :text "x" :room-id "r1" :cwd "/p"}
         (guard/forward-event {:type :user-ext/forward :cwd "/p"
                               :event {:type :ext.notes/save :text "x"}}
                              "r1")))
  (is (nil? (guard/forward-event {:type :user-ext/forward :event {:type :agent/abort}} "r1"))
      "only extension events are forwarded"))

(deftest wrapped-page-is-sanitized-and-isolated
  (let [ext (guard/wrap {:id :notes
                         :pages {:notes/list (fn [_ _] [:div [:script "x"] "hi"])
                                 :notes/boom (fn [_ _] (throw (js/Error. "kaput")))}
                         :nav-items [{:menu :sidebar :label "Notes" :event {:type :ext.notes/open}}
                                     {:menu :sidebar :label "Evil" :event {:type :agent/abort}}]})]
    (is (= [:div nil "hi"] ((get-in ext [:pages :notes/list]) {} identity)))
    (is (re-find #"failed to render: kaput"
                 (str ((get-in ext [:pages :notes/boom]) {} identity))))
    (is (= [{:menu :sidebar :label "Notes"
             :event {:type :user-ext/forward :event {:type :ext.notes/open}}}]
           (:nav-items ext))
        "own nav events become forwards; foreign ones are dropped")))

;; ── the sandboxed evaluator ──────────────────────────────────────────────────

(deftest load!-evaluates-a-bundle-in-the-sandbox
  (let [bundle {:id :notes :ns "notes.web"
                :sources {"notes.web"
                          (str "(ns notes.web (:require [notes.ui :as ui]))"
                               "(def web-extension"
                               "  {:id :notes"
                               "   :routes {\"notes\" {:parse (fn [_] {:page :notes/list})}}"
                               "   :pages {:notes/list (fn [st dispatch!] (ui/view st))}})")
                          "notes.ui"
                          "(ns notes.ui) (defn view [_] [:section [:script \"x\"] \"notes\"])"}}
        [r]    (user-sci/load! [bundle] {})]
    (is (nil? (:error r)) (str (:error r)))
    (is (= [:section nil "notes"] ((get-in r [:web-ext :pages :notes/list]) {} identity))
        "sibling requires resolve against the bundle, output is sanitized")
    (is (= {:page :notes/list} ((get-in r [:web-ext :routes "notes" :parse]) [])))))

(deftest load!-rejects-escapes-and-mismatched-ids
  (let [src (fn [ns body] {:id :x :ns ns :sources {ns (str "(ns " ns ") " body)}})
        [a b c] (user-sci/load!
                 [(src "a.web" "(def web-extension {:id :x :pages {:x/p ((js/Function \"return 1\"))}})")
                  (src "b.web" "(def web-extension {:id :other})")
                  (src "c.web" "(def web-extension {:id :x :pages {:x/p (fn [_ _] (aget (js-obj) \"constructor\"))}})")]
                 {})]
    (is (re-find #"eval error" (:error a)) "no js/ access")
    (is (re-find #"must match" (:error b)))
    (is (re-find #"eval error.*aget is not allowed" (:error c)) "denied core fns rejected at load")))
