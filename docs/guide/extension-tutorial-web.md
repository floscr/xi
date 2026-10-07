# Tutorial: a page in the browser

Give the notes extension from the [first tutorial](extension-tutorial-tool.md)
a page in the web client: a list of the notes, a Refresh button, and a field
to add one. This covers the browser half of an extension, routes, menu
entries, and how the two halves talk.

## How the halves fit together

An extension's logic stays on the server. The browser gets a second file,
`notes/web.cljs`, that only **renders** and **dispatches**: a page is a
function of the state that returns hiccup, and a click sends an event to the
server half, which does the work and puts the result into the extension's
per-chat state. That state is mirrored to every client in the chat, so the
page re-renders with it.

```text
browser                                 server
notes/web.cljs                          notes.cljs
  page reads [:ext :notes :text]  <──   handler stores it there
  button dispatches :ext.notes/refresh ──>  handler → effect reads the file
```

## 1. The server half

Extend `~/.config/xi/extensions/notes.cljs` with a state slice, a handler the
page can call, and one that stores the file's text:

```clojure
(ns notes
  (:require [xi.api.fs :as fs]
            [xi.api.promise :as p]))

(defn- notes-file [ctx]
  (str (fs/data-dir ctx) "/notes.md"))

(defn- append [ctx text]
  (-> (fs/read ctx (notes-file ctx))
      (p/catch (fn [_] ""))
      (p/then (fn [old] (fs/write ctx (notes-file ctx) (str old text "\n"))))))

(def extension
  {:id :notes
   :init {:room {:text nil}}

   :tool-definitions
   [{:name "notes_add"
     :description "Append one line to the user's notes file."
     :input_schema {:type "object"
                    :properties {:text {:type "string"}}
                    :required ["text"]}}]
   :tool-registry
   {"notes_add" (fn [{:keys [text]} ctx]
                  (-> (append ctx text)
                      (p/then (fn [_] {:content [{:type "text" :text "Noted."}]}))))}

   :handlers
   {;; the page asks for the file
    :ext.notes/refresh (fn [_st {:keys [room-id]}]
                         {:effects [[:ext.notes/read {:room-id room-id}]]})
    ;; the page adds a line, then asks again
    :ext.notes/add     (fn [_st {:keys [room-id text]}]
                         {:effects [[:ext.notes/append {:room-id room-id :text text}]]})
    ;; the effect reports back; this is what the page renders
    :ext.notes/loaded  (fn [st {:keys [room-id text]}]
                         {:state (assoc-in st [:rooms room-id :ext :notes :text] text)})}

   :fx
   {:ext.notes/read
    (fn [{:keys [dispatch!] :as ctx} {:keys [room-id]}]
      (-> (fs/read ctx (notes-file ctx))
          (p/catch (fn [_] ""))
          (p/then (fn [text]
                    (dispatch! {:type :ext.notes/loaded :room-id room-id :text text})))))
    :ext.notes/append
    (fn [{:keys [dispatch!] :as ctx} {:keys [room-id text]}]
      (-> (append ctx text)
          (p/then (fn [_]
                    (dispatch! {:type :ext.notes/refresh :room-id room-id})))))}})
```

## 2. The browser half

Create the directory `~/.config/xi/extensions/notes/` and in it `web.cljs`:

```clojure
(ns notes.web
  (:require [clojure.string :as str]
            [ui.button :as button]
            [ui.form :as form]
            [xi.core.state :as state]))

(defn- notes-page [st dispatch!]
  (let [room  (state/active-room st)
        text  (get-in room [:ext :notes :text])
        draft (get-in st [:user-ext/ui :notes :draft])]
    [:div {:style {:padding "1.5rem" :max-width "40rem"}}
     [:h2 "Notes"]
     (if-not room
       [:p "Open a chat first; notes are read through it."]
       [:div
        [:div {:style {:display "flex" :gap "0.5rem" :margin "1rem 0"}}
         (form/form-input {:type :text
                           :placeholder "A new note"
                           :attrs {:bind [:draft]}})
         (button/button {:variant :primary :size :sm
                         :disabled (str/blank? draft)
                         :on-click (fn [_]
                                     (dispatch! {:type :ext.notes/add :text draft})
                                     (dispatch! {:type :ext-ui/set :path [:draft] :value nil}))}
                        "Add")
         (button/button {:variant :secondary :size :sm
                         :on-click (fn [_] (dispatch! {:type :ext.notes/refresh}))}
                        "Refresh")]
        [:pre {:style {:white-space "pre-wrap"}}
         (if (seq text) text "(no notes yet)")]])]))

(def web-extension
  {:id :notes
   :routes {"notes" {:parse (fn [_segments] {:page :notes/list})
                     :path  {:notes/list (fn [_route] "/notes")}}}
   :pages {:notes/list notes-page}
   :nav-items [{:menu :sidebar :label "Notes" :icon :file-text
                :event {:type :route/navigate :page :notes/list}}]})
```

