(ns xi.blocks.hiccup
  "The `hiccup` block kind's allowlist (xi.blocks). An agent writes a tree as
   JSON arrays (`[\"ul\", [\"li\", \"a\"]]`) or an EDN string; `normalize`
   keeps only allowlisted tags and attributes and reports what it dropped, so
   the tool result can tell the model.

   The kept tree is data only: keyword tags, an attribute map on every
   element, string children. It carries no class, id, style, data-* or on*
   attribute; the look comes from a few semantic attributes (`:tone`,
   `:emphasis`, `:layout`, `:gap`, `:align`) the web renderer maps to its own
   classes, and from ui.* components named as tags (`:ui/button`). Controls
   name a block action (`:action`); xi.ext.blocks.web binds them, and the
   only thing they can do is record that action on their block."
  (:require [clojure.string :as str]
            #?(:cljs [cljs.tools.reader.edn :as edn]
               :clj  [clojure.edn :as edn])))

(def max-nodes 2000)
(def max-depth 40)
(def max-text-chars 20000)

(def ^:private tags
  "Allowed tag → its own allowed attributes (beyond `global-attrs`)."
  (merge
   (zipmap [:p :span :strong :em :b :i :u :s :small :code :pre :blockquote :br
            :hr :h1 :h2 :h3 :h4 :h5 :h6 :mark :kbd :sub :sup :abbr :del :ins
            :ul :li :dl :dt :dd :div :section :header :footer :figure
            :figcaption :summary :caption :thead :tbody :tfoot :tr :table :label]
           (repeat #{}))
   {:ol       #{:start}
    :a        #{:href}
    :img      #{:src :alt :width :height}
    :td       #{:colspan :rowspan}
    :th       #{:colspan :rowspan}
    :details  #{:open}
    :button   #{:action :value :send :disabled}
    :input    #{:type :name :placeholder :value :checked :min :max :step :action :disabled}
    :select   #{:name :action :value}
    :option   #{:value :selected}
    :textarea #{:name :placeholder :rows :action :value}
    ;; SVG subset: shapes and text, nothing that references or embeds
    :svg      #{:viewBox :width :height :fill :stroke :stroke-width :preserveAspectRatio}
    :g        #{:fill :stroke :stroke-width :transform :opacity}
    :path     #{:d :fill :stroke :stroke-width :stroke-linecap :stroke-linejoin :opacity}
    :circle   #{:cx :cy :r :fill :stroke :stroke-width :opacity}
    :ellipse  #{:cx :cy :rx :ry :fill :stroke :stroke-width :opacity}
    :rect     #{:x :y :width :height :rx :ry :fill :stroke :stroke-width :opacity}
    :line     #{:x1 :y1 :x2 :y2 :stroke :stroke-width :stroke-linecap :opacity}
    :polyline #{:points :fill :stroke :stroke-width :opacity}
    :polygon  #{:points :fill :stroke :stroke-width :opacity}
    :text     #{:x :y :dx :dy :fill :font-size :text-anchor :opacity}
    :tspan    #{:x :y :dx :dy :fill :font-size}
    :title    #{}
    ;; ui.* components, expanded by the web renderer
    :ui/button   #{:variant :size :action :value :send :disabled}
    :ui/badge    #{:variant}
    :ui/progress #{:value :variant}
    :ui/alert    #{:variant}
    :ui/chip     #{:active :action :value :send}}))

(def ^:private global-attrs
  #{:title :tone :emphasis :layout :gap :align :lang})

(def semantic-values
  "Semantic attribute → allowed values."
  {:tone     #{"info" "warn" "success" "error" "neutral"}
   :emphasis #{"strong" "muted"}
   :layout   #{"row" "grid" "stack"}
   :gap      #{"sm" "md" "lg"}
   :align    #{"start" "center" "end"}})

(def ^:private void-tags #{:br :hr :img :input})

(defn- tag-key
  "A tag (keyword or string) as a keyword; `ui.button` / `ui-button` read as
   `:ui/button`. Shorthand classes and ids (`div.x#y`) are cut off; the
   second value says whether there were any."
  [t]
  (let [s (cond (keyword? t) (if (namespace t) (str (namespace t) "/" (name t)) (name t))
                (string? t)  t
                :else        nil)]
    (when s
      (let [s    (str/lower-case (str/trim s))
            ui?  (re-find #"^ui[./-]" s)
            base (if ui? (subs s 3) s)
            [b & more] (str/split base #"[.#]")
            k    (if ui? (keyword "ui" b) (keyword b))]
        [(if (= k :viewbox) :viewBox k) (boolean (seq more))]))))

(defn- attr-key [k]
  (let [s (if (keyword? k) (name k) (str k))]
    (case (str/lower-case s)
      "viewbox"             :viewBox
      "preserveaspectratio" :preserveAspectRatio
      (keyword (str/lower-case s)))))

(defn- safe-url? [attr v]
  (let [s (-> (str v) (str/replace #"[\x00-\x20]" "") str/lower-case)]
    (case attr
      :href (or (str/starts-with? s "https://") (str/starts-with? s "http://")
                (str/starts-with? s "mailto:"))
      :src  (or (str/starts-with? s "https://") (str/starts-with? s "http://")
                (and (str/starts-with? s "data:image/")
                     (not (str/starts-with? s "data:image/svg"))))
      true)))

(defn- attr-value
  "The kept value of `attr` on `tag`, or ::drop."
  [tag attr v]
  (cond
    (not (or (string? v) (number? v) (boolean? v))) ::drop
    (and (string? v) (re-find #"(?i)url\s*\(|javascript:|expression\s*\(" v)) ::drop
    (and (string? v) (> (count v) 2000)) ::drop
    (contains? semantic-values attr)
    (let [s (str/lower-case (str v))]
      (if (contains? (semantic-values attr) s) s ::drop))
    (#{:href :src} attr) (if (safe-url? attr v) (str v) ::drop)
    (= :type attr)
    (let [s (str/lower-case (str v))]
      (if (#{"text" "number" "checkbox" "radio" "range"} s) s ::drop))
    (= :action attr) (let [s (str/trim (str v))]
                       (if (re-matches #"[A-Za-z0-9_.:-]{1,60}" s) s ::drop))
    (and (= :ui/progress tag) (= :value attr)) (if (number? v) v ::drop)
    :else v))

(defn- split-attrs
  "[kept-attrs dropped-names] for element `tag`."
  [tag attrs]
  (let [allowed (into global-attrs (get tags tag))]
    (reduce (fn [[kept dropped] [k v]]
              (let [a (attr-key k)
                    x (when (contains? allowed a) (attr-value tag a v))]
                (if (or (nil? x) (= ::drop x))
                  [kept (conj dropped (str "attribute " (name a) " on " (name tag)))]
                  [(assoc kept a x) dropped])))
            [{} []]
            attrs)))

(defn- read-input
  "A tree from JSON arrays (already data) or an EDN / JSON string."
  [x]
  (if (string? x)
    (let [s (str/trim x)]
      (try
        (cond
          (str/starts-with? s "[\"") #?(:cljs (js->clj (js/JSON.parse s)) :clj (edn/read-string s))
          (str/starts-with? s "[")   (edn/read-string s)
          :else                      x)
        (catch #?(:cljs :default :clj Exception) _ ::unreadable)))
    x))

(defn normalize
  "{:tree t :dropped [what …]} for agent hiccup `x`. `t` is nil when nothing
   renderable is left. A bare list of nodes is wrapped in a :div."
  [x]
  (let [dropped (volatile! [])
        nodes   (volatile! 0)
        chars   (volatile! 0)
        drop!   (fn [what] (vswap! dropped conj what) nil)
        walk    (fn walk [node depth]
                  (cond
                    (or (string? node) (number? node))
                    (let [s (str node)]
                      (vswap! chars + (count s))
                      (if (> @chars max-text-chars) (drop! "text beyond the size limit") s))

                    (or (nil? node) (boolean? node)) nil

                    (> depth max-depth) (drop! "nodes nested too deep")

                    (and (sequential? node) (seq node)
                         (or (keyword? (first node)) (string? (first node))))
                    (let [[tag shorthand?] (tag-key (first node))]
                      (vswap! nodes inc)
                      (cond
                        (> @nodes max-nodes) (drop! "nodes beyond the size limit")
                        (not (contains? tags tag)) (drop! (str "tag " (name (or tag :?))))
                        :else
                        (let [more      (rest node)
                              [attrs children] (if (map? (first more)) [(first more) (rest more)] [{} more])
                              [kept ds] (split-attrs tag attrs)]
                          (when shorthand? (drop! (str "class/id shorthand on " (name tag))))
                          (doseq [d ds] (drop! d))
                          (into [tag kept]
                                (when-not (void-tags tag)
                                  (keep #(walk % (inc depth)) (mapcat (fn [c] (if (and (sequential? c) (not (or (keyword? (first c)) (string? (first c))))) c [c])) children)))))))

                    (sequential? node)
                    (let [kids (vec (keep #(walk % (inc depth)) node))]
                      (when (seq kids) (into [:div {}] kids)))

                    (map? node) (drop! "a bare object")
                    :else (drop! (str "value " (pr-str node)))))
        input   (read-input x)
        tree    (if (= ::unreadable input)
                  (drop! "unreadable hiccup string")
                  (walk input 0))]
    {:tree    (if (string? tree) [:p {} tree] tree)
     :dropped (vec (distinct @dropped))}))

(defn- elements [tree]
  (filter #(and (vector? %) (keyword? (first %))) (tree-seq vector? #(drop 2 %) tree)))

(defn actions
  "The action names the controls of `tree` declare."
  [tree]
  (into #{} (keep #(get-in % [1 :action])) (elements tree)))

(defn send-actions
  "The actions of `tree` whose control is marked `:send`."
  [tree]
  (into #{} (keep #(when (true? (get-in % [1 :send])) (get-in % [1 :action]))) (elements tree)))

(def ^:private block-tags
  #{:p :div :section :header :footer :figure :figcaption :blockquote :pre :h1 :h2
    :h3 :h4 :h5 :h6 :ul :ol :li :dl :dt :dd :table :tr :caption :details :summary
    :hr :br :ui/alert :ui/progress})

(defn text
  "The visible text of `tree`, one line per block element."
  [tree]
  (letfn [(walk [node]
            (cond
              (string? node) node
              (vector? node)
              (let [[tag attrs & kids] node
                    inner (apply str (map walk kids))
                    inner (case tag
                            :li (str "- " inner)
                            (:td :th) (str inner "  ")
                            :ui/progress (str (:value attrs) "%")
                            :input (if (= "checkbox" (:type attrs))
                                     (if (:checked attrs) "[x]" "[ ]")
                                     (str "[" (or (:value attrs) (:placeholder attrs) "") "]"))
                            (:button :ui/button :ui/chip) (str "[" inner "] ")
                            :ui/badge (str inner " ")
                            inner)]
                (if (block-tags tag) (str "\n" inner "\n") inner))
              :else ""))]
    (-> (walk tree)
        (str/replace #"[ \t]+\n" "\n")
        (str/replace #"[ \t]+$" "")
        (str/replace #"\n{2,}" "\n")
        str/trim)))
