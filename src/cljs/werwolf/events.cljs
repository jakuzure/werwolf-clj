(ns werwolf.events
  "re-frame events. Split from `core` so `views` can reference them without a
   circular require, which is also why the event registry is the natural seam:
   views know event NAMES, not implementations.

   One rule governs this whole layer: **the client stores `:view` and derives
   everything from it.** It does not track who is alive, whose turn it is, or
   which buttons should exist — each of those is a field the server already sent,
   redacted for this player. The client is structurally incapable of knowing more
   than it was told, which is what makes the redaction boundary real rather than
   a convention."
  (:require [re-frame.core :as rf]
            [werwolf.ws :as ws]))

(def initial-db
  {:conn/status :connecting
   :screen      :landing
   :me          nil          ; {:room-id :player-id :host?} from :server/welcome
   :view        nil          ; the server's redacted state — the only game truth
   :chat        []
   :error       nil
   :locale      :de
   :now         (.getTime (js/Date.))
   :form        {:name "" :code "" :text ""}})

(rf/reg-event-db ::init (fn [_ _] initial-db))

(rf/reg-event-db ::connected    (fn [db _] (assoc db :conn/status :connected)))
(rf/reg-event-db ::disconnected (fn [db _] (assoc db :conn/status :disconnected)))

(rf/reg-event-db ::tick (fn [db _] (assoc db :now (.getTime (js/Date.)))))

(rf/reg-event-db ::form-set (fn [db [_ k v]] (assoc-in db [:form k] v)))

(rf/reg-event-db ::dismiss-error (fn [db _] (assoc db :error nil)))

;; ------------------------------------------------------------------ outbound ---

(rf/reg-event-fx
 ::create-room
 (fn [{:keys [db]} _]
   (let [nm (.trim (get-in db [:form :name] ""))]
     (when (seq nm)
       {::ws/send {:type :client/create :name nm :locale (:locale db :de)}}))))

(rf/reg-event-fx
 ::join-room
 (fn [{:keys [db]} _]
   (let [nm   (.trim (get-in db [:form :name] ""))
         code (.toUpperCase (.trim (get-in db [:form :code] "")))]
     (when (and (seq nm) (seq code))
       {::ws/send {:type :client/join :room-id code :name nm
                   :locale (:locale db :de)}}))))

(rf/reg-event-fx
 ::act
 ;; Every game action funnels through here, which is why the UI needs no
 ;; per-action code: an action plus an optional target IS the whole vocabulary.
 (fn [_ [_ action target]]
   {::ws/send {:type :client/command
               :cmd (cond-> {:type action}
                      (some? target) (assoc :target target))}}))

(rf/reg-event-fx
 ::act-pair
 ;; Two targets in one command. Amor is the only role that needs it today, but
 ;; the UI reaches here because the view said the action's arity was 2 — not
 ;; because anything in the client knows what Amor is.
 (fn [_ [_ action a b]]
   {::ws/send {:type :client/command :cmd {:type action :a a :b b}}}))

(rf/reg-event-fx
 ::set-locale
 (fn [_ [_ locale]]
   {::ws/send {:type :client/command :cmd {:type :set-locale :locale locale}}}))

(rf/reg-event-fx
 ::send-chat
 (fn [{:keys [db]} _]
   (let [text (.trim (get-in db [:form :text] ""))]
     (when (seq text)
       {:db (assoc-in db [:form :text] "")
        ::ws/send {:type :client/chat :text text}}))))

(rf/reg-event-fx
 ::leave
 (fn [{:keys [db]} _]
   {:db (merge db (select-keys initial-db [:screen :me :view :chat :error]))
    ::ws/forget-seat true
    ::ws/send {:type :client/command :cmd {:type :leave}}}))

;; ------------------------------------------------------------------- inbound ---

(defmulti handle-server
  "One method per server message type. Anything unknown is ignored rather than
   throwing: a newer server may say things this client has never heard of, and
   the right response to that is to keep playing.

   Public and pure — db in, db out — because this is the whole of the client's
   state logic and it should be testable without a browser."
  (fn [_db msg] (:type msg)))

(defmethod handle-server :default [db _] db)

(defmethod handle-server :server/hello [db msg]
  (assoc db :server/protocol (:protocol msg)))

(defmethod handle-server :server/pong [db _] db)

(defmethod handle-server :server/welcome [db {:keys [room-id player-id host?]}]
  (assoc db
         :screen :room
         :error nil
         :me {:room-id room-id :player-id player-id :host? host?}))

(defmethod handle-server :room/view [db {:keys [view] n :seq}]
  ;; Views may legitimately arrive out of order, so an older one must never
  ;; overwrite a newer. This is precisely what :seq is for.
  (if (and n (:view/seq db) (< n (:view/seq db)))
    db
    (assoc db :view view :view/seq n :screen :room)))

(defmethod handle-server :room/chat [db msg]
  (update db :chat (fn [c] (vec (take-last 300 (conj (vec c) msg))))))

(defmethod handle-server :room/chat-history [db {:keys [messages]}]
  (assoc db :chat (vec messages)))

(def fatal-errors
  "Errors meaning the stored credential is worthless. Anything else is a
   rejected move: worth showing, not worth throwing the session away for."
  #{:seat-gone :expired :bad-signature :wrong-room})

(defmethod handle-server :server/error [db {:keys [code]}]
  (cond-> (assoc db :error code)
    (fatal-errors code) (assoc :screen :landing :me nil :view nil :chat [])))

(rf/reg-event-fx
 ::server-message
 (fn [{:keys [db]} [_ msg]]
   (cond-> {:db (handle-server db msg)}
     ;; Persisting on every welcome matters: a resume hands back a REFRESHED
     ;; token, and keeping the old one would expire the session at its original
     ;; time however long the game runs.
     (= :server/welcome (:type msg))
     (assoc ::ws/save-seat (select-keys msg [:room-id :token]))

     (fatal-errors (:code msg))
     (assoc ::ws/forget-seat true))))
