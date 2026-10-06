(ns xisite.pages
  "Page renderers: home, docs index, docs page. Each returns a full html string."
  (:require [hiccup2.core :as h]
            [ui.button :as button]
            [ui.card :as card]
            [xisite.core :as core]
            [xisite.markdown :as md]
            [xisite.ui :as ui]))

;; --- Home ---

(def ^:private install-sample
  "bun install -g xi-agent
xi server                  # start the server, web client on localhost:7474
xi                         # connect a client")

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

(defn- code [lang source]
  (md/code-block-hiccup lang source))

(defn- feature-list [items]
  (into [:ul.feature-list]
        (for [[title text] items]
          [:li [:strong title] " " text])))

(defn- phone [src alt]
  [:figure.phone
   [:img {:src src :alt alt :width "585" :height "1266"}]])

;; --- Architecture diagram (inline SVG; colours come from .arch-* in main.css) ---

(defn- arch-group [x y w h label]
  [:g
   [:rect.arch-group {:x x :y y :width w :height h :rx 14}]
   [:text.arch-label {:x (+ x 16) :y (+ y 24)} label]])

(defn- arch-box
  ([x y w h title] (arch-box x y w h title nil))
  ([x y w h title sub]
   [:g
    [:rect.arch-box {:x x :y y :width w :height h :rx 8}]
    (if sub
      [:g
       [:text.arch-title {:x (+ x 14) :y (+ y 22)} title]
       [:text.arch-sub {:x (+ x 14) :y (+ y 40)} sub]]
      [:text.arch-title {:x (+ x 14) :y (+ y (/ h 2) 5)} title])]))

(defn- arch-arrow [x1 y1 x2 y2 & {:keys [both?]}]
  [:line.arch-line.arch-arrow
   (cond-> {:x1 x1 :y1 y1 :x2 x2 :y2 y2 :marker-end "url(#arch-head)"}
     both? (assoc :marker-start "url(#arch-head)"))])

(defn- architecture-diagram []
  [:svg.arch-svg {:viewBox "0 0 960 364" :role "img"
                  :aria-label "Xi architecture: terminal, web, one-shot and Babashka clients connect over WebSocket to the Xi server, which holds rooms, the event queue, rules and extensions, and talks to model providers, the clj tool and MCP servers."}
   [:defs
    [:marker {:id "arch-head" :viewBox "0 0 10 10" :refX 8 :refY 5 :markerWidth 7 :markerHeight 7
              :orient "auto-start-reverse"}
     [:path.arch-head {:d "M 0 0 L 10 5 L 0 10 z"}]]]
   ;; clients
   (arch-group 10 20 230 334 "CLIENTS")
   (arch-box 26 54 198 56 "Terminal" "TUI, standalone or connected")
   (arch-box 26 122 198 56 "Web client" "browser, phone, installable")
   (arch-box 26 190 198 56 "xi prompt" "one-shot, no TUI")
   (arch-box 26 258 198 56 "Babashka client" "drive a session from a script")
   ;; server
   (arch-group 330 20 300 334 "XI SERVER")
   (arch-box 346 54 268 56 "Rooms" "one state per session")
   (arch-box 346 122 268 56 "Event queue" "every action is an event, handled purely")
   (arch-box 346 190 268 56 "Rules" "allow, ask, deny or nudge each tool call")
   (arch-box 346 258 268 56 "Extensions" "tools, commands, keys, pages")
   ;; models and tools
   (arch-group 720 20 230 170 "MODELS")
   (arch-box 736 50 198 28 "Claude · Agent SDK")
   (arch-box 736 84 198 28 "OpenAI · Codex")
   (arch-box 736 118 198 28 "Ollama")
   (arch-box 736 152 198 28 "Zen")
   (arch-group 720 204 230 150 "TOOLS")
   (arch-box 736 234 198 28 "clj REPL")
   (arch-box 736 268 198 28 "MCP servers")
   (arch-box 736 302 198 28 "Files and shell, gated")
   ;; connections
   (arch-arrow 240 187 330 187 :both? true)
   [:text.arch-sub.arch-edge {:x 285 :y 172 :text-anchor "middle"} "WebSocket"]
   [:text.arch-sub.arch-edge {:x 285 :y 207 :text-anchor "middle"} "events"]
   (arch-arrow 630 105 720 105)
   [:text.arch-sub.arch-edge {:x 675 :y 92 :text-anchor "middle"} "providers"]
   (arch-arrow 630 279 720 279 :both? true)
   [:text.arch-sub.arch-edge {:x 675 :y 266 :text-anchor "middle"} "tool calls"]])

(defn home []
  (ui/render-page
   (ui/layout
    {:title "Xi — an AI harness you can shape"
     :description core/site-description
     :path "/"
     :body-class "home"}
    [:section.hero
     [:div.wrap
      [:p.eyebrow "AI harness · terminal and browser"]
      [:h1 "An AI harness you can shape."]
      [:p.lede
       "An AI harness with Clojure scripting access and permission gating via rules. Configurable extensions and MCP support."]
      [:div.hero-actions
       (button/button {:variant :primary :size :lg :href "/docs/getting-started/"} "Get started")
       (button/button {:variant :secondary :size :lg :href "/docs/"} "Read the docs")]
      (code "sh" install-sample)]]

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
       [:h2 "Extend it with a file."]
       [:p "Drop a ClojureScript file into " [:code "~/.config/xi/extensions/"]
        ", list it in your config, and it loads. No build step. "
        [:code "/ext reload"] " swaps a changed file in while the server runs."]
       (feature-list
        [["Tools, commands, keys, pages." "An extension is a plain map. Give the agent a tool, add a slash command, bind a key, or ship a page for the web client."]
         ["Sandboxed." "Extensions have no host access of their own. Files, shell commands and network requests go through the same rules as the agent's tool calls."]
         ["Your data stays yours." "Each extension gets a data directory it may use freely. Everything else asks, or is pre-allowed by a rule you wrote."]])
       [:p [:a {:href "/docs/extensions/"} "Writing extensions →"]]]
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
       (phone "/img/web-sessions.png" "The web client's session list on a phone")
       (phone "/img/web-chat.png" "A chat in the web client, with a diff from an edit")]]]

    [:section.feature.architecture {:id "architecture"}
     [:div.wrap
      [:div.arch-intro
       [:p.eyebrow "Architecture"]
       [:h2 "One server, every surface."]
       [:p "A server holds each session as one state and runs every action through a single event queue. Terminal, browser and scripts are clients of the same session. Models, the clj tool and MCP servers sit behind rules you wrote."]]
      [:div.arch (architecture-diagram)]]]

    [:section.under-the-hood
     [:div.wrap
      [:p.eyebrow "Under the hood"]
      [:div.trio
       [:div [:h3 "One state, every surface."]
        [:p "Terminal, server and browser run the same pure handlers over one state map. Standalone just means not connected."]]
       [:div [:h3 "Everything is an event."]
        [:p "Prompts, output, commands, dialogs and renders all pass through one queue. The wire protocol is the event maps themselves."]]
       [:div [:h3 "ClojureScript on Bun."]
        [:p "One runtime dependency. Model access through the Claude Agent SDK, Ollama, or an OpenAI-compatible endpoint."]]]]]

    [:section.band
     [:div.wrap
      [:div
       [:h2 "Install Xi"]
       [:p "One package, one command. Sign in with your Claude account or an API key."]]
      (button/button {:variant :primary :size :lg :href "/docs/getting-started/"
                      :icon-right :arrow-right}
                     "Getting started")]])))

