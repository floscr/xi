(ns xi.web.diff-jump
  "Where an inline code chip of an agent reply points to in the room's diff
   buffers, so a click opens the diff viewer on that line (xi.web.core's
   delegated listener, :diff/jump). Nothing is asked of the model: a
   `path:line` chip is exact, a symbol is looked up in the rows the viewer
   already holds — a line defining it first, else a changed line mentioning
   it. Pure over room state."
  (:require [clojure.string :as str]
            [xi.buffers :as buffers]
            [xi.diff :as diff]
            [xi.markdown.code-kind :as code-kind]))

(defn path-ref
  "[path line] of a `src/a.cljs:12` or `a.cljs:12-15` chip, nil otherwise."
  [text]
  (when-let [[_ path line] (re-matches #"([^\s:]+\.[A-Za-z0-9]{1,5}):(\d+)(?:-\d+)?" (str text))]
    [path (parse-long line)]))

;; What a symbol may continue with: a match must end before one of these, so
;; `read-token` isn't found inside `read-tokens` or `read-token?`. Angle
;; brackets are left out so `<ThemeToggle` and `Map<Token>` count as edges.
(def ^:private sym-chars "\\w$?!*+=-")

(defn- escape-re [s]
  (str/replace s #"[.*+?^${}()|\[\]\\/-]" "\\$&"))

(def ^:private def-words
  ["defn-?" "def[a-z]*" "fn" "function\\*?" "func" "fun" "const" "let" "var" "class"
   "struct" "type" "interface" "enum" "trait" "impl" "macro" "proc" "sub"])

(defn- definition-re
  "A line defining `sym`: a definition keyword (one modifier token allowed in
   between: `defn- ^:private x`, `async def x`), a method head `x(` at the
   line's start, or `x = (`/`x: function`."
  [sym]
  (let [s (escape-re sym)
        end (str "(?![" sym-chars "])")]
    (js/RegExp.
     (str "(?:^|[\\s(])(?:" (str/join "|" def-words) ")\\s+(?:\\^?[\\w$.:-]+\\s+)?" s end
          "|^\\s*(?:async\\s+)?" s "\\s*\\("
          "|(?:^|[\\s.])" s "\\s*[:=]\\s*(?:async\\s+)?(?:function|fn|\\(|\\[)"))))

(defn- mention-re [sym]
  (js/RegExp. (str "(?:^|[^" sym-chars "])" (escape-re sym) "(?![" sym-chars "])")))

(defn- candidates
  "The names to look for, best first: the chip's text, it without a call's
   parens or an element's angle brackets (`useTheme()`, `<ThemeToggle>`),
   then that without its dir, namespace or sigil prefix (`cfo/get-tokens` is
   defined as `get-tokens`)."
  [text]
  (let [bare (-> text
                 (str/replace #"\(.*\)$" "")
                 (str/replace #"^<|/?>$" ""))
        [prefix leaf] (code-kind/split-prefix bare)]
    (cond-> [text]
      (and (seq bare) (not= bare text)) (conj bare)
      (and (seq prefix) (seq leaf) (not= leaf bare)) (conj leaf))))

(defn- row-sides
  "[[side n text changed?] …] of a :line row, one per side it shows: a
   unified line's one, a :split row's old and new."
  [{:keys [line]}]
  (let [{:keys [type text old-line new-line lhs rhs]} line]
    (case type
      :split   (cond-> []
                 lhs (conj [:old (:n lhs) (:text lhs) (boolean (:changed? lhs))])
                 rhs (conj [:new (:n rhs) (:text rhs) (boolean (:changed? rhs))]))
      :add     [[:new new-line text true]]
      :delete  [[:old old-line text true]]
      :context [[:new new-line text false]]
      [])))

(defn- hit
  "{:filename :side :line :sel-idx} of row `r`'s side."
  [{:keys [filename sel-idx]} [side n]]
  {:filename filename :side side :line n :sel-idx sel-idx})

(defn- tier
  "How good a side is for `sym`: 0 defined on an added line, 1 on an unchanged
   one, 2 on a removed one; 3 mentioned on a changed line, 4 on an unchanged
   one; nil when absent."
  [sym [side _ text changed?]]
  (when (and text (.test (mention-re sym) text))
    (if (.test (definition-re sym) text)
      (cond (and changed? (= side :new)) 0
            (not changed?)               1
            :else                        2)
      (if changed? 3 4))))

(defn- symbol-hit
  "The best side of `rows` for the chip text: by tier, then the chip's full
   text over its leaf, the new side over the old, the earliest row."
  [rows text]
  (let [cands (map-indexed vector (candidates text))]
    (->> rows
         (keep-indexed
          (fn [ri {:keys [row] :as r}]
            (when (= :line row)
              (->> (for [side (row-sides r)
                         [ci sym] cands
                         :let [t (tier sym side)]
                         :when t]
                     [[t ci (if (= :new (first side)) 0 1) ri] (hit r side)])
                   (sort-by first)
                   first))))
         (sort-by first)
         first
         second)))

(defn- path-hit
  "The line a `path:line` chip names in the file whose path ends with it."
  [rows [path line]]
  (let [suffix (str "/" path)]
    (some (fn [{:keys [row filename] :as r}]
            (when (and (= :line row)
                       (or (= filename path) (str/ends-with? filename suffix)))
              (some (fn [[side n :as s]]
                      (when (and (= :new side) (= n line)) (hit r s)))
                    (row-sides r))))
          rows)))

(defn find-in-rows
  "The line of `rows` (xi.diff, either layout) chip `text` names, or nil:
   {:filename :side :line :sel-idx}. A `path:line` chip's line of the file
   with that path; else the symbol's definition (added, unchanged, removed),
   else the first changed line mentioning it, else any mentioning it."
  [rows text]
  (let [text (str/trim (str text))]
    (when (seq text)
      (if-let [ref (path-ref text)]
        (path-hit rows ref)
        (symbol-hit rows text)))))

(defn diff-buffers
  "`[id buffer]` of a room's diff buffers the viewer can show rows of: the one
   in front first, then newest first."
  [room]
  (let [active (get-in room [:ui :active-buffer])]
    (->> (get-in room [:ui :buffers])
         (filter (fn [[id {:keys [engine rows] :as buf}]]
                   (and (= :diff (buffers/kind id buf))
                        (or (not= :difft engine) rows))))
         (sort-by (fn [[id buf]] [(if (= id active) 0 1) (- (or (:opened-at buf) 0))])))))

;; buffer-id → [content rows]: the rows a buffer's content last parsed to, so
;; hovering chips doesn't re-parse a big diff.
(defonce ^:private rows-memo (atom {}))

(defn- buffer-rows [id {:keys [rows text] :as buf} layout]
  (let [k [(or rows text) layout]
        [k' memo] (get @rows-memo id)]
    (if (and memo (= k k'))
      memo
      (let [rows (diff/buffer-rows buf layout)]
        (swap! rows-memo assoc id [k rows])
        rows))))

(defn target
  "Where chip `text` points in `room`'s diff buffers in `layout` (the
   viewer's, so :sel-idx selects the line there): the hit of the first buffer
   holding it with its :buffer-id, nil when none does."
  [room text layout]
  (some (fn [[id buf]]
          (when-let [h (find-in-rows (buffer-rows id buf (or layout (diff/default-layout (:engine buf))))
                                     text)]
            (assoc h :buffer-id id)))
        (diff-buffers room)))
