# Workspaces

A workspace is a set of projects. Pick one in the tab bar across the top of
the web client and the chat lists, the project list and the home page show
only chats and projects in it. The first tab, **All**, is always there and
shows everything. The bar appears once a tab besides All is open. On a
phone or a narrow window it sits atop the sidebar instead.

A chat belongs to a workspace when it runs in one of the workspace's
directories or below one. A chat with no directory, such as a terminal or
a personal agent's, is in All only.

Each tab keeps its own open chat. Leaving a tab remembers the chat on
screen, and coming back opens it again. A tab with no chat of its own keeps
the one on screen if it belongs there, and otherwise shows the home page.

## The Workspace section

With a workspace other than All selected, the sidebar has a **Workspace**
section below Projects: every chat you opened while that workspace was
selected, whatever its directory. It keeps them, however old, until you
prune the workspace: **Prune workspace** in the palette or in the tab's
right-click menu empties the section. **Remove from workspace** in a chat's
menu there takes out one chat. Neither deletes or hides the chats
themselves. The section belongs to your user, like your workspaces.

Each workspace also remembers which sidebar groups you collapsed. One you
haven't changed yet starts with All's.

## Your own workspaces

**New workspace…** in the command palette (Ctrl+K / Cmd+K) or the
sidebar's ⋯ menu creates one; so does **New workspace with this project…**
when you right-click a project (in the sidebar, on the home page or in the
project list). Name it; the tab bar appears and the new workspace is
selected.

The palette's **Workspaces** group also has **Switch workspace…**, and while
one of your own is selected, **Add project to …**, **Rename workspace …**
and **Remove workspace …**.

The same menu puts a project in or out of each of your workspaces (**Add
to Penpot**, **Remove from Penpot**). Inside a workspace, **Add project…**
at the end of its Projects group opens the project picker. Right-click one
of your tabs to rename or delete the workspace; deleting it leaves its chats
and projects alone.

These workspaces belong to your [user](server.md#users), so your phone
shows the same tabs as your laptop, and a colleague on the same server has
their own. The selected tab and each tab's open chat belong to the browser
window: two windows can show two workspaces, and a reload keeps the
window's tab.

## Closing a tab

The × on a tab, or **Close workspace** in its right-click menu, closes it.
Every tab but All can be closed, the ones from the config file too. Closing
only hides the tab: **Switch workspace…** in the palette still lists the
workspace, marked closed, and picking it opens the tab again. With every
tab but All closed, the bar hides. Closed tabs
belong to your user, like your workspaces.

## Workspaces from the config file

Workspaces everyone on the server sees, and that fill themselves, come from
`:workspaces` in `~/.config/xi/config.edn`:

```clojure
{:type    :xi/config
 :version 1
 :workspaces [{:id    :penpot
               :label "Penpot"
               :dirs  ["~/code/penpot" "~/code/penpot-*"]}
              {:id   :home
               :dirs ["~/code/home/*"]}]}
```

| Key | Meaning |
| --- | --- |
| `:id` | A keyword or string, unique in the list. `:all` is taken by the root tab. |
| `:label` | The tab's text. Defaults to the id. |
| `:dirs` | One or more directories. `~` works. A `*` matches one name in the path, so `~/code/penpot-*` takes every sibling that starts with `penpot-`, and `~/code/home/*` every project under `~/code/home`, as soon as it exists. |

These tabs come first, before your own, and the UI does not edit them. The
file is read when the lists refresh, so a change applies without a restart.
An invalid entry makes the whole config invalid; the error is on the
server's stderr, and no project configuration loads until it is fixed.

## Keys

No key is bound by default. The actions, to bind under `:keys` in
`config.edn` ([Keyboard shortcuts](keyboard.md#changing-keys)):

| Action | Does |
| --- | --- |
| `:workspace/switch` | Opens the palette's workspace list. |
| `:workspace/new` | Asks for a name and creates a workspace. |
| `:workspace/close` | Closes the selected tab; the tab to its left takes over. |
| `:workspace/prune` | Empties the selected workspace's Workspace section. |
| `:workspace/rename` | Renames the selected workspace, when it is one of yours. |
| `:workspace/delete` | Deletes the selected workspace, when it is one of yours. Its chats stay. |
| `:workspace/next` / `:workspace/prev` | The next or previous tab, wrapping around. |
| `:workspace/other` | The tab selected before this one. |
| `:workspace/tab-1` … `:workspace/tab-9` | Tab 1 to 9, counting All as 1. |
| `:workspace/tab-last` | The last tab. |

Doom Emacs' `SPC TAB` map, as [leader keys](keyboard.md#leader-keys):

```clojure title="~/.config/xi/config.edn"
:keys {:web {:mode/navigate {["space" "tab" "tab"] :workspace/switch
                             ["space" "tab" "."]   :workspace/switch
                             ["space" "tab" "n"]   :workspace/new
                             ["space" "tab" "r"]   :workspace/rename
                             ["space" "tab" "d"]   :workspace/close
                             ["space" "tab" "D"]   :workspace/delete
                             ["space" "tab" "]"]   :workspace/next
                             ["space" "tab" "["]   :workspace/prev
                             ["space" "tab" "`"]   :workspace/other
                             ["space" "tab" "1"]   :workspace/tab-1
                             ["space" "tab" "2"]   :workspace/tab-2
                             ["space" "tab" "3"]   :workspace/tab-3
                             ["space" "tab" "0"]   :workspace/tab-last}}}
```

## What a workspace does not do

- The lists it narrows are the ones the web client already has: the sidebar
  shows the most recent hundred chats, so a quiet workspace may show fewer
  old chats than **All sessions** finds.
- A chat you open from a link outside the workspace still opens; only the
  lists are filtered.
- The terminal client has no workspaces.
