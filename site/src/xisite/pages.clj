(ns xisite.pages
  "Page renderers: home, docs index, docs page. Each returns a full html string."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [ui.badge :as badge]
            [ui.button :as button]
            [ui.button-group :as button-group]
            [ui.card :as card]
            [ui.chip :as chip]
            [ui.form :as form]
            [ui.icon :as icon]
            [xisite.core :as core]
            [xisite.markdown :as md]
            [xisite.theme :as theme]
            [xisite.ui :as ui]))

;; --- Home ---

(def ^:private install-command "bun install -g xi-agent")

(def ^:private extension-sample
  ";; ~/.config/xi/extensions/notes.cljs
(ns notes
  (:require [xi.api.fs :as fs]
            [xi.api.promise :as p]))

(defn- notes-file [ctx] (str (fs/data-dir ctx) \"/notes.md\"))

(def extension
  {:id :notes
   ;; a tool the agent can call
   :tool-definitions
   [{:name \"notes_add\"
     :description \"Append a line to my notes.\"
     :input_schema {:type \"object\"
                    :properties {:text {:type \"string\"}}
                    :required [\"text\"]}}]
   :tool-registry
   {\"notes_add\"
    (fn [{:keys [text]} ctx]
      (-> (fs/read ctx (notes-file ctx))
          (p/catch (fn [_] \"\"))
          (p/then #(fs/write ctx (notes-file ctx) (str % text \"\\n\")))
          (p/then (fn [_] {:content [{:type \"text\" :text \"added\"}]}))))}
   ;; and a slash command for you
   :commands
   [{:name \"notes\" :description \"Show my notes\"
     :handler (fn [_st {:keys [room-id]}]
                {:effects [[:ext.notes/show {:room-id room-id}]]})}]})")

(def ^:private mcp-sample
  "/mcp add browser npx -y chrome-devtools-mcp@1.10.1 --headless
# connects, caches the tool list, done — tools are there on the next turn

/mcp trust browser
# run its tools without asking, until the server's code changes")

(def ^:private clj-sample
  ";; count TODOs per file, return only the summary
(->> (glob \"src/**/*.cljs\")
     (map (fn [f] [f (count (re-seq #\"TODO\" (cat f)))]))
     (filter (fn [[_ n]] (pos? n)))
     (into {}))
;; => {\"src/xi/agent.cljs\" 3, \"src/xi/fx.cljs\" 1}

;; a real CLI, argv-style — reviewed before it runs
(sh \"ffmpeg\" \"-i\" \"talk.mp4\" \"-vn\" \"talk.mp3\")")

(def ^:private rules-sample
  ";; ~/.config/xi/rules.edn — first match wins
{:type :xi/rules
 :version 1
 :rules
 [{:match  {:tool #{:write :edit} :path #\"\\.sh$\"}
   :action {:type :nudge :message \"write babashka scripts, not shell scripts\"}}

  {:match  {:tool :sh :cli \"git\" :command #\"\\bgit push\\b\"}
   :action {:type :ask :message \"Push to the remote?\"}}

  {:match  {:tool #{:read :grep :find :ls} :path #\"^~/Code(?:/|$)\"}
   :action {:type :allow}}]}")

(def ^:private users-sample
  ";; ~/.config/xi/config.edn
:users {\"alice\" {:name \"Alice\"
                :avatar \"https://example.com/a.png\"
                :meta {:team \"ops\"}}
        \"bob\"   {:name \"Bob\"}}")

(def ^:private users-cli-sample
  "xi join --user alice            # this terminal acts as alice
xi clients user iPhone bob      # pin a paired phone to bob")

(def ^:private providers-sample
  ";; ~/.config/xi/config.edn
:providers [:anthropic
            {:id :ollama
             :base-url \"http://gpu:11434\"}
            {:dir \"~/code/my-provider\"}]")

(def ^:private provider-sample
  "// main.ts — its manifest is in package.json
import { serve } from \"@xi-agent/provider-kit\";
import { chatCompletionsTurn }
  from \"@xi-agent/provider-kit/openai-compat\";

serve({
  turn: (req, io) =>
    chatCompletionsTurn(req, io, {
      baseUrl: \"http://localhost:8080\",
    }),
});")

(def ^:private durable-sample
  "xi server
# mid-turn: the server dies, a deploy restarts it, the machine reboots

xi server
[ws] continuing the chat cut off by the restart: Fix the flaky login test")

(defn- code [lang source]
  (md/code-block-hiccup lang source))

(defn- feature-list [items]
  (into [:ul.feature-list]
        (for [[title text] items]
          [:li [:strong title] " " text])))

(defn- phone
  "A phone running the web client on a tour's `variant` (steps.json): live,
   like the stage. site.js scales the 390×844 iframe to the phone and loops
   the phones of one .phones group together."
  [tour variant title]
  [:figure.phone
   [:div.phone-screen
    [:iframe.phone-frame {:data-src (str "/tour/?tape=" tour "&variant=" variant)
                          :title title}]]])

;; --- Architecture diagram ---
;; Inline SVG in the style of a router illustration: cards on hairline wires,
;; accent beams carrying events from a client into the server and out to a
;; provider and a tool. Colours and the beam/flash keyframes are .arch-* in
;; main.css; one cycle is 6s, an event starts every 1.5s.

(defn- arch-icon [k x y size]
  (into [:svg.arch-icon {:x x :y y :width size :height size :viewBox "0 0 24 24"
                         :fill "none" :stroke "currentColor" :stroke-width 2
                         :stroke-linecap "round" :stroke-linejoin "round"}]
        (get icon/icon-paths k)))

(defn- arch-port [x y & [on?]]
  [:rect {:class (cond-> "arch-port" on? (str " on"))
          :x (- x 3.5) :y (- y 3.5) :width 7 :height 7 :rx 1.5}])

(defn- arch-elbow
  "Wire from x1,y1 to x2,y2 turning at xm with rounded corners."
  [x1 y1 x2 y2 xm]
  (let [dy (- y2 y1)
        r (min 12 (/ (abs dy) 2))
        s (if (neg? dy) -1 1)]
    (if (zero? dy)
      (str "M " x1 " " y1 " L " x2 " " y2)
      (str "M " x1 " " y1 " L " (- xm r) " " y1
           " Q " xm " " y1 " " xm " " (+ y1 (* s r))
           " L " xm " " (- y2 (* s r))
           " Q " xm " " y2 " " (+ xm r) " " y2
           " L " x2 " " y2))))

(defn- arch-curve [x1 y1 x2 y2]
  (let [k (* 0.6 (- x2 x1))]
    (str "M " x1 " " y1 " C " (+ x1 k) " " y1 " " (- x2 k) " " y2 " " x2 " " y2)))

(defn- arch-beam
  "A comet along `d`, leaving `delay` seconds into the cycle."
  [d delay]
  [:g.arch-beam {:style (str "--delay:" delay "s")}
   (for [part ["tail" "mid" "head"]]
     [:path {:class (str "arch-beam-" part) :d d :pathLength 100}])])

(defn- arch-flash
  "Lights a list row up when an event passes it: every 1.5s, or once per
   cycle with `slow?`."
  [x y w h delay slow?]
  [:g {:class (cond-> "arch-flash" slow? (str " slow"))
       :style (str "--delay:" delay "s")}
   [:rect.arch-flash-bg {:x (inc x) :y y :width (- w 2) :height h}]
   [:rect.arch-flash-bar {:x x :y (+ y 6) :width 2 :height (- h 12) :rx 1}]])

(defn- arch-node [{:keys [x y w h icon title sub]}]
  (let [cy (+ y (/ h 2))]
    [:g
     [:rect.arch-card {:x x :y y :width w :height h :rx 10}]
     [:rect.arch-tile {:x (+ x 12) :y (- cy 16) :width 32 :height 32 :rx 8}]
     (arch-icon icon (+ x 20) (- cy 8) 16)
     [:text.arch-title {:x (+ x 56) :y (- cy 3)} title]
     [:text.arch-sub {:x (+ x 56) :y (+ cy 13)} sub]]))

(defn- arch-list
  "A card with a header and rows; a row's :flashes are [delay slow?] pairs."
  [{:keys [x y w head-h row-h mark title meta rows]}]
  (let [right (+ x w -14)
        hy (+ y (/ head-h 2))]
    [:g
     [:rect.arch-card {:x x :y y :width w :height (+ head-h (* row-h (count rows))) :rx 10}]
     [:rect.arch-tile.solid {:x (+ x 12) :y (- hy 11) :width 22 :height 22 :rx 6}]
     (if (keyword? mark)
       (arch-icon mark (+ x 17) (- hy 6) 12)
       [:text.arch-mark {:x (+ x 23) :y (+ hy 3.5) :text-anchor "middle"} mark])
     [:text.arch-title {:x (+ x 44) :y (+ hy 4.5)} title]
     [:text.arch-meta {:x right :y (+ hy 4) :text-anchor "end"} meta]
     (map-indexed
      (fn [i {:keys [icon label meta flashes]}]
        (let [ry (+ y head-h (* i row-h))
              cy (+ ry (/ row-h 2))]
          [:g
           [:line.arch-sep {:x1 x :y1 ry :x2 (+ x w) :y2 ry}]
           (for [[delay slow?] flashes]
             (arch-flash x ry w row-h delay slow?))
           (when icon (arch-icon icon (+ x 16) (- cy 7) 14))
           [:text.arch-row {:x (+ x (if icon 40 16)) :y (+ cy 4.5)} label]
           (when meta [:text.arch-meta {:x right :y (+ cy 4)  :text-anchor "end"} meta])]))
      rows)]))

(def ^:private arch-clients
  [{:icon :terminal :title "Terminal" :sub "full-screen TUI"}
   {:icon :globe :title "Web client" :sub "browser and phone"}
   {:icon :play :title "xi prompt" :sub "one-shot, no TUI"}
   {:icon :code :title "Babashka client" :sub "scripted sessions"}])

(defn- architecture-diagram []
  (let [;; clients: four cards on the left
        client-y (fn [i] (+ 20 (* i 80)))
        client-cy (fn [i] (+ (client-y i) 30))
        ;; the server: header + Rooms, Event queue, Rules, Extensions
        hub {:x 316 :y 64 :w 260 :head-h 44 :row-h 44}
        hub-row-cy (fn [i] (+ (:y hub) (:head-h hub) (* i (:row-h hub)) 22))
        hub-right (+ (:x hub) (:w hub))
        [rooms-y queue-y rules-y] (map hub-row-cy [0 1 2])
        ;; providers and tools: two lists on the right
        providers {:x 700 :y 16 :w 180 :head-h 36 :row-h 30}
        tools {:x 700 :y 192 :w 180 :head-h 36 :row-h 30}
        row-cy (fn [{:keys [y head-h row-h]} i] (+ y head-h (* i row-h) (/ row-h 2)))
        client-wire (fn [i] (arch-elbow 196 (client-cy i) (:x hub) rooms-y 256))
        provider-wire (fn [i] (arch-curve hub-right queue-y (:x providers) (row-cy providers i)))
        tool-wire (fn [i] (arch-curve hub-right rules-y (:x tools) (row-cy tools i)))
        ;; event k: client k → Rooms → Event queue → provider k → Rules → a tool
        tool-of [0 1 2 0]
        travel 0.9
        start (fn [k] (* 1.5 k))
        out-at (fn [k] (+ (start k) 1.0))
        tool-at (fn [k] (+ (start k) 1.9))
        landed (fn [t] (mod (+ t travel) 6))]
    [:svg.arch-svg {:viewBox "0 0 880 336" :role "img"
                    :aria-label "Xi architecture: terminal, web, one-shot and Babashka clients connect over WebSocket to the Xi server, which holds rooms, the event queue, rules and extensions, and talks to model providers over stdio and to the clj tool and MCP servers through rules."}
     ;; wires, then the beams riding them
     [:g
      (for [i (range 4)] [:path.arch-wire {:d (client-wire i)}])
      (for [i (range 4)] [:path.arch-wire {:d (provider-wire i)}])
      (for [i (range 3)] [:path.arch-wire {:d (tool-wire i)}])]
     [:g
      (for [k (range 4)] (arch-beam (client-wire k) (start k)))
      (for [k (range 4)] (arch-beam (provider-wire k) (out-at k)))
      (for [k (range 4)] (arch-beam (tool-wire (tool-of k)) (tool-at k)))]
     ;; the WebSocket tag on the clients' bus
     [:g
      [:rect.arch-pill {:x 222 :y 240 :width 68 :height 20 :rx 10}]
      [:text.arch-meta {:x 256 :y 254 :text-anchor "middle"} "WebSocket"]]
     (for [[i c] (map-indexed vector arch-clients)]
       (arch-node (assoc c :x 0 :y (client-y i) :w 196 :h 60)))
     (arch-list (assoc hub
                       :mark "Xi" :title "Xi server" :meta "one state per room"
                       :rows [{:icon :users :label "Rooms" :meta "one per session"
                               :flashes [[(- travel 0.15)]]}
                              {:icon :list :label "Event queue" :meta "pure handlers"
                               :flashes [[(- travel 0.05)]]}
                              {:icon :shield :label "Rules" :meta "allow · ask · deny"
                               :flashes [[1.8]]}
                              {:icon :package :label "Extensions" :meta "tools · keys · pages"}]))
     (arch-list (assoc providers
                       :mark :zap :title "Providers" :meta "stdio"
                       :rows (map-indexed
                              (fn [k row] (assoc row :flashes [[(landed (out-at k)) true]]))
                              [{:label "Claude" :meta "Agent SDK"}
                               {:label "Codex · OpenCode"}
                               {:label "Ollama" :meta "local"}
                               {:label "Your own" :meta "any language"}])))
     (arch-list (assoc tools
                       :mark :terminal :title "Tools" :meta "via rules"
                       :rows (map-indexed
                              (fn [i row]
                                (assoc row :flashes (for [k (range 4) :when (= i (tool-of k))]
                                                      [(landed (tool-at k)) true])))
                              [{:label "clj REPL" :meta "Babashka"}
                               {:label "MCP servers"}
                               {:label "Files and shell" :meta "gated"}])))
     ;; ports on top of wire ends
     (for [i (range 4)] (arch-port 196 (client-cy i)))
     (arch-port (:x hub) rooms-y true)
     (arch-port hub-right queue-y true)
     (arch-port hub-right rules-y true)
     (for [i (range 4)] (arch-port (:x providers) (row-cy providers i)))
     (for [i (range 3)] (arch-port (:x tools) (row-cy tools i)))]))

;; --- Tour stage (a recorded session replayed in the real web client) ---

(defn- tour-src [tour] (str "/tour/?tape=" tour))

(defn- stage-window
  "A browser window around a tour iframe (`frame-attrs`). opts: :title (the
   bar's middle, default the client's URL), :replay? (a Replay button site.js
   shows once the tour is done), :overlay (over the viewport)."
  [frame-attrs & [{:keys [title replay? overlay]}]]
  [:div.stage-window
   [:div.stage-bar
    [:span.stage-dots [:i] [:i] [:i]]
    [:span.stage-url (or title "localhost:1414")]
    (when replay?
      (button/button {:variant :ghost :size :sm :icon-left :refresh :class "stage-replay"
                      :attrs {:type "button" :hidden true}}
                     "Replay"))]
   [:div.stage-viewport
    [:iframe.stage-frame (merge {:title "Xi's web client replaying a recorded session"}
                                frame-attrs)]
    overlay]])

(defn- tour-chapters
  "A tour's {\"chapter\": title, \"about\": line} steps as {:chapter :about}
   (site/tours/<tour>/steps.json)."
  [tour]
  (let [f (java.io.File. (str "tours/" tour "/steps.json"))]
    (when (.exists f)
      (->> (:steps (json/parse-string (slurp f) true))
           (filter :chapter)
           (mapv #(select-keys % [:chapter :about]))))))

(defn- stage-chapters
  "The tour's chapters as cards under its stage. site.js marks the one the
   tour is at, fills its progress line, and jumps to a chapter on click."
  [chapters]
  [:ol.stage-chapters {:aria-label "Chapters of the tour"}
   (map-indexed
    (fn [i {:keys [chapter about]}]
      [:li
       [:button.stage-chapter {:type "button" :data-chapter chapter}
        [:span.stage-chapter-n (format "%02d" (inc i))]
        [:span.stage-chapter-title chapter]
        (when about [:span.stage-chapter-about about])
        [:span.stage-chapter-progress]]])
    chapters)])

(defn- stage
  "The web client in a window frame, replaying site/public/tours/<tour>.json
   through /tour/. site.js loads the iframe (data-src) once the stage is in
   view and scales it from 1280×800 to the frame. With :phone-variant, small
   screens get that variant of the tape in a phone frame instead (390×844,
   looping). A tour with chapters gets them as cards under it, and its step
   in the window's title."
  [tour caption & [{:keys [phone-variant]}]]
  (let [chapters (tour-chapters tour)]
    [:section.stage
     [:figure.stage-figure
      (stage-window (cond-> {:data-src (tour-src tour)}
                      phone-variant (assoc :data-phone-src (str (tour-src tour) "&variant=" phone-variant)))
                    {:title   [:span.stage-title
                               [:i.stage-live]
                               [:strong "Xi tour"]
                               (when (seq chapters)
                                 [:span "· step " [:span.stage-step "1"] " of " (count chapters)])]
                     :overlay [:div.stage-seek {:aria-hidden "true"}]})
      (when (seq chapters)
        (stage-chapters chapters))
      (when caption [:figcaption caption])]]))

(defn- slideshow
  "A release's video: its slides' tours played one after another in one
   stage window, each opening on its title; the chips below jump to a slide
   (site.js)."
  [slides]
  [:figure.slideshow
   (stage-window {:data-src (tour-src (:tour (first slides)))}
                 {:replay? true
                  :overlay [:div.slideshow-title {:aria-hidden "true"}]})
   [:div.slideshow-chapters
    (map-indexed
     (fn [i {:keys [tour title]}]
       (chip/chip {:active (zero? i)
                   :attrs {:type "button" :data-slide-src (tour-src tour) :data-slide-title title}}
         (str (inc i) ". " title)))
     slides)]])


;; --- Theming section (themes the site and its previews, site/public/js/theme-demo.js) ---

(def ^:private theme-presets
  "[label hue] per preset chip; theme-demo.js builds the theme from the hue,
   nil is the default theme."
  [["Default" nil] ["Ember" 28] ["Ocean" 235] ["Forest" 150] ["Rose" 350] ["Violet" 290]])

(defn- theme-controls []
  [:div.theme-controls {:data-theme-demo ""}
   [:div.theme-presets
    (for [[label hue] theme-presets]
      (chip/chip {:active (nil? hue)
                  :dot-color (when hue (str "oklch(0.62 0.16 " (mod (+ hue 20) 360) ")"))
                  :attrs {:type "button" :data-hue (str hue)}}
        label))]
   [:label.theme-hue
    [:span "Hue"]
    (form/form-range {:min 0 :max 359 :step 1 :value 28
                      :attrs {:aria-label "Hue" :data-theme-hue ""}})]
   (button-group/button-group {:variant :boxed :class "theme-modes"}
     (button-group/button-group-item {:attrs {:type "button" :data-mode "dark"}} "Dark")
     (button-group/button-group-item {:attrs {:type "button" :data-mode "light"}} "Light"))])

(defn- hero
  "The home page's intro: a spotlight in the logo's colours turning behind
   the logo tile, headline and install bar (main.css, .hero-*). Copy
   [data-copy] buttons, and the terminal line [data-run] types after a
   copy, are wired up in site.js."
  []
  [:section.hero
   [:div.hero-bg {:aria-hidden "true"} [:div.hero-dome] [:div.hero-rays]]
   [:div.wrap
    [:div.hero-mark.hero-in
     [:svg {:viewBox "0 0 55 40" :width "40" :height "30" :aria-hidden "true"} ui/logo-glyph]]
    [:a.hero-pill.hero-in {:href "/changelog/"}
     [:b (str "v" core/version)] "Open source · MIT · ClojureScript on Bun"
     [:span.hero-pill-arrow {:aria-hidden "true"} "→"]]
    [:h1.hero-in "An AI harness " [:span.hero-gradient "you can shape."]]
    [:p.lede.hero-in
     "An AI coding harness for any LLM provider." [:br]
     "With advanced Clojure scripting, permission gating via rules and broad configurability via extensions and MCP support."]
    [:div.hero-install.hero-in
     [:code [:span {:aria-hidden "true"} "$ "] install-command]
     (button/button {:variant :primary :icon-left :copy :class "hero-copy"
                     :attrs {:type "button" :data-copy install-command
                             :data-run "xi start" :aria-label "Copy install command"
                             :title "Copy"}})]]])

(def ^:private tape-fetch-script
  "Starts loading the ?tape= while main.js downloads; xi.web.core/tour-init!
   takes the parsed tape from window.xiTape."
  "(function(){var t=new URLSearchParams(location.search).get('tape');if(t)window.xiTape=fetch(t.indexOf('/')<0?'/tours/'+t+'.json':t).then(function(r){return r.json()})})()")

(defn tour-page
  "/tour/: the web client alone, for the stage iframe. Ships the client's own
   default theme and runtime (xisite.build/tour-files), not the site's; only
   the stored light/dark choice is shared."
  []
  (ui/render-page
   [:html {:lang "en"}
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width,initial-scale=1"}]
     [:meta {:name "robots" :content "noindex"}]
     [:script (h/raw tape-fetch-script)]
     [:script (h/raw theme/head-script)]
     [:title "Xi tour"]
     [:link {:rel "stylesheet" :href "/tour/theme.css"}]
     [:link {:rel "stylesheet" :href "/tour/style.css"}]
     [:link {:rel "stylesheet" :href "/css/tour.css"}]]
    [:body
     [:div#app]
     [:script {:src "/tour/ui-runtime.js"}]
     [:script {:src "/tour/main.js"}]]]))

(defn home []
  (ui/render-page
   (ui/layout
    {:title "Xi — an AI harness you can shape"
     :description core/site-description
     :path "/"
     :body-class "home"}
    (hero)

    (stage "scene" nil {:phone-variant "phone"})

    [:section.feature {:id "clj"}
     [:div.wrap.split
      [:div.feature-text
       [:p.eyebrow "Scripting"]
       [:h2 "The agent scripts in Clojure, not shell pipelines."]
       [:p "Instead of a bash tool, Xi gives the agent a persistent Clojure REPL with file helpers. Scripts filter and aggregate in the runtime; only the result enters the conversation."]
       (feature-list
        [["Small context." "Load a large file once, query it across calls. The file never enters the conversation."]
         ["Reviewable." "Every real command runs argv-style through one call you can read. No quoting tricks, no hidden pipes."]
         ["Gated." "Read-only tools run freely. Anything else asks once, or is allowed by a rule."]])
       [:p [:a {:href "/docs/clj-tool/"} "The clj tool →"]]]
      [:div.feature-code (code "clojure" clj-sample)]]]

    [:section.feature.alt {:id "rules"}
     [:div.wrap.split.reverse
      [:div.feature-text
       [:p.eyebrow "Permissions"]
       [:h2 "Permission rules."]
       [:p "What the agent may do on its own is a list of rules in a file, evaluated top to bottom. Allow, deny, ask, or nudge it in another direction."]
       (feature-list
        [["Scoped." "Match on the tool, the path, the command, the git repo, or the extension making the call."]
         ["Layered." "Repo rules beat global rules; both beat anything granted in the moment. A hardened tier stays out of reach."]
         ["Agents can't touch them." "The rules files are off-limits to every tool. Only you edit them."]])
       [:p [:a {:href "/docs/rules/"} "Permissions and rules →"]]]
      [:div.feature-code (code "clojure" rules-sample)]]]

    [:section.feature {:id "extensions"}
     [:div.wrap.split
      [:div.feature-text
       [:p.eyebrow "Extensions"]
       [:h2 "Extension."]
       [:p "Drop a ClojureScript file into " [:code "~/.config/xi/extensions/"]
        ", list it in your config, and it loads. No build step. "
        [:code "/ext reload"] " swaps a changed file in while the server runs."]
       (feature-list
        [["Tools, commands, keys, pages." "An extension is a plain map. Give the agent a tool, add a slash command, bind a key, or ship a page for the web client."]
         ["Sandboxed." "Extensions have no host access of their own. Files, shell commands and network requests go through the same rules as the agent's tool calls."]
         ["Your data stays yours." "Each extension gets a data directory it may use freely. Everything else asks, or is pre-allowed by a rule you wrote."]])
       [:p [:a {:href "/docs/extensions/"} "Writing extensions →"] " · " [:a {:href "/extensions/"} "Browse extensions →"]]]
      [:div.feature-code (code "clojure" extension-sample)]]]

    [:section.feature.alt {:id "mcp"}
     [:div.wrap.split.reverse
      [:div.feature-text
       [:p.eyebrow "MCP"]
       [:h2 "Plug in any MCP server."]
       [:p "Xi speaks the Model Context Protocol on both sides: it offers its own tools to the model, and it pulls in tools from servers you add. One command registers a server; its tools show up on the next turn."]
       (feature-list
        [["Trust once." "A server runs nothing until you approve it. Trust it and its calls stop asking, until its code changes."]
         ["Stdio or HTTP." "Spawn a local process, or point at a hosted server with an API key kept outside your config."]
         ["Private servers for extensions." "An extension can bring its own server, like a headless browser, that only it can call."]])
       [:p [:a {:href "/docs/mcp-servers/"} "Adding MCP servers →"]]]
      [:div.feature-code (code "sh" mcp-sample)]]]

    [:section.feature {:id "web"}
     [:div.wrap.split
      [:div.feature-text
       [:p.eyebrow "Web client"]
       [:h2 "Start at your desk. Continue on your phone."]
       [:p "The web client joins the same session as your terminal. Begin a refactor in the TUI, stand up, and the conversation is already on your phone, still streaming. Reply from there and the terminal follows."]
       (feature-list
        [["Pair once." "A new device shows a four-digit code; approve it from the terminal or the browser."]
         ["Installable." "Add it to your home screen. Recent chats are cached, so the list opens before the connection does."]
         ["Review in place." "Diffs, tool calls and permission requests render where they happen. Allow or deny with one tap."]])
       [:p [:a {:href "/docs/web-client/"} "The web client →"]]]
      [:div.phones
       (phone "desk" "list" "The web client's session list on a phone, following a running session")
       (phone "desk" "phone" "A chat in the web client on a phone, replaying a recorded session")]]]

    [:section.feature.alt {:id "durable"}
     [:div.wrap.split.reverse
      [:div.feature-text
       [:p.eyebrow "Durability"]
       [:h2 "Durable by default."]
       [:p "Chats live on the server, not in a tab or a terminal. A crash, a deploy or a reboot does not end a turn: when the server is back, every chat it cut off continues where it stopped. Nothing to turn on."]
       (feature-list
        [["Runs unattended." "Close the laptop and the task finishes. The result waits in the chat list."]
         ["Survives restarts." "A chat cut off mid-turn continues on its own, whether or not anyone has it open."]
         ["Queued prompts follow." "Messages queued behind the interrupted turn are sent after it, in order."]])
       [:p [:a {:href "/docs/server/#chats-keep-running"} "Chats keep running →"]]]
      [:div.feature-code (code "sh" durable-sample)]]]

    [:section.feature.theming {:id "theming"}
     [:div.wrap.split
      [:div.feature-text
       [:p.eyebrow "Themes"]
       [:h2 "Make it yours."]
       [:p "Pick a background and an accent in the Appearance dialog. Xi derives the rest in OKLCH: surfaces, borders, text, status colors, diff bands and code all follow your color. Try a few: this page and every live session on it follow."]
       [:p [:a {:href "/docs/web-client/#color-themes"} "Color themes →"]]]
      (theme-controls)]]
    (stage "desk" "The same session in your colors, live." {:phone-variant "phone"})
    [:script {:src "/js/theme-demo.js" :defer true}]

    [:section.feature.alt {:id "users"}
     [:div.wrap.split.reverse
      [:div.feature-text
       [:p.eyebrow "Multi-user " (badge/badge {:variant :secondary :size :sm} "Beta")]
       [:h2 "First-class multi-user."]
       [:p "Use Xi with your team. Share one server and every connection acts as a user, so each person keeps their own identity and settings across terminal, browser and phone."]
       (feature-list
        [["User settings." "Theme, collapsed groups, preferred model, and which chats are read or hidden are kept per user on the server, so they follow each person from device to device."]
         ["Profiles are optional." "Declare a name and avatar in the config, or don't: an undeclared user is a circle with initials. Extensions can read profiles and keep data per user."]])
       [:p [:a {:href "/docs/server/#users"} "Users and servers →"]]]
      [:div.feature-code
       (code "clojure" users-sample)
       (code "sh" users-cli-sample)]]]

    [:section.feature {:id "providers"}
     [:div.wrap.split
      [:div.feature-text
       [:p.eyebrow "Providers"]
       [:h2 "Providers"]
       [:p "Every model provider is a program Xi starts for each message: Claude through the Agent SDK, GPT on a ChatGPT plan, OpenCode, Ollama, or one you write. Xi keeps the tools, the rules and the chat; a provider only turns a prompt into text and tool calls."]
       (feature-list
        [["Pick in your config." "List the providers you want, in the order the model picker shows them, with options for each. Only those load."]
         ["Write your own." "A manifest and a program that speaks JSON lines over stdio. With the TypeScript kit it is a dozen lines; babashka or Go work just as well."]
         ["Same rules for every model." "A provider asks Xi to run each tool, so your rules and approvals apply whichever model is talking."]])
       [:p [:a {:href "/docs/providers/"} "Providers →"]]]
      [:div.feature-code
       (code "clojure" providers-sample)
       (code "ts" provider-sample)]]]

    [:section.feature.architecture {:id "architecture"}
     [:div.wrap
      [:div.arch-intro
       [:p.eyebrow "Architecture"]
       [:h2 "One server, every surface."]
       [:p "A server holds each session as one state and runs every action through a single event queue. Terminal, browser and scripts are clients of the same session. Model providers are programs it starts per message; their tool calls, the clj tool and MCP servers sit behind rules you wrote."]]
      [:div.arch (architecture-diagram)]]]

    [:section.under-the-hood
     [:div.wrap
      [:p.eyebrow "Under the hood"]
      [:div.trio
       [:div [:h3 "One state, every surface."]
        [:p "Terminal, server and browser run the same pure handlers over one state interface."]]
       [:div [:h3 "Everything is an event."]
        [:p "Prompts, output, commands, dialogs and renders all pass through one queue. The wire protocol is the event maps themselves."]]
       [:div [:h3 "ClojureScript on Bun."]
        [:p "A light runtime with no dependencies that starts fast. Server, terminal and browser share the same code, and you can change it from a REPL while it runs."]]]]]

    [:section.band
     [:div.wrap
      [:div
       [:h2 "Install Xi"]
       [:p "Bring a Claude login, a ChatGPT plan, an OpenCode key or a local Ollama."]]
      (button/button {:variant :primary :size :lg :href "/docs/getting-started/"
                      :icon-right :arrow-right}
                     "Getting started")]])))

;; --- Docs ---

(defn- sidebar-subsections
  "The current page's headings, nested under its sidebar link."
  [headings]
  (when (seq headings)
    [:div.docs-subsections
     (for [{:keys [id text]} headings]
       [:a {:href (str "#" id)} text])]))

(defn- sidebar [sections current-path headings]
  [:nav.docs-sidebar {:aria-label "Documentation"}
   [:a.docs-sidebar-home {:href "/docs/" :class (when (= current-path "/docs/") "active")} "Overview"]
   (for [{:keys [title pages]} sections]
     [:div.docs-section
      [:div.docs-section-title title]
      (for [{:keys [path nav-title]} pages
            :let [active? (= path current-path)]]
        (list
         [:a {:href path :class (when active? "active")} nav-title]
         (when active? (sidebar-subsections headings))))])])

(defn- docs-layout [{:keys [title description path sections headings]} & body]
  (ui/layout
   {:title (str title " · Xi docs")
    :description description
    :path path
    :body-class "docs"}
   [:div.docs-shell
    (button/button {:variant :secondary :size :sm :icon-left :menu :class "docs-nav-toggle"
                    :attrs {:type "button" :aria-expanded "false" :aria-controls "docs-sidebar"}}
                   "Menu")
    [:div#docs-sidebar.docs-sidebar-wrap (sidebar sections path headings)]
    (into [:article.docs-content] body)]))

(defn docs-index [sections]
  (ui/render-page
   (docs-layout
    {:title "Documentation"
     :description "How to install Xi, use it every day, and make it yours."
     :path "/docs/"
     :sections sections}
    [:h1 "Documentation"]
    [:p.lede "How to install Xi, use it every day, and make it yours. Start at the top; the pages build on each other."]
    (for [{:keys [title pages]} sections]
      [:section.docs-index-section
       [:h2 title]
       [:div.docs-cards
        (for [{:keys [path nav-title summary]} pages]
          [:a.docs-card-link {:href path}
           (card/card {:class "docs-card"}
                      [:strong nav-title]
                      (when (seq summary) [:span summary]))])]]))))

(defn- with-release-videos
  "Each release's slideshow (core/releases-file) right under its `## X.Y.Z - date` heading."
  [html releases]
  (str/replace html #"(<h2 id=\"[^\"]*\">(\d+\.\d+\.\d+)\b.*?</h2>)"
               (fn [[_ heading version]]
                 (if-let [slides (seq (get releases version))]
                   (str heading (h/html (slideshow slides)))
                   heading))))

(defn changelog
  "The release notes: CHANGELOG.md at the repo root, set like a docs page without the sidebar."
  []
  (let [{:keys [title summary body]} (md/split-title (slurp core/changelog-file))
        releases (edn/read-string (slurp core/releases-file))]
    (ui/render-page
     (ui/layout
      {:title (str title " · Xi")
       :description summary
       :path "/changelog/"
       :body-class "changelog"}
      [:div.changelog-shell
       [:article.docs-content
        [:h1 title]
        (h/raw (with-release-videos (md/render-body body) releases))]]))))

(defn docs-page [sections {:keys [title summary html headings path source-path prev next]}]
  (ui/render-page
   (docs-layout
    {:title title
     :description (if (seq summary) summary core/site-description)
     :path path
     :sections sections
     :headings (filter #(= 2 (:level %)) headings)}
    [:h1 title]
    (h/raw html)
    [:div.docs-footer
     [:div.docs-pager
      (when prev [:a.prev {:href (:path prev)} [:small "Previous"] (:nav-title prev)])
      (when next [:a.next {:href (:path next)} [:small "Next"] (:nav-title next)])]])))
