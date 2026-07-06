(ns xi.ext.github-code-search.core
  "GitHub code-search tool for agents — hits github.com's *new* code search
   (the blackbird engine behind github.com/search?type=code), which supports
   the full query syntax (`path:*.nix`, `language:`, regex, …) that the legacy
   `/search/code` REST API does not.

   That endpoint is unavailable to the API token: it only answers a logged-in
   *web session*. So we borrow the user's `user_session` cookie, sourced from
   the GITHUB_USER_SESSION env var or ~/.config/xi/github/auth.json, and set it
   as a request cookie. `/github-login <cookie>` persists it.

   Tools:
     github_code_search  — search code across GitHub with github.com syntax
   Commands:
     /github-login <user_session>  — save the session cookie"
  (:require [clojure.string :as str]
            ["node:fs/promises" :as fsp]
            ["node:path" :as path]
            ["node:os" :as os]))

;; ── Constants ─────────────────────────────────────────────────────────────────

(def ^:private AUTH_PATH
  (path/join (os/homedir) ".config" "xi" "github" "auth.json"))

(def ^:private SEARCH_ENDPOINT "https://github.com/search")
(def ^:private FETCH_TIMEOUT_MS 20000)
(def ^:private MAX_RESULTS 30)
(def ^:private MAX_SNIPPETS_PER_RESULT 4)
(def ^:private USER_AGENT
  "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

;; ── Cookie storage ────────────────────────────────────────────────────────────

(defn- load-cookie
  "Resolve the GitHub user_session cookie. Tries GITHUB_USER_SESSION env var,
   then ~/.config/xi/github/auth.json. Returns promise of string or nil."
  []
  (let [env (some-> (aget js/process.env "GITHUB_USER_SESSION") str/trim not-empty)]
    (if env
      (js/Promise.resolve env)
      (-> (fsp/readFile AUTH_PATH "utf8")
          (.then (fn [raw]
                   (let [parsed (js->clj (js/JSON.parse raw) :keywordize-keys true)]
                     (some-> (:user_session parsed) str/trim not-empty))))
          (.catch (fn [_] nil))))))

(defn- save-cookie
  "Persist the user_session cookie to disk (mode 0600). Returns promise."
  [value]
  (let [data (js/JSON.stringify (clj->js {:user_session value}) nil 2)]
    (-> (fsp/mkdir (path/dirname AUTH_PATH) #js {:recursive true})
        (.then #(fsp/writeFile AUTH_PATH (str data "\n") #js {:encoding "utf8" :mode 384}))
        (.then #(fsp/chmod AUTH_PATH 384)))))

;; ── HTML snippet cleanup ──────────────────────────────────────────────────────

(defn- decode-entities [text]
  (-> text
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"")
      (str/replace #"&#0?39;" "'")
      (str/replace "&#x27;" "'")
      (str/replace "&#x2F;" "/")
      (str/replace "&nbsp;" " ")
      (str/replace "&amp;" "&")))

(defn- strip-html
  "GitHub snippet lines are syntax-highlighted HTML. Strip tags, keep the text;
   entity decoding runs last so decoded `<`/`>` aren't treated as tags."
  [html]
  (-> html
      (str/replace #"<[^>]+>" "")
      decode-entities))

;; ── Search ────────────────────────────────────────────────────────────────────

(defn- ref-branch
  "refs/heads/main → main; leave other refs untouched."
  [ref-name]
  (if (and ref-name (str/starts-with? ref-name "refs/heads/"))
    (subs ref-name (count "refs/heads/"))
    ref-name))

(defn- fetch-search
  "GET the code-search JSON for query/page with the session cookie.
   Resolves {:status :payload} or rejects."
  [query page cookie]
  (let [url (str SEARCH_ENDPOINT "?q=" (js/encodeURIComponent query)
                 "&type=code&p=" page)
        controller (js/AbortController.)
        timer (js/setTimeout #(.abort controller) FETCH_TIMEOUT_MS)]
    (-> (js/fetch url
                  #js {:signal (.-signal controller)
                       :headers #js {"Accept" "application/json"
                                     "User-Agent" USER_AGENT
                                     "Cookie" (str "user_session=" cookie
                                                   "; __Host-user_session_same_site=" cookie
                                                   "; logged_in=yes")}})
        (.then (fn [resp]
                 (js/clearTimeout timer)
                 (-> (.text resp)
                     (.then (fn [body]
                              {:status (.-status resp)
                               :payload (try
                                          (:payload (js->clj (js/JSON.parse body)
                                                             :keywordize-keys true))
                                          (catch :default _ nil))})))))
        (.catch (fn [err]
                  (js/clearTimeout timer)
                  (js/Promise.reject err))))))

;; ── Formatting ────────────────────────────────────────────────────────────────

(defn- format-result [idx {:keys [repo_nwo path ref_name commit_sha language_name
                                  match_count snippets]}]
  (let [branch (ref-branch ref_name)
        url (str "https://github.com/" repo_nwo "/blob/"
                 (or commit_sha branch) "/" path)
        header (str "[" (inc idx) "] " repo_nwo " — " path
                    (when language_name (str "  (" language_name
                                             (when match_count (str ", " match_count " matches"))
                                             ")")))
        lines (->> (take MAX_SNIPPETS_PER_RESULT snippets)
                   (mapcat (fn [{:keys [lines starting_line_number]}]
                             (map-indexed
                              (fn [i raw]
                                (str "    " (+ starting_line_number i) ": "
                                     (str/trimr (strip-html raw))))
                              lines))))]
    (str/join "\n" (into [header (str "    " url)] lines))))

(defn- format-results [query total results]
  (if (empty? results)
    (str "No code results for: " query)
    (str "GitHub code search: " query "\n"
         (count results) " of " total " result(s) shown\n\n"
         (str/join "\n\n"
                   (map-indexed format-result results)))))

;; ── Tool ──────────────────────────────────────────────────────────────────────

(def ^:private search-def
  {:name "github_code_search"
   :description "Search code across all of GitHub using github.com's code search (the new blackbird engine, not the legacy REST API).

Supports the full github.com query syntax, including qualifiers the REST API can't do:
- `path:*.nix` / `path:src/**/*.ts` — glob file paths
- `language:clojure` — filter by language
- `repo:owner/name`, `org:owner`, `user:login` — scope to repo/org/user
- `\"exact phrase\"`, `/regex/` — phrase and regex matching

Example query: `recordly path:*.nix`

Returns matching repos, file paths, blob URLs, and code snippets. Requires a saved GitHub session cookie (see /github-login)."
   :input_schema {:type "object"
                  :properties {:query {:type "string"
                                       :description "github.com code-search query, e.g. `recordly path:*.nix`"}
                               :page {:type "number"
                                      :description "Result page (1-based, default 1)"}}
                  :required ["query"]}})

(defn- github-code-search [{:keys [query page]} _ctx]
  (-> (load-cookie)
      (.then
       (fn [cookie]
         (if-not cookie
           {:content [{:type "text"
                       :text (str "No GitHub session cookie configured. GitHub's code search "
                                  "needs a logged-in web session.\n\n"
                                  "Provide the `user_session` cookie from a logged-in github.com "
                                  "session via either:\n"
                                  "  • the GITHUB_USER_SESSION env var, or\n"
                                  "  • the /github-login <user_session> command.")}]
            :is-error true}
           (-> (fetch-search query (max 1 (or page 1)) cookie)
               (.then
                (fn [{:keys [status payload]}]
                  (cond
                    (nil? payload)
                    {:content [{:type "text" :text (str "GitHub returned a non-JSON response (HTTP " status ").")}]
                     :is-error true}

                    (not (:logged_in payload))
                    {:content [{:type "text"
                                :text (str "GitHub rejected the session cookie (not logged in). "
                                           "It has likely expired — refresh it via /github-login.")}]
                     :is-error true}

                    :else
                    (let [results (take MAX_RESULTS (:results payload))]
                      {:content [{:type "text"
                                  :text (format-results query (:result_count payload) results)}]}))))))))
      (.catch (fn [err]
                {:content [{:type "text"
                            :text (str "GitHub code search failed: " (.-message err))}]
                 :is-error true}))))

;; ── Command ───────────────────────────────────────────────────────────────────

(defn- login-command
  "/github-login <user_session> — pure: defer the cookie save to an effect."
  [st {:keys [room-id args]}]
  (let [value (some-> args str/trim not-empty)]
    {:effects [[:github-code-search/login {:room-id room-id :value value}]]}))

(defn- login-fx
  [{:keys [dispatch!]} {:keys [room-id value]}]
  (if-not value
    (dispatch! {:type :history/append :room-id room-id
                :entry {:kind :status
                        :text "Usage: /github-login <user_session cookie value>"}})
    (-> (save-cookie value)
        (.then (fn [_]
                 (dispatch! {:type :history/append :room-id room-id
                             :entry {:kind :status
                                     :text "GitHub code search: session cookie saved."}})))
        (.catch (fn [err]
                  (dispatch! {:type :history/append :room-id room-id
                              :entry {:kind :status
                                      :text (str "Failed to save GitHub cookie: " (.-message err))}}))))))

;; ── Extension ─────────────────────────────────────────────────────────────────

(def extension
  {:id               :github-code-search
   :commands         [{:name "github-login"
                       :description "Save the GitHub session cookie for code search"
                       :handler login-command}]
   :fx               {:github-code-search/login login-fx}
   :tool-definitions [search-def]
   :tool-registry    {"github_code_search" github-code-search}})
