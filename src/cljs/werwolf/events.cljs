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

;; The voice effects are named by LITERAL keyword rather than through an alias,
;; and werwolf.audio is deliberately NOT required here.
;;
;; That namespace reaches livekit-client through `shadow.lazy`, whose `loadable`
;; needs a module to resolve against — which exists in the :app build and cannot
;; in :test-node, a target with no modules at all. Requiring it from here dragged
;; the whole chain into the test build and broke it.
;;
;; re-frame identifies effects by keyword, so dispatching one needs no reference
;; to the namespace that registers it. `werwolf.core` — the browser entry point,
;; and the only place a browser is guaranteed — does the requiring. Keep these
;; keywords in step with the `rf/reg-fx` calls in werwolf.audio; `check:app` in
;; bb.edn compiles that namespace so a rename cannot pass the suite unnoticed.

(def default-locale
  "German, unconditionally.

   The browser's language used to decide this, which sounds friendlier than it
   played: this is a German group's game, the terminology is Düsterwald's, and
   a phone set to English opened the whole table in the translation. Switching
   is one tap in the header and the choice is then per-player game state, so
   the cost of guessing wrong in this direction is a tap — and in the other
   direction it was a room reading the wrong language by default."
  :de)

(def initial-db
  {:conn/status :connecting
   :screen      :landing
   :me          nil          ; {:room-id :player-id :host?} from :server/welcome
   :view        nil          ; the server's redacted state — the only game truth
   ;; Which view we last accepted, so an out-of-order frame cannot overwrite a
   ;; newer one. Listed here — and therefore reset on leave — because it is
   ;; PER-ROOM: a new room starts its own sequence, and a stale high-water mark
   ;; from the last game would silently drop every view of the next one.
   :view/seq    nil
   :chat        []
   :rooms       []          ; joinable lobbies, from :server/rooms
   :error       nil
   ;; Only covers the landing screen. Once seated, the server's per-player copy
   ;; wins — see ::set-locale and the ::locale subscription.
   :locale      default-locale
   :now         (.getTime (js/Date.))
   :form        {:name "" :code "" :text ""}
   ;; Filled in by :server/media once seated. Reset on leave, so a credential for
   ;; the last room is never carried into the next one.
   :audio       nil})

(rf/reg-event-db ::init (fn [_ _] initial-db))

(rf/reg-event-fx
 ::connected
 ;; Ask for the room list as soon as there is a socket to ask on. A landing
 ;; screen that only fills in after you tap refresh looks like there are no
 ;; games, which is the one thing it exists to answer.
 (fn [{:keys [db]} _]
   {:db (assoc db :conn/status :connected)
    ::ws/send {:type :client/rooms}}))
(rf/reg-event-db ::disconnected (fn [db _] (assoc db :conn/status :disconnected)))

(rf/reg-event-db ::tick (fn [db _] (assoc db :now (.getTime (js/Date.)))))

;; Two ways to write a form field, and the difference matters to the input.
;;
;; ::form-set is what the input itself dispatches as you type. It must NOT bump
;; the generation counter: the whole point is that the element already shows `v`,
;; and app-db is only catching up.
;;
;; ::form-put is a write from anywhere else — filling in a code you tapped,
;; clearing the chat box after sending. It bumps the counter, which is the signal
;; a `field` needs in order to drop its local draft and show what it was handed.
;; Without it, clearing :text after a send left the sent message sitting in the
;; box, because the draft outranks app-db while you are typing.
(rf/reg-event-db ::form-set (fn [db [_ k v]] (assoc-in db [:form k] v)))

(defn form-put
  "Programmatic write: set the value AND bump the field's generation."
  [db k v]
  (-> db
      (assoc-in [:form k] v)
      (update-in [:form/gen k] (fnil inc 0))))

(rf/reg-event-db ::form-put (fn [db [_ k v]] (form-put db k v)))

(rf/reg-event-db ::dismiss-error (fn [db _] (assoc db :error nil)))

(rf/reg-event-db ::toggle-log (fn [db _] (update db :log/show-all? not)))

;; ------------------------------------------------------------------ outbound ---

;; Both of these used to return nil when a field was empty, which is a silent
;; no-op: you tap the button, nothing happens, and nothing says why. On a phone
;; that is indistinguishable from a broken button — and it was reported as one.
;; Say what is missing instead.

;; Named and public rather than anonymous, so the guard clauses are testable
;; without a browser — the same reason `handle-server` is.

(defn create-room-fx [{:keys [db]} _]
  (let [nm (.trim (get-in db [:form :name] ""))]
    (if (empty? nm)
      {:db (assoc db :error :name-required)}
      {:db (assoc db :error nil)
       ::ws/send {:type :client/create :name nm :locale (:locale db :de)}})))

