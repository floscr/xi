(ns xi.ext.terminal.core
  "Pure helpers of the terminal extension (xi.ext.terminal): the scrollback a
   late client replays, argv and titles, the buffer a terminal shows in, and
   its rules request."
  (:require [clojure.string :as str]
            [xi.buffers :as buffers]))

;; ── Scrollback ───────────────────────────────────────────────────────────────

(def scrollback-max
  "Characters of output a terminal keeps for clients that attach later."
  262144)

(def empty-scrollback {:chunks [] :size 0})

(defn scrollback-append
  "Add `text` to scrollback `sb`. Past `max-size` it keeps the newest three
   quarters, from the first line break on, so a replay rarely starts inside
   an escape sequence."
  ([sb text] (scrollback-append sb text scrollback-max))
  ([{:keys [chunks size] :or {chunks [] size 0}} text max-size]
   (let [size (+ size (count text))]
     (if (<= size max-size)
       {:chunks (conj chunks text) :size size}
       (let [all  (apply str (conj chunks text))
             tail (subs all (- (count all) (quot (* 3 max-size) 4)))
             nl   (str/index-of tail "\n")
             kept (if nl (subs tail (inc nl)) tail)]
         {:chunks [kept] :size (count kept)})))))

(defn scrollback-text [sb]
  (apply str (:chunks sb)))

;; ── Argv ─────────────────────────────────────────────────────────────────────

(defn valid-argv? [argv]
  (and (vector? argv) (seq argv) (every? #(and (string? %) (not (str/blank? %))) argv)))

(defn parse-args
  "`/term` arguments → argv: words split on whitespace, \"double\" or 'single'
   quotes keep spaces. Blank → nil (the user's shell)."
  [s]
  (when-not (str/blank? s)
    (not-empty
     (mapv (fn [[_ dq sq word]] (or dq sq word))
           (re-seq #"\"([^\"]*)\"|'([^']*)'|(\S+)" s)))))

(defn shell-argv
  "The user's login shell, from `$SHELL`."
  [shell]
  [(if (str/blank? shell) "/bin/sh" shell)])

(defn argv-title
  "A terminal's buffer title: the program's name and its arguments."
  [argv]
  (let [s (str/join " " (cons (last (str/split (first argv) #"/")) (rest argv)))]
    (if (> (count s) 48) (str (subs s 0 47) "…") s)))

;; ── Buffer + rules ───────────────────────────────────────────────────────────

(defn buffer
  "The room buffer of terminal `term-id`. `tag` is free for extensions to
   recognise their own terminals by (`:vim`)."
  [{:keys [term-id argv cwd title tag]}]
  (cond-> {:kind    :terminal
           :term-id term-id
           :title   (or title (argv-title argv))
           :argv    argv
           :cwd     cwd}
    tag (assoc :tag tag)))

(defn rules-request
  "The decision request (xi.rules.store/request) for opening `argv` in `cwd`."
  [argv cwd]
  {:tool          :terminal
   :cli           (first argv)
   :command       (str/join " " argv)
   :argv          argv
   :effective-cwd cwd})

(defn buffer-term-id
  "The terminal behind `buffer-id` of the room, nil for any other buffer."
  [room buffer-id]
  (when (= :terminal (get-in room [:ui :buffers buffer-id :kind]))
    (or (get-in room [:ui :buffers buffer-id :term-id])
        (buffers/terminal-of buffer-id))))
