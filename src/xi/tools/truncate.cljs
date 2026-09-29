(ns xi.tools.truncate
  "Truncation for oversized tool output that doesn't lose the rest.

   When output exceeds the cap, the full text is spilled to a log file under
   the OS tmp dir and the truncation marker names that file plus how to look
   at it — so the agent can grep/tail the full log instead of re-running the
   command with its own redirect."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(defn spill!
  "Write `s` to a fresh `<tmp>/xi-output/<prefix>-….log`. Returns the path,
   or nil when the write fails (the note then just omits it)."
  [prefix s]
  (try
    (let [dir  (node-path/join (os/tmpdir) "xi-output")
          file (node-path/join dir (str prefix "-" (.getTime (js/Date.)) "-"
                                        (.toString (rand-int 0x7fffffff) 36) ".log"))]
      (fs/mkdirSync dir #js {:recursive true})
      (fs/writeFileSync file s "utf8")
      file)
    (catch :default _ nil)))

(defn note
  "The truncation marker. `keep` is :head or :tail (which part was kept),
   `how` a short phrase naming the tools to inspect the file with."
  [{:keys [keep shown total path how]}]
  (str "[truncated: showing " (if (= keep :tail) "last" "first") " "
       shown " of " total " chars"
       (when path
         (str " — full output saved to " path
              ". Don't re-run the command; " how))
       "]"))

(defn truncate
  "Return `s` unchanged when it fits in `max-len`, else keep the head (or the
   tail, with `:keep :tail`) and append/prepend a `note` pointing at the
   spilled full output. opts: :keep, :prefix (spill file-name prefix), :how."
  [s max-len {:keys [keep prefix how] :or {keep :head prefix "out"}}]
  (let [s (str s)
        n (count s)]
    (if (<= n max-len)
      s
      (let [m (note {:keep keep :shown max-len :total n
                     :path (spill! prefix s) :how how})]
        (if (= keep :tail)
          (str m "\n" (subs s (- n max-len)))
          (str (subs s 0 max-len) "\n… " m))))))
