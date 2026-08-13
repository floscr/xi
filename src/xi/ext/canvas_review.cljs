(ns xi.ext.canvas-review
  "Canvas review extension — an experimental node-based review canvas.

   `/canvas-review [source]` loads a diff (default: this session's uncommitted
   edits, like /diff session-git), installs it as the room's canvas-review
   state, opens the web Canvas view, and seeds an agent turn instructing the
   model to lay the changes out on a spatial canvas: one node per meaningful
   changed code block, comment/prose nodes explaining what and why, labeled
   connections describing how blocks relate, and a targeted walkthrough plan
   (where to look first) the human steps through with Next/Prev.

   The model drives the canvas through five tools (canvas_review_*). Tools
   only receive {:cwd}, so they can't touch app state directly — instead the
   :tool-gate intercepts each call, dispatches a pure state-mutating event, and
   short-circuits with {:intercepted true :result …} (the todo-intercept
   pattern). Canvas state lives room-scoped at [:rooms rid :ext :canvas-review]
   and mirrors to every client, so the canvas builds up live as the model works.

   Node/server half. The browser half (xi.ext.canvas-review.web) renders the
   canvas page."
  (:require [clojure.string :as str]
            [xi.core.state :as state]
            [xi.diff :as diff]
            [xi.ext.canvas-review.handlers :as h]
            [xi.ext.diff.git :as git]
            [xi.fx :as fx]
            [xi.session :as session]))

(def ^:private ext-id :canvas-review)

