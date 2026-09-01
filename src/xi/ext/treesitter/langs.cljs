(ns xi.ext.treesitter.langs
  "Per-language skeleton extractors over the xi-treesitter JSON parse tree.

   Each extractor is (fn [root src-buffer] → [entry]) where entries are the
   maps xi.ext.treesitter.skeleton formats. The generic trick used everywhere:
   a definition's signature is its text up to the `body` field
   (parse/sig-before-body), which works across languages without assembling
   signatures field by field."
  (:require [clojure.string :as str]
            [xi.ext.treesitter.parse :as p]))

(def ext->lang
  "File extension → grammar name (<lang>.so + tree_sitter_<lang>)."
  {"ts" "typescript" "mts" "typescript" "cts" "typescript"
   "tsx" "tsx"
   "js" "javascript" "jsx" "javascript" "mjs" "javascript" "cjs" "javascript"
   "py" "python" "pyi" "python"
   "rs" "rust"
   "go" "go"
   "nix" "nix"
   "sh" "bash" "bash" "bash" "zsh" "bash"})

(def ^:private member-cap
  "Max fields/members listed per container before truncating (maki uses 8)."
  8)

(defn- cap-members [items total]
  (if (> total member-cap)
    (conj (vec (take member-cap items))
          (str "[" (- total member-cap) " more truncated]"))
    (vec items)))

(defn- strip-trailing [s re] (str/replace s re ""))

(defn- entry
  ([section src n text] (entry section src n text nil))
  ([section src n text nm]
   (cond-> {:section section
            :text text
            :start (p/start-line n)
            :end (p/end-line src n)}
     nm (assoc :name nm))))

(defn- field-text [src n f]
  (some->> (p/child-by-field n f) (p/node-text src)))

;; ── TypeScript / TSX / JavaScript ────────────────────────────────────────────

