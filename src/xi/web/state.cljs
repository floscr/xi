(ns xi.web.state
  "Global application state atom for the web client.")

(defonce app-state
  (atom {:view             :home          ;; :home | :chat
         :rooms            []             ;; from server handshake
         :room-id          nil            ;; joined room id
         :connected?       false
         :busy?            false
         :messages         []             ;; [{:type :user/:assistant/:tool/:thinking/:status :text ...}]
         :compose-text     ""
         :compose-images   []             ;; [{:data base64 :media-type mime :preview-url blob-url}]
         :model            nil
         :cwd              nil
         :collapsed-blocks #{}
         :resume-sessions  nil
         :lightbox-image   nil}))          ;; nil = hidden, [] = loading, [...] = show picker
