(ns xi.ext.process-manager.web
  "Browser half of the process-manager — composed by xi.web.core (never
   loaded by the node builds). Applies the register/deregister broadcasts
   with the shared reducers (xi.ext.process-manager.registry), so the chat
   header's process chip (xi.web.views/process-chip) lists the room's
   background processes. Picking one sends :process-manager/open-log; the
   server installs and keeps refreshing a `:process-log` buffer, rendered
   here."
  (:require [ui.badge :as badge]
            [xi.ext.process-manager.registry :as reg]))

(defn- mirror [f]
  (fn [st ev] (when (:remote? ev) (f st ev))))

(defn- open-log
  "Forward to the server, which reads the log (xi.ext.process-manager)."
  [st {:keys [remote?] :as ev}]
  (when-not remote?
    {:effects [[:ws/send (-> ev
                             (update :room-id #(or % (:active-room st)))
                             (dissoc :event/id :event/ts))]]}))

;; ── Buffer view ──────────────────────────────────────────────────────────────

(def ^:private stick-px
  "How close to the bottom counts as following the tail."
  24)

(defn- at-bottom? [^js node]
  (<= (- (.-scrollHeight node) (.-scrollTop node) (.-clientHeight node)) stick-px))

(defn- follow-tail
  "Keep the log scrolled to its end across refreshes, unless the reader
   scrolled up (remembered on the node, which survives re-renders)."
  [{:replicant/keys [^js node]}]
  (when-not (false? (.-xiStick node))
    (set! (.-scrollTop node) (.-scrollHeight node))))

(defn- status-badge [{:keys [running? exit]}]
  (cond
    running?   (badge/badge {:variant :outline :size :sm} "running")
    (= 0 exit) (badge/badge {:variant :success :size :sm} "exited 0")
    exit       (badge/badge {:variant :danger :size :sm} (str "exited " exit))
    :else      (badge/badge {:variant :warning :size :sm} "stopped")))

(defn- log-view
  [_state _dispatch! _room _id {:keys [pid command logfile text] :as buf}]
  [:div {:class ["file-tab" "process-log"]}
   [:div {:class ["file-tab-header"]}
    (status-badge buf)
    [:span {:class ["process-log-command"] :data-tooltip command} (str "$ " command)]
    [:span {:class ["process-log-meta"] :data-tooltip logfile} (str "PID " pid)]]
   [:pre {:class ["file-tab-code" "process-log-body"]
          :replicant/on-render follow-tail
          :on {:scroll (fn [^js e]
                         (let [node (.-currentTarget e)]
                           (set! (.-xiStick node) (at-bottom? node))))}}
    (if (seq text) text "(no output yet)")]])

(def extension
  {:id           reg/ext-id
   :init         {:room {:processes []}}
   :handlers     {:ext.process-manager/register   (mirror reg/register-process)
                  :ext.process-manager/deregister (mirror reg/deregister-process)
                  :process-manager/open-log       open-log}
   :buffer-views {:process-log {:view log-view :icon :terminal}}})
