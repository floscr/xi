# Tutorial: users and roles

Give the people on a shared server roles, and let the roles decide what their
chats may do. Rules restrict the agent's tools per role. An extension reads
the same roles to gate its own features and to hand out extra roles at
runtime. No code is needed for the first half.

## What you build

Alice is an admin, Bob is a guest. Bob's chats can't run programs and can't
clear the team board. Alice's `git push` runs without a dialog. A small
extension, `board`, gives everyone a shared board the agent can post to,
lets only moderators clear it, and gives admins a `/grant` command that
makes someone a moderator.

## 1. Who is who

Roles are plain data in each user's `:meta` in `~/.config/xi/config.edn`.
Xi gives the word "role" no meaning of its own: `:roles` is just a key the
rules and the extension below agree on.

```clojure
;; ~/.config/xi/config.edn
{:type       :xi/config
 :version    1
 :extensions ["board.cljs"]
 :users      {"alice" {:name "Alice" :meta {:roles ["admin"]}}
              "bob"   {:name "Bob"   :meta {:roles ["guest"]}}}}
```

You decide what is in this file: the agent asks before every change to it,
and extensions can read `:meta` but not change it.

Then tie each device to its person. Nothing in Xi checks who someone is: a
browser can pick any user from the sidebar, so a role is only as good as this
assignment.

```sh
xi clients                       # the paired devices
xi clients user bobs-laptop bob  # this device is always bob
```