;; --- Docs ---

(defn- sidebar [sections current-path]
  [:nav.docs-sidebar {:aria-label "Documentation"}
   [:a.docs-sidebar-home {:href "/docs/" :class (when (= current-path "/docs/") "active")} "Overview"]
   (for [{:keys [title pages]} sections]
     [:div.docs-section
      [:div.docs-section-title title]
      (for [{:keys [path nav-title]} pages]
        [:a {:href path :class (when (= path current-path) "active")} nav-title])])])

(defn- toc [headings]
  (when (seq headings)
    [:nav.docs-toc {:aria-label "On this page"}
     [:div.docs-toc-title "On this page"]
     (for [{:keys [level id text]} headings]
       [:a {:href (str "#" id) :class (str "level-" level)} text])]))

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
    [:div#docs-sidebar.docs-sidebar-wrap (sidebar sections path)]
    (into [:article.docs-content] body)
    (toc headings)]))

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
                      [:span summary])])]]))))

(defn docs-page [sections {:keys [title summary html headings path source-path prev next]}]
  (ui/render-page
   (docs-layout
    {:title title
     :description summary
     :path path
     :sections sections
     :headings (filter #(= 2 (:level %)) headings)}
    [:h1 title]
    (h/raw html)
    [:div.docs-footer
     [:div.docs-pager
      (when prev [:a.prev {:href (:path prev)} [:small "Previous"] (:nav-title prev)])
      (when next [:a.next {:href (:path next)} [:small "Next"] (:nav-title next)])]
     (button/button {:variant :ghost :size :sm :icon-left :edit
                     :href (str core/repo-url "/edit/master/" source-path)
                     :attrs {:rel "noopener"}}
                    "Edit this page")])))
