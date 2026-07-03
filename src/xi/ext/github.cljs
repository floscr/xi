(ns xi.ext.github
  "GitHub pull-request views for the web client — shells out to the `gh`
   CLI in a project's cwd. Roomless events (:pr/web-list, :pr/web-detail)
   arrive keyed by :client-id; the server-fx do the gh I/O and reply
   directly to the requesting client. A non-GitHub repo or a `gh` failure
   replies with {:error <stderr>} rather than rejecting."
  (:require [clojure.string :as str]))

;; ── gh CLI plumbing ───────────────────────────────────────────────────────────

(defn- run-gh
  "Run `gh` with args in cwd. Resolves {:ok stdout} or {:err message}."
  [cwd args]
  (js/Promise.
   (fn [resolve _reject]
     (let [proc (js/Bun.spawn
                 (clj->js (cons "gh" args))
                 #js {:stdout "pipe" :stderr "pipe"
                      :cwd (or cwd (.cwd js/process))})]
       (-> (js/Promise.all #js [(.text (.-stdout proc))
                                (.text (.-stderr proc))
                                (.-exited proc)])
           (.then (fn [^js results]
                    (let [stdout (aget results 0)
                          stderr (aget results 1)
                          code   (aget results 2)]
                      (resolve (if (and code (not= code 0))
                                 {:err (str/trim (or stderr (str "gh exited " code)))}
                                 {:ok stdout}))))))))))

(defn- ->pr-summary [m]
  {:number    (:number m)
   :title     (:title m)
   :state     (:state m)
   :draft?    (:isDraft m)
   :author    (get-in m [:author :login])
   :assignees (mapv :login (:assignees m))
   :head      (:headRefName m)
   :base      (:baseRefName m)
   :updated   (:updatedAt m)
   :url       (:url m)})

(def ^:private list-fields
  "number,title,author,assignees,state,isDraft,headRefName,baseRefName,updatedAt,url")

(def ^:private view-fields
  "number,title,body,author,assignees,state,isDraft,headRefName,baseRefName,additions,deletions,changedFiles,url,createdAt,updatedAt")

(defn- pr-list
  "Open PRs for cwd plus the viewer's login (for client-side mine/assigned
   filtering). Resolves {:prs [...] :me \"login\"} or {:error <msg>}."
  [cwd]
  (-> (js/Promise.all
       #js [(run-gh cwd ["pr" "list" "--state" "open" "--limit" "100"
                         "--json" list-fields])
            (run-gh cwd ["api" "user" "--jq" ".login"])])
      (.then (fn [^js results]
               (let [lst (aget results 0)
                     usr (aget results 1)]
                 (if-let [err (:err lst)]
                   {:error err}
                   (let [prs (js->clj (js/JSON.parse (:ok lst)) :keywordize-keys true)]
                     {:prs (mapv ->pr-summary prs)
                      :me  (str/trim (or (:ok usr) ""))})))))
      (.catch (fn [e] {:error (str e)}))))

(defn- pr-detail
  "Full metadata + unified diff for a single PR. Resolves
   {:pr {...} :diff \"...\"} or {:error <msg>}."
  [cwd number]
  (-> (js/Promise.all
       #js [(run-gh cwd ["pr" "view" (str number) "--json" view-fields])
            (run-gh cwd ["pr" "diff" (str number)])])
      (.then (fn [^js results]
               (let [view (aget results 0)
                     diff (aget results 1)]
                 (if-let [err (:err view)]
                   {:error err}
                   (let [m (js->clj (js/JSON.parse (:ok view)) :keywordize-keys true)]
                     {:pr   {:number        (:number m)
                             :title         (:title m)
                             :body          (:body m)
                             :state         (:state m)
                             :draft?        (:isDraft m)
                             :author        (get-in m [:author :login])
                             :assignees     (mapv :login (:assignees m))
                             :head          (:headRefName m)
                             :base          (:baseRefName m)
                             :additions     (:additions m)
                             :deletions     (:deletions m)
                             :changed-files (:changedFiles m)
                             :url           (:url m)
                             :created       (:createdAt m)
                             :updated       (:updatedAt m)}
                      :diff (or (:ok diff) "")})))))
      (.catch (fn [e] {:error (str e)}))))

;; ── Handlers (pure, roomless) ─────────────────────────────────────────────────

(defn- pr-web-list
  "Roomless: return the open pull requests for a CWD."
  [_st {:keys [client-id cwd]}]
  {:effects [[:pr/web-list-reply {:client-id client-id :cwd cwd}]]})

(defn- pr-web-detail
  "Roomless: return one pull request's metadata + diff."
  [_st {:keys [client-id cwd number]}]
  {:effects [[:pr/web-detail-reply {:client-id client-id :cwd cwd :number number}]]})

;; ── Server fx (reply to the requesting client) ────────────────────────────────

(defn- server-fx [{:keys [send!]}]
  {:pr/web-list-reply
   (fn [_ {:keys [client-id cwd]}]
     (-> (pr-list cwd)
         (.then (fn [res]
                  (send! client-id
                         (merge {:type :pr/web-list-result :cwd cwd} res))))))

   :pr/web-detail-reply
   (fn [_ {:keys [client-id cwd number]}]
     (-> (pr-detail cwd number)
         (.then (fn [res]
                  (send! client-id
                         (merge {:type :pr/web-detail-result
                                 :cwd cwd :number number}
                                res))))))})

;; ── Extension ─────────────────────────────────────────────────────────────────

(def extension
  {:id              :github
   :handlers        {:pr/web-list   pr-web-list
                     :pr/web-detail pr-web-detail}
   :server-fx       server-fx
   :roomless-events #{:pr/web-list :pr/web-detail}})
