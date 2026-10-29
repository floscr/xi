(ns xi.ext.terminal.core
  "Pure helpers of the terminal extension (xi.ext.terminal): the scrollback a
   late client replays, argv and titles, the buffer a terminal shows in, and
   its rules request."
  (:require [clojure.string :as str]
            [xi.buffers :as buffers]
            [xi.util :as util]))

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

(defn valid-command? [command]
  (and (string? command) (not (str/blank? command))))

(defn command-argv
  "`command` (a script, any number of lines) run by the user's shell."
  [shell command]
  (conj (shell-argv shell) "-c" command))

(defn- clip-title [s]
  (if (> (count s) 48) (str (subs s 0 47) "…") s))

(defn argv-title
  "A terminal's buffer title: the program's name and its arguments."
  [argv]
  (clip-title (str/join " " (cons (last (str/split (first argv) #"/")) (rest argv)))))

(defn command-title
  "A command terminal's buffer title: the script's first line."
  [command]
  (let [lines (remove str/blank? (str/split-lines command))]
    (clip-title (str (str/trim (first lines)) (when (next lines) " …")))))

;; ── Code blocks ──────────────────────────────────────────────────────────────

(def ^:private shell-langs #{"sh" "bash" "zsh" "shell" "fish"})
(def ^:private session-langs #{"console" "shell-session" "shellsession"})

(defn- inline-command?
  "Inline code that reads as a command: one line with an argument
   (`ssh host`), not a bare name (`gui`, `localStorage`)."
  [text]
  (boolean (re-matches #"[^\s\"'].*\s.*\S" text)))

(defn block-command
  "The command a chat code block runs in a terminal (the code menu's Run in
   terminal, xi.ext.terminal.web): a shell block's text, a console session's
   `$ ` lines without the prompt, a message's fenced block without a
   language, its inline code that reads as a command. nil for other
   blocks, and for a tool block naming a file (`:path`)."
  [{:keys [text lang path markdown]}]
  (let [lang (some-> lang str/lower-case)
        text (str/trim (or text ""))]
    (when-not (or path (str/blank? text))
      (cond
        (shell-langs lang) text

        (session-langs lang)
        (some->> (str/split-lines text)
                 (keep #(second (re-find #"^\s*\$\s+(.*\S)" %)))
                 seq
                 (str/join "\n"))

        lang nil

        (= :block markdown) text

        (= :inline markdown) (when (inline-command? text) text)))))

;; ── Buffer + rules ───────────────────────────────────────────────────────────

(defn buffer
  "The room buffer of terminal `term-id`. `tag` is free for extensions to
   recognise their own terminals by (`:vim`)."
  [{:keys [term-id argv cwd title tag hold?]}]
  (cond-> {:kind    :terminal
           :term-id term-id
           :title   (or title (argv-title argv))
           :argv    argv
           :cwd     cwd}
    tag   (assoc :tag tag)
    hold? (assoc :hold? true)))

(defn rules-request
  "The decision request (xi.rules.store/request) for opening `argv` in `cwd`."
  [argv cwd]
  {:tool          :terminal
   :cli           (first argv)
   :command       (str/join " " argv)
   :argv          argv
   :effective-cwd cwd})

(defn- session-room [st session-id]
  (some (fn [[rid room]] (when (= session-id (get-in room [:session :id])) rid))
        (:rooms st)))

(defn send-event
  "The event a program's `POST /send` asks for (xi.ext.terminal.socket):
   `body` {:text :to :label} from terminal `term` {:session-id :user}.
   `:to \"subagent\"` starts a sub-agent in the terminal's chat (id `sub-id`,
   set by the socket when it streams it), else the text is the user's next
   prompt there, shown as `label` when given. → {:event} or {:error}."
  [st {:keys [session-id user]} {:keys [text to label sub-id]}]
  (let [room-id (session-room st session-id)
        label   (when (string? label) (not-empty (str/trim label)))]
    (cond
      (not (and (string? text) (not (str/blank? text))))
      {:error "needs a non-empty :text"}

      (nil? room-id)
      {:error "the terminal's chat is not open"}

      (= "subagent" to)
      {:event (cond-> {:type :subagent/spawn :room-id room-id :task text :prompt text}
                label  (assoc :label label)
                sub-id (assoc :sub-id sub-id))}

      :else
      {:event (cond-> {:type :prompt/submit :room-id room-id :text text}
                user  (assoc :user user)
                label (assoc :collapsed-label label))})))

(def ^:private detail-keys
  [:path :file_path :pattern :command :query :url :description])

(defn- tool-detail [arguments]
  (when-let [v (some #(let [v (or (get arguments %) (get arguments (name %)))]
                        (when (string? v) v))
                     detail-keys)]
    (let [v (first (str/split-lines v))]
      (if (> (count v) 80) (str (subs v 0 79) "…") v))))

(defn stream-item
  "What sub-agent event `ev` adds to a program's stream of that sub-agent
   (xi.ext.terminal.socket/push!), nil for the events it skips. A \"done\"
   item ends the stream."
  [{:keys [type text tool arguments error aborted?]}]
  (case type
    :subagent/text-delta (when (seq text) {:type "text" :text text})
    :subagent/tool-start (cond-> {:type "tool" :tool (util/strip-mcp-prefix (str tool))}
                           (tool-detail arguments) (assoc :detail (tool-detail arguments)))
    :subagent/error      {:type "error" :error (str error)}
    :subagent/turn-end   {:type "done" :status (if aborted? "stopped" "done")}
    nil))

(defn buffer-term-id
  "The terminal behind `buffer-id` of the room, nil for any other buffer."
  [room buffer-id]
  (when (= :terminal (get-in room [:ui :buffers buffer-id :kind]))
    (or (get-in room [:ui :buffers buffer-id :term-id])
        (buffers/terminal-of buffer-id))))