(defn join-room-fx [{:keys [db]} _]
  (let [nm   (.trim (get-in db [:form :name] ""))
        code (.toUpperCase (.trim (get-in db [:form :code] "")))]
    (cond
      (empty? nm)   {:db (assoc db :error :name-required)}
      (empty? code) {:db (assoc db :error :code-required)}
      :else {:db (assoc db :error nil)
             ::ws/send {:type :client/join :room-id code :name nm
                        :locale (:locale db :de)}})))

(defn join-listed-fx
  "Join a room picked off the landing screen's list. Same guard as typing the
   code by hand — the name is still required, and saying so beats a tap that
   appears to do nothing."
  [{:keys [db]} [_ code]]
  (let [nm (.trim (get-in db [:form :name] ""))]
    (if (empty? nm)
      {:db (assoc db :error :name-required)}
      ;; form-put so the code field actually shows what you tapped, even if you
      ;; had started typing a different one by hand.
      {:db (-> db (assoc :error nil) (form-put :code code))
       ::ws/send {:type :client/join :room-id code :name nm
                  :locale (:locale db :de)}})))

(rf/reg-event-fx ::create-room create-room-fx)
(rf/reg-event-fx ::join-room   join-room-fx)
(rf/reg-event-fx ::join-listed join-listed-fx)

(rf/reg-event-fx
 ::refresh-rooms
 ;; Pull, not push. The server answers the asker alone, so a room list can
 ;; never become a side channel into a game somebody is not in.
 (fn [_ _] {::ws/send {:type :client/rooms}}))

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
 ::act-with
 ;; An action plus whatever keys it needs, merged over `{:type action}`. The GM
 ;; overrides are the reason this exists: they name a phase or a role rather
 ;; than a seat, and `:target` means a player id everywhere in the protocol.
 ;;
 ;; Which key a choice travels under comes from `proto/choice-key`, read by the
 ;; caller — shared wire vocabulary, so the client is not the place it is
 ;; decided. The map is passed through as-is and validated server-side like
 ;; every other command.
 (fn [_ [_ action kvs]]
   {::ws/send {:type :client/command :cmd (into {:type action} kvs)}}))

(rf/reg-event-fx
 ::set-locale
 ;; Both: locally so the landing screen switches before there is a seat to
 ;; record it against, and as a command so the server holds it as per-player
 ;; state that survives a reload. The server's copy wins once seated.
 (fn [{:keys [db]} [_ locale]]
   {:db (assoc db :locale locale)
    ::ws/send {:type :client/command :cmd {:type :set-locale :locale locale}}}))

(defn send-chat-fx
  "Named and public so the clearing behaviour is testable without a browser —
   the same reason create-room-fx and join-room-fx are."
  [{:keys [db]} _]
  (let [text (.trim (get-in db [:form :text] ""))]
    (when (seq text)
      ;; form-put, not assoc-in: the chat box normally HAS focus when this fires,
      ;; and the input's local draft outranks app-db while typing. A plain assoc
      ;; left the message you just sent sitting in the box.
      {:db (form-put db :text "")
       ::ws/send {:type :client/chat :text text}})))

(rf/reg-event-fx ::send-chat send-chat-fx)

;; ---------------------------------------------------------------------- audio ---
;;
;; The server hands out a credential; joining is the player's decision. Browsers
;; refuse a microphone without a user gesture, so an automatic join would fail on
;; the first load of every session — and opening someone's mic because they loaded
;; a page is the wrong default regardless.

(rf/reg-event-fx
 ::request-media
 (fn [_ _] {::ws/send {:type :client/media}}))

(rf/reg-event-fx
 ::join-audio
 (fn [{:keys [db]} _]
   (let [{:keys [url token available?]} (:audio db)]
     (when (and available? url token)
       {:db (assoc-in db [:audio :state] :joining)
        :werwolf.audio/connect {:url url :token token}}))))

