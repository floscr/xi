(ns xi.holds
  "Holds — releasable, cross-process blocks on a shared resource.

   While a room holds one, every other room's calls that touch the same
   resource *wait* until it is released; the holder's own calls pass. It is
   not policy (rules decide whether a call may run at all, before this) and
   it never grants anything — it only orders access.

   The registry is fixed in core. A hold is a data map:

     :id          keyword
     :label       what is held, for status lines (\"git index\")
     :ops         (fn [tool-name arguments cwd] → [op]) — what a tool call
                  does under this hold; [] = unaffected
     :key         (fn [op cwd] → key|nil) — the resource an op touches (nil =
                  nothing to hold, e.g. outside a repo)
     :cwd-key     (fn [cwd] → key|nil) — the resource a room's cwd sits on
                  (turn-end settle, /holds, /release)
     :lease-path  (fn [key] → path|nil) — the lease file (xi.holds.lease)
     :stale?      (fn [key lease] → bool) — staleness beyond a dead owner
     :settle?     (fn [key] → bool) — release condition, checked after each
                  held call and at turn end
     :refuse      (fn [ops ctx] → msg|nil) — hook: refuse outright, no wait
     :detail      (fn [key] → str|nil) — appended to wait/timeout messages
     :on-acquire  (fn [key fresh?] → str|nil) — status line after acquiring
     :wait-ms     (fn [] → ms) — give up waiting after this
     :hint        what the user can do about a stuck holder

   Enforcement: `wrap` runs around every tool exec-fn (xi.tools.registry/
   with-extensions, all providers) — after the rules have allowed the call,
   so a rules :allow can't skip it. The clj worker, whose git runs mid-eval,
   asks the main thread to `acquire!` and settles itself.

   Release: `settle!` after each held call, `settle-room!` at turn end, a dead
   or idle owner (its turn ended with the hold unsettled) or `:stale?`, and
   /release."
  (:require [clojure.string :as str]
            [xi.core.state :as state]
            [xi.git-lock :as git-lock]
            [xi.holds.lease :as lease]))

(def registry
  "Every hold, in acquisition order."
  [git-lock/hold])

(defn- by-id [id] (some #(when (= id (:id %)) %) registry))

(defn room-key
  "The lease owner's room key — also the clj worker's room key."
  [room-id]
  (or (some-> room-id name) "default"))

(defn owner
  "Lease owner for a room: {:pid :room :label}."
  [get-state room-id]
  {:pid   js/process.pid
   :room  (room-key room-id)
   :label (when get-state (some-> (state/get-room (get-state) room-id) lease/room-label))})

;; Leases this process acquired, per room key: #{[hold-id key]} — so turn end
;; settles every resource the room took, not just its cwd's.
(defonce ^:private held (atom {}))

(defn- status! [dispatch! room-id text]
  (when (and dispatch! (seq text))
    (dispatch! {:type :ui/status :room-id room-id :text text})))

(defn- secs [ms] (js/Math.round (/ ms 1000)))

(defn- turn-over? [get-state room-id]
  (not (get-in (get-state) [:rooms room-id :agent :busy?])))

(defn settle!
  "Release `owner`'s lease on `key` when the hold's `:settle?` says so. Sync
   (the clj worker thread calls it). True when released."
  [hold key owner]
  (when-let [path (and key ((:lease-path hold) key))]
    (when (and ((:settle? hold) key) (lease/release! path owner))
      (swap! held update (:room owner) disj [(:id hold) key])
      true)))

(defn- settle-or-idle!
  "The owner's turn is over: release its lease on `key` if the hold settles,
   else flag it idle — a stopped room can't release it, so it must not keep
   other rooms waiting."
  [hold key owner]
  (or (settle! hold key owner)
      (when-let [path (and key ((:lease-path hold) key))]
        (lease/mark-idle! path owner)
        nil)))

(defn- acquire-key!
  "Wait for `key`'s lease. → Promise<nil (acquired / nothing to hold) | error text>."
  [hold key {:keys [room-id get-state dispatch!]} own]
  (if-let [path ((:lease-path hold) key)]
    (let [label  (:label hold)
          detail #(or ((:detail hold) key) "")]
      (-> (lease/wait-acquire!
           {:path       path
            :owner      own
            :stale?     #((:stale? hold) key %)
            :timeout-ms ((:wait-ms hold))
            :cancelled? (when get-state #(turn-over? get-state room-id))
            :on-wait    (fn [holder]
                          (status! dispatch! room-id
                                   (str "⏳ " label " is held by "
                                        (lease/describe-holder holder) (detail)
                                        " — waiting… To unlock: " (:hint hold) ".")))})
          (.then
           (fn [{:keys [status waited-ms holder fresh?]}]
             (case status
               :acquired
               (do (swap! held update (:room own) (fnil conj #{}) [(:id hold) key])
                   (when (pos? (secs waited-ms))
                     (status! dispatch! room-id
                              (str "🔓 " label " acquired after " (secs waited-ms) "s")))
                   (status! dispatch! room-id ((:on-acquire hold) key fresh?))
                   ;; Turn ended while we were polling: don't sit on a lease
                   ;; nobody will use.
                   (when (and get-state (turn-over? get-state room-id))
                     (settle-or-idle! hold key own))
                   nil)
               :cancelled
               (str label ": stopped waiting (turn ended)")
               :timeout
               (str label ": gave up after " (secs waited-ms) "s waiting for "
                    (lease/describe-holder holder) ", which holds the " label
                    (detail) ". Tell the user; they can " (:hint hold) "."))))))
    (js/Promise.resolve nil)))

(defn acquire!
  "Take `hold` for `ops` on behalf of ctx's room ({:room-id :cwd :get-state
   :dispatch!}): the `:refuse` hook first, then wait for each distinct key's
   lease in turn. → Promise<{:keys [key …]} | {:error text}>."
  [hold ops {:keys [room-id get-state cwd] :as ctx}]
  (if-let [msg (when-let [refuse (:refuse hold)] (refuse ops ctx))]
    (js/Promise.resolve {:error msg})
    (let [own  (owner get-state room-id)
          keys (distinct (keep #((:key hold) % cwd) ops))]
      (reduce (fn [p k]
                (.then p (fn [acc]
                           (if (:error acc)
                             acc
                             (-> (acquire-key! hold k ctx own)
                                 (.then (fn [err]
                                          (if err
                                            {:error err}
                                            (update acc :keys conj k)))))))))
              (js/Promise.resolve {:keys []})
              keys))))

(defn- error-result [text]
  {:content [{:type "text" :text text}] :is-error true})

(defn wrap
  "Wrap tool `tool-name`'s exec-fn: a call that runs ops under a hold first
   acquires it (waiting on other rooms, or refused), then runs, then settles.
   Calls no hold cares about run straight through."
  [tool-name exec-fn]
  (fn [args {:keys [room-id get-state] :as ctx}]
    (let [cwd  (or (:cwd ctx) (.cwd js/process))
          ctx  (assoc ctx :cwd cwd)
          jobs (keep (fn [hold]
                       (let [ops ((:ops hold) tool-name args cwd)]
                         (when (seq ops) [hold ops])))
                     registry)]
      (if (empty? jobs)
        (exec-fn args ctx)
        (-> (reduce (fn [p [hold ops]]
                      (.then p (fn [acc]
                                 (if (:error acc)
                                   acc
                                   (-> (acquire! hold ops ctx)
                                       (.then (fn [{:keys [error keys]}]
                                                (if error
                                                  {:error error}
                                                  (update acc :taken conj [hold keys])))))))))
                    (js/Promise.resolve {:taken []})
                    jobs)
            (.then
             (fn [{:keys [error taken]}]
               (if error
                 (error-result error)
                 (let [own (owner get-state room-id)]
                   (-> (js/Promise.resolve)
                       (.then (fn [_] (exec-fn args ctx)))
                       (.finally (fn []
                                   (doseq [[hold keys] taken
                                           k keys]
                                     (try (settle! hold k own)
                                          (catch :default _ nil)))))))))))))))

(defn settle-room!
  "Turn end: settle every lease the room took in this process, plus its
   cwd's resources. What doesn't settle is flagged idle, so another room can
   take it over instead of waiting on a room that has stopped."
  [room-id cwd]
  (let [own  {:pid js/process.pid :room (room-key room-id)}
        took (for [[hold-id k] (get @held (:room own))
                   :let [hold (by-id hold-id)]
                   :when hold]
               [hold k])
        here (for [hold registry
                   :let [k (when cwd ((:cwd-key hold) cwd))]
                   :when k]
               [hold k])]
    (doseq [[hold k] (distinct (concat took here))]
      (try (settle-or-idle! hold k own) (catch :default _ nil)))))

;; ── /holds, /release ─────────────────────────────────────────────────────────

(defn- cwd-leases
  "[{:hold :key :path :lease}] for each hold's resource under `cwd`."
  [cwd]
  (for [hold registry
        :let [k    (when cwd ((:cwd-key hold) cwd))
              path (when k ((:lease-path hold) k))]
        :when path]
    {:hold hold :key k :path path :lease (lease/read-lease path)}))

(defn- list-fx [{:keys [dispatch! get-state]} {:keys [room-id]}]
  (let [cwd  (get-in (get-state) [:rooms room-id :cwd])
        rows (filter :lease (cwd-leases cwd))]
    (status! dispatch! room-id
             (if (seq rows)
               (str "Holds:\n"
                    (str/join "\n"
                              (map (fn [{:keys [hold key lease]}]
                                     (str "  " (:label hold) " — held by "
                                          (lease/describe-holder lease)
                                          ((:detail hold) key)))
                                   rows)))
               "No holds on this room's resources."))))

(defn- release-fx [{:keys [dispatch! get-state]} {:keys [room-id]}]
  (let [cwd     (get-in (get-state) [:rooms room-id :cwd])
        dropped (keep (fn [{:keys [hold path]}]
                        (when-let [l (lease/force-release! path)]
                          (str (:label hold) " (was held by " (lease/describe-holder l) ")")))
                      (cwd-leases cwd))]
    (status! dispatch! room-id
             (if (seq dropped)
               (str "🔓 released: " (str/join ", " dropped))
               "Nothing held on this room's resources."))))

(def fx
  "Effects behind the /holds and /release commands (xi.commands)."
  {:holds/list    list-fx
   :holds/release release-fx})