(defn- ts-member [src m]
  (let [t (p/node-type m)]
    (cond
      (= t "method_definition")
      {:text (p/sig-before-body src m)
       :name (field-text src m "name")
       :start (p/start-line m) :end (p/end-line src m)}

      (#{"public_field_definition" "property_definition"
         "abstract_method_signature" "method_signature"} t)
      {:text (p/truncate (strip-trailing (p/compact-ws (p/node-text src m)) #";$") 80)
       :start (p/start-line m) :end (p/end-line src m)}

      :else nil)))

(defn- ts-declarator [src n decl exported?]
  (let [nm (field-text src decl "name")
        value (p/child-by-field decl "value")
        prefix (if exported? "export " "")]
    (if (and value (#{"arrow_function" "function_expression" "function"
                      "generator_function"} (p/node-type value)))
      (let [sig (-> (p/sig-before-body src value)
                    (strip-trailing #"\s*=>$"))]
        (entry :fns src n (str prefix nm " = " sig) nm))
      (entry :consts src n
             (p/truncate (str prefix (p/compact-ws (p/node-text src decl))) 80)
             nm))))

(defn- ts-extract-node [src n exported?]
  (let [t (p/node-type n)
        prefix (if exported? "export " "")]
    (case t
      "import_statement"
      [(entry :imports src n
              (-> (p/compact-ws (p/node-text src n))
                  (strip-trailing #"^import\s+")
                  (strip-trailing #";$")))]

      ("lexical_declaration" "variable_declaration")
      (mapv #(ts-declarator src n % exported?)
            (p/children-of-type n "variable_declarator"))

      ("function_declaration" "generator_function_declaration")
      [(entry :fns src n (str prefix (p/sig-before-body src n))
              (field-text src n "name"))]

      ("class_declaration" "abstract_class_declaration")
      (let [members (keep #(ts-member src %) (p/named-children (p/child-by-field n "body")))]
        [(assoc (entry :classes src n (str prefix (p/sig-before-body src n))
                       (field-text src n "name"))
                :children (cap-members members (count members)))])

      "interface_declaration"
      (let [ms (->> (p/named-children (p/child-by-field n "body"))
                    (mapv #(strip-trailing (p/compact-ws (p/node-text src %)) #"[;,]$")))]
        [(assoc (entry :types src n (str prefix (p/sig-before-body src n))
                       (field-text src n "name"))
                :children (cap-members ms (count ms)))])

      "type_alias_declaration"
      [(entry :types src n
              (p/truncate (strip-trailing (p/compact-ws (p/node-text src n)) #";$") 120)
              (field-text src n "name"))]

      "enum_declaration"
      (let [names (->> (p/named-children (p/child-by-field n "body"))
                       (mapv #(p/compact-ws (p/node-text src %))))]
        [(assoc (entry :types src n (str prefix (p/sig-before-body src n))
                       (field-text src n "name"))
                :children (cap-members names (count names)))])

      "export_statement"
      (when-let [decl (some #(when-not (#{"comment" "string"} (p/node-type %)) %)
                            (p/named-children n))]
        (ts-extract-node src decl true))

      nil)))

(defn- ts-extract [root src]
  (into [] (mapcat #(ts-extract-node src % false)) (p/named-children root)))

;; ── Python ───────────────────────────────────────────────────────────────────

(defn- py-sig [src n]
  (-> (p/sig-before-body src n) (strip-trailing #":$") str/trim))

(defn- py-class-member [src m]
  (let [t (p/node-type m)]
    (cond
      (= t "function_definition")
      {:text (py-sig src m) :name (field-text src m "name")
       :start (p/start-line m) :end (p/end-line src m)}

      (= t "decorated_definition")
      (when-let [d (p/child-by-field m "definition")]
        (when (= "function_definition" (p/node-type d))
          {:text (py-sig src d) :name (field-text src d "name")
           :start (p/start-line m) :end (p/end-line src m)}))

      (= t "expression_statement")
      (when-let [a (p/child-of-type m "assignment")]
        {:text (p/truncate (p/compact-ws (p/node-text src a)) 60)
         :start (p/start-line m) :end (p/end-line src m)})

      :else nil)))

(defn- py-extract-node [src n]
  (let [t (p/node-type n)]
    (case t
      ("import_statement" "import_from_statement" "future_import_statement")
      [(entry :imports src n (p/compact-ws (p/node-text src n)))]

      "function_definition"
      [(entry :fns src n (py-sig src n) (field-text src n "name"))]

      "class_definition"
      (let [members (keep #(py-class-member src %) (p/named-children (p/child-by-field n "body")))]
        [(assoc (entry :classes src n (py-sig src n) (field-text src n "name"))
                :children (cap-members members (count members)))])

      "decorated_definition"
      (when-let [d (p/child-by-field n "definition")]
        (mapv #(assoc % :start (p/start-line n)) (py-extract-node src d)))

      "expression_statement"
      (when-let [a (p/child-of-type n "assignment")]
        (let [left (p/child-by-field a "left")]
          (when (and left (= "identifier" (p/node-type left)))
            [(entry :consts src n
                    (p/truncate (p/compact-ws (p/node-text src a)) 80)
                    (p/node-text src left))])))

      nil)))

(defn- py-extract [root src]
  (into [] (mapcat #(py-extract-node src %)) (p/named-children root)))

;; ── Rust ─────────────────────────────────────────────────────────────────────

(defn- rust-trait-member [src m]
  (case (p/node-type m)
    "function_item"
    {:text (p/sig-before-body src m) :name (field-text src m "name")
     :start (p/start-line m) :end (p/end-line src m)}
    "function_signature_item"
    {:text (strip-trailing (p/compact-ws (p/node-text src m)) #";$")
     :name (field-text src m "name")
     :start (p/start-line m) :end (p/end-line src m)}
    nil))

(defn- rust-extract-node [src n]
  (let [t (p/node-type n)]
    (case t
      "use_declaration"
      [(entry :imports src n
              (-> (p/compact-ws (p/node-text src n))
                  (strip-trailing #"^use\s+")
                  (strip-trailing #";$")))]

      ("const_item" "static_item")
      [(entry :consts src n
              (p/truncate (strip-trailing (p/compact-ws (p/node-text src n)) #";$") 100)
              (field-text src n "name"))]

      "function_item"
      [(entry :fns src n (p/sig-before-body src n) (field-text src n "name"))]

      "struct_item"
      (let [body (p/child-by-field n "body")
            fields (when body
                     (->> (p/children-of-type body "field_declaration")
                          (mapv #(p/compact-ws (p/node-text src %)))))]
        [(assoc (entry :types src n (p/sig-before-body src n) (field-text src n "name"))
                :children (cap-members (or fields []) (count fields)))])

      "enum_item"
      (let [body (p/child-by-field n "body")
            variants (when body
                       (->> (p/children-of-type body "enum_variant")
                            (mapv #(or (field-text src % "name")
                                       (p/compact-ws (p/node-text src %))))))]
        [(assoc (entry :types src n (p/sig-before-body src n) (field-text src n "name"))
                :children (cap-members (or variants []) (count variants)))])

      "type_item"
      [(entry :types src n
              (p/truncate (strip-trailing (p/compact-ws (p/node-text src n)) #";$") 100)
              (field-text src n "name"))]

      "trait_item"
      (let [members (keep #(rust-trait-member src %) (p/named-children (p/child-by-field n "body")))]
        [(assoc (entry :traits src n (p/sig-before-body src n) (field-text src n "name"))
                :children (cap-members members (count members)))])

      "impl_item"
      (let [members (keep #(rust-trait-member src %) (p/named-children (p/child-by-field n "body")))]
        [(assoc (entry :impls src n (p/sig-before-body src n)
                       (some->> (p/child-by-field n "type") (p/node-text src) p/compact-ws))
                :children (cap-members members (count members)))])

      "mod_item"
      [(entry :mods src n (p/sig-before-body src n) (field-text src n "name"))]

      "macro_definition"
      [(entry :macros src n (str "macro_rules! " (field-text src n "name"))
              (field-text src n "name"))]

      nil)))

(defn- rust-extract [root src]
  (into [] (mapcat #(rust-extract-node src %)) (p/named-children root)))

;; ── Go ───────────────────────────────────────────────────────────────────────

(defn- go-specs
  "Flatten a const/var/import declaration into its *_spec nodes (they either
   sit directly under the declaration or inside a (...) spec list)."
  [n spec-type]
  (concat (p/children-of-type n spec-type)
          (mapcat #(p/children-of-type % spec-type)
                  (p/children-of-type n (str spec-type "_list")))))

(defn- go-type-spec [src decl spec]
  (let [nm (field-text src spec "name")
        type-node (p/child-by-field spec "type")
        tt (some-> type-node p/node-type)
        line-entry (fn [text children]
                     (cond-> (entry :types src spec text nm)
                       (seq children) (assoc :children (cap-members children (count children)))))]
    (case tt
      "struct_type"
      (line-entry (str "type " nm " struct")
                  (->> (p/child-of-type type-node "field_declaration_list")
                       ((fn [fl] (when fl (p/children-of-type fl "field_declaration"))))
                       (mapv #(p/compact-ws (p/node-text src %)))))

      "interface_type"
      (line-entry (str "type " nm " interface")
                  (->> (p/named-children type-node)
                       (mapv #(p/compact-ws (p/node-text src %)))))

      (line-entry (p/truncate (p/compact-ws (p/node-text src spec)) 100) []))))

(defn- go-extract-node [src n]
  (let [t (p/node-type n)]
    (case t
      "package_clause"
      [(entry :mods src n (p/compact-ws (p/node-text src n)))]

      "import_declaration"
      (mapv #(entry :imports src % (p/compact-ws (p/node-text src %)))
            (go-specs n "import_spec"))

      "const_declaration"
      (mapv #(entry :consts src %
                    (p/truncate (p/compact-ws (p/node-text src %)) 80)
                    (field-text src % "name"))
            (go-specs n "const_spec"))

      "var_declaration"
      (mapv #(entry :consts src %
                    (p/truncate (p/compact-ws (p/node-text src %)) 80)
                    (field-text src % "name"))
            (go-specs n "var_spec"))

      "type_declaration"
      (mapv #(go-type-spec src n %) (p/children-of-type n "type_spec"))

      ("function_declaration" "method_declaration")
      [(entry :fns src n (p/sig-before-body src n) (field-text src n "name"))]

      nil)))

(defn- go-extract [root src]
  (into [] (mapcat #(go-extract-node src %)) (p/named-children root)))

;; ── Bash ─────────────────────────────────────────────────────────────────────

(defn- bash-extract [root src]
  (into []
        (keep (fn [n]
                (case (p/node-type n)
                  "function_definition"
                  (entry :fns src n (str (field-text src n "name") "()")
                         (field-text src n "name"))
                  "variable_assignment"
                  (entry :consts src n
                         (p/truncate (p/compact-ws (p/node-text src n)) 60)
                         (field-text src n "name"))
                  nil)))
        (p/named-children root)))

;; ── Nix ──────────────────────────────────────────────────────────────────────

(defn- nix-binding->entry [src b]
  (let [attrpath (some->> (p/child-by-field b "attrpath") (p/node-text src))
        expr (p/child-by-field b "expression")
        nested (when (and expr (#{"attrset_expression" "rec_attrset_expression"}
                                (p/node-type expr)))
                 (when-let [bs (p/child-of-type expr "binding_set")]
                   (->> (p/children-of-type bs "binding")
                        (mapv #(some->> (p/child-by-field % "attrpath")
                                        (p/node-text src))))))]
    (when attrpath
      (cond-> (entry :bindings src b attrpath attrpath)
        (seq nested) (assoc :children (cap-members nested (count nested)))))))

(defn- nix-binding-set-entries [src n]
  (when-let [bs (p/child-of-type n "binding_set")]
    (into [] (keep #(nix-binding->entry src %)) (p/children-of-type bs "binding"))))

(defn- nix-walk [src n entries depth]
  (if (or (nil? n) (> depth 12))
    entries
    (case (p/node-type n)
      "function_expression"
      (let [params (some #(when-not (= "body" (p/field %)) %) (p/named-children n))
            entries (if params
                      (conj entries
                            (entry :fns src n
                                   (p/truncate (str (p/compact-ws (p/node-text src params)) ": …") 100)))
                      entries)]
        (nix-walk src (p/child-by-field n "body") entries (inc depth)))

      "let_expression"
      (nix-walk src (p/child-by-field n "body")
                (into entries (nix-binding-set-entries src n))
                (inc depth))

      "with_expression"
      (nix-walk src (p/child-by-field n "body") entries (inc depth))

      "parenthesized_expression"
      (nix-walk src (first (p/named-children n)) entries (inc depth))

      ("attrset_expression" "rec_attrset_expression")
      (into entries (nix-binding-set-entries src n))

      entries)))

(defn- nix-extract [root src]
  (nix-walk src (first (p/named-children root)) [] 0))

;; ── Dispatch ─────────────────────────────────────────────────────────────────

(def ^:private extractors
  {"typescript" ts-extract
   "tsx"        ts-extract
   "javascript" ts-extract
   "python"     py-extract
   "rust"       rust-extract
   "go"         go-extract
   "bash"       bash-extract
   "nix"        nix-extract})

(defn lang-for
  "Grammar name for a file path, nil when unsupported."
  [path]
  (let [ext (some-> (re-find #"\.([^./\\]+)$" path) second str/lower-case)]
    (get ext->lang ext)))

(defn extractor [lang] (get extractors lang))
