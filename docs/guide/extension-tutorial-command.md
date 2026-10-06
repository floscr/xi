# Tutorial: a command and a key

Show a desktop notification when the agent finishes a turn, toggled with a
key and visible as a badge next to the prompt. This covers reacting to events,
per-chat state, keybindings, badges, and running a program from an extension.

## What you build

Press `Ctrl+Shift+N` in the terminal client and a bell appears next to the
prompt. From then on, every finished turn in that chat raises a notification
with `notify-send` (Linux; use `osascript` on macOS). Press the key again to
turn it off.

## 1. State and a toggle

Create `~/.config/xi/extensions/ping.cljs`:

```clojure
(ns ping
  (:require [xi.core.state :as state]))

(defn- enabled? [st room-id]
  (boolean (:enabled? (state/room-ext st room-id :ping))))

(defn- toggle [st {:keys [room-id]}]
  (let [on? (not (enabled? st room-id))]
    {:state   (assoc-in st [:rooms room-id :ext :ping :enabled?] on?)
     :effects [[:app/dispatch {:type :ui/status :room-id room-id
                               :text (str "Notifications " (if on? "on" "off"))}]]}))

(def extension
  {:id :ping
   :init {:room {:enabled? false}}
   :handlers {:ext.ping/toggle toggle}
   :keybindings [{:key "ctrl+shift+n" :event {:type :ext.ping/toggle}}]})
```

Three new things:

- **`:init`** gives the extension its own state. `{:room {…}}` is per chat,
  lives at `[:rooms <id> :ext :ping]` and is shared with every client in the
  chat. `{:process {…}}` would be one value for the whole server.
  `state/room-ext` reads the per-chat slice.
- **`:handlers`** map an event to a function of the state and the event. It
  returns the new state, effects, or both. Here the event is the extension's
  own `:ext.ping/toggle`.
- **`:keybindings`** dispatch an event when a key is pressed in the terminal.
  Xi adds the current `:room-id`.

The handler may only change the extension's own slice. A change anywhere else
in the state is dropped, so a bug cannot corrupt a chat.

Enable the file in `config.edn`, start Xi (keybindings are read at startup),
press `Ctrl+Shift+N`, and the status line answers.

## 2. A badge

```clojure
(defn- badge [st]
  (when-let [room (state/active-room st)]
    (when (enabled? st (:id room)) " 🔔")))

;; in the map:
   :prompt-badge badge
```

The badge function gets the state and returns a string, or nil for nothing.
It is drawn after the prompt on every repaint.

## 3. React to a turn ending

Xi's own events are open to handlers too. `:agent/turn-end` fires when a
turn finishes; its event carries `:room-id` and `:aborted?`.

```clojure
(defn- on-turn-end [st {:keys [room-id aborted?]}]
  (when (and (enabled? st room-id) (not aborted?))
    (let [title (or (get-in st [:rooms room-id :session :name]) "Xi")]
      {:effects [[:ext.ping/notify {:title title}]]})))

;; in the map:
   :handlers {:ext.ping/toggle toggle
              :agent/turn-end on-turn-end}
```

Your handler runs after Xi's own handler for the event, so the state it sees
is already updated. Returning nil means "nothing to do".

## 4. Run a program

The notification is a side effect, so it is an **effect** that shells out:

```clojure
(ns ping
  (:require [xi.api.promise :as p]
            [xi.api.sh :as sh]
            [xi.core.state :as state]))

(defn- notify [ctx {:keys [title]}]
  (-> (sh/sh ctx "notify-send" "-a" "Xi" title "Turn finished")
      (p/catch (fn [_] nil))))

;; in the map:
   :fx {:ext.ping/notify notify}
```

`sh/sh` takes `ctx`, the program, and its arguments, one each. It resolves to
the program's output, or rejects on a non-zero exit.

## 5. Allow the program

Run a turn with the bell on, and nothing happens. A program run by an
extension **asks** by default, and an effect has no dialog to ask in, so the
call is refused. The rule that allows it goes in `~/.config/xi/rules.edn`,
pinned to this extension and this program:

```clojure
{:type    :xi/rules
 :version 1
 :rules
 [{:match  {:tool :sh :extension "ping" :cli "notify-send"}
   :action {:type :allow}}]}
```

`/rules reload` (or a restart), and the next finished turn notifies. `/rules`
lists the rule, so you always know what your extensions may run.

The same applies to reading files outside the data directory and to network
requests: pre-allow them with a rule, pinned to the extension.

## The whole file

```clojure
(ns ping
  (:require [xi.api.promise :as p]
            [xi.api.sh :as sh]
            [xi.core.state :as state]))

(defn- enabled? [st room-id]
  (boolean (:enabled? (state/room-ext st room-id :ping))))

(defn- toggle [st {:keys [room-id]}]
  (let [on? (not (enabled? st room-id))]
    {:state   (assoc-in st [:rooms room-id :ext :ping :enabled?] on?)
     :effects [[:app/dispatch {:type :ui/status :room-id room-id
                               :text (str "Notifications " (if on? "on" "off"))}]]}))

(defn- on-turn-end [st {:keys [room-id aborted?]}]
  (when (and (enabled? st room-id) (not aborted?))
    (let [title (or (get-in st [:rooms room-id :session :name]) "Xi")]
      {:effects [[:ext.ping/notify {:title title}]]})))

(defn- notify [ctx {:keys [title]}]
  (-> (sh/sh ctx "notify-send" "-a" "Xi" title "Turn finished")
      (p/catch (fn [_] nil))))

(defn- badge [st]
  (when-let [room (state/active-room st)]
    (when (enabled? st (:id room)) " 🔔")))

(def extension
  {:id           :ping
   :init         {:room {:enabled? false}}
   :handlers     {:ext.ping/toggle toggle
                  :agent/turn-end  on-turn-end}
   :fx           {:ext.ping/notify notify}
   :keybindings  [{:key "ctrl+shift+n" :event {:type :ext.ping/toggle}}]
   :prompt-badge badge})
```

## Also useful

- `:ui/dialog-open` fires when a dialog opens; `(= :confirm (:type dialog))`
  is a permission request waiting for you. Notify on that too, and you never
  miss a question while away from the screen.
- `:on-mount` and `:on-unmount` run when the extension is loaded and
  unloaded. Use them for a polling loop; see the reference.
- A command can do what the key does: add
  `{:name "ping" :handler (fn [_ {:keys [room-id]}] {:effects [[:app/dispatch {:type :ext.ping/toggle :room-id room-id}]]})}`
  to `:commands`.

## When something is off

**The key does nothing.** Keybindings are read when the terminal client
starts; a reload is not enough. Restart Xi. In the web client, keys from
extensions are not available; use a command.

**The badge shows but no notification comes.** Check the rule: the
`:extension` value is the id as a string, and `:cli` is the program's name
without a path. `/rules` shows whether it loaded.

**The notification fires twice.** A handler for `:agent/turn-end` runs for
every chat on a server, so make sure it checks its own `room-id`, as the one
above does.

## Next

[Tutorial: a web tool](extension-tutorial-http.md) makes HTTP requests.
