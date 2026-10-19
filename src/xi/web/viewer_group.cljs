(ns xi.web.viewer-group
  "Pure helpers for the super-collapsed viewer group (appearance setting
   `:super-collapsed?`): a run of collapsed tool / thinking blocks folded into
   one summary row. Views describe each block as an `info` map

     {:kind     :tool-call | :thinking
      :name     \"Read\" / \"Thinking\"
      :detail   short argument summary (first line) or nil
      :running? true while the tool call is in flight
      :error?   true when the tool call failed}

   and `summarize` reduces a run of them to what the summary row shows.")

(defn super-collapsible?
  "Does a run of group `items` ({:collapsed? bool …}) fold into a summary row?
   Only a run of two or more blocks that all start collapsed — a lone block
   is already one header row, and a run containing an open block (the
   :tool-blocks :open setting) must stay visible."
  [items]
  (boolean (and (next items) (every? :collapsed? items))))

(defn summarize
  "Reduce the `infos` of a run (oldest → newest) to the summary row's data:
   {:count :tool-count :thinking-count :error-count :running? :latest :previous}.
   `:latest` is the newest block's info, `:previous` the one before it (what
   the row showed until `:latest` arrived), `:running?` is true if any block
   is still in flight."
  [infos]
  (let [kinds (frequencies (map :kind infos))]
    {:count          (count infos)
     :tool-count     (get kinds :tool-call 0)
     :thinking-count (get kinds :thinking 0)
     :error-count    (count (filter :error? infos))
     :running?       (boolean (some :running? infos))
     :latest         (last infos)
     :previous       (last (butlast infos))}))

(defn count-label
  "\"1 step\" / \"5 steps\" for a summary's `:count`."
  [n]
  (str n (if (= 1 n) " step" " steps")))
