(ns xi.api.store-test
  "xi.api.store — each extension's own key-value entries, on disk under its id."
  (:require [cljs.test :refer [deftest is async]]
            [clojure.string :as str]
            [xi.api.core :as core]
            [xi.api.store :as store]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(defn- with-data-home
  "Run (f) with XDG_DATA_HOME pointed at a fresh tmp dir, passed to `f`;
   restore after. `f` returns a Promise."
  [f done]
  (let [saved (aget js/process.env "XDG_DATA_HOME")
        tmp   (fs/mkdtempSync (node-path/join (os/tmpdir) "xi-api-store-"))]
    (aset js/process.env "XDG_DATA_HOME" tmp)
    (-> (js/Promise.resolve tmp)
        (.then f)
        (.catch (fn [e] (is false (str "unexpected: " (ex-message e)))))
        (.finally (fn []
                    (if saved
                      (aset js/process.env "XDG_DATA_HOME" saved)
                      (js-delete js/process.env "XDG_DATA_HOME"))
                    (done))))))

(defn- ctx-for [id] {:extension id :xi.api/token (core/issue-token! id)})

(def ^:private notes (ctx-for :notes))

(defn- rejection [p]
  (-> p (.then (constantly ::resolved)) (.catch ex-message)))

(deftest values-round-trip-under-the-extension-id
  (async done
    (with-data-home
      (fn [home]
        (let [v {:plan  {:commits [{:subject "x" :items ["h1"]}]}
                 :tags  #{:a :b}
                 :list  '(1 2.5 "three")
                 :id    (uuid "8c5f3b5e-6f1e-4c1a-9b7e-2f0a3c4d5e6f")
                 :when  (js/Date. 0)
                 :text  "quotes \" and \\ and\nnewlines"}]
          (-> (store/put! notes :plan-1 v)
              (.then (fn [r]
                       (is (nil? r))
                       (is (fs/existsSync (node-path/join home "xi" "extensions" "notes" "store" "plan-1.edn"))
                           "stored in the extension's data dir, under its id")
                       (store/get notes "plan-1")))
              (.then (fn [got]
                       (is (= v got) "a keyword and a string name the same entry")
                       (store/keys notes)))
              (.then (fn [ks] (is (= ["plan-1"] ks)))))))
      done)))

(deftest calls-on-a-key-run-in-order
  (async done
    (with-data-home
      (fn [_]
        ;; not awaited: the read still sees the last write before it
        (store/put! notes "n" 1)
        (store/put! notes "n" 2)
        (store/put! notes "n" 3)
        (-> (store/get notes "n")
            (.then (fn [v]
                     (is (= 3 v))
                     (store/put! notes "n" nil)))
            (.then (fn [_] (store/get notes "n")))
            (.then (fn [v]
                     (is (nil? v) "putting nil removes the entry")
                     (store/put! notes "m" :x)))
            (.then (fn [_] (store/delete! notes :m)))
            (.then (fn [_] (store/keys notes)))
            (.then (fn [ks] (is (= [] ks))))))
      done)))

(deftest an-extension-only-sees-its-own-entries
  (async done
    (with-data-home
      (fn [_]
        (let [other (ctx-for :other)]
          (-> (store/put! notes "secret" "mine")
              (.then (fn [_] (store/get other "secret")))
              (.then (fn [v]
                       (is (nil? v))
                       (store/keys other)))
              (.then (fn [ks] (is (= [] ks))))
              (.then (fn [_] (rejection (store/get {:extension :notes} "secret"))))
              (.then (fn [msg]
                       (is (re-find #"not an extension ctx" (str msg))
                           "a ctx without the loader's token is refused"))))))
      done)))

(deftest bad-keys-and-values-are-refused
  (async done
    (with-data-home
      (fn [home]
        (-> (js/Promise.all
             (clj->js (map rejection [(store/put! notes "../escape" 1)
                                      (store/put! notes ".hidden" 1)
                                      (store/put! notes :a/b 1)
                                      (store/get notes "")
                                      (store/put! notes "obj" #js {:a 1})
                                      (store/put! notes "fn" {:f inc})])))
            (.then (fn [msgs]
                     (is (every? #(str/starts-with? (str %) "xi.api.store: ") msgs) (pr-str msgs))
                     (is (not (fs/existsSync (node-path/join home "xi" "extensions" "escape.edn"))))
                     (store/keys notes)))
            (.then (fn [ks] (is (= [] ks) "nothing was written")))))
      done)))