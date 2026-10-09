(ns xi.web.prefetch
  "Warm the cache for the sessions next to the one being viewed, so a sidebar
   walk (xi.web.sidebar-nav) never lands on one with nothing to paint. Once
   the viewed session's join lands, the uncached sidebar neighbours are asked
   for with a roomless :session/peek (xi.server.ws); the reply goes to the
   cache's memory tier (xi.web.cache/remember-room!), not into a room.

   Pure helpers (`neighbours`, `peek-result`) are tested in
   xi.web.prefetch-test."
  (:require [xi.commands :as commands]
            [xi.web.cache :as cache]))

;; ── Pure ─────────────────────────────────────────────────────────────────────

(def ^:private reach
  "Sessions prefetched on each side of the viewed one."
  2)


(defn- interleave-all [a b]
  (lazy-seq
   (cond (empty? a) b
         (empty? b) a
         :else (cons (first a) (cons (first b) (interleave-all (rest a) (rest b)))))))

(defn neighbours
  "Up to `reach` session ids on each side of `sid` in `sids` (sidebar order),
   nearest first; nothing when `sid` isn't listed."
  [sids sid]
  (let [sids (vec (distinct sids))]
    (if-let [i (first (keep-indexed (fn [i s] (when (= s sid) i)) sids))]
      (let [after  (subvec sids (inc i) (min (count sids) (+ i 1 reach)))
            before (rseq (subvec sids (max 0 (- i reach)) i))]
        (vec (interleave-all after before)))
      [])))


(defn peek-result
  "Handler for :session/peek-result: the history goes to the cache's memory
   tier, and straight onto the screen when it is the session being viewed
   with nothing painted yet."
  [st {:keys [session-id messages msg-hash msg-count model]}]
  (when (and session-id (seq messages))
    (let [slice    {:history   (commands/messages->history messages)
                    :model     model
                    :msg-hash  msg-hash
                    :msg-count msg-count}
          viewing? (and (= session-id (get-in st [:web/route :session-id]))
                        (empty? (get-in st [:web/cache session-id :history])))]
      (cond-> {:effects [[:cache/remember-room {:session-id session-id :slice slice}]]}
        viewing? (assoc :state (assoc-in st [:web/cache session-id] slice))))))

;; ── Effects ──────────────────────────────────────────────────────────────────

;; session-id → ms of the last peek sent, so a walk back and forth asks once.
(defonce ^:private requested (atom {}))

(def ^:private re-peek-after-ms 30000)

(defn- sidebar-session-ids []
  (->> (array-seq (.querySelectorAll js/document ".sidebar [data-nav^='s:']"))
       (map #(subs (.. ^js % -dataset -nav) 2))))

(defn- idle! [f]
  (if (exists? js/requestIdleCallback)
    (js/requestIdleCallback f #js {:timeout 1000})
    (js/setTimeout f 50)))

(defn prefetch-around!
  "On idle: send :session/peek for the uncached sidebar neighbours of sid."
  [dispatch! sid]
  (idle!
   (fn [_]
     (let [now  (js/Date.now)
           sids (->> (neighbours (sidebar-session-ids) sid)
                     (remove cache/cached?)
                     (remove #(when-let [t (get @requested %)] (< (- now t) re-peek-after-ms))))]
       (when (seq sids)
         (swap! requested into (map (fn [s] [s now])) sids)
         (dispatch! {:type :session/prefetch :session-ids (vec sids)}))))))

(defn tap
  "App tap: prefetch around the viewed session once its join has landed."
  [dispatch!]
  (fn [event state]
    (when (= :room/joined (:type event))
      (let [sid (get-in event [:room :session :id])]
        (when (and sid (= sid (get-in state [:web/route :session-id])))
          (prefetch-around! dispatch! sid))))))

(def handlers
  {:session/prefetch    (fn [_ {:keys [session-ids]}]
                          {:effects (mapv (fn [sid] [:ws/send {:type :session/peek :session-id sid}])
                                          session-ids)})
   :session/peek-result peek-result})