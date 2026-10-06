(ns xi.api.dialog
  "Dialogs for user extensions: ask the user something in a room and get the
   answer back as a Promise.

     (confirm ctx \"Delete the cache?\")                    → true / false
     (select  ctx \"Which tab?\" [{:label \"a\" :value 1} …]) → the value, nil on dismiss
     (alert   ctx \"Done.\")                                → nil once dismissed
     (form    ctx \"Commit\" [{:name \"msg\" :label \"Message\"}]) → {\"msg\" …}, nil on cancel

   Each takes an optional trailing opts map with :room-id. Without it the
   dialog opens in the ctx's room (tool calls carry one; an :fx gets its room
   from its payload, so pass it). The dialog goes through the app's own dialog
   system (xi.ext.core/create-dialogs) and its text starts with
   `[extension <id>]`, so the user always knows it isn't the agent asking.

   Opening a dialog changes nothing by itself, so there is no rules request.
   The guard on the extension's own dispatch! is not involved: the dialog is
   opened with the host's dispatch, which the loader installs in xi.api.core
   (`set-dialog-host!`). It lives there because the sandbox gets every public
   var of this namespace, and must not be able to swap the host.
   Like every other dialog it waits in the room until someone answers, also
   while no client is connected; only prompt mode, where no client can ever
   attach, answers at once with the safe default (false / nil)."
  (:require [clojure.string :as str]
            [xi.api.core :as core]))

(defn- labelled [id text]
  (str "[extension " (name id) "] " text))

(defn- open!
  "Open `dialog` for the extension behind `ctx` → Promise of the answer."
  [ctx opts dialog]
  (-> (js/Promise.resolve nil)
      (.then
       (fn [_]
         (let [id      (core/caller ctx)
               room-id (or (:room-id opts) (:room-id ctx))
               {:keys [ask! dispatch! get-state]} (core/dialog-host)]
           (cond
             (str/blank? (:message dialog))
             (throw (ex-info "xi.api.dialog: the dialog needs a message" {}))

             (nil? ask!)
             (throw (ex-info "xi.api.dialog: dialogs aren't available in this process" {}))

             (nil? room-id)
             (throw (ex-info "xi.api.dialog: no room — pass {:room-id …} (an :fx gets it from its payload)" {}))

             (nil? (get-in (get-state) [:rooms room-id]))
             (throw (ex-info (str "xi.api.dialog: unknown room " room-id) {:room-id room-id}))

             :else
             (ask! {:dispatch! dispatch! :state (get-state)}
                   {:room-id room-id
                    :dialog  (update dialog :message #(labelled id %))})))))))

(defn- option [o]
  (if (map? o)
    (select-keys o [:label :value])
    {:label (str o) :value o}))

(defn confirm
  "Yes / no → Promise<boolean>."
  ([ctx message] (confirm ctx message nil))
  ([ctx message opts]
   (-> (open! ctx opts {:type :confirm :message message :options [:yes :no]})
       (.then boolean))))

(defn select
  "Pick one of `options` → Promise of its :value, nil when dismissed. An option
   is {:label :value} or a plain value (shown with str)."
  ([ctx message options] (select ctx message options nil))
  ([ctx message options opts]
   (if (empty? options)
     (js/Promise.reject (ex-info "xi.api.dialog: select needs options" {}))
     (open! ctx opts {:type :select :message message :options (mapv option options)}))))

(defn alert
  "A message with an OK button → Promise<nil> once dismissed."
  ([ctx message] (alert ctx message nil))
  ([ctx message opts]
   (-> (open! ctx opts {:type :alert :message message})
       (.then (constantly nil)))))

(defn form
  "Text fields (`[{:name … :label … :value …}]`; :label defaults to the
   humanized name, :value prefills) → Promise of {name → text}, nil when
   cancelled."
  ([ctx message fields] (form ctx message fields nil))
  ([ctx message fields opts]
   (if (empty? fields)
     (js/Promise.reject (ex-info "xi.api.dialog: form needs fields" {}))
     (open! ctx opts {:type   :form
                      :message message
                      :fields (mapv #(select-keys % [:name :label :value]) fields)}))))