A device with an assignment can't switch to another user. See
[Users](server.md#users).

## 2. Rules per role

A rule with `:user` applies only to the users it names. A map is matched
against their profile, so `{:meta {:roles "guest"}}` means "anyone whose
`:roles` contains `guest`".

```clojure
;; ~/.config/xi/rules.edn
{:type    :xi/rules
 :version 1
 :rules
 [;; guests never run programs
  {:match  {:user {:meta {:roles "guest"}} :tool #{:bash :clj :sh :bb}}
   :action {:type :deny :message "Guests can't run programs."}}

  ;; guests can't clear the board, whatever the extension allows
  {:match  {:user {:meta {:roles "guest"}} :tool-name "board_clear"}
   :action {:type :deny :message "Guests can't clear the board."}}

  ;; admins push without a dialog
  {:match  {:user {:meta {:roles "admin"}} :tool :sh :command #"^git push\b"}
   :action {:type :allow}}]}
```

A call acts for whoever sent the chat's latest prompt, and a sub-agent acts
for the user its parent works for. The first matching rule wins; a user no
rule names gets the defaults like before. `/rules` lists the rules in order.

`:user` also takes ids directly, for one-off exceptions:
`{:user "bob"}`, `{:user #{"bob" "carol"}}`. Every option is in the
[rules reference](rules-reference.md#match).

## 3. An extension that knows the roles

Rules decide whether a call runs at all. An extension can make finer
decisions in its own tools, and it can keep roles of its own. Create
`~/.config/xi/extensions/board.cljs`, starting with the roles:

```clojure
(ns board
  (:require [clojure.string :as str]
            [xi.api.fs :as fs]
            [xi.api.promise :as p]
            [xi.api.user :as user]
            [xi.core.state :as state]))

(defn- roles
  "A user's roles: those config.edn gives them, plus those granted here."
  [meta granted]
  (into (set (:roles meta)) (:roles granted)))
```

The extension reads roles from two places. `:meta` is the operator's say
from `config.edn`. `granted` is what the extension itself keeps for the user
(step 5), which only the extension's own features look at.

## 4. A tool only moderators may use

```clojure
(defn- board-file [ctx]
  (str (fs/data-dir ctx) "/board.md"))

(defn- text [s] {:content [{:type "text" :text s}]})

(defn- post [{:keys [line]} ctx]
  (let [line (str (user/current ctx) ": " line "\n")]
    (-> (fs/read ctx (board-file ctx))
        (p/catch (fn [_] ""))
        (p/then (fn [old] (fs/write ctx (board-file ctx) (str old line))))
        (p/then (fn [_] (text "Posted."))))))

(defn- clear [_ ctx]
  (let [mine (roles (:meta (user/info ctx)) (user/state ctx))]
    (if (contains? mine "moderator")
      (-> (fs/write ctx (board-file ctx) "")
          (p/then (fn [_] (text "Board cleared."))))
      (assoc (text "Only moderators can clear the board.") :is-error true))))
```

In a tool, `ctx` knows the user the turn acts for: `user/current` is the id,
`user/info` the profile with its `:meta`, `user/state` what this extension
keeps for them. The refusal is an error result, so the agent reads why and
tells the user.

## 5. A command that grants a role

A command's handler is pure: it gets the state and `{:keys [user args room-id]}`,
where `user` is whoever typed it. It checks that the sender is an admin, then
leaves the write to an effect:

```clojure
(defn- status [room-id s]
  [:app/dispatch {:type :ui/status :room-id room-id :text s}])

(defn- grant-cmd [st {:keys [user args room-id]}]
  (let [[who role] (str/split (str/trim (str args)) #"\s+")
        sender     (roles (:meta (state/user-record st user))
                          (state/user-ext st user :board))]
    (cond
      (not (contains? sender "admin"))
      {:effects [(status room-id "Only admins can grant roles.")]}

      (str/blank? role)
      {:effects [(status room-id "Usage: /grant <user> <role>")]}

      :else
      {:effects [[:ext.board/grant {:who who :role role :room-id room-id}]]})))

(defn- grant! [{:keys [dispatch!] :as ctx} {:keys [who role room-id]}]
  (dispatch! {:type :ui/status :room-id room-id
              :text (try
                      (user/set-state! ctx who (update (user/state ctx who) :roles
                                                       (fnil conj #{}) role))
                      (str who " is now " role)
                      (catch :default e (ex-message e)))}))
```

`user/set-state!` keeps the value for that user on the server, so it
survives restarts and follows them to every device. Only this extension can
read or change it. It throws for something that is not a user id
(`/grant Carol!! moderator`), and the status line shows why.

## 6. Who am I

```clojure
(defn- whoami-cmd [st {:keys [user room-id]}]
  (let [mine (roles (:meta (state/user-record st user))
                    (state/user-ext st user :board))]
    {:effects [(status room-id (str user ": "
                                    (if (seq mine) (str/join ", " (sort mine)) "no roles")))]}))
```

## The whole file

```clojure
;; ~/.config/xi/extensions/board.cljs
(ns board
  (:require [clojure.string :as str]
            [xi.api.fs :as fs]
            [xi.api.promise :as p]
            [xi.api.user :as user]
            [xi.core.state :as state]))

(defn- roles
  "A user's roles: those config.edn gives them, plus those granted here."
  [meta granted]
  (into (set (:roles meta)) (:roles granted)))

(defn- board-file [ctx]
  (str (fs/data-dir ctx) "/board.md"))

(defn- text [s] {:content [{:type "text" :text s}]})

(defn- post [{:keys [line]} ctx]
  (let [line (str (user/current ctx) ": " line "\n")]
    (-> (fs/read ctx (board-file ctx))
        (p/catch (fn [_] ""))
        (p/then (fn [old] (fs/write ctx (board-file ctx) (str old line))))
        (p/then (fn [_] (text "Posted."))))))

(defn- clear [_ ctx]
  (let [mine (roles (:meta (user/info ctx)) (user/state ctx))]
    (if (contains? mine "moderator")
      (-> (fs/write ctx (board-file ctx) "")
          (p/then (fn [_] (text "Board cleared."))))
      (assoc (text "Only moderators can clear the board.") :is-error true))))

(defn- status [room-id s]
  [:app/dispatch {:type :ui/status :room-id room-id :text s}])

(defn- grant-cmd [st {:keys [user args room-id]}]
  (let [[who role] (str/split (str/trim (str args)) #"\s+")
        sender     (roles (:meta (state/user-record st user))
                          (state/user-ext st user :board))]
    (cond
      (not (contains? sender "admin"))
      {:effects [(status room-id "Only admins can grant roles.")]}

      (str/blank? role)
      {:effects [(status room-id "Usage: /grant <user> <role>")]}

      :else
      {:effects [[:ext.board/grant {:who who :role role :room-id room-id}]]})))

(defn- grant! [{:keys [dispatch!] :as ctx} {:keys [who role room-id]}]
  (dispatch! {:type :ui/status :room-id room-id
              :text (try
                      (user/set-state! ctx who (update (user/state ctx who) :roles
                                                       (fnil conj #{}) role))
                      (str who " is now " role)
                      (catch :default e (ex-message e)))}))

(defn- whoami-cmd [st {:keys [user room-id]}]
  (let [mine (roles (:meta (state/user-record st user))
                    (state/user-ext st user :board))]
    {:effects [(status room-id (str user ": "
                                    (if (seq mine) (str/join ", " (sort mine)) "no roles")))]}))

(def extension
  {:id :board

   :tool-definitions
   [{:name "board_post"
     :description "Post one line to the team board everyone on this server shares."
     :input_schema {:type "object"
                    :properties {:line {:type "string" :description "The message, one line"}}
                    :required ["line"]}}
    {:name "board_clear"
     :description "Empty the team board. Only moderators may."
     :input_schema {:type "object" :properties {}}}]

   :tool-registry {"board_post" post
                   "board_clear" clear}

   :commands [{:name "grant" :description "Give a user a role: /grant <user> <role>"
               :handler grant-cmd}
              {:name "whoami" :description "Show your user and roles"
               :handler whoami-cmd}]

   :fx {:ext.board/grant grant!}})
```

`/ext reload`, then try it:

1. As Alice, `/grant carol moderator`. The status line says
   `carol is now moderator`.
2. As Carol, ask the agent to clear the board. It calls `board_clear`, and
   the board is empty.
3. As Bob, `/grant bob moderator` answers `Only admins can grant roles.`
   Even if Alice made him a moderator, a call to `board_clear` from his chats
   would still be refused: the rule from step 2 matches first.

## Rules or the extension?

| Question | Use |
| --- | --- |
| May this user's chats run a tool, a program, write a path? | A rule. It covers every tool, including other extensions' and MCP tools, and the agent can't get around it. |
| May this user use a feature of my extension? | The extension, reading `user/info` and `user/state`. |
| Who may grant a role at runtime? | The extension, as in `/grant`. |

Rules see only `:meta` from `config.edn`, never what an extension keeps.
That is on purpose: what an extension grants can widen what the extension
itself allows, but never what the rest of Xi allows. To widen that, edit
`config.edn` or `rules.edn`.

## Next

The [extension reference](extensions-reference.md) has every key, API
function and limit. [Tutorial: a page in the browser](extension-tutorial-web.md)
shows how to give the board a page of its own.
