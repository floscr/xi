(ns xi.client.subagents-buffer
  "Live sub-agents buffer — a pager specialization over the room's
   [:ext :subagents :agents] extension state (xi.ext.subagent).

   Unlike the text/diff buffers this one has no [:ui :buffers] entry: the
   host (xi.client.tui) invalidates it whenever the agents vector changes,
   so the view streams a running sub-agent's output live.

   Two views:
     :list    — one compact card per sub-agent (status, label, task, last
                output line). j/k select whole cards; ⏎ opens the transcript.
     :detail  — the selected sub-agent's full streaming transcript. While
                the viewport sits at the bottom it follows the tail as new
                output arrives; q/Esc goes back to the list.

   Keybindings (on top of the pager's scroll/yank set):
     j/k or ↑/↓  List: select next / previous sub-agent
     ⏎ / Tab     List: open the sub-agent's transcript
     o           Open the selected / viewed finished sub-agent as a chat
                 (promotes it to a full session and resumes it here)
     x           Stop the selected / viewed running sub-agent
     q / Esc     Detail: back to list · List: back to chat"
  (:require [clojure.string :as str]
            [xi.client.view :as view]
            [xi.tui.ansi :as ansi]
            [xi.tui.core :as tui]
            [xi.tui.pager :as pager]
            [xi.util :as util]))

(def ^:private ESC (str (char 27)))

;; ── Rendering ─────────────────────────────────────────────────────────────────

(defn- status-part [status]
  (case status
    :running (ansi/fg :accent "● running")
    :done    (ansi/fg :green  "✓ done")
    :error   (ansi/fg :red    "✗ error")
    :stopped (ansi/fg :yellow "■ stopped")
    (ansi/fg :dim (str status))))

(defn- duration-str [{:keys [started ended]}]
  (when started
    (let [secs (quot (- (or ended (.now js/Date)) started) 1000)
          mins (quot secs 60)]
      (if (>= mins 1) (str mins "m " (mod secs 60) "s") (str secs "s")))))

(defn- head-line
  "One sub-agent's card head: status, label, duration, id."
  [{:keys [id label task] :as agent}]
  (str (status-part (:status agent))
       "  " (ansi/fg :bold (or label task "sub-agent"))
       (when-let [d (duration-str agent)]
         (ansi/fg :dim (str "  " d)))
       (ansi/fg :dim (str "  [" id "]"))))

(defn- tool-line [{:keys [tool arguments status]}]
  (let [display (util/strip-mcp-prefix tool)
        canonical (some-> display str/lower-case)
        args (some-> (view/format-tool-args canonical arguments)
                     (util/truncate 100))]
    (str (ansi/fg :dim "⚙ ") (ansi/fg :accent display)
         (when (seq args) (ansi/fg :dim (str " " args)))
         (case status
           :running (ansi/fg :dim " …")
           :error   (ansi/fg :red " ✗")
           ""))))

(defn- entry-lines
  "History entry → rendered lines (unindented), width-aware."
  [entry width]
  (case (:kind entry)
    :text      (ansi/wrap-text (str (:text entry)) width)
    :thinking  (mapv #(ansi/fg :dim %)
                     (ansi/wrap-text (str (:text entry)) width))
    :tool-call [(tool-line entry)]
    :error     (mapv #(ansi/fg :red %)
                     (ansi/wrap-text (str "error: " (or (get-in entry [:error :message])
                                                        (pr-str (:error entry))))
                                     width))
    nil))

(defn- one-line
  "First wrapped line of text, with a dim ellipsis when it was longer."
  [text width]
  (let [ls (ansi/wrap-text (str text) width)]
    (cond-> (or (first ls) "")
      (next ls) (str (ansi/fg :dim " …")))))

(defn- preview-line
  "The last rendered line of the newest history entry — a one-line live
   preview of what the sub-agent is currently doing."
  [history width]
  (if-let [e (peek history)]
    (or (last (entry-lines e width)) "")
    (ansi/fg :dim "(no output yet)")))

(defn- render-list
  "Compact card list: head + task + latest-output preview per agent. Head
   lines double as the jump positions j/k moves between; each agent's
   [start end] line range (body-relative) is written into ranges-atom so
   the key handler can map cursor → agent."
  [agents ranges-atom width]
  (if (empty? agents)
    (do (reset! ranges-atom [])
        {:lines [(ansi/fg :dim "  (no sub-agents in this room)")]
         :file-starts [] :change-starts []})
    (let [lines  (atom [])
          starts (atom [])
          ranges (atom [])
          body-w (max 20 (- width 2))]
      (doseq [[i {:keys [id task history] :as agent}] (map-indexed vector agents)]
        (when (pos? i) (swap! lines conj ""))
        (let [start (count @lines)]
          (swap! starts conj start)
          (swap! lines conj (head-line agent))
          (when (seq task)
            (swap! lines conj (str "  " (ansi/fg :dim (one-line task (- body-w 2))))))
          (swap! lines conj (str "  " (preview-line history (- body-w 2))))
          (swap! ranges conj {:start start :end (dec (count @lines))
                              :id id :status (:status agent)})))
      (reset! ranges-atom @ranges)
      {:lines @lines :file-starts @starts :change-starts []})))

(defn- render-detail
  "Full transcript of one sub-agent: head, task, then every history entry
   at full width (streaming while it runs)."
  [{:keys [task history] :as agent} width]
  {:lines (-> [(head-line agent)]
              (into (map #(ansi/fg :dim %))
                    (when (seq task) (ansi/wrap-text task width)))
              (conj "")
              (into (if (seq history)
                      (into [] (comp (map #(entry-lines % width))
                                     (interpose [""])
                                     cat)
                            history)
                      [(ansi/fg :dim "(no output yet)")])))
   :file-starts [] :change-starts []})

;; ── Component ─────────────────────────────────────────────────────────────────

(defn make-subagents-buffer
  "Create the live sub-agents pager component.

   opts:
     :get-agents      — (fn []) → current agents vector (read from app state)
     :on-stop         — (fn [sub-id]) stop a running sub-agent
     :on-open         — (fn [sub-id]) open a finished sub-agent as a chat
     :on-close        — (fn []) q/Escape from the list view
     :on-command-mode — (fn []) ':' focuses the editor

   The host must call the returned component's :invalidate whenever the
   agents vector changes identity (streaming deltas), then let the normal
   render pass rebuild the lines."
  [{:keys [get-agents on-stop on-open on-close on-command-mode]}]
  (let [mode      (atom {:view :list})   ;; or {:view :detail :id sub-id}
        ranges    (atom [])
        pager-ref (atom nil)
        refresh!  (fn []
                    (when-let [p @pager-ref] ((:invalidate p)))
                    (tui/request-render!))
        agent-at  (fn [body-line]
                    (when body-line
                      (some (fn [r] (when (<= (:start r) body-line (:end r)) r))
                            @ranges)))
        detail-agent (fn []
                       (when-let [id (:id @mode)]
                         (some #(when (= id (:id %)) %) (get-agents))))
        open-detail! (fn [id set-cursor!]
                       (reset! mode {:view :detail :id id})
                       (refresh!)
                       ;; Jump to the streaming tail: raw cursor clamps to the
                       ;; last line at the next render, offset 0 = bottom.
                       (set-cursor! 999999)
                       (tui/scroll-to-bottom!))
        close-detail! (fn [set-cursor!]
                        (reset! mode {:view :list})
                        (refresh!)
                        (set-cursor! 0)
                        (tui/scroll-to-offset! 999999))
        extra-keys
        (fn [data {:keys [body-cursor set-cursor!
                          jump-next-file! jump-prev-file!]}]
          (let [detail? (= :detail (:view @mode))]
            (cond
              ;; List: j/k (and arrows) select whole cards, not lines
              (and (not detail?)
                   (or (= data "j") (= data (str ESC "[B"))))
              (do (jump-next-file!) true)

              (and (not detail?)
                   (or (= data "k") (= data (str ESC "[A"))))
              (do (jump-prev-file!) true)

              ;; List: ⏎ / Tab open the selected sub-agent's transcript
              (and (not detail?)
                   (or (= data "\r") (= data "\n") (= data "\t")))
              (when-let [{:keys [id]} (agent-at body-cursor)]
                (open-detail! id set-cursor!)
                true)

              ;; o: open the selected / viewed finished sub-agent as a chat
              (= data "o")
              (let [{:keys [id status]} (if detail? (detail-agent) (agent-at body-cursor))]
                (when (and id on-open (not= :running status))
                  (on-open id))
                true)

              ;; x: stop the selected / viewed running sub-agent
              (= data "x")
              (let [{:keys [id status]} (if detail? (detail-agent) (agent-at body-cursor))]
                (when (and id on-stop (= :running status))
                  (on-stop id))
                true)

              ;; Detail: q / Esc go back to the list (list q/Esc falls
              ;; through to the pager's on-close → back to chat)
              (and detail? (or (= data "q") (= data ESC)))
              (do (close-detail! set-cursor!) true)

              :else nil)))
        p (pager/make-pager
           {:title "Sub-agents"
            :lines-fn (fn [width]
                        (let [agents (or (get-agents) [])]
                          (if (= :detail (:view @mode))
                            (if-let [agent (some #(when (= (:id @mode) (:id %)) %) agents)]
                              (render-detail agent width)
                              ;; Viewed agent vanished (ext state cleared):
                              ;; drop back to the list.
                              (do (reset! mode {:view :list})
                                  (render-list agents ranges width)))
                            (render-list agents ranges width))))
            :extra-keys extra-keys
            :help (pager/help-bar [["j/k" "select"] ["⏎" "open"] ["o" "open chat"]
                                   ["x" "stop"] ["y" "yank"] ["q" "back/close"]
                                   [":" "command"]])
            :on-close on-close
            :on-command-mode on-command-mode})
        ;; Follow the streaming tail: when the transcript is open and the
        ;; viewport sits at the bottom, keep the cursor pinned to the last
        ;; line as new output invalidates the buffer.
        p (assoc p :invalidate
                 (fn []
                   ((:invalidate p))
                   (when (and (= :detail (:view @mode))
                              (not (tui/scrolled-up?)))
                     ((:set-cursor! p) 999999))))]
    (reset! pager-ref p)
    p))
