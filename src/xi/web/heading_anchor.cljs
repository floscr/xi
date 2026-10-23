(ns xi.web.heading-anchor
  "Scrolling rendered markdown to the heading a #fragment names, by GitHub's
   anchor slugs (xi.url/heading-slugs). Headings carry no ids (a file can be
   on screen twice), so a fragment is matched against their text. Used by
   xi.web.views/open-linked-file! and the file-view extension's web half."
  (:require [clojure.string :as str]
            [xi.url :as url]))

(def ^:private gap-px 12)

;; A linked file arrives over the WS; stop waiting for its heading after this.
(def ^:private wait-ms 3000)

(defn- find-heading [^js root fragment]
  (let [hs   (array-seq (.querySelectorAll root "h1, h2, h3, h4, h5, h6"))
        want (str/lower-case fragment)]
    (some (fn [[h slug]] (when (= want slug) h))
          (map vector hs (url/heading-slugs (map #(.-textContent ^js %) hs))))))

(defn- scroll-parent [^js el]
  (loop [^js p (.-parentElement el)]
    (when p
      (if (and (#{"auto" "scroll"} (.-overflowY (js/getComputedStyle p)))
               (> (.-scrollHeight p) (.-clientHeight p)))
        p
        (recur (.-parentElement p))))))

(defn- scroll-to! [^js heading]
  (if-let [^js sc (scroll-parent heading)]
    (set! (.-scrollTop sc)
          (+ (.-scrollTop sc)
             (- (.-top (.getBoundingClientRect heading))
                (.-top (.getBoundingClientRect sc))
                gap-px)))
    (.scrollIntoView heading)))

(defn reveal!
  "Scroll the heading under `root` that `fragment` names into view; true when
   there was one."
  [^js root fragment]
  (when-let [h (and root fragment (find-heading root fragment))]
    (scroll-to! h)
    true))

(defn- file-tab [path]
  (some #(when (= path (.getAttribute ^js % "data-file-path")) %)
        (array-seq (.querySelectorAll js/document ".file-tab"))))

(defn reveal-in-file!
  "Scroll the file buffer for `path` (a :file/open the server answers) to the
   heading `fragment` names. A tab already showing that file is rebuilt when
   the fresh read lands, so each new tab node within the wait is revealed
   once; scrolling a revealed one is left to the user."
  [path fragment]
  (let [deadline (+ (js/performance.now) wait-ms)]
    ((fn attempt [revealed]
       (let [tab      (file-tab path)
             revealed (if (and tab (not (identical? tab revealed)) (reveal! tab fragment))
                        tab
                        revealed)]
         (when (< (js/performance.now) deadline)
           (js/requestAnimationFrame #(attempt revealed)))))
     nil)))
