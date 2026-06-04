(ns xi.web.state
  "Global application state atom for the web client.")

(defonce app-state
  (atom {:route            {:page :home}  ;; {:page :home} | {:page :chat :session-id "..."}
         :rooms            []             ;; from server handshake
         :home-sessions    []             ;; personal-agent sessions from handshake
         :active-sessions  #{}            ;; set of session-ids with live rooms
         :watched-sessions {}             ;; {session-id response-count} — sessions user is tracking
         :response-counts  {}             ;; {session-id count} from server query
         :room-id          nil            ;; joined room id
         :session-id       nil            ;; xi session id for current room
         :connected?       false
         :personal-agent?  false
         :busy?            false
         :messages         []             ;; [{:type :user/:assistant/:tool/:thinking/:status :text ...}]
         :pending-messages []             ;; [{:id :payload :timestamp :status}]
         :compose-text     ""
         :compose-images   []             ;; [{:data base64 :media-type mime :preview-url blob-url}]
         :model            nil
         :cwd              nil
         :commands         []              ;; [{:name :description}] from server
         :slash-selected   0               ;; selected index in slash command dropdown
         :collapsed-blocks #{}
         :resume-sessions  nil
         :confirm-request  nil             ;; {:confirm-id :message} — pending confirmation dialog
         :lightbox-image   nil}))          ;; nil = hidden, [] = loading, [...] = show picker
