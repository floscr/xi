(ns xi.ext.github.web
  "Browser half of the GitHub extension — composed by xi.web.core (never
   loaded by the node builds). Contributes the /pulls routes + the roomless
   PR pages (list, detail, focused diff), the client-side :pr/* handlers and
   the overflow-menu entries via the ext/compose web seams
   (:routes/:pages/:nav-items)."
  (:require [clojure.string :as str]
            [ui.badge :as badge]
            [ui.button :as button]
            [ui.form :as form]
            [ui.icon :as icon]
            [xi.diff :as diff]
            [xi.markdown.hiccup :as md]
            [xi.web.views :as views]))

;; ── Handlers (client-local, never mirrored) ──────────────────────────────────

(defn- pr-review-prompt
  "The prompt seeded into a fresh agent room to review a PR. The agent runs in
   the PR's project cwd, so it fetches the PR itself rather than us shipping a
   (possibly huge) diff through app state."
  [number title]
  (str "Please review pull request #" number
       (when (seq title) (str " (\"" title "\")"))
       " in this repository.\n\n"
       "Run `gh pr view " number "` and `gh pr diff " number "` to load the "
       "description and full diff, then give a thorough code review:\n"
       "- a short summary of what the PR does\n"
       "- correctness bugs and edge cases\n"
       "- security or performance concerns\n"
       "- style / consistency with the surrounding code\n"
       "- concrete suggestions with file:line references\n\n"
       "Prioritise the most important findings first."))

(defn- pr-review
  "Open a fresh agent room in the PR's project and seed a review prompt; the
   pending-submit tap fires it once :room/joined arrives (mirrors
   gtd-web-start-task)."
  [st {:keys [cwd number title]}]
  {:state   (-> st
                (assoc :web/route {:page :chat})
                (assoc :web/timeline-window nil)
                (assoc :web/pending-submit {:session-id nil
                                            :text (pr-review-prompt number title)})
                (dissoc :web/pending-room :web/overflow-menu?))
   :effects [[:ws/send {:type :room/join :target "new" :cwd cwd}]]})

(defn- pr-canvas-review
  "Like pr-review, but seed the `/canvas-review pr <number>` command so the
   new room loads the PR's diff via `gh pr diff` and builds the node-based
   review canvas. The canvas-review extension's originator-only
   :canvas-review/open then navigates this client to the Canvas view."
  [st {:keys [cwd number]}]
  {:state   (-> st
                (assoc :web/route {:page :chat})
                (assoc :web/timeline-window nil)
                (assoc :web/pending-submit {:session-id nil
                                            :text (str "/canvas-review pr " number)})
                (dissoc :web/pending-room :web/overflow-menu?))
   :effects [[:ws/send {:type :room/join :target "new" :cwd cwd}]]})

(defn- on-navigate
  "Chained after the base router navigate: sync the PR drill-down state from
   the route and fetch what the destination needs — the list on :pr-list, one
   PR's detail on :pr-detail, and on :pr-diff only when that PR isn't already
   loaded (e.g. arriving from its detail page)."
  [st {:keys [page cwd number]}]
  (case page
    :pr-list
    {:state   (assoc st :web/prs-cwd cwd)
     :effects (when cwd
                [[:app/dispatch {:type :pr/load :cwd cwd}]])}

    (:pr-detail :pr-diff)
    {:state   (assoc st :web/pr-detail-cwd cwd
                        :web/pr-detail-number number)
     :effects (when (and cwd number
                         (or (= page :pr-detail)
                             (not= (:web/pr-detail-number st) number)))
                [[:app/dispatch {:type :pr/detail-load :cwd cwd :number number}]])}

    nil))

(def ^:private handlers
  {:route/navigate on-navigate
   :pr/open              (fn [_st {:keys [cwd]}]
                           {:effects [[:app/dispatch {:type :route/navigate
                                                      :page :pr-list :cwd cwd}]]})
   :pr/load              (fn [st {:keys [cwd]}]
                           {:state (assoc st :web/prs-loading? true
                                             :web/prs-cwd cwd
                                             :web/prs nil
                                             :web/prs-error nil)
                            :effects [[:ws/send {:type :pr/web-list :cwd cwd}]]})
   :pr/refresh           (fn [st _]
                           (let [cwd (:web/prs-cwd st)]
                             {:state (assoc st :web/prs-loading? true)
                              :effects [[:ws/send {:type :pr/web-list :cwd cwd}]]}))
   :pr/web-list-result   (fn [st {:keys [cwd prs me error]}]
                           {:state (assoc st :web/prs prs
                                             :web/prs-me me
                                             :web/prs-error error
                                             :web/prs-cwd cwd
                                             :web/prs-loading? false)})
   :pr/set-filter        (fn [st {:keys [key]}]
                           {:state (update-in st [:web/pr-filters key] not)})
   :pr/select            (fn [_st {:keys [cwd number]}]
                           {:effects [[:app/dispatch {:type :route/navigate
                                                      :page :pr-detail
                                                      :cwd cwd :number number}]]})
   :pr/diff-open         (fn [_st {:keys [cwd number]}]
                           {:effects [[:app/dispatch {:type :route/navigate
                                                      :page :pr-diff
                                                      :cwd cwd :number number}]]})
   :pr/review            pr-review
   :pr/canvas-review     pr-canvas-review
   :pr/detail-load       (fn [st {:keys [cwd number]}]
                           {:state (assoc st :web/pr-detail-loading? true
                                             :web/pr-detail-cwd cwd
                                             :web/pr-detail-number number
                                             :web/pr-detail nil
                                             :web/pr-detail-error nil)
                            :effects [[:ws/send {:type :pr/web-detail
                                                 :cwd cwd :number number}]]})
   :pr/web-detail-result (fn [st {:keys [cwd number pr diff error]}]
                           {:state (assoc st :web/pr-detail (when pr {:pr pr :diff diff})
                                             :web/pr-detail-error error
                                             :web/pr-detail-cwd cwd
                                             :web/pr-detail-number number
                                             :web/pr-detail-loading? false)})})

;; ── Views ────────────────────────────────────────────────────────────────────

(defn- pr-state-badge
  "Colored badge for a PR's state (and draft marker)."
  [state draft?]
  (let [open? (= state "OPEN")]
    (badge/badge {:variant (cond
                             (and open? draft?) :secondary
                             open?              :success
                             (= state "MERGED") :secondary
                             (= state "CLOSED") :danger
                             :else              :outline)
                  :size :sm}
                 (if (and open? draft?)
                   "Draft"
                   (str/capitalize (str/lower-case (or state "")))))))

(defn- pr-filtered
  "Apply the assigned/created toggles. With neither set, show everything; with
   both, show the union (created-by-me OR assigned-to-me)."
  [prs me {:keys [assigned? created?]}]
  (if (and (not assigned?) (not created?))
    prs
    (filterv (fn [pr]
               (or (and created? (= me (:author pr)))
                   (and assigned? (some #(= me %) (:assignees pr)))))
             prs)))

(defn- pr-list-view
  "Roomless pull-requests list for a project (reached from the project view's
   overflow menu). Lists open PRs from `gh`, with toggles to narrow to the
   viewer's own / assigned PRs; a row opens the detail page."
  [state dispatch!]
  (let [cwd      (:web/prs-cwd state)
        prs      (:web/prs state)
        me       (:web/prs-me state)
        error    (:web/prs-error state)
        loading? (:web/prs-loading? state)
        filters  (:web/pr-filters state)
        shown    (pr-filtered prs me filters)]
    [:div {:class ["container"] :replicant/key "pr-list"}
     [:div {:class ["topbar"]}
      (views/nav-group dispatch! (fn [_]
                                   (dispatch! {:type :nav/back
                                               :fallback {:page :home :dir cwd}})))
      [:div {:class ["topbar-title"]} "Pull requests · " (views/shorten-path cwd)]
      [:button {:class ["icon-btn"]
                :title "Refresh"
                :on {:click (fn [_] (dispatch! {:type :pr/refresh}))}}
       (icon/icon {:icon-name :refresh :size :md})]
      (views/overflow-menu dispatch! state)]
     [:div {:class ["home"]}
      [:div {:class ["pr-filters"]}
       (form/form-checkbox
        {:label "Created by me"
         :checked (boolean (:created? filters))
         :on-change (fn [_] (dispatch! {:type :pr/set-filter :key :created?}))})
       (form/form-checkbox
        {:label "Assigned to me"
         :checked (boolean (:assigned? filters))
         :on-change (fn [_] (dispatch! {:type :pr/set-filter :key :assigned?}))})]
      (cond
        (and loading? (nil? prs))
        [:div {:class ["empty-state"]} (views/spinner) [:p "Loading pull requests…"]]

        error
        [:div {:class ["empty-state"]}
         [:p "Could not load pull requests."]
         [:pre {:class ["pr-error"]} error]]

        (empty? shown)
        [:div {:class ["empty-state"]}
         (if (seq prs) "No matching pull requests." "No open pull requests.")]

        :else
        [:div {:class ["pr-list"]}
         (for [pr shown]
           [:button {:class ["pr-row"] :replicant/key (:number pr)
                     :on {:click (fn [_] (dispatch! {:type :pr/select
                                                     :cwd cwd :number (:number pr)}))}}
            [:div {:class ["pr-row-main"]}
             [:span {:class ["pr-row-title"]} (:title pr)]
             [:span {:class ["pr-row-meta"]}
              "#" (:number pr) " · " (:author pr)
              " · " (:head pr) " → " (:base pr)]]
            (pr-state-badge (:state pr) (:draft? pr))])])]]))

(defn- pr-detail-view
  "Roomless PR detail page: metadata header, markdown body, and the unified
   diff rendered read-only with the same +/- coloring + syntax highlighting as
   the chat :diff buffer."
  [state dispatch!]
  (let [cwd      (:web/pr-detail-cwd state)
        number   (:web/pr-detail-number state)
        detail   (:web/pr-detail state)
        error    (:web/pr-detail-error state)
        loading? (:web/pr-detail-loading? state)
        {:keys [pr diff]} detail]
    [:div {:class ["container"] :replicant/key "pr-detail"}
     [:div {:class ["topbar"]}
      (views/nav-group dispatch! (fn [_]
                                   (dispatch! {:type :nav/back
                                               :fallback {:page :pr-list :cwd cwd}})))
      [:div {:class ["topbar-title"]} "PR #" number]
      (views/overflow-menu dispatch! state {:mode :pr-detail :cwd cwd :number number})]
     [:div {:class ["pr-detail-scroll"]}
      (cond
        (and loading? (nil? detail))
        [:div {:class ["empty-state"]} (views/spinner) [:p "Loading pull request…"]]

        error
        [:div {:class ["empty-state"]}
         [:p "Could not load pull request."]
         [:pre {:class ["pr-error"]} error]]

        pr
        (list
         [:div {:class ["pr-detail-head"]}
          [:h2 {:class ["pr-detail-title"]} (:title pr)]
          [:div {:class ["pr-detail-meta"]}
           (pr-state-badge (:state pr) (:draft? pr))
           [:span "#" (:number pr)]
           [:span (:author pr)]
           [:span (:head pr) " → " (:base pr)]
           [:span {:class ["pr-detail-stat" "pr-detail-stat--add"]} "+" (:additions pr)]
           [:span {:class ["pr-detail-stat" "pr-detail-stat--del"]} "−" (:deletions pr)]]
          [:div {:class ["pr-detail-actions"]}
           (button/button
            {:variant :primary :size :sm :icon-left :zap
             :on-click (fn [_] (dispatch! {:type :pr/review
                                          :cwd cwd :number number
                                          :title (:title pr)}))}
            "Review with agent")
           (button/button
            {:variant :outline :size :sm :icon-left :git-branch
             :on-click (fn [_] (dispatch! {:type :pr/canvas-review
                                          :cwd cwd :number number}))}
            "Canvas review")]]
         (when (seq (:body pr))
           [:div {:class ["post-content" "pr-detail-body"]} (md/render (:body pr))])
         [:div {:class ["diff-tab"]}
          (if (str/blank? diff)
            [:div {:class ["empty-state"]} "No diff."]
            (views/diff-rows-view dispatch!
                                  (diff/diff-rows (diff/parse-diff-text diff))
                                  nil nil))]))]]))

(defn- pr-diff-view
  "Focused full-screen unified diff for one PR — reached from the PR detail
   page's overflow menu. Reuses the already-fetched :web/pr-detail diff so big
   PRs can be reviewed without scrolling past the metadata + markdown body."
  [state dispatch!]
  (let [cwd      (:web/pr-detail-cwd state)
        number   (:web/pr-detail-number state)
        detail   (:web/pr-detail state)
        error    (:web/pr-detail-error state)
        loading? (:web/pr-detail-loading? state)
        diff     (:diff detail)]
    [:div {:class ["container"] :replicant/key "pr-diff"}
     [:div {:class ["topbar"]}
      (views/nav-group dispatch! (fn [_]
                                   (dispatch! {:type :nav/back
                                               :fallback {:page :pr-detail
                                                          :cwd cwd :number number}})))
      [:div {:class ["topbar-title"]} "PR #" number " diff"]
      (views/overflow-menu dispatch! state)]
     [:div {:class ["diff-tab"]}
      (cond
        (and loading? (nil? detail))
        [:div {:class ["empty-state"]} (views/spinner) [:p "Loading diff…"]]

        error
        [:div {:class ["empty-state"]}
         [:p "Could not load pull request."]
         [:pre {:class ["pr-error"]} error]]

        (str/blank? diff)
        [:div {:class ["empty-state"]} "No diff."]

        :else
        (views/diff-rows-view dispatch!
                              (diff/diff-rows (diff/parse-diff-text diff))
                              nil nil))]]))

;; ── Route ────────────────────────────────────────────────────────────────────

(defn- parse-route
  "Remaining /pulls/… segments → route map.
     /pulls              → PR list (cwd-less; the list page needs a cwd to load)
     /pulls/:cwd         → PR list for a project
     /pulls/:n/:cwd      → PR detail
     /pulls/:n/diff/:cwd → focused PR diff"
  [segs]
  (let [seg1 (first segs)]
    (if (and seg1 (re-matches #"\d+" seg1))
      (if (= "diff" (second segs))
        {:page :pr-diff
         :number (js/parseInt seg1)
         :cwd (js/decodeURIComponent (str/join "/" (drop 2 segs)))}
        {:page :pr-detail
         :number (js/parseInt seg1)
         :cwd (js/decodeURIComponent (str/join "/" (rest segs)))})
      (cond-> {:page :pr-list}
        seg1 (assoc :cwd (js/decodeURIComponent (str/join "/" segs)))))))

;; ── Extension ────────────────────────────────────────────────────────────────

(def extension
  {:id :github
   :handlers handlers
   :routes {"pulls" {:parse parse-route
                     :path {:pr-list   (fn [{:keys [cwd]}]
                                         (if cwd
                                           (str "/pulls/" (js/encodeURIComponent cwd))
                                           "/pulls"))
                            :pr-detail (fn [{:keys [number cwd]}]
                                         (str "/pulls/" number "/"
                                              (js/encodeURIComponent cwd)))
                            :pr-diff   (fn [{:keys [number cwd]}]
                                         (str "/pulls/" number "/diff/"
                                              (js/encodeURIComponent cwd)))}}}
   :pages {:pr-list   pr-list-view
           :pr-detail pr-detail-view
           :pr-diff   pr-diff-view}
   :nav-items [{:menu :overflow :mode :project :label "Pull requests"
                :icon :message-circle :event {:type :pr/open}}
               {:menu :overflow :mode :pr-detail :label "Diff"
                :icon :file-text :event {:type :pr/diff-open}}]})
