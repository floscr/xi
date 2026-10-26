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
   which the client matches before it submits; :sub-id, a sub-agent it
   spawned and looks up by that id)."
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

(def ^:private skip-to
  "The chapter a tour opened with ?chapter= fast-forwards to: until its step
   runs, frames play without their spacing and steps without their pauses."
  (atom nil))

(def ^:private visit-ms
  "How long a session the visitor opens takes to answer, as from a server."
  120)

(defn create!
  "A transport that replays `tape` (parsed JSON, keywordized). opts:
   :on-status (fn [connected?]), :on-done (fn []), :on-send (fn [type],
   for each gate-type send the client makes), :room-of (fn [room-id] → the
   client's mirror of that room), :intercept (as ws-transport's: terminal
   output goes to xterm.js, not the app). Besides the ws-transport keys
   (:send! too, a terminal's raw sends: its attach can be a gate) it returns
   :mirror! (fn [type]): pass a gate another client of the same tape sent,
   so a client that only watches (the session list beside the chat) keeps
   pace with it; and :visit? (fn [session-id]): an earlier session the tape
   answers joins for.

   A join of a session (the visitor opening it from the sidebar) is no gate:
   an earlier one plays the tape's recorded answer (:visits); the tour's own
   room is re-sent from the client's mirror, which kept following the tape."
  [tape {:keys [on-status on-done on-send room-of intercept]}]
  (let [frames  (:frames tape)
        gates   (set (:gates tape))
        visits  (into {} (map (juxt :id :frames)) (:visits tape))
        offset  (- (js/Date.now) (:recordedAt tape))
        ;; :auth/hello is the transport's own send, made on connect
        player  (atom {:i 0 :prev-t 0 :sent {"auth/hello" 1} :waiting nil
                       :dispatch nil
                       ;; session id → room id, for the tape's own rooms
                       :own {}})]
    (letfn [(dispatch! [ev]
              ((:dispatch @player) (assoc ev :remote? true)))
            (decode [data]
              (wire/decode (-> data
                               (fill-echoes (:echoes @player))
                               (shift-times offset))))
            (emit! [data]
              (when-let [ev (decode data)]
                (when (= :room/joined (:type ev))
                  (swap! player assoc-in [:own (get-in ev [:room :session :id])] (:room-id ev)))
                (when-not (and intercept (intercept ev))
                  (dispatch! ev))))
            (visit! [session-id]
              (js/setTimeout
               (fn []
                 (if-let [answer (get visits session-id)]
                   (run! #(some-> (decode %) dispatch!) answer)
                   (let [room-id (get-in @player [:own session-id])]
                     (when-let [room (and room-id room-of (room-of room-id))]
                       (dispatch! {:type :room/joined :room-id room-id :room room})))))
               visit-ms))
            (play! []
              (loop []
                (let [{:keys [i sent prev-t]} @player
                      {:keys [op t data] gate :type :as step} (advance frames gates sent i)]
                  (swap! player assoc :i (:i step) :sent (:sent step))
                  (case op
                    :done (when on-done (on-done))
                    :wait (swap! player assoc :waiting gate :gate-t t)
                    :emit (let [d (if @skip-to 0 (delay-ms prev-t t))]
                            (swap! player assoc :prev-t t)
                            (if (< d 8)
                              (do (emit! data) (recur))
                              (js/setTimeout #(do (emit! data) (play!)) d)))))))
            (gate! [sent-type]
              (let [{:keys [waiting gate-t i]} @player]
                (cond
                  (= sent-type waiting)
                  (do (swap! player assoc :waiting nil :i (inc i) :prev-t gate-t)
                      (play!))

                  (contains? gates sent-type)
                  (swap! player update-in [:sent sent-type] (fnil inc 0)))))
            (send! [event]
              ;; ids the client makes up and the server echoes: the room's
              ;; join token, a sub-agent it spawns (the diff view's Ask)
              (doseq [k [:join-token :sub-id]]
                (when-let [v (get event k)]
                  (swap! player assoc-in [:echoes k] v)))
              (let [sent-type (event-type event)]
                (if (and (= :room/join (:type event)) (:session-id event))
                  (visit! (:session-id event))
                  (do (when (and on-send (contains? gates sent-type))
                        (on-send sent-type))
                      (gate! sent-type)))))]
      {:effects       {:ws/send (fn [_ event] (send! event))}
       :send!         send!
       :mirror!       gate!
       :visit?        #(contains? visits %)
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
;;   {:key "g g"}            press keys on the page, space-separated chords
;;                           ("Alt+u", "G", "Space"), each shown as a keycap
;;   {:app "label"}          click inside the last app view (an MCP Apps
;;   {:app "#selector"}      iframe, xi.ext.blocks.frame): a 3D object by its
;;                           label, or an element; the view says where
;;   {:appOrbit radians}     drag the view's camera around (resources/
;;                           extensions/scene/view.cljs answers both)
;;   {:chapter "title"}      tell the embedding page the tour reached it
;;                           (site.js marks it on the stage's chapter cards;
;;                           :about is the card's line, read by xisite.pages)

(defn- sleep [ms] (js/Promise. (fn [res] (js/setTimeout res (if @skip-to 0 ms)))))

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

    ;; Enter on an empty composer sends nothing: wait for what fills it (an
    ;; app view's Ask does a moment after its click)
    enter
    (let [^js el (.querySelector js/document enter)]
      (when (and el (not (str/blank? (.-value el)))) el))

    :else
    (.querySelector js/document (or click typed))))

(defn- await-el
  "Resolves to the step's element once it is in the DOM, or nil after 10 s
   not counting the time `paused?` holds (the visitor reading another
   session, where the element can't be)."
  [step paused?]
  (js/Promise.
   (fn [res]
     ((fn poll [left]
        (if (paused?)
          (js/setTimeout #(poll left) 200)
          (if-let [el (find-el step)]
            (res el)
            (if (neg? left)
              (res nil)
              (js/setTimeout #(poll (- left 100)) 100)))))
      10000))))

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

(defn- move-cursor! [x y]
  (set! (.. (cursor) -style -transform) (str "translate(" x "px," y "px)"))
  (sleep 750))

(defn- move-to! [^js el]
  (let [r (.getBoundingClientRect el)]
    (move-cursor! (+ (.-left r) (* 0.5 (.-width r)))
                  (+ (.-top r) (* 0.6 (.-height r))))))

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

(def ^:private named-keys
  {"Space" " " "Esc" "Escape" "Escape" "Escape" "Enter" "Enter" "Tab" "Tab"
   "Up" "ArrowUp" "Down" "ArrowDown" "Left" "ArrowLeft" "Right" "ArrowRight"
   "Backspace" "Backspace"})

(defn key-init
  "A chord as written in a {:key} step (\"Alt+Shift+p\", \"G\", \"Space\") → the
   KeyboardEvent init map. An upper-case letter implies Shift."
  [chord]
  (let [parts (str/split chord #"\+")
        mods  (set (map str/lower-case (butlast parts)))
        k     (last parts)
        key   (get named-keys k k)
        upper (re-matches #"[A-Z]" key)]
    {:key      key
     :code     (cond
                 (re-matches #"[a-zA-Z]" key) (str "Key" (str/upper-case key))
                 (re-matches #"\d" key)       (str "Digit" key)
                 (= " " key)                  "Space"
                 :else                        key)
     :ctrlKey  (contains? mods "ctrl")
     :altKey   (contains? mods "alt")
     :metaKey  (contains? mods "meta")
     :shiftKey (or (contains? mods "shift") (some? upper))}))

(defn- keycap! [chord]
  (let [el (.createElement js/document "kbd")]
    (set! (.-textContent el) chord)
    (set! (.. el -style -cssText)
          (str "position:fixed;left:50%;bottom:48px;z-index:2147483647;pointer-events:none;"
               "transform:translateX(-50%);padding:10px 18px;border-radius:10px;"
               "font:600 22px/1 ui-monospace,monospace;color:#fff;background:rgba(17,17,17,.82);"
               "box-shadow:0 8px 24px rgba(0,0,0,.35);transition:opacity .4s"))
    (.append (.-body js/document) el)
    (js/setTimeout #(set! (.. el -style -opacity) "0") 700)
    (js/setTimeout #(.remove el) 1100)))

(defn- press-keys!
  "Each chord of `keys` as a keydown on the focused element (the page's
   keymap listens on document), a keycap shown for each."
  [keys]
  (reduce (fn [p chord]
            (.then p (fn []
                       (keycap! chord)
                       (.dispatchEvent (or (.-activeElement js/document) (.-body js/document))
                                       (js/KeyboardEvent.
                                        "keydown"
                                        (clj->js (assoc (key-init chord)
                                                        :bubbles true :cancelable true))))
                       (sleep 900))))
          (js/Promise.resolve)
          (str/split (str/trim keys) #"\s+")))

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

;; ── Inside an app view ──
;; The view is an opaque-origin iframe the autopilot can't reach into, so it
;; asks the view by postMessage where a thing is ({xiTour: "locate"} →
;; "located"), moves the cursor there and asks for the click.

(defn- app-frame ^js []
  (last (array-seq (.querySelectorAll js/document "iframe.ab-app-frame"))))

(defn- post-view! [^js frame msg]
  (.postMessage (.-contentWindow frame) (clj->js msg) "*"))

(defn- ask-view
  "The view's answer to a locate of `target` ({:label} or {:selector}) →
   Promise of {:x :y} in the view's own pixels, or nil within a second (not
   drawn yet)."
  [^js frame target]
  (js/Promise.
   (fn [res]
     (let [id (str (random-uuid))
           on (fn on [^js e]
                (let [d (.-data e)]
                  (when (and d (= "located" (.-xiTour d)) (= id (.-id d)))
                    (.removeEventListener js/window "message" on)
                    (res (some-> (.-at d) (js->clj :keywordize-keys true))))))]
       (.addEventListener js/window "message" on)
       (js/setTimeout #(do (.removeEventListener js/window "message" on) (res nil)) 1000)
       (post-view! frame (merge {:xiTour "locate" :id id} target))))))

(defn- locate-in-view
  "Retries until the view has drawn `target` (it loads three.js first) →
   Promise of [frame {:x :y}], or nil after 20 s not counting `paused?`."
  [target paused?]
  ((fn try! [left]
     (if (paused?)
       (-> (sleep 200) (.then #(try! left)))
       (let [frame (app-frame)]
         (-> (if frame (ask-view frame target) (js/Promise.resolve nil))
             (.then (fn [at]
                      (cond
                        at          [frame at]
                        (neg? left) nil
                        :else       (-> (sleep 300) (.then #(try! (- left 1300)))))))))))
   20000))

(defn- app-target
  "An :app step's string: a CSS selector (`#ask`, `.x`, `[…]`) or a label."
  [s]
  (if (re-find #"^[#.\[]" s) {:selector s} {:label s}))

(defn- app-click! [target paused?]
  (-> (locate-in-view (app-target target) paused?)
      (.then (fn [[^js frame {:keys [x y]}]]
               (when frame
                 (let [r (.getBoundingClientRect frame)]
                   (-> (move-cursor! (+ (.-left r) x) (+ (.-top r) y))
                       (.then #(post-view! frame {:xiTour "click" :x x :y y})))))))))

(defn- app-orbit!
  "The cursor drags across the view while its camera turns `angle`."
  [angle paused?]
  (-> (locate-in-view {:selector "canvas"} paused?)
      (.then (fn [[^js frame]]
               (when frame
                 (let [r  (.getBoundingClientRect frame)
                       cx (+ (.-left r) (* 0.6 (.-width r)))
                       cy (+ (.-top r) (* 0.55 (.-height r)))
                       ms 1000]
                   (-> (move-cursor! cx cy)
                       (.then (fn []
                                (post-view! frame {:xiTour "orbit" :angle angle :ms ms})
                                (set! (.. (cursor) -style -transition)
                                      (str "transform " ms "ms cubic-bezier(.45,0,.55,1)"))
                                (move-cursor! (- cx (* angle 180)) cy)))
                       (.then #(sleep (- ms 750)))
                       (.then #(set! (.. (cursor) -style -transition)
                                     "transform .7s cubic-bezier(.3,.7,.2,1)")))))))))

(defn- run-step! [{:keys [wait waitText text enter after app appOrbit chapter] typed :type pressed :key :as step} paused?]
  (cond
    chapter  (do (when (= chapter @skip-to) (reset! skip-to nil))
                 (.postMessage js/parent #js {:xiTour "chapter" :title chapter} "*"))
    wait     (sleep wait)
    waitText (await-el step paused?)
    pressed  (-> (sleep (or after 0)) (.then #(press-keys! pressed)))
    app      (-> (sleep (or after 0)) (.then #(app-click! app paused?)))
    appOrbit (-> (sleep (or after 0)) (.then #(app-orbit! appOrbit paused?)))
    :else
    (-> (sleep (or after 0))
        (.then #(await-el step paused?))
        (.then (fn [^js el]
                 (when el
                   (-> (move-to! el)
                       (.then (fn []
                                (cond
                                  ;; the visitor opened another session meanwhile
                                  (paused?) (run-step! (dissoc step :after) paused?)
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

(defn chapter-progress
  "How far into its chapter the tour is once each of `steps` ran: 0 at a
   {:chapter} step, 1 after the chapter's last step; nil before the first."
  [steps]
  (let [n-after (fn [i] (count (take-while (complement :chapter) (drop (inc i) steps))))]
    (first
     (reduce (fn [[out start n] [i step]]
               (cond
                 (:chapter step) [(conj out 0) i (n-after i)]
                 start           [(conj out (/ (- i start) n)) start n]
                 :else           [(conj out nil) start n]))
             [[] nil 0]
             (map-indexed vector steps)))))

(defn autopilot!
  "Play `steps` in order with a :mouse or :touch `pointer`; resolves when
   the last one ran. A step waits while `paused?` holds. With `from`, one of
   the steps' chapters, it fast-forwards to that chapter. After each step it
   posts the embedding page {xiTour: \"progress\", value} (chapter-progress)."
  [steps pointer-kind paused? from]
  (reset! pointer pointer-kind)
  (reset! skip-to (some #{from} (keep :chapter steps)))
  (reduce (fn [p [step progress]]
            (.then p (fn []
                       (-> (js/Promise.resolve (run-step! step paused?))
                           (.then #(when progress
                                     (.postMessage js/parent #js {:xiTour "progress" :value progress} "*")))))))
          (js/Promise.resolve)
          (map vector steps (chapter-progress steps))))