The file defines `web-extension` with the same `:id` as the server half.

- **`:routes`** maps the first URL segment to a page. `:parse` turns the
  remaining segments into a route map, `:path` turns a route back into a
  URL. Here `/notes` is the only page.
- **`:pages`** maps a page keyword (namespaced with the extension id) to a
  function of the state and `dispatch!`.
- **`:nav-items`** put the page in a menu: `:sidebar`, the command palette
  (`:palette`), the home top bar, or the chat's overflow menu.

The page reads the notes from the chat's state, where the server half put
them. Clicking **Refresh** dispatches `:ext.notes/refresh`; the browser
forwards it to the server, tagged with the chat, and the server half handles
it.

The text field is the one piece of state that stays in the browser. The
sandbox cannot read DOM events, so `:bind [:draft]` tells the host to keep
the field's value at `[:user-ext/ui :notes :draft]` of the state the page is
rendered with. The Add button reads it from there, sends it, and clears it
with `:ext-ui/set`.

## 3. Load it

Reload with `/ext reload`, then reload the browser page. Web halves are
loaded once per page load. **Notes** appears in the sidebar.

Open a chat, go to Notes, add a line, ask the agent to add one too, press
Refresh. Both are there, on every device in the chat.

## What a page may do

- Use the `ui.*` components (`ui.button`, `ui.form`, `ui.badge`, `ui.icon`,
  …), plain hiccup, `xi.core.state` helpers, a few view helpers from
  `xi.web.views` (`spinner`, `shorten-path`, `diff-rows-view`, `user-avatar`,
  `avatar-stack`), `xi.markdown.hiccup/render` for markdown and
  `xi.api.time/now` for the clock.
- Show data that is not tied to a chat: the server half dispatches one of its
  events with `:to-users #{…}` or `:to-client <id>`, and the web half's
  `:handlers` fold it into the extension's own slice at
  `[:user-ext/state <id>]`. See the [reference](extensions-reference.md#browser-halves).
- Dispatch its own `:ext.<id>/*` events, `:route/navigate` and `:nav/back`.
  Anything else is dropped and logged in the browser console.
- Nothing dangerous: script tags, inline handlers and `javascript:` links are
  removed before rendering, and a page that throws shows an error box instead
  of taking the client down.

## Loading on entering a page

There is no "on enter" hook. To fetch when the page opens, add a **tap** that
watches navigation:

```clojure
(defn- load-on-navigate [dispatch!]
  (fn [event _state]
    (when (and (= :route/navigate (:type event))
               (= :notes/list (:page event)))
      (dispatch! {:type :ext.notes/refresh}))))

;; in web-extension:
   :taps [load-on-navigate]
```

## Starting a chat from a page

A page can hand something to the agent. The server half dispatches
`:chat/start` with the text and the clicking client's id, and that browser
lands in the new chat:

```clojure
;; browser half
(button/button {:on-click (fn [_] (dispatch! {:type :ext.notes/discuss}))} "Discuss")

;; server half, in :handlers
:ext.notes/discuss
(fn [st {:keys [room-id client-id]}]
  {:effects [[:app/dispatch {:type :chat/start
                             :client-id client-id
                             :text (str "Let's go through my notes:\n"
                                        (get-in st [:rooms room-id :ext :notes :text]))}]]})
```

This is allowed from events a client sent, from commands and from keys, but
never from a tool: the agent cannot start chats through your extension.

## When something is off

**The menu entry is missing.** The browser loads web halves once; reload the
page. The file must be `notes/web.cljs` next to `notes.cljs`, and define
`web-extension` with the matching `:id`.

**The page shows an error box.** The console has the exception. A common one
is a `:require` the browser sandbox does not have; it has `clojure.*`,
`xi.core.state`, the `ui.*` components and the helpers listed above.

**Clicking does nothing.** The event's namespace must be `:ext.notes/…`.
Anything else is blocked, and the console says so.

**The field does not keep what I type.** `:bind` goes in `:attrs` for a
`ui.form` input, and the path is a vector of keywords.

## Next

[Tutorial: your own MCP server](extension-tutorial-mcp.md).