;; ── Diff loading (impure edge, reuses the diff extension's git plumbing) ──────

(defn- gh-pr-diff
  "Native unified diff for a GitHub PR via `gh pr diff <number>`, run in cwd.
   Returns the diff string, or nil on failure. Used by the `pr <n>` source the
   web PR page seeds from its \"Canvas review\" button."
  [cwd number]
  (try
    (let [proc (js/Bun.spawnSync (into-array ["gh" "pr" "diff" (str number)])
                                 #js {:cwd cwd})]
      (when (zero? (.-exitCode proc))
        (.toString (.-stdout proc) "utf-8")))
    (catch :default _e nil)))

(defn- load-diff
  "Resolve a /canvas-review source to {:title :text}. Defaults (nil or
   \"session-git\") to this session's edited files diffed against HEAD — the
   still-uncommitted work — matching /diff session-git."
  [cwd room args]
  (let [arg (some-> args str/trim not-empty)]
    (if-let [[_ number] (some->> arg (re-matches #"pr\s+(\S+)"))]
      {:title (str "PR #" number) :text (gh-pr-diff cwd number)}
      (case arg
        (nil "session-git")
        (let [files (fx/session-edited-files room cwd)]
          {:title "Session Changes (uncommitted)"
           :text  (when (seq files) (git/session-diff-text cwd "HEAD" files))})

        "staged"   {:title "Staged Changes"   :text (:ok (git/git-diff-out cwd ["diff" "--staged"]))}
        "unstaged" {:title "Unstaged Changes" :text (:ok (git/git-diff-out cwd ["diff"]))}
        "git"      {:title "All Git Changes"  :text (git/all-git-changes-text cwd)}

        (if (git/git-ref? cwd arg)
          {:title (str "Diff: " arg) :text (:ok (git/diff-against-ref cwd arg))}
          {:title (str "Diff: " arg)
           :text  (:ok (git/git-diff-out cwd (into ["diff"] (str/split arg #"\s+"))))})))))

;; ── Seed prompt ──────────────────────────────────────────────────────────────

(defn- annotate-diff
  "Render the diff as a line-numbered listing so the model can cite precise
   `file` + `start_line`/`end_line` (new-side) ranges when adding code blocks.
   Deletions show their old-side number in the left gutter."
  [text]
  (->> (diff/parse-diff-text text)
       (map (fn [{:keys [filename hunks]}]
              (str "### " filename "\n"
                   (->> hunks
                        (mapcat :lines)
                        (map (fn [{:keys [type text old-line new-line]}]
                               (let [n    (or new-line old-line)
                                     sign (case type :add "+" :delete "-" " ")]
                                 (str (when n (str n)) "\t" sign text))))
                        (str/join "\n")))))
       (str/join "\n\n")))

(def ^:private MAX_DIFF_CHARS 24000)

(defn- build-seed-prompt [title text]
  (let [annotated (annotate-diff text)
        annotated (if (> (count annotated) MAX_DIFF_CHARS)
                    (str (subs annotated 0 MAX_DIFF_CHARS)
                         "\n\n… (diff truncated — cover the most important changes)")
                    annotated)]
    (str
     "You are building a **node-based review canvas** to help a human understand "
     "this diff (\"" title "\") as fast and deeply as possible. Use the "
     "`canvas_review_*` tools to lay the changes out spatially, then produce a "
     "guided walkthrough.\n\n"
     "Workflow:\n"
     "1. Read the diff below and identify the meaningful changed regions.\n"
     "2. For each one, call `canvas_review_add_block` with the `file` and the "
     "new-side `start_line`/`end_line` (the numbers in the left gutter). Give it "
     "a short memorable `id` and a `label`.\n"
     "3. Add `canvas_review_add_comment` nodes explaining what a block does and, "
     "crucially, *why* it changed and any subtle correctness/edge-case concerns. "
     "Use `attach_to` to tie a comment to its block.\n"
     "4. Use `canvas_review_add_prose` for larger narrative: the overall shape of "
     "the change, cross-cutting concerns, or background a reviewer needs.\n"
     "5. Use `canvas_review_connect` to draw labeled relationships between nodes "
     "(e.g. \"calls\", \"new invariant relied on here\", \"must stay in sync with\").\n"
     "6. Finish with `canvas_review_set_plan`: an ordered walkthrough of where to "
     "look first and why, referencing the node ids. This is what the human steps "
     "through with Next/Prev — order it so understanding builds naturally "
     "(entry points and the core change first, details and edge cases later).\n\n"
     "Optimize purely for human understanding — be concrete, cite the code, and "
     "surface the non-obvious. Keep individual notes tight.\n\n"
     "Annotated diff (left gutter = line number, then +/-/space):\n\n"
     "```\n" annotated "\n```")))

;; ── Command → effect ─────────────────────────────────────────────────────────

(defn- cmd-canvas-review
  "/canvas-review [source] — pure; defers the git read to :canvas-review/start."
  [_st {:keys [room-id args client-id]}]
  {:effects [[:canvas-review/start (cond-> {:room-id room-id :args args}
                                     client-id (assoc :client-id client-id))]]})

(defn- start-fx
  "Load the diff, install it, open the canvas on the originating web client,
   and seed the review turn."
  [{:keys [dispatch! get-state]} {:keys [room-id args client-id]}]
  (let [room (state/get-room (get-state) room-id)
        cwd  (or (:cwd room) (.cwd js/process))
        {:keys [title text]} (load-diff cwd room args)
        ;; One deterministic label, reused for the session name and the
        ;; sub-agent entry so both read identically ("Canvas review: PR #333").
        label (str "Canvas review: " title)]
    (if (str/blank? text)
      (dispatch! {:type :ui/status :room-id room-id
                  :text "No changes to review."})
      (do
        ;; Broadcast: install the diff + reset the canvas for every client, and
        ;; name the (still-unnamed) session so the sidebar shows the review.
        (dispatch! {:type :canvas-review/load :room-id room-id
                    :source (or (some-> args str/trim not-empty) "session-git")
                    :title title :text text :name label})
        ;; Originator-only: tell the web client that ran the command to open
        ;; the Canvas view (a no-op on the server + TUI).
        (dispatch! (cond-> {:type :canvas-review/open :room-id room-id
                            :session-id (get-in room [:session :id])}
                     client-id (assoc :client-id client-id)))
        ;; Kick off a background sub-agent that builds the canvas — the
        ;; canvas_review_* tools act on THIS room (the sub-agent's gate uses
        ;; the parent room-id), so the canvas fills live while the verbose
        ;; block-by-block work stays out of the parent chat. The user ran
        ;; /canvas-review, so this bypasses the spawn confirmation.
        (dispatch! {:type :subagent/spawn :room-id room-id
                    :label label
                    :task  (str "Build a review canvas for " title)
                    :prompt (build-seed-prompt title text)})))))

;; ── Tools (advertised to the model, executed via the gate) ───────────────────

(def ^:private tool-defs
  [{:name "canvas_review_add_block"
    :description "Add a code-block node to the review canvas, referencing a changed region of the diff by file and new-side line range."
    :input_schema {:type "object"
                   :properties {:file       {:type "string" :description "File path as shown in the diff"}
                                :start_line {:type "integer" :description "First line of the region (new-side gutter number)"}
                                :end_line   {:type "integer" :description "Last line of the region (new-side gutter number)"}
                                :id         {:type "string" :description "Short memorable id to reference in connect/plan (optional; generated if omitted)"}
                                :label      {:type "string" :description "Short title for the block"}}
                   :required ["file" "start_line" "end_line"]}}
   {:name "canvas_review_add_comment"
    :description "Add a short comment/explanation node. Use attach_to to connect it to a block it explains."
    :input_schema {:type "object"
                   :properties {:text      {:type "string" :description "The comment (markdown, keep it tight)"}
                                :attach_to {:type "string" :description "Node id this comment explains (optional; auto-connects)"}
                                :id        {:type "string" :description "Short memorable id (optional)"}}
                   :required ["text"]}}
   {:name "canvas_review_add_prose"
    :description "Add a larger markdown prose node for narrative/background that spans the whole change."
    :input_schema {:type "object"
                   :properties {:title    {:type "string" :description "Heading for the prose node"}
                                :markdown {:type "string" :description "Markdown body"}
                                :id       {:type "string" :description "Short memorable id (optional)"}}
                   :required ["markdown"]}}
   {:name "canvas_review_connect"
    :description "Draw a labeled connection between two nodes describing how they relate."
    :input_schema {:type "object"
                   :properties {:from  {:type "string" :description "Source node id"}
                                :to    {:type "string" :description "Target node id"}
                                :label {:type "string" :description "How they relate, e.g. \"calls\", \"must stay in sync with\""}
                                :kind  {:type "string" :description "Relationship kind (optional): relates|calls|depends|explains"}}
                   :required ["from" "to"]}}
   {:name "canvas_review_highlight"
    :description "Emphasize specific lines inside an existing code-block node, drawing the reader's eye to the exact lines that matter. Pass the block id and the new-side line numbers."
    :input_schema {:type "object"
                   :properties {:id    {:type "string" :description "The code-block node id to highlight lines in"}
                                :lines {:type "array" :items {:type "integer"}
                                        :description "New-side line numbers to highlight"}}
                   :required ["id" "lines"]}}
   {:name "canvas_review_set_plan"
    :description "Set the ordered walkthrough — where the human should look first and why. Drives the Next/Prev stepper."
    :input_schema {:type "object"
                   :properties {:steps {:type "array"
                                        :description "Ordered steps"
                                        :items {:type "object"
                                                :properties {:node_id {:type "string" :description "Node id to focus"}
                                                             :title   {:type "string" :description "Step title"}
                                                             :note    {:type "string" :description "Why to look here / what to notice"}}
                                                :required ["node_id"]}}}
                   :required ["steps"]}}])

(def ^:private tool-names (set (map :name tool-defs)))

(defn- gen-id [prefix]
  (str prefix (.toString (js/Math.floor (* (js/Math.random) 1e9)) 36)))

(defn- safe-kind
  "Coerce a free-form edge `kind` into an EDN-safe keyword: lowercased with any
   run of non-alphanumeric characters collapsed to a single hyphen. The model
   often passes multi-word kinds (\"shared invariant\", \"consumed by\") that a
   bare `keyword` call would turn into a keyword CONTAINING A SPACE — which
   `pr-str` writes to the canvas sidecar unescaped (`:shared invariant`),
   yielding EDN that no longer reads back (an odd-form map literal). That silent
   parse failure is why a completed review resumed to a permanently empty canvas."
  [s]
  (some-> s
          str/trim
          str/lower-case
          (str/replace #"[^a-z0-9]+" "-")
          (str/replace #"^-+|-+$" "")
          not-empty
          keyword))

(defn- ok-result [text]
  {:intercepted true :result {:content [{:type "text" :text text}]}})

(defn- handle-tool
  "Turn a canvas_review_* call into state-mutating dispatches and a result."
  [dispatch! room-id name args]
  (case name
    "canvas_review_add_block"
    (let [id (or (not-empty (:id args)) (gen-id "b"))]
      (dispatch! {:type :canvas-review/add-node :room-id room-id
                  :node {:id id :kind :code
                         :file (:file args)
                         :start (:start_line args) :end (:end_line args)
                         :label (:label args)}})
      (ok-result (str "Added block node '" id "' → " (:file args)
                      ":" (:start_line args) "-" (:end_line args))))

    "canvas_review_add_comment"
    (let [id (or (not-empty (:id args)) (gen-id "c"))]
      (dispatch! {:type :canvas-review/add-node :room-id room-id
                  :node {:id id :kind :comment :text (:text args)}})
      (when-let [target (not-empty (:attach_to args))]
        (dispatch! {:type :canvas-review/add-edge :room-id room-id
                    :edge {:id (gen-id "e") :from id :to target
                           :label "explains" :kind :explains}}))
      (ok-result (str "Added comment node '" id "'"
                      (when (:attach_to args) (str " → " (:attach_to args))))))

    "canvas_review_add_prose"
    (let [id (or (not-empty (:id args)) (gen-id "p"))]
      (dispatch! {:type :canvas-review/add-node :room-id room-id
                  :node {:id id :kind :prose :title (:title args) :markdown (:markdown args)}})
      (ok-result (str "Added prose node '" id "'")))

    "canvas_review_connect"
    (let [id (gen-id "e")]
      (dispatch! {:type :canvas-review/add-edge :room-id room-id
                  :edge {:id id :from (:from args) :to (:to args)
                         :label (:label args)
                         :kind (or (safe-kind (:kind args)) :relates)}})
      (ok-result (str "Connected " (:from args) " → " (:to args)
                      (when (:label args) (str " (" (:label args) ")")))))

    "canvas_review_highlight"
    (let [lines (vec (:lines args))]
      (dispatch! {:type :canvas-review/highlight :room-id room-id
                  :id (:id args) :lines lines})
      (ok-result (str "Highlighted " (count lines) " line(s) in '" (:id args) "'")))

    "canvas_review_set_plan"
    (let [plan (mapv (fn [s] {:node (:node_id s) :title (:title s) :note (:note s)})
                     (:steps args))]
      (dispatch! {:type :canvas-review/set-plan :room-id room-id :plan plan})
      (ok-result (str "Set walkthrough plan (" (count plan) " steps).")))

    nil))

(defn- tool-gate
  "Intercept canvas_review_* calls: mutate canvas state, short-circuit with a
   result. Everything else passes through."
  [tool-call {:keys [dispatch! room-id]}]
  (let [{:keys [name arguments]} tool-call]
    (if (tool-names name)
      (or (handle-tool dispatch! room-id name arguments)
          tool-call)
      tool-call)))

;; ── Persistence (node-only) ──────────────────────────────────────────────────
;; Canvas state is room-scoped runtime state that would die with the room when
;; it's reaped. We persist it to a per-session EDN sidecar on every mutation and
;; rehydrate it when a session resumes from disk, so a reload / server restart
;; brings the canvas (and its Canvas tab) back. Only the server half wires this;
;; the web half keeps the bare (non-persisting) handlers.

(defn- with-persist
  "Wrap a canvas mutation handler so it also emits a persist effect after the
   state change. Skips persisting when the handler no-ops (returns nil)."
  [handler]
  (fn [st {:keys [room-id] :as ev}]
    (when-let [result (handler st ev)]
      (update result :effects (fnil conj [])
              [:canvas-review/persist {:room-id room-id}]))))

(defn- on-session-resumed
  "Chained after the core :session/resumed handler — rehydrate this session's
   persisted canvas (if any) from disk."
  [_st {:keys [room-id]}]
  {:effects [[:canvas-review/rehydrate {:room-id room-id}]]})

(def ^:private node-handlers
  "Server-side handler set: the shared canvas handlers with the mutating ones
   wrapped to persist, plus the resume rehydration hook."
  (merge h/handlers
         {:canvas-review/load      (with-persist h/load-handler)
          :canvas-review/add-node  (with-persist h/add-node-handler)
          :canvas-review/add-edge  (with-persist h/add-edge-handler)
          :canvas-review/set-plan  (with-persist h/set-plan-handler)
          :canvas-review/move-node (with-persist h/move-node-handler)
          :canvas-review/highlight (with-persist h/highlight-handler)
          :session/resumed         on-session-resumed}))

(defn- persist-fx
  "Write the room's current canvas to its session sidecar. A review driven only
   by a sub-agent never gets a :provider-session-id (the sub-agent runs in a
   throwaway config dir), so the core :session/sync never writes the session's
   metadata to disk — the room, its sidebar card, and this canvas would all
   vanish when the room reaps. Persist the metadata alongside the canvas so the
   review survives as a real, resumable session. Once a normal turn gives the
   session a provider-session-id, :session/sync owns the metadata and we leave
   it alone."
  [{:keys [get-state]} {:keys [room-id]}]
  (let [st      (get-state)
        session (get-in st [:rooms room-id :session])
        canvas  (get-in st [:rooms room-id :ext ext-id])]
    (when (:id session)
      (session/save-canvas! session canvas)
      (when-not (:provider-session-id session)
        (session/save-session! (fx/->disk-session session))))))

(defn- rehydrate-fx
  "Load a persisted canvas from disk and install it into the resumed room."
  [{:keys [dispatch! get-state]} {:keys [room-id]}]
  (let [session (get-in (get-state) [:rooms room-id :session])]
    (when-let [canvas (session/load-canvas session)]
      (dispatch! {:type :canvas-review/hydrate :room-id room-id :canvas canvas}))))

;; ── Extension ────────────────────────────────────────────────────────────────

(def extension
  {:id               ext-id
   :init             {:room {:nodes {} :edges {} :plan []}}
   :commands         [{:name "canvas-review"
                       :description "Build a node-based review canvas for a diff (default: this session's uncommitted edits)"
                       :handler cmd-canvas-review
                       :subcommands [{:name "session-git" :description "This session's uncommitted edits (default)"}
                                     {:name "staged"      :description "Staged changes"}
                                     {:name "unstaged"    :description "Unstaged changes"}
                                     {:name "git"         :description "All git changes"}]}]
   :fx               {:canvas-review/start     start-fx
                      :canvas-review/persist   persist-fx
                      :canvas-review/rehydrate rehydrate-fx}
   :handlers         node-handlers
   ;; :canvas-review/open only tells the originating web client to navigate to
   ;; the Canvas view — it must not flip other clients or reach the server as
   ;; state, so it rides originator-only with no server handler.
   :originator-only  #{:canvas-review/open}
   ;; Loading a review names the (unnamed) session; a fresh lobby fan-out lets
   ;; every client's recent-sessions sidebar pick up the new :session-name.
   :lobby-relevant   #{:canvas-review/load}
   :tool-definitions tool-defs
   :tool-gate        tool-gate})
