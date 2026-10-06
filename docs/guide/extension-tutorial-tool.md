# Tutorial: a tool

Give the agent a `notes_add` tool that appends a line to a notes file, and a
`/notes` command that shows the file. Fifteen minutes, one file.

## What you build

When you say "note that the API returns dates as strings", the agent calls
`notes_add` and the line lands in `~/.local/share/xi/extensions/notes/notes.md`.
`/notes` prints the file in the status line.

## 1. The file

Create `~/.config/xi/extensions/notes.cljs`:

```clojure
(ns notes
  (:require [xi.api.fs :as fs]
            [xi.api.promise :as p]))

(defn- notes-file [ctx]
  (str (fs/data-dir ctx) "/notes.md"))

(def extension
  {:id :notes})
```

An extension is a namespace with a `def` called `extension`. The map needs an
`:id`; everything else is optional. The namespace name is yours to pick; the
file name is what you list in the config.

`fs/data-dir` returns a directory that belongs to this extension,
`~/.local/share/xi/extensions/notes/`. The extension may read and write there
without asking anyone.

## 2. Enable it

```clojure
;; ~/.config/xi/config.edn
{:type       :xi/config
 :version    1
 :extensions ["notes.cljs"]}
```

Start Xi, or run `/ext reload`. `/ext list` shows `notes`.

## 3. The tool

A tool is two things: a **definition** the model reads to decide when to call
it, and a **function** that runs when it does.

```clojure
(def extension
  {:id :notes

   :tool-definitions
   [{:name "notes_add"
     :description "Append one line to the user's notes file."
     :input_schema {:type "object"
                    :properties {:text {:type "string"
                                        :description "The note, one line"}}
                    :required ["text"]}}]

   :tool-registry
   {"notes_add" (fn [{:keys [text]} ctx]
                  (-> (fs/read ctx (notes-file ctx))
                      (p/catch (fn [_] ""))
                      (p/then (fn [old]
                                (fs/write ctx (notes-file ctx) (str old text "\n"))))
                      (p/then (fn [_]
                                {:content [{:type "text" :text "Noted."}]}))))}})
```

The definition is the usual tool-schema shape: a name, a description for the
model, and a JSON schema for the arguments. Write the description for the
model: say what the tool is for and when to use it.

The function gets the arguments as a map with keyword keys, and a `ctx`. It
passes `ctx` to every `xi.api` call; that is how Xi knows which extension is
asking. The `xi.api` functions return promises, chained with `xi.api.promise`
(`.then` is not available in the sandbox). The first `p/catch` turns a missing
file into an empty string.

A tool returns a result map: `{:content [{:type "text" :text "…"}]}`, with
`:is-error true` when it failed. Return the map, or a promise of it.

`/ext reload`, then ask the agent to note something. The tool call shows up as
a block in the chat.

## 4. The command

A slash command is for you, not for the agent. Its handler is a pure function
of the state and the command; anything with side effects goes in an **effect**:

```clojure
   :commands
   [{:name "notes"
     :description "Show my notes"
     :handler (fn [_state {:keys [room-id]}]
                {:effects [[:ext.notes/show {:room-id room-id}]]})}]

   :fx
   {:ext.notes/show
    (fn [{:keys [dispatch!] :as ctx} {:keys [room-id]}]
      (-> (fs/read ctx (notes-file ctx))
          (p/catch (fn [_] "(no notes yet)"))
          (p/then (fn [text]
                    (dispatch! {:type :ui/status :room-id room-id :text text})))))}
```

The handler returns `{:effects [[effect-name payload] …]}`. Xi runs the
effect's function from `:fx` with a `ctx` and the payload. The effect reads
the file and reports back by **dispatching an event**: `:ui/status` shows
text in the status line.

Effect names are namespaced with the extension's id, `:ext.notes/…`. That is
the convention the sandbox enforces: an extension may only define effects and
events under its own prefix.

## The whole file

```clojure
;; ~/.config/xi/extensions/notes.cljs
(ns notes
  (:require [xi.api.fs :as fs]
            [xi.api.promise :as p]))

(defn- notes-file [ctx]
  (str (fs/data-dir ctx) "/notes.md"))

(def extension
  {:id :notes

   :tool-definitions
   [{:name "notes_add"
     :description "Append one line to the user's notes file."
     :input_schema {:type "object"
                    :properties {:text {:type "string"
                                        :description "The note, one line"}}
                    :required ["text"]}}]

   :tool-registry
   {"notes_add" (fn [{:keys [text]} ctx]
                  (-> (fs/read ctx (notes-file ctx))
                      (p/catch (fn [_] ""))
                      (p/then (fn [old]
                                (fs/write ctx (notes-file ctx) (str old text "\n"))))
                      (p/then (fn [_]
                                {:content [{:type "text" :text "Noted."}]}))))}

   :commands
   [{:name "notes"
     :description "Show my notes"
     :handler (fn [_state {:keys [room-id]}]
                {:effects [[:ext.notes/show {:room-id room-id}]]})}]

   :fx
   {:ext.notes/show
    (fn [{:keys [dispatch!] :as ctx} {:keys [room-id]}]
      (-> (fs/read ctx (notes-file ctx))
          (p/catch (fn [_] "(no notes yet)"))
          (p/then (fn [text]
                    (dispatch! {:type :ui/status :room-id room-id :text text})))))}})
```

## When something is off

**`/ext list` does not show it.** The file is not in `:extensions`, or the
config file is invalid (Xi prints why on startup). File names are exact,
including `.cljs`.

**`/ext reload` says the file was rejected.** The message carries the
evaluation error with a line number. A common one is a missing `:require`:
the sandbox has `clojure.core`, `clojure.string`, `clojure.set`,
`clojure.walk` and `clojure.edn`, and the `xi.api.*` namespaces. Nothing else.

**The tool is not offered to the agent.** Tools apply from the next turn after
a reload. Send another message.

**Writing the file asks for permission.** The path is outside the data
directory. Use `fs/data-dir`, or add a [rule](rules.md) for the path.

## Next

[Tutorial: a command and a key](extension-tutorial-command.md) reacts to
events and runs a program.
