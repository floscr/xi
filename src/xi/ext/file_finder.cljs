(ns xi.ext.file-finder
  "Instant fuzzy file finder for the TUI (Ctrl+P). Lists the room's files
   (git-tracked when available, else a recursive walk) into the editor's
   fuzzy completion menu; selecting a file dispatches :file/open, which the
   file-view extension reads from disk and installs as the :file buffer (the
   same read-only viewer the web client uses).

   The listing runs where the key was caught (same as the /project picker), so
   it reads the local filesystem — correct for standalone and the in-process
   server TUI."
  (:require [xi.core.state :as state]
            [xi.server.files :as files]))

(defn- open
  "Ctrl+P — defer the (I/O-bound) listing to the :file-finder/list effect."
  [_st {:keys [room-id]}]
  {:effects [[:file-finder/list {:room-id room-id}]]})

(defn- list-fx
  "List the room's files and open a fuzzy picker; each row opens the file in
   the :file tab. Falls back to a status line when there's nothing to show."
  [{:keys [dispatch! state]} {:keys [room-id]}]
  (let [room (state/get-room state room-id)
        cwd  (or (:cwd room) (.cwd js/process))
        {:keys [files error]} (files/list-files cwd)]
    (cond
      error
      (dispatch! {:type :ui/status :room-id room-id
                  :text (str "File finder failed: " error)})

      (empty? files)
      (dispatch! {:type :ui/status :room-id room-id :text "No files found."})

      :else
      (dispatch! {:type :ui/menu-open :room-id room-id
                  :menu {:id     :file-finder
                         :prompt "file> "
                         :items  (mapv (fn [f]
                                         {:label f
                                          :event {:type :file/open
                                                  :room-id room-id
                                                  :path f}})
                                       files)}}))))

(def extension
  {:id          :file-finder
   :handlers    {:file-finder/open open}
   :fx          {:file-finder/list list-fx}
   :keybindings [{:key "ctrl+p" :label "Find a file" :event {:type :file-finder/open}}]})
