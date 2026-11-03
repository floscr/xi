(ns xi.partial-writes
  "Keeps a streaming `write` call's content on disk (the :partial-write/*
   effects xi.agent emits), so a call cut off by an abort, the output limit or
   a server restart leaves it behind, and the next turn's prompt says where
   (`turn-notes`, xi.agent/create-fx's :turn-notes).

   From `min-lines` on, a new file the rules let the call write without asking
   is saved in place; anything else goes to `<root>/<session-id>/<call-id>.partial`.
   A `<call-id>.edn` beside it marks the call unreported until `turn-notes`
   reads it. Node-only; wired by xi.cli."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [xi.ext.rules :as rules]
            [xi.tools.fs :as tfs]
            [xi.util :as util]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(def ^:private min-lines 100)

(def ^:private save-interval-ms 1000)

;; /tmp over os.tmpdir(): a server started from a nix-shell has
;; TMPDIR=/tmp/nix-shell.XXXX, gone with that shell
(defn- root []
  (node-path/join (if (fs/existsSync "/tmp") "/tmp" (os/tmpdir)) "xi-partial-writes"))

(defn- session-dir [session-id]
  (node-path/join (root) (str session-id)))

(defn- meta-file [session-id id]
  (node-path/join (session-dir session-id) (str id ".edn")))

(defn note
  "What the next turn's prompt says about a cut-off call whose `target`
   holds `lines` lines."
  [{:keys [path file in-place? existed?]} lines]
  (let [cut (str "Your `write` to " path " was cut off before it ran "
                 "(an abort, the output limit or a restart). ")
        go-on "Continue from there instead of writing it again: "]
    (cond
      in-place?
      (str cut "Its first " lines " lines are saved in that file. " go-on
           "read its last lines and add the rest with `write` and `append: true`.")

      existed?
      (str cut "The file still has its old content; the first " lines
           " lines of the new one are in " file ". " go-on
           "copy that over " path ", read its last lines and add the rest with "
           "`write` and `append: true`.")

      :else
      (str cut "Its first " lines " lines are in " file
           " (the rules ask before writing " path ", so it wasn't written). " go-on
           "copy it to " path ", read its last lines and add the rest with "
           "`write` and `append: true`."))))

(defn- pick-target
  "Where a call's content goes: in place for a new file the rules let it write
   without asking (writing elsewhere early would skip an ask), else the
   session's dir."
  [get-state {:keys [room-id session-id cwd id path content]}]
  (let [resolved (tfs/resolve-path path cwd)
        existed? (fs/existsSync resolved)]
    (if (and (not existed?)
             (rules/allows-without-ask?
              {:name "write" :arguments {:path path :content content}}
              {:get-state get-state :room-id room-id :cwd cwd}))
      {:path resolved :file resolved :in-place? true}
      {:path     resolved
       :file     (node-path/join (session-dir session-id) (str id ".partial"))
       :existed? existed?})))

(defn- save
  "`entry` {:latest payload :target …} written out: the content once it holds
   `min-lines` (its target picked then), the meta file on the first save."
  [get-state {:keys [latest target] :as entry}]
  (let [{:keys [session-id id content]} latest
        lines (util/line-count content)]
    (if (or target (>= lines min-lines))
      (let [target (or target (pick-target get-state latest))]
        (tfs/ensure-parent-dirs (:file target))
        (fs/writeFileSync (:file target) content "utf8")
        (when-not (:target entry)
          (let [m (meta-file session-id id)]
            (tfs/ensure-parent-dirs m)
            (fs/writeFileSync m (pr-str target) "utf8")))
        (assoc entry :target target :lines lines :saved-at (js/Date.now)))
      entry)))

(defn- save-quietly
  "`save`, best-effort: a failure leaves the call unsaved (as without this ns)
   and waits an interval before the next try."
  [get-state entry]
  (try (save get-state entry)
       (catch :default _ (assoc entry :saved-at (js/Date.now)))))

(defn- rm [p]
  (fs/rmSync p #js {:force true}))

(defn create-fx
  "The :partial-write/* effects; the latest arguments of each streaming call
   live here (runtime state, like xi.agent's in-flight turns)."
  []
  (let [calls (js/Map.)]
    {;; every argument update of a streaming write; saves at most once a second
     :partial-write/save
     (fn [{:keys [get-state]} {:keys [id] :as payload}]
       (let [entry (assoc (or (.get calls id) {:saved-at 0}) :latest payload)
             due?  (>= (- (js/Date.now) (:saved-at entry)) save-interval-ms)]
         (.set calls id (if due? (save-quietly get-state entry) entry))))

     ;; the call ran
     :partial-write/drop
     (fn [_ {:keys [id]}]
       (when-let [{:keys [latest target]} (.get calls id)]
         (.delete calls id)
         (when target
           (rm (meta-file (:session-id latest) id))
           (when-not (:in-place? target) (rm (:file target))))))

     ;; the turn ended with the room's writes still streaming: save their
     ;; latest content and show it on their cards
     :partial-write/flush
     (fn [{:keys [dispatch! get-state]} {:keys [room-id]}]
       (doseq [[id entry] (vec (es6-iterator-seq (.entries calls)))
               :when (= room-id (:room-id (:latest entry)))]
         (.delete calls id)
         (let [{:keys [target lines]} (save-quietly get-state entry)]
           (when target
             (dispatch! {:type    :agent/tool-partial
                         :room-id room-id
                         :id      id
                         :partial {:path      (:path target)
                                   :file      (:file target)
                                   :lines     lines
                                   :in-place? (boolean (:in-place? target))}})))))}))

(defn turn-notes
  "A `note` for each of the session's cut-off calls not reported yet, whose
   content is still there; reading one reports it."
  [{:keys [session-id]}]
  (let [dir (when session-id (session-dir session-id))]
    (when (and dir (fs/existsSync dir))
      (->> (fs/readdirSync dir)
           (filter #(str/ends-with? % ".edn"))
           (keep (fn [f]
                   (let [m      (node-path/join dir f)
                         target (try (reader/read-string (fs/readFileSync m "utf8"))
                                     (catch :default _ nil))]
                     (rm m)
                     (when (and (:file target) (fs/existsSync (:file target)))
                       (note target (util/line-count (fs/readFileSync (:file target) "utf8")))))))
           vec))))
