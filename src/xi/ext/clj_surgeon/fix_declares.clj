(ns clj-surgeon.fix-declares
  "Compound operation: eliminate all removable declares in one shot.

   The problem: :mv moves one form at a time, doesn't check dependencies,
   and doesn't delete the stale declare. The AI ends up in a Whac-A-Mole
   loop — move form, new forward ref, add declare, move again...

   :fix-declares does the whole thing:
   1. Find removable declares (topo sort separates band-aids from cycles)
   2. For each: compute where defn needs to go + all its dependencies
   3. Move everything in topological order (leaves first)
   4. Delete stale declare lines
   5. Return what it did

   ALL PLANNING IS PURE. Only execute! writes files."
  (:require [clj-surgeon.analyze :as analyze]
            [clj-surgeon.outline :as outline]
            [clj-surgeon.forward-refs :as fwd]
            [rewrite-clj.zip :as z]
            [clojure.string :as str]))

;; ============================================================
;; Pure: Build a fix plan
;; ============================================================

(defn- form-def-line
  "Given an outline, find the line where a form is defined (not declared)."
  [forms name-str]
  (some (fn [f]
          (when (and (= (str (:name f)) name-str)
                     (not= 'declare (:type f)))
            (:line f)))
        forms))

(defn- form-end-line
  "Given an outline, find the end-line of a form's definition."
  [forms name-str]
  (some (fn [f]
          (when (and (= (str (:name f)) name-str)
                     (not= 'declare (:type f)))
            (:end-line f)))
        forms))

(defn- declare-line
  "Find the line of a (declare name) form."
  [forms name-str]
  (some (fn [f]
          (when (and (= (str (:name f)) name-str)
                     (= 'declare (:type f)))
            (:line f)))
        forms))

(defn- first-usage-line
  "Find where a forward-ref'd name is first used."
  [forward-refs name-str]
  (some (fn [fr]
          (when (= (str (:name fr)) name-str)
            (:used-at fr)))
        forward-refs))

(defn- form-at-or-before
  "Find the form that starts at or just before a given line."
  [forms target-line]
  (->> forms
       (filter #(and (<= (:line %) target-line)
                     (not= 'declare (:type %))
                     (:name %)))
       (sort-by :line)
       last))

(defn plan
  "Build a plan to fix all removable declares in a file.
   Returns {:actions [...] :summary {...}} or {:error ...}.
   PURE — no side effects."
  [file]
  (let [result (outline/outline file)
        forms (:forms result)
        ns-name (:ns result)
        forward-refs (if ns-name
                       (or (fwd/detect-forward-refs file ns-name) [])
                       [])
        zloc (analyze/file->zloc file)
        deps-list (analyze/intra-ns-deps zloc)
        topo (analyze/topological-sort zloc)
        truly-cyclic (set (:cycles topo))
        all-declares (->> forms
                          (filter #(= 'declare (:type %))))
        removable (->> all-declares
                       (remove #(contains? truly-cyclic (str (:name %)))))
        needed (->> all-declares
                    (filter #(contains? truly-cyclic (str (:name %)))))]
    (if (empty? removable)
      {:file file
       :actions []
       :summary {:removable 0 :needed (count needed) :message "No removable declares."}}
      (let [actions
            (->> removable
                 (map (fn [decl]
                        (let [name-str (str (:name decl))
                              def-line (form-def-line forms name-str)
                              end-line (form-end-line forms name-str)
                              decl-line (:line decl)
                              usage-line (first-usage-line forward-refs name-str)
                              target-form (when usage-line
                                            (form-at-or-before forms usage-line))
                              form-deps (some (fn [d]
                                                (when (and (= name-str (:name d))
                                                           (not= "declare" (:type d)))
                                                  (:depends-on d)))
                                              deps-list)
                              unresolved (when target-form
                                           (->> (or form-deps #{})
                                                (filter (fn [dep]
                                                          (let [dep-line (form-def-line forms dep)]
                                                            (and dep-line
                                                                 (> dep-line (:line target-form))))))
                                                set
                                                not-empty))
                              all-leaves? (when unresolved
                                            (every? (fn [dep]
                                                      (let [dep-entry (first (filter #(= dep (:name %)) deps-list))]
                                                        (or (nil? dep-entry)
                                                            (empty? (:depends-on dep-entry)))))
                                                    unresolved))
                              pull-deps (when (and unresolved all-leaves?)
                                          unresolved)]
                          (cond-> {:type :fix-declare
                                   :name name-str
                                   :declare-line decl-line
                                   :defn-line def-line
                                   :defn-end-line end-line}
                            target-form (assoc :move-before (str (:name target-form))
                                               :move-before-line (:line target-form))
                            usage-line (assoc :first-usage usage-line)
                            pull-deps (assoc :pull-deps pull-deps)
                            (and unresolved (not all-leaves?))
                            (assoc :unresolved-deps unresolved
                                   :warning "Moving this form creates new forward refs to non-leaf dependencies")
                            (nil? def-line) (assoc :error "No defn found for declare")
                            (nil? usage-line) (assoc :stale? true
                                                     :note "Declare has no forward ref — defn is already above all callers")))))
                 vec)]
        {:file file
         :actions actions
         :needed-declares (mapv #(str (:name %)) needed)
         :summary {:removable (count removable)
                   :needed (count needed)
                   :safe (count (filter #(and (not (:unresolved-deps %))
                                              (not (:pull-deps %))) actions))
                   :safe-with-pull (count (filter :pull-deps actions))
                   :unsafe (count (filter :unresolved-deps actions))
                   :stale (count (filter :stale? actions))}}))))

;; ============================================================
;; Effects: Execute the fix plan
;; ============================================================

(defn execute!
  "Execute a fix-declares plan. Processes actions in order:
   1. Move defns above their first callers (safe ones only)
   2. Delete stale declare lines
   Returns a log of actions taken."
  [file]
  (let [p (plan file)]
    (if (:error p)
      p
      (let [source (slurp file)
            lines (vec (str/split-lines source))
            safe-actions (->> (:actions p)
                              (filter #(and (not (:unresolved-deps %))
                                            (not (:error %))
                                            (:defn-line %))))
            stale-actions (filter :stale? safe-actions)
            move-actions (remove :stale? safe-actions)
            pull-actions (->> (:actions p)
                              (filter :pull-deps))
            all-move-actions (concat move-actions pull-actions)
            ;; Process moves from bottom to top to preserve line numbers
            ;; (moving a form from line 1900 doesn't affect lines 1-1899)
            sorted-moves (sort-by :defn-line > all-move-actions)
            log (atom [])]
        ;; PRE-PASS: For pull-deps actions, move their leaf deps first
        ;; (so the main form's move becomes safe)
        (doseq [action pull-actions
                :let [target-line (:move-before-line action)]
                dep-name (:pull-deps action)]
          (let [current-outline (outline/outline file)
                current-forms (:forms current-outline)
                dep-form (first (filter #(and (= (str (:name %)) dep-name)
                                              (not= 'declare (:type %)))
                                        current-forms))]
            ;; Only move if dep is below the target
            (when (and dep-form
                       (> (:line dep-form) target-line))
              (let [current-lines (vec (str/split-lines (slurp file)))
                    form-start (let [idx (dec (dec (:line dep-form)))]
                                 (loop [i idx]
                                   (if (neg? i) 0
                                       (if (str/starts-with? (str/trim (nth current-lines i "")) ";")
                                         (recur (dec i))
                                         (inc i)))))
                    form-end (:end-line dep-form)
                    form-text (subvec current-lines form-start form-end)
                    without-form (into (subvec current-lines 0 form-start)
                                       (subvec current-lines form-end))
                    cur-outline (outline/outline file)
                    cur-target (form-at-or-before (:forms cur-outline) target-line)
                    adj-target (if cur-target (:line cur-target) target-line)
                    adj-target (if (< form-start (dec adj-target))
                                 (- adj-target (- form-end form-start))
                                 adj-target)
                    insert-at (dec adj-target)
                    with-move (str/join "\n"
                                        (concat (subvec without-form 0 insert-at)
                                                [""]
                                                form-text
                                                [""]
                                                (subvec without-form insert-at)))]
                (spit file with-move)
                (swap! log conj {:action :pull-dep :dep dep-name
                                 :for (:name action)
                                 :from (:line dep-form) :to adj-target})))))
        ;; Apply main moves one at a time, re-reading file after each
        ;; (line numbers shift, so we re-parse)
        (doseq [action sorted-moves]
          (let [current-source (slurp file)
                current-lines (vec (str/split-lines current-source))
                current-outline (outline/outline file)
                current-forms (:forms current-outline)
                name-str (:name action)
                defn-form (first (filter #(and (= (str (:name %)) name-str)
                                               (not= 'declare (:type %)))
                                         current-forms))
                decl-form (first (filter #(and (= (str (:name %)) name-str)
                                               (= 'declare (:type %)))
                                         current-forms))
                ns-name (:ns current-outline)
                current-fwd (when ns-name
                              (fwd/detect-forward-refs file ns-name))
                usage (first (filter #(= (str (:name %)) name-str) current-fwd))
                target (when usage
                         (form-at-or-before current-forms (:used-at usage)))]
            (when (and defn-form target
                       (> (:line defn-form) (:line target)))
              (let [form-start (let [idx (dec (dec (:line defn-form)))]
                                 (loop [i idx]
                                   (if (neg? i) 0
                                       (if (str/starts-with? (str/trim (nth current-lines i "")) ";")
                                         (recur (dec i))
                                         (inc i)))))
                    form-end (:end-line defn-form)
                    form-text (subvec current-lines form-start form-end)
                    without-form (into (subvec current-lines 0 form-start)
                                       (subvec current-lines form-end))
                    target-line (:line target)
                    adj-target (if (< form-start (dec target-line))
                                 (- target-line (- form-end form-start))
                                 target-line)
                    insert-at (dec adj-target)
                    with-move (str/join "\n"
                                        (concat (subvec without-form 0 insert-at)
                                                [""]
                                                form-text
                                                [""]
                                                (subvec without-form insert-at)))]
                (spit file with-move)
                (swap! log conj {:action :move :form name-str
                                 :from (:line defn-form) :to adj-target})))))
        ;; Now delete stale declares (re-read file after moves)
        (let [all-to-delete (concat stale-actions move-actions pull-actions)
              names-to-delete (set (map :name all-to-delete))]
          (when (seq names-to-delete)
            (let [current-source (slurp file)
                  current-lines (str/split-lines current-source)
                  filtered (remove (fn [line]
                                     (let [trimmed (str/trim line)]
                                       (some (fn [n]
                                               (= trimmed (str "(declare " n ")")))
                                             names-to-delete)))
                                   current-lines)
                  ;; Also remove blank lines left behind by deleted declares
                  ;; (deduplicate consecutive blank lines)
                  cleaned (reduce (fn [acc line]
                                    (if (and (str/blank? line)
                                             (seq acc)
                                             (str/blank? (peek acc)))
                                      acc
                                      (conj acc line)))
                                  [] filtered)]
              (spit file (str/join "\n" cleaned))
              (swap! log conj {:action :delete-declares
                               :names (vec names-to-delete)}))))
        {:file file
         :log @log
         :skipped-unsafe (mapv #(select-keys % [:name :unresolved-deps])
                               (filter :unresolved-deps (:actions p)))
         :summary {:moves (count (filter #(#{:move :pull-dep} (:action %)) @log))
                   :declares-deleted (count (set (map :name (concat stale-actions move-actions pull-actions))))
                   :skipped (count (filter :unresolved-deps (:actions p)))}}))))