(rf/reg-event-fx
 ::leave-audio
 (fn [{:keys [db]} _]
   {:db (-> db (assoc-in [:audio :state] :idle)
            (assoc-in [:audio :speakers] #{}))
    :werwolf.audio/disconnect true}))

(rf/reg-event-db ::audio-joined (fn [db _] (assoc-in db [:audio :state] :joined)))
(rf/reg-event-db ::audio-left   (fn [db _] (-> db (assoc-in [:audio :state] :idle)
                                               (assoc-in [:audio :speakers] #{}))))

(rf/reg-event-db
 ::audio-failed
 (fn [db [_ reason]]
   ;; Surfaced through :error like every other failure, so it renders in the
   ;; player's language rather than as a browser exception.
   (-> db (assoc-in [:audio :state] :idle) (assoc :error reason))))

(rf/reg-event-db ::audio-speakers (fn [db [_ ids]] (assoc-in db [:audio :speakers] ids)))

(rf/reg-event-fx
 ::toggle-mute
 (fn [{:keys [db]} _]
   (let [muted? (not (get-in db [:audio :muted?]))]
     {:db (assoc-in db [:audio :muted?] muted?)
      :werwolf.audio/set-muted muted?})))

(defn leave-fx
  "Also the way out of a finished game. Landing on the landing screen with a
   stale room list would mean the first thing you see after a game is the game
   you just left, so ask again on the way out.

   Named and public so the reset is testable — it has to be COMPLETE, and it
   was not: `:view/seq` stayed behind and rejected the next room's views."
  [{:keys [db]} _]
  {:db (merge db (select-keys initial-db
                              [:screen :me :view :view/seq :chat :error :audio]))
   ::ws/forget-seat true
   ;; Hang up on the way out. Without this the microphone stays live and
   ;; published into a room the player has walked out of — they would be audible
   ;; to a table they are no longer at, which is the worst possible bug in a game
   ;; about who knows what.
   :werwolf.audio/disconnect true
   :dispatch [::refresh-rooms]
   ::ws/send {:type :client/command :cmd {:type :leave}}})

(rf/reg-event-fx ::leave leave-fx)

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

(defmethod handle-server :server/rooms [db msg]
  (assoc db :rooms (vec (:rooms msg))))

(defn media-fx
  "Store what the server said about audio.

   `:enabled? false` is a normal answer from a text-only server, not an error —
   the UI omits the voice panel entirely rather than showing a dead button.

   The credential is kept in app-db while the connection itself lives in
   `werwolf.audio`: a token is inert data, a Room with a microphone attached is
   not."
  [db {:keys [enabled? url token room]}]
  (assoc db :audio {:available? (boolean enabled?)
                    :url url :token token :room room
                    :state :idle
                    :muted? false
                    :speakers #{}}))

(defmethod handle-server :server/media [db msg] (media-fx db msg))

(defmethod handle-server :server/welcome [db {:keys [room-id player-id host?]}]
  (assoc db
         :screen :room
         :error nil
         ;; A new room numbers its views from its own beginning, so the previous
         ;; room's high-water mark has to go. Keeping it meant leaving a long game
         ;; and joining a fresh one dropped every view of the new room as "old",
         ;; leaving you on a room screen with nothing on it.
         :view/seq nil
         :view nil
         :me {:room-id room-id :player-id player-id :host? host?}))

(defmethod handle-server :room/view [db {:keys [view] n :seq}]
  ;; A view only means anything if we hold a seat, and this guard is why leaving
  ;; takes one click instead of two.
  ;;
  ;; The server drops a socket from a room's `:sinks` when it CLOSES, not when
  ;; the player leaves — so the commit that records `:player/left` still
  ;; broadcasts one final view to the player who just left. This method used to
  ;; set `:screen :room` unconditionally, so that trailing frame put them
  ;; straight back into the room they had just walked out of; the second click
  ;; only worked because by then there was nothing left to send.
  ;;
  ;; Checking `:me` rather than filtering on the message is deliberate: it also
  ;; covers any frame still in flight for the previous room, which is the same
  ;; bug wearing a different hat. `:server/welcome` always arrives before any
  ;; view for that socket (see protocol/handle :client/join, which welcomes then
  ;; attaches), so a legitimate view is never dropped by this.
  (cond
    (nil? (:me db)) db
    ;; Views may legitimately arrive out of order, so an older one must never
    ;; overwrite a newer. This is precisely what :seq is for.
    (and n (:view/seq db) (< n (:view/seq db))) db
    :else (assoc db :view view :view/seq n :screen :room)))

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
    ;; :view/seq goes with the rest of it — a discarded session must not leave a
    ;; sequence number behind to reject the next room's views.
    (fatal-errors code)
    (assoc :screen :landing :me nil :view nil :view/seq nil :chat [])))

(rf/reg-event-fx
 ::server-message
 (fn [{:keys [db]} [_ msg]]
   (cond-> {:db (handle-server db msg)}
     ;; Persisting on every welcome matters: a resume hands back a REFRESHED
     ;; token, and keeping the old one would expire the session at its original
     ;; time however long the game runs.
     (= :server/welcome (:type msg))
     (assoc ::ws/save-seat (select-keys msg [:room-id :token]))

     ;; Ask what the voice situation is as soon as there is a seat to ask about.
     ;; Asking only fetches a credential — it does not open a microphone, which
     ;; stays behind an explicit tap.
     (= :server/welcome (:type msg))
     (assoc :dispatch [::request-media])

     (fatal-errors (:code msg))
     (assoc ::ws/forget-seat true))))
