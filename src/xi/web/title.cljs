(ns xi.web.title
  "The browser tab title (`document.title`): what this client is looking at —
   the session, the buffer open in it, the project or page — most specific
   first, so a row of tabs stays tellable apart. Pure: `page-title` maps the
   web state to a string; xi.web.core/render! writes it when it changes."
  (:require [clojure.string :as str]
            [xi.core.state :as state]))

(def app-name "Xi")

(def ^:private sep " · ")

(defn- session-name
  "The name of session `sid`: the joined room's own session (freshest — a
   rename lands there first), else any lobby / listing row naming it."
  [st sid]
  (when sid
    (let [room (state/active-room st)]
      (or (when (= sid (get-in room [:session :id]))
            (not-empty (get-in room [:session :name])))
          (some #(when (= sid (:session-id %))
                   (not-empty (or (:name %) (:session-name %))))
                (concat (get-in st [:lobby :sessions])
                        (get-in st [:lobby :rooms])
                        (:web/all-sessions st)
                        (:web/project-sessions st)))))))

(defn- dir-name
  "The last component of a directory path (`~/Code/Projects/xi` → `xi`)."
  [path]
  (when (string? path)
    (last (str/split path #"/"))))

(defn- active-buffer-title
  "The label of the buffer the chat at `sid` shows, nil on the chat itself.
   A virtual new chat (no session id) keeps its buffers on the pending room."
  [st sid]
  (let [room (state/active-room st)
        ui   (cond
               (and sid (= sid (get-in room [:session :id]))) (:ui room)
               (nil? sid) (get-in st [:web/pending-room :ui]))
        id   (get ui :active-buffer :chat)
        buf  (get-in ui [:buffers id])]
    (when (and buf (not= id :chat))
      (or (not-empty (:title buf))
          (if (keyword? id) (name id) (str id))))))

(defn- page-label
  "An extension page's name from its keyword: `:canvas-review` → `Canvas review`."
  [page]
  (str/capitalize (str/replace (name page) "-" " ")))

(defn page-title
  "The tab title for web state `st`, from its `:web/route`."
  [st]
  (let [{:keys [page session-id dir cwd]} (:web/route st)
        parts (case page
                :chat       (if session-id
                              [(active-buffer-title st session-id)
                               (or (session-name st session-id) "Session")]
                              [(active-buffer-title st nil) "New session"])
                :git-status ["Git status" (dir-name cwd)]
                (:home nil) (cond
                              (= dir :all) ["All sessions"]
                              dir          [(dir-name dir)])
                ;; an extension page, scoped to a session when its route is
                [(page-label page) (session-name st session-id)])]
    (str/join sep (concat (remove str/blank? parts) [app-name]))))
