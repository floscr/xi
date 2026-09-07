(ns xi.client.sidebar
  "TUI session drawer — the terminal counterpart to the web's recent-sidebar.
   Renders the same Recent / Hidden / Earlier groups (from the shared
   xi.session.sidebar logic over the lobby mirror) as a left column, and drives
   session switching + per-session actions from the keyboard.

   Toggled with Alt+\\. While open it is modal: keys drive the drawer instead
   of the editor (Alt+j/k or j/k switch sessions live, x/s/m act on the
   highlighted one, Esc/Alt+\\ close). Wired into xi.tui.core via set-sidebar!."
  (:require [clojure.string :as str]
            [xi.core.state :as state]
            [xi.session.sidebar :as sb]
            [xi.tui.ansi :as ansi]))

(def ^:private ESC (str (char 27)))
(def width 34)

;; ── Key sequences (legacy ESC-prefixed + kitty CSI-u encodings) ───────────────
(def ^:private alt-backslash #{(str ESC "\\") (str ESC "[92;3u")})
(def ^:private alt-j #{(str ESC "j") (str ESC "[106;3u")})
(def ^:private alt-k #{(str ESC "k") (str ESC "[107;3u")})
(def ^:private arrow-down (str ESC "[B"))
(def ^:private arrow-up (str ESC "[A"))
(def ^:private ctrl-c (str (char 3)))

(defn- enter? [d] (or (= d "\r") (= d "\n")))
(defn- esc-close? [d] (or (= d ESC) (= d (str ESC "[27u"))))

;; ── Model ─────────────────────────────────────────────────────────────────────

(defn- current-sid [st]
  (get-in (state/active-room st) [:session :id]))

(defn- clamp-cursor
  "Ensure the cursor points at a session that still exists in `order`; fall back
   to the active session, then the first entry."
  [cursor order st]
  (let [order-set (set order)]
    (cond
      (order-set cursor)              cursor
      (order-set (current-sid st))    (current-sid st)
      (seq order)                     (first order)
      :else                           nil)))

;; ── Rendering ─────────────────────────────────────────────────────────────────

(defn- fit
  "Pad or hard-truncate a plain (ANSI-free) string to exactly `w` columns."
  [s w]
  (let [n (count s)]
    (cond
      (= n w) s
      (< n w) (str s (apply str (repeat (- w n) " ")))
      :else   (if (pos? w) (str (subs s 0 (max 0 (dec w))) "\u2026") ""))))

(defn- row-text
  "Plain fixed-width text for a session row: marker + name on the left, relative
   time on the right."
  [marker {:keys [name timestamp]} w]
  (let [nm   (or name "New session")
        rt   (or (sb/format-relative-time timestamp) "")
        head (str marker " " nm)
        ;; Reserve room for the right-aligned time (plus a gap + trailing space).
        maxh (max 0 (- w (count rt) 2))
        head (if (> (count head) maxh)
               (str (subs head 0 (max 0 (dec maxh))) "\u2026")
               head)
        gap  (max 1 (- w (count head) (count rt) 1))]
    (fit (str head (apply str (repeat gap " ")) rt) w)))

(defn- session-line
  "Colored, fixed-width line for one session card. Selected → reverse video."
  [{:keys [busy? has-dialog? active? current?] :as card} selected? w]
  (let [marker (cond has-dialog? "!"
                     busy?       "\u2022"
                     active?     "\u2022"
                     current?    "\u203a"
                     :else       " ")
        text   (row-text marker card w)]
    (cond
      selected?   (ansi/reverse-video text)
      has-dialog? (ansi/fg :warning text)
      busy?       (ansi/fg :success text)
      current?    (str ansi/bold text ansi/reset)
      :else       text)))

(defn- header-line [label w]
  (ansi/fg :border (fit (str "  " (str/upper-case label)) w)))

(defn- display-rows
  "Flat list of drawer rows, each {:sid s-or-nil :line str}. Headers carry a nil
   :sid so they're skipped by cursor navigation but still rendered."
  [st cursor w]
  (let [{:keys [recent hidden earlier]} (sb/sidebar-session-groups st)
        cur  (current-sid st)
        mark (fn [c] (assoc c :current? (= (:session-id c) cur)))
        grp  (fn [label cards]
               (when (seq cards)
                 (into [{:sid nil :line (header-line label w)}]
                       (map (fn [c]
                              (let [c (mark c)]
                                {:sid  (:session-id c)
                                 :line (session-line c (= (:session-id c) cursor) w)}))
                            cards))))]
    (vec (concat (grp "Recent" recent)
                 (grp "Hidden" hidden)
                 (grp "Earlier" earlier)))))

(defn- window
  "Slice `rows` to `height` lines keeping the row at `cursor-idx` visible."
  [rows cursor-idx height]
  (let [n (count rows)]
    (if (<= n height)
      rows
      (let [ci    (or cursor-idx 0)
            start (-> ci (- (quot height 2)) (max 0) (min (max 0 (- n height))))]
        (subvec rows start (min n (+ start height)))))))

(defn- render-lines [st cursor w height]
  (let [title (ansi/fg :accent (fit "  Sessions  \u2325\\ close" w))
        rows  (display-rows st cursor w)
        body-h (max 0 (dec height))                    ;; title takes one line
        ci    (first (keep-indexed (fn [i r] (when (= (:sid r) cursor) i)) rows))
        shown (window rows ci body-h)
        lines (into [title] (map :line shown))
        lines (if (empty? rows)
                (conj lines (ansi/fg :dim (fit "  No sessions" w)))
                lines)]
    ;; Pad/truncate to exactly `height` lines.
    (into (subvec (into [] (take height lines)) 0 (min height (count lines)))
          (repeat (max 0 (- height (count lines))) (fit "" w)))))

;; ── Component ─────────────────────────────────────────────────────────────────

(defn make-sidebar
  "Build the drawer component consumed by xi.tui.core/set-sidebar!.
   opts: {:get-state :dispatch! :render! :repaint!}. Returns a map with
   :width :open? :render :handle-key."
  [{:keys [get-state dispatch! render! repaint!]}]
  (let [ui (atom {:open? false :cursor nil})
        order (fn [st] (sb/sidebar-session-order st))
        join! (fn [sid] (when sid (dispatch! {:type :room/join
                                              :target {:session-id sid}})))
        act!  (fn [type]
                (when-let [sid (:cursor @ui)]
                  (dispatch! {:type type :session-id sid})
                  (render!)))
        open! (fn []
                (let [st (get-state)]
                  (swap! ui assoc :open? true
                         :cursor (clamp-cursor (current-sid st) (order st) st)))
                (repaint!))
        close! (fn [] (swap! ui assoc :open? false) (repaint!))
        move! (fn [dir]
                (let [st  (get-state)
                      ord (order st)
                      n   (count ord)]
                  (when (pos? n)
                    (let [cur (clamp-cursor (:cursor @ui) ord st)
                          idx (or (first (keep-indexed (fn [i s] (when (= s cur) i)) ord)) 0)
                          nxt (case dir
                                :next (min (dec n) (inc idx))
                                :prev (max 0 (dec idx)))
                          sid (nth ord nxt)]
                      (swap! ui assoc :cursor sid)
                      ;; Switch live on every step, like the web ALT+j/k.
                      (when (not= sid cur) (join! sid))
                      (render!)))))]
    {:width  width
     :open?  (fn [] (:open? @ui))
     :render (fn [w height] (render-lines (get-state) (:cursor @ui) w height))
     :handle-key
     (fn [data]
       (cond
         (alt-backslash data) (do (if (:open? @ui) (close!) (open!)) true)
         (not (:open? @ui))   false
         ;; ── modal: drawer owns the keyboard while open ──
         (esc-close? data)                              (do (close!) true)
         (enter? data)                                  (do (close!) true)
         (or (alt-j data) (= data "j") (= data arrow-down)) (do (move! :next) true)
         (or (alt-k data) (= data "k") (= data arrow-up))   (do (move! :prev) true)
         (= data "x")                                   (do (act! :dismissed/toggle) true)
         (= data "s")                                   (do (act! :favorites/toggle) true)
         (= data "m")                                   (do (act! :session/mark-read) true)
         (= data ctrl-c)                                false      ;; let quit through
         :else                                          true))}))
