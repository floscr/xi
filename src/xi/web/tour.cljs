(ns xi.web.tour
  "Site tours: the real web client replays a recorded tape of server frames,
   with no server behind it (?tape=<name or url>, booted by xi.web.core).

   A tape is JSON written by scripts/tour-bake.mjs from a recording against
   the fake-LLM tour server (`bb tour`, site/tours/<name>/):
     {:recordedAt ms :gates [type …] :steps [step …]
      :frames [[t \"in\" transit] | [t \"out\" type] …]}
   `create!` has the shape of xi.client.ws-transport/create!: incoming frames
   play back with their recorded spacing, and at a recorded send of a gate
   type the tape waits until the client sends the same type. `autopilot!`
   plays the visitor's part (steps: clicks, typing) unless the visitor acts
   first."
  (:require [clojure.string :as str]
            [xi.wire :as wire]))

;; ── Tape (pure) ──────────────────────────────────────────────────────────────

(def ^:private max-gap-ms
  "Longest pause between two replayed frames; recorded idle time is cut."
  1500)

(defn event-type
  "\"room/join\" for {:type :room/join}."
  [event]
  (some-> (:type event) str (subs 1)))

(defn advance
  "The player's next move from frame `i`. `sent` counts gate-type sends the
   client made before the tape reached them. Returns {:op :emit :t :data :i},
   {:op :wait :t :type :i} at an unmet gate, or {:op :done}; non-gate sends
   in the tape are skipped. Every result carries the updated :sent."
  [frames gates sent i]
  (loop [i i sent sent]
    (if (>= i (count frames))
      {:op :done :i i :sent sent}
      (let [[t dir data] (nth frames i)]
        (cond
          (= "in" dir)               {:op :emit :t t :data data :i (inc i) :sent sent}
          (not (contains? gates data)) (recur (inc i) sent)
          (pos? (get sent data 0))   (recur (inc i) (update sent data dec))
          :else                      {:op :wait :t t :type data :i i :sent sent})))))

(defn delay-ms
  "Wait before playing a frame recorded at `t` after one at `prev-t`."
  [prev-t t]
  (-> (- t prev-t) (max 0) (min max-gap-ms)))

(defn fill-echoes
  "Put the replaying client's own values into the @@key@@ slots the bake left
   where the server echoed a value the recording client made up (:join-token,
   which the client matches before it submits)."
  [data echoes]
  (reduce-kv (fn [d k v] (str/replace d (str "@@" (name k) "@@") v)) data echoes))

(defn shift-times
  "Move the absolute times in a transit frame (epoch ms and ISO-8601 strings)
   by `offset` ms, so a tape recorded weeks ago still reads \"25m ago\"."
  [data offset]
  (-> data
      (str/replace #"\b1[6-9]\d{11}\b" #(str (+ (js/parseInt % 10) offset)))
      (str/replace #"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z"
                   #(.toISOString (js/Date. (+ (.getTime (js/Date. %)) offset))))))

;; ── Transport ────────────────────────────────────────────────────────────────

(defn create!
  "A transport that replays `tape` (parsed JSON, keywordized). opts:
   :on-status (fn [connected?]), :on-done (fn []), :on-send (fn [type],
   for each gate-type send the client makes). Besides the ws-transport keys
   it returns :mirror! (fn [type]): pass a gate another client of the same
   tape sent, so a client that only watches (the session list beside the
   chat) keeps pace with it."
  [tape {:keys [on-status on-done on-send]}]
  (let [frames  (:frames tape)
        gates   (set (:gates tape))
        offset  (- (js/Date.now) (:recordedAt tape))
        ;; :auth/hello is the transport's own send, made on connect
        player  (atom {:i 0 :prev-t 0 :sent {"auth/hello" 1} :waiting nil
                       :dispatch nil})]
    (letfn [(emit! [data]
              (when-let [ev (wire/decode (-> data
                                             (fill-echoes (:echoes @player))
                                             (shift-times offset)))]
                ((:dispatch @player) (assoc ev :remote? true))))
            (play! []
              (let [{:keys [i sent prev-t]} @player
                    {:keys [op t data] gate :type :as step} (advance frames gates sent i)]
                (swap! player assoc :i (:i step) :sent (:sent step))
                (case op
                  :done (when on-done (on-done))
                  :wait (swap! player assoc :waiting gate :gate-t t)
                  :emit (let [d (delay-ms prev-t t)]
                          (swap! player assoc :prev-t t)
                          (if (< d 8)
                            (do (emit! data) (play!))
                            (js/setTimeout #(do (emit! data) (play!)) d))))))
            (gate! [sent-type]
              (let [{:keys [waiting gate-t i]} @player]
                (cond
                  (= sent-type waiting)
                  (do (swap! player assoc :waiting nil :i (inc i) :prev-t gate-t)
                      (play!))

                  (contains? gates sent-type)
                  (swap! player update-in [:sent sent-type] (fnil inc 0)))))
            (send! [event]
              (when-let [token (:join-token event)]
                (swap! player assoc-in [:echoes :join-token] token))
              (let [sent-type (event-type event)]
                (when (and on-send (contains? gates sent-type))
                  (on-send sent-type))
                (gate! sent-type)))]
      {:effects       {:ws/send (fn [_ event] (send! event))}
       :mirror!       gate!
       :set-dispatch! (fn [dispatch!]
                        (swap! player assoc :dispatch dispatch!)
                        (js/setTimeout (fn []
                                         (when on-status (on-status true))
                                         (play!))
                                       0))
       :close!        (fn [] nil)})))

;; ── Autopilot ────────────────────────────────────────────────────────────────
;; Steps (from the tour's steps.json):
;;   {:wait ms}
;;   {:waitText s}           until the page shows `s` (e.g. a tool finished)
;;   {:click css}            move the cursor there and click
;;   {:clickText s :within css :contains bool :after ms}  an element (default a
;;                           button) by its text; waits `after` ms first so the
;;                           visitor can click it themselves
;;   {:type css :text s}     type into a textarea, a key at a time
;;   {:enter css}            press Enter in it

(defn- sleep [ms] (js/Promise. (fn [res] (js/setTimeout res ms))))

(defn- find-el [{:keys [click enter clickText within contains waitText] typed :type}]
  (cond
    waitText
    (when (str/includes? (.. js/document -body -innerText) waitText)
      (.-body js/document))

    clickText
    (->> (.querySelectorAll js/document (or within "button"))
         array-seq
         (filter #(let [text (str/trim (.-textContent %))]
                    (if contains (str/includes? text clickText) (= clickText text))))
         first)

    :else
    (.querySelector js/document (or click typed enter))))

(defn- await-el
  "Resolves to the step's element once it is in the DOM, or nil after 10 s."
  [step]
  (js/Promise.
   (fn [res]
     (let [deadline (+ (js/Date.now) 10000)]
       ((fn poll []
          (if-let [el (find-el step)]
            (res el)
            (if (> (js/Date.now) deadline)
              (res nil)
              (js/setTimeout poll 100)))))))))

;; :mouse draws an arrow pointer, :touch a fingertip dot (phone layouts)
(def ^:private pointer (atom :mouse))

(defn- cursor []
  (or (.getElementById js/document "tour-cursor")
      (let [el     (.createElement js/document "div")
            touch? (= :touch @pointer)]
        (set! (.-id el) "tour-cursor")
        (set! (.-innerHTML el)
              (if touch?
                "<div style=\"width:30px;height:30px;margin:-15px 0 0 -15px;border-radius:50%;background:rgba(127,127,127,.35);border:2px solid rgba(255,255,255,.85)\"></div>"
                "<svg width=\"22\" height=\"22\" viewBox=\"0 0 24 24\"><path d=\"M4 2l16 9-7 2-3 7z\" fill=\"#fff\" stroke=\"#111\" stroke-width=\"1.5\" stroke-linejoin=\"round\"/></svg>"))
        (set! (.. el -style -cssText)
              (str "position:fixed;left:0;top:0;z-index:2147483647;pointer-events:none;"
                   "transform:translate(70vw,70vh);transition:transform .7s cubic-bezier(.3,.7,.2,1);"
                   "filter:drop-shadow(0 2px 4px rgba(0,0,0,.4))"))
        (.append (.-body js/document) el)
        el)))

(defn- move-to! [^js el]
  (let [r (.getBoundingClientRect el)
        x (+ (.-left r) (* 0.5 (.-width r)))
        y (+ (.-top r) (* 0.6 (.-height r)))]
    (set! (.. (cursor) -style -transform) (str "translate(" x "px," y "px)"))
    (sleep 750)))

(defn- set-value! [^js el v]
  ;; The native setter, so the input event carries the new value through.
  (let [^js desc (js/Object.getOwnPropertyDescriptor
                  (.-prototype js/HTMLTextAreaElement) "value")]
    (.call (.-set desc) el v)
    (.dispatchEvent el (js/InputEvent. "input" #js {:bubbles true}))))

(defn type-stops
  "Prefix lengths a typed `text` shows, 1–3 characters apart."
  [text]
  (->> (iterate #(+ % 1 (rand-int 3)) 0)
       rest
       (take-while #(< % (count text)))
       vec
       (#(conj % (count text)))))

(defn- type-text!
  "Type into `el` without focusing it: focus would pull the embedding page's
   keyboard focus into the iframe."
  [^js el text]
  (reduce (fn [p n]
            (.then p (fn []
                       (set-value! el (subs text 0 n))
                       (sleep (+ 12 (rand-int 16))))))
          (js/Promise.resolve)
          (type-stops text)))

(defn contain-focus!
  "In an iframe, ignore the app's programmatic focus until the visitor clicks
   inside: focusing moves the embedding page's keyboard focus (and can scroll
   it) into the tour."
  []
  (when (not= js/window js/parent)
    (let [native (.. js/HTMLElement -prototype -focus)
          user?  (atom false)]
      (.addEventListener js/document "pointerdown" #(reset! user? true) true)
      (set! (.. js/HTMLElement -prototype -focus)
            (fn [& args]
              (this-as el
                (when @user? (.apply native el (into-array args)))))))))

(defn- run-step! [{:keys [wait waitText text enter after] typed :type :as step}]
  (cond
    wait     (sleep wait)
    waitText (await-el step)
    :else
    (-> (sleep (or after 0))
        (.then #(await-el step))
        (.then (fn [^js el]
                 (when el
                   (-> (move-to! el)
                       (.then (fn []
                                (cond
                                  typed (type-text! el text)
                                  enter (.dispatchEvent
                                         el (js/KeyboardEvent. "keydown"
                                                               #js {:key "Enter" :bubbles true
                                                                    :cancelable true}))
                                  ;; the visitor may have clicked it meanwhile
                                  (.-isConnected el) (.click el)))))))))))

(defn variant
  "The tape's run for `variant-name` (?variant=, e.g. a phone layout):
   {:steps :mirror? :pointer}. Without one, the tape's own :steps. A
   :mirror? run passes its gates on the sends of a sibling iframe, relayed
   by the embedding page (site.js)."
  [tape variant-name]
  (let [v (get-in tape [:variants (keyword variant-name)])]
    {:steps   (if v (:steps v) (:steps tape))
     :mirror? (boolean (:mirror v))
     :pointer (keyword (or (:pointer v) "mouse"))}))

(defn autopilot!
  "Play `steps` in order with a :mouse or :touch `pointer`; resolves when
   the last one ran."
  [steps pointer-kind]
  (reset! pointer pointer-kind)
  (reduce (fn [p step] (.then p #(run-step! step)))
          (js/Promise.resolve)
          steps))
