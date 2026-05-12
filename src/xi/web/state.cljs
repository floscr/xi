(ns xi.web.state
  "Global application state atom for the web client.")

(defonce app-state
  (atom {:view             :home          ;; :home | :chat
         :sessions         []             ;; from server handshake
         :session-id       nil            ;; joined session id
         :connected?       false
         :busy?            false
         :messages         []             ;; [{:type :user/:assistant/:tool/:thinking/:status :text ...}]
         :compose-text     ""
         :model            nil
         :cwd              nil
         :collapsed-blocks #{}}))
