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
            [werwolf.chime :as chime]
            [werwolf.i18n.core :as i18n]
            [werwolf.subs :as sub]
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
   ;; When the list was last asked for, so the landing screen's poll can ride the
   ;; half-second tick without asking twice a second.
   :rooms/asked-at nil
   ;; A create or join is on its way and has not been answered. Repeat taps are
   ;; dropped meanwhile: the server would only answer them with the same seat.
   :seat/pending? false
   ;; Whether the landing screen shows the field for a room's password. Opened
   ;; by tapping a locked room, or by the server saying a join needs one.
   :join/password? false
   :error       nil
   ;; Only covers the landing screen. Once seated, the server's per-player copy
   ;; wins — see ::set-locale and the ::locale subscription.
   :locale      default-locale
   :now         (.getTime (js/Date.))
   :form        {:name "" :code "" :text ""}
   ;; Filled in by :server/media once seated. Reset on leave, so a credential for
   ;; the last room is never carried into the next one.
   :audio       nil
   ;; The deck the host's last stepper tap SENT and the server has not yet
   ;; shown back. See `deck-step-fx` — without it, two quick taps both counted
   ;; from the same view and the second was lost. Cleared by `unseat`: a tap
   ;; still unanswered when the host walks out belongs to the room he left.
   :deck/draft  nil
   ;; The phase chime. A preference of this device, not of any room — which is
   ;; why `unseat` leaves it alone. Overwritten from storage by ::init.
   :sound/on?   true})

(declare stored-dawn)

(rf/reg-event-fx ::init (fn [_ [_ invited]]
                          {:db (cond-> (assoc initial-db :sound/on? (chime/stored-on?)
                                              :dawn/seen (stored-dawn))
                                 ;; Arrived by a shared link: the code is filled
                                 ;; in, and the start page leads with joining.
                                 ;; A plain write: no field is mounted yet to
                                 ;; be told about it — see `form-put`.
                                 invited (-> (assoc-in [:form :code] invited)
                                             (assoc :invited invited)))
                           ::fetch-build true}))

(rf/reg-event-db ::dismiss-invite (fn [db _] (dissoc db :invited)))
(rf/reg-event-db ::dismiss-notice (fn [db _] (dissoc db :notice)))

(rf/reg-fx
 ::fetch-build
 ;; Which build is running, for the start page's footer: the server's answer,
 ;; since the bundle carries no commit. A failure leaves the bundle's version.
 (fn [_]
   (try
     (-> (js/fetch "/api/build")
         (.then #(.json %))
         (.then #(rf/dispatch [::build (js->clj % :keywordize-keys true)]))
         (.catch (fn [_] nil)))
     (catch :default _ nil))))

(rf/reg-event-db ::build (fn [db [_ b]] (assoc db :build (select-keys b [:version :commit]))))

(rf/reg-event-fx
 ::connected
 ;; Ask for the room list as soon as there is a socket to ask on. A landing
 ;; screen that only fills in after you tap refresh looks like there are no
 ;; games, which is the one thing it exists to answer.
 (fn [{:keys [db]} _]
   ;; Stamped as an ask, or the landing screen's poll would repeat it on the
   ;; very next tick.
   {:db (assoc db :conn/status :connected :rooms/asked-at (:now db))
    ::ws/send {:type :client/rooms}}))
(defn disconnected-db
  "The socket is gone. A create or join that was in flight may have been lost
   with it, so the buttons are freed — a repeat that turns out to have been
   unnecessary is answered by the server with the seat you already got."
  [db]
  (assoc db :conn/status :disconnected :seat/pending? false))

(rf/reg-event-db ::disconnected (fn [db _] (disconnected-db db)))

(def ^:const error-ttl-ms
  "How long an error stays on screen before retiring itself.

   Long enough to read a sentence, short enough that a banner from two minutes ago
   is not still occupying the top of a phone screen. Errors here are transient by
   nature — a rejected move, a name you forgot to type — and none of them needs
   acknowledging."
  6000)

(defn tick-db
  "The clock, and where errors go to retire.

   Expiry rides the tick that already runs rather than each error site scheduling
   its own timeout: the bookkeeping stays in one place, and the race is
   impossible — a NEW error is stamped fresh the moment it differs from the one
   being shown, so it gets its own full reading time instead of inheriting the
   remains of its predecessor's.

   Named and public so all of that is testable without a clock or a registry."
  [db]
  (let [now (.getTime (js/Date.))]
    (cond-> (assoc db :now now)
      ;; Newly arrived — start its clock. Both tests read the PRE-tick db, so an
      ;; error stamped on this tick cannot also be retired on it.
      (and (:error db) (not= (:error db) (:error/shown db)))
      (assoc :error/shown (:error db) :error/at now)

      (and (:error db)
           (= (:error db) (:error/shown db))
           (:error/at db)
           (> (- now (:error/at db)) error-ttl-ms))
      (assoc :error nil :error/shown nil :error/at nil))))

(defn dismiss-db
  "Tapping the ×. Clears the bookkeeping too, or the next identical error would
   count as still-showing and retire early."
  [db]
  (assoc db :error nil :error/shown nil :error/at nil))

(rf/reg-event-db ::tick (fn [db _] (tick-db db)))

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

(rf/reg-event-db ::dismiss-error (fn [db _] (dismiss-db db)))

(rf/reg-event-db ::toggle-log (fn [db _] (update db :log/show-all? not)))

(rf/reg-event-db ::toggle-chronik
                 (fn [db _] (assoc db :log/open? (not (sub/chronik-open? (:log/open? db))))))

(defn toggle-sound-fx
  "The bell in the stage header: chime on the turn of the phase, or not. Kept
   per device, because the phone on the table and the one in a pocket want
   different answers."
  [{:keys [db]} _]
  (let [on? (not (:sound/on? db))]
    {:db (assoc db :sound/on? on?)
     ::chime/store-on on?}))

(rf/reg-event-fx ::toggle-sound toggle-sound-fx)

;; ------------------------------------------------------------------ outbound ---

;; Both of these used to return nil when a field was empty, which is a silent
;; no-op: you tap the button, nothing happens, and nothing says why. On a phone
;; that is indistinguishable from a broken button — and it was reported as one.
;; Say what is missing instead.

;; Named and public rather than anonymous, so the guard clauses are testable
;; without a browser — the same reason `handle-server` is.

;; All three also refuse to send while an earlier create or join is unanswered.
;; A double tap on a slow phone used to send both: the first seated you, the
;; second was refused as :already-in-room, and the error landed on top of the
;; room you had just entered. The tap is dropped rather than queued, because the
;; only possible answer to it is the seat the first one is about to give you.

(defn- typed-password
  "A password field's content, or nil when there is nothing in it. Trimmed for
   the same reason the server trims: the space a keyboard appends after a
   suggestion is not part of the word. Nil rather than \"\", because the wire
   refuses an empty password — leaving it out is how 'none' is spelled."
  [db k]
  (let [pw (.trim (str (get-in db [:form k] "")))]
    (when (seq pw) pw)))

(defn- with-password [msg pw]
  (cond-> msg pw (assoc :password pw)))

(defn create-room-fx [{:keys [db]} _]
  (let [nm (.trim (get-in db [:form :name] ""))]
    (cond
      (:seat/pending? db) nil
      (empty? nm)         {:db (assoc db :error :name-required)}
      :else {:db (assoc db :error nil :seat/pending? true)
             ::ws/send (with-password {:type :client/create :name nm :locale (:locale db :de)}
                         (typed-password db :room-password))})))

(defn join-room-fx [{:keys [db]} _]
  (let [nm   (.trim (get-in db [:form :name] ""))
        code (.toUpperCase (.trim (get-in db [:form :code] "")))]
    (cond
      (:seat/pending? db) nil
      (empty? nm)         {:db (assoc db :error :name-required)}
      (empty? code)       {:db (assoc db :error :code-required)}
      :else {:db (assoc db :error nil :seat/pending? true)
             ::ws/send (with-password {:type :client/join :room-id code :name nm
                                       :locale (:locale db :de)}
                         (typed-password db :join-password))})))

(defn join-listed-fx
  "Join a room picked off the landing screen's list. Same guard as typing the
   code by hand — the name is still required, and saying so beats a tap that
   appears to do nothing.

   A room the list marks as locked is not joined on the first tap unless a
   password is already typed: the list has said a password is needed, so
   sending the join anyway would only fetch an error banner saying the same.
   The tap opens the field instead, with the code filled in, and the join
   button sends both."
  [{:keys [db]} [_ code locked?]]
  (let [nm (.trim (get-in db [:form :name] ""))
        pw (typed-password db :join-password)]
    (cond
      (:seat/pending? db) nil
      (empty? nm)         {:db (assoc db :error :name-required)}
      (and locked? (nil? pw))
      {:db (-> db (assoc :error nil :join/password? true) (form-put :code code))}
      ;; form-put so the code field actually shows what you tapped, even if you
      ;; had started typing a different one by hand.
      :else {:db (-> db (assoc :error nil :seat/pending? true) (form-put :code code))
             ::ws/send (with-password {:type :client/join :room-id code :name nm
                                       :locale (:locale db :de)}
                         pw)})))

(defn set-password-fx
  "The host locks the room, or gives it a new password. A blank box sends
   nothing: opening the door is its own button, and a tap on 'set' with an
   empty field must not quietly do the opposite of what it says.

   The box is emptied as the message goes, with form-put because it has focus —
   the same reason `send-chat-fx` does it that way. Nothing confirms success but
   the 🔒 arriving with the next view, which is the confirmation everyone else
   at the table gets too."
  [{:keys [db]} _]
  (when-let [pw (typed-password db :lobby-password)]
    {:db (form-put db :lobby-password "")
     ::ws/send {:type :client/set-password :password pw}}))

(defn clear-password-fx [_ _]
  {::ws/send {:type :client/set-password :password nil}})

(rf/reg-event-fx ::create-room create-room-fx)
(rf/reg-event-fx ::join-room   join-room-fx)
(rf/reg-event-fx ::join-listed join-listed-fx)
(rf/reg-event-fx ::set-password set-password-fx)
(rf/reg-event-fx ::clear-password clear-password-fx)

(rf/reg-event-fx
 ::refresh-rooms
 ;; Pull, not push. The server answers the asker alone, so a room list can
 ;; never become a side channel into a game somebody is not in.
 (fn [_ _] {::ws/send {:type :client/rooms}}))

(def ^:const rooms-poll-ms
  "How often the landing screen asks for the room list by itself.

   It used to be asked for on connect, after leaving and on a tap of refresh, and
   at no other time — so a room opened a minute after you arrived was not there
   until you thought to tap, and the report was 'the others cannot be found'.
   Pushing the list would make it a broadcast, which `::refresh-rooms` explains
   it must not be; asking every few seconds keeps it pull-only, and the answer is
   a handful of codes. Short enough that a friend's new room appears while they
   are still reading the code out."
  4000)

(defn poll-rooms-fx
  "Ask again if the list is stale — only on the landing screen, the one place it
   is shown, and only while connected: a send on a closed socket goes to the
   outbox, and a poll there would queue one ask per interval to all go out at
   once on reconnect.

   Reads the clock from `:now`, which the tick keeps, so this stays pure and
   testable without a timer."
  [{:keys [db]} _]
  (let [now (:now db)
        at  (:rooms/asked-at db)]
    (when (and (= :landing (:screen db))
               (= :connected (:conn/status db))
               (or (nil? at) (>= (- now at) rooms-poll-ms)))
      {:db (assoc db :rooms/asked-at now)
       ::ws/send {:type :client/rooms}})))

(rf/reg-event-fx ::poll-rooms poll-rooms-fx)

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

(defn deck-step-fx
  "One stepper tap: the next deck, sent whole, counted from the deck this page
   last SENT if the server has not shown it back yet, otherwise from the view.

   Counting from the view alone lost taps. Two quick taps on + both read the
   same view — the answer to the first had not arrived — so both sent 'one
   more than now', the second was the same deck again, and the host saw his
   second wolf vanish. On a phone, two quick taps is how a stepper is used.

   The draft is not a second source of truth: it is what this page has asked
   for and not yet been told. The server's view clears it the moment it agrees
   (`:room/view`), and any refusal clears it too (`:server/error`)."
  [{:keys [db]} [_ role delta limit]]
  (let [view (:view db)
        base (or (:deck/draft db) (:deck view) (sub/deck-from-preview (:deck-preview view)))
        next (sub/deck-step base role delta limit)]
    (when (not= next base)
      {:db (assoc db :deck/draft next)
       ::ws/send {:type :client/command :cmd {:type :gm/set-deck :deck next}}})))

(rf/reg-event-fx ::deck-step deck-step-fx)

(rf/reg-event-fx
 ::deck-auto
 ;; Back to the automatic deck. Drops any draft on the way: a tap still in
 ;; flight is superseded by this, and a draft outliving it would make the next
 ;; 'Eigene Auswahl' count from a deck the host has already abandoned.
 (fn [{:keys [db]} _]
   {:db (assoc db :deck/draft nil)
    ::ws/send {:type :client/command :cmd {:type :gm/auto-deck}}}))

(defn set-locale-fx
  "Both: locally so the landing screen switches before there is a seat to
   record it against, and as a command so the server holds it as per-player
   state that survives a reload. The server's copy wins once seated.

   The command only while seated, like `voice-report`. On the landing page the
   socket has no seat, the server answered :not-in-room, and switching the
   language greeted the player with \"You are not in this game\". Nothing is
   lost by not sending it: create and join carry the local choice along."
  [{:keys [db]} [_ locale]]
  (cond-> {:db (assoc db :locale locale)}
    (:me db) (assoc ::ws/send {:type :client/command
                               :cmd {:type :set-locale :locale locale}})))

(rf/reg-event-fx ::set-locale set-locale-fx)

(def ^:private dawn-key "werwolf/dawn-seen")

(defn stored-dawn
  "Which morning this tab last dismissed, as `sub/dawn-id` names it. Per tab
   (sessionStorage): a reload keeps it, another device shows its own."
  []
  (try (.getItem js/sessionStorage dawn-key) (catch :default _ nil)))

(rf/reg-fx ::store-dawn
  (fn [id] (try (.setItem js/sessionStorage dawn-key id) (catch :default _ nil))))

(rf/reg-event-fx
 ::dawn-seen
 ;; The morning card is read. Named by room and night, so the next morning —
 ;; or another room's — shows its own, and a reload does not bring it back.
 (fn [{:keys [db]} [_ id]] {:db (assoc db :dawn/seen id) ::store-dawn id}))

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

(defn- voice-report
  "Tell the table this page is, or is no longer, in the call — but only while
   seated. Hanging up is also part of LEAVING a room, and by then the socket has
   no seat to report for: the server would answer :not-in-room, and that error
   would greet the player on the landing screen."
  [db joined?]
  (when (:me db) {:type :client/voice :joined? joined?}))

(rf/reg-event-fx
 ::leave-audio
 (fn [{:keys [db]} _]
   (cond-> {:db (-> db (assoc-in [:audio :state] :idle)
                    (assoc-in [:audio :speakers] #{}))
            :werwolf.audio/disconnect true}
     (voice-report db false) (assoc ::ws/send (voice-report db false)))))

(defn audio-joined-fx [{:keys [db]} _]
  (cond-> {:db (assoc-in db [:audio :state] :joined)}
    (voice-report db true) (assoc ::ws/send (voice-report db true))))

(defn audio-left-fx
  "The SDK says the call is over — a network drop, or the far end closing it.
   Reported only if this page thought it was in the call, so a hang-up the page
   asked for (and has already reported) is not said twice."
  [{:keys [db]} _]
  (cond-> {:db (-> db (assoc-in [:audio :state] :idle)
                   (assoc-in [:audio :speakers] #{}))}
    (and (= :joined (get-in db [:audio :state])) (voice-report db false))
    (assoc ::ws/send (voice-report db false))))

(rf/reg-event-fx ::audio-joined audio-joined-fx)
(rf/reg-event-fx ::audio-left audio-left-fx)

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

(defn- unseat
  "Back to the landing screen with nothing of the last room left behind. Shared
   by leaving here and by being released from another tab, so the two resets
   cannot drift apart — this one has to be COMPLETE, and once was not."
  [db]
  (-> db
      (merge (select-keys initial-db
                          [:screen :me :view :view/seq :chat :error :audio
                           :join/password? :deck/draft]))
      ;; The confirm that got us here. Left armed, it would put 'really?' on the
      ;; next room's leave button before anyone had touched it. And a password
      ;; half-typed for the last room's lock, which is nothing to the next.
      ;; And the host's half-confirmed card buttons: they name a seat NUMBER,
      ;; and the next room has a seat with that number too — the first thing
      ;; its host would see is 'really remove?' on somebody he never tapped.
      (update :form dissoc :leave :lobby-password :gm)))

(defn leave-fx
  "Also the way out of a finished game. Landing on the landing screen with a
   stale room list would mean the first thing you see after a game is the game
   you just left, so ask again on the way out.

   Named and public so the reset is testable — it has to be COMPLETE, and it
   was not: `:view/seq` stayed behind and rejected the next room's views."
  [{:keys [db]} _]
  ;; Said on the start page: arriving there unasked, with nothing saying why,
  ;; read as the app having crashed — the same reason a kick says so.
  {:db (assoc (unseat db) :notice :left)
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
  (let [live (select-keys (:audio db) [:state :muted? :speakers])]
    (assoc db :audio (merge {:available? (boolean enabled?)
                             :url url :token token :room room
                             :state :idle
                             :muted? false
                             :speakers #{}}
                            ;; A socket that reconnects is welcomed again, and the
                            ;; welcome asks for credentials again — while the call
                            ;; itself, a separate connection, may never have
                            ;; dropped. Resetting to :idle then offered "join" for
                            ;; a call the player was audibly in, and the tap did
                            ;; nothing, because the SDK was already connected.
                            (when (#{:joining :joined} (:state live)) live)))))

(defmethod handle-server :server/media [db msg] (media-fx db msg))

(defmethod handle-server :server/welcome [db {:keys [room-id player-id host?]}]
  (assoc db
         :screen :room
         :notice nil
         ;; The invitation is used up; back at the start page it is the
         ;; ordinary one again.
         :invited nil
         :error nil
         :seat/pending? false
         ;; A new room numbers its views from its own beginning, so the previous
         ;; room's high-water mark has to go. Keeping it meant leaving a long game
         ;; and joining a fresh one dropped every view of the new room as "old",
         ;; leaving you on a room screen with nothing on it.
         :view/seq nil
         :view nil
         :deck/draft nil
         :me {:room-id room-id :player-id player-id :host? host?}
         :join/password? false
         ;; Nor its half-confirmed leave. `leave-fx` clears that on the way out,
         ;; but a fatal error throws a session away without passing through it.
         ;;
         ;; Nor the landing screen's passwords: they have done their job, and
         ;; app-db is exactly what a debugging tool prints to the console. The
         ;; lobby's box is left alone — a reconnect welcomes again, and emptying
         ;; app-db under a half-typed word would leave the DOM showing text the
         ;; button can no longer see. `unseat` and sending it clear that one.
         :form (dissoc (:form db) :leave :room-password :join-password)))

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
    :else (cond-> (assoc db :view view :view/seq n :screen :room)
            ;; Out of a finished game into the next: the old chat goes with it,
            ;; as it does on the server — see room/commit!.
            (and (= :game-over (get-in db [:view :phase]))
                 (not= :game-over (:phase view)))
            (assoc :chat [])
            ;; The server has caught up with the last stepper tap, or the lobby
            ;; is over and there is nothing left to choose.
            (or (= (:deck view) (:deck/draft db))
                (not= :lobby (:phase view)))
            (assoc :deck/draft nil))))

(defmethod handle-server :room/chat [db msg]
  (update db :chat (fn [c] (vec (take-last 300 (conj (vec c) msg))))))

(defmethod handle-server :room/chat-history [db {:keys [messages]}]
  (assoc db :chat (vec messages)))

(def password-errors
  "Refusals that mean 'this room wants a password' — the join needs one, the one
   given was wrong, or too many were. Each opens the field, so the retry is one
   word and one tap away."
  #{:password-required :wrong-password :too-many-attempts})

(def fatal-errors
  "Errors meaning the stored credential is worthless. Anything else is a
   rejected move: worth showing, not worth throwing the session away for."
  #{:seat-gone :expired :bad-signature :wrong-room})

(defmethod handle-server :server/error [db {:keys [code]}]
  (cond
    ;; A refusal to seat you somewhere because you are already seated, arriving
    ;; while you ARE seated, is about the seat on your screen. It happens when a
    ;; join waited in the outbox through a reconnect: the stored seat's resume
    ;; goes out first and seats you, and the join behind it is refused. A banner
    ;; saying 'you are already in a round' over that round says nothing useful.
    (and (= :already-in-room code) (:me db))
    (assoc db :seat/pending? false)

    ;; Not seated yet, so this answers the stored seat's resume as the page
    ;; loaded: the room was retired while the tab was away. Nobody asked for
    ;; anything, so there is nothing to refuse — forget the seat (see
    ;; `server-message-fx`) and show the start page as if it had never been.
    ;; A red "Dein Platz existiert nicht mehr" over it read as a fault.
    (and (fatal-errors code) (nil? (:me db)))
    (assoc db :seat/pending? false)

    :else
    ;; :seat/pending? is released for every refusal, these included — a retry
    ;; with the password is a NEW request, and a lock left held would drop it as
    ;; a double tap. A refused deck leaves no draft behind: the next tap must
    ;; count from what the server holds, not from what it just turned down.
    (cond-> (assoc db :error code :seat/pending? false :deck/draft nil)
      (password-errors code)
      (assoc :join/password? true)

      ;; :view/seq goes with the rest of it — a discarded session must not leave
      ;; a sequence number behind to reject the next room's views.
      (fatal-errors code)
      (assoc :screen :landing :me nil :view nil :view/seq nil :chat []))))

(defn- released?
  "Is this :server/left about the room this page is sitting in?

   It goes to every socket of the seat, including the one whose tap caused it —
   which has already gone back to the landing screen, and may by now have sat
   down somewhere else. Only a page still showing THAT room has anything to do."
  [db {:keys [room-id]}]
  (and (:me db) (= room-id (get-in db [:me :room-id]))))

(defmethod handle-server :server/left [db msg]
  ;; The player left from another tab: this page's seat is gone (or, mid-game,
  ;; no longer this page's), and the server has stopped sending it anything.
  (if (released? db msg) (unseat db) db))

(defmethod handle-server :server/kicked [db msg]
  ;; The host removed this seat. The same way out as being released from
  ;; another tab, plus the reason: arriving on the landing screen unasked, with
  ;; nothing saying why, reads as the app having crashed. Shown through :error
  ;; like any other refusal, so it is in the player's own language.
  (if (released? db msg) (assoc (unseat db) :error :kicked) db))

(defn turn-title
  "The tab's name for this view: marked while the game waits on this player."
  [view]
  (let [title (i18n/t (or (get-in view [:me :locale]) default-locale) :app/title)]
    (if (sub/my-turn? view) (str "● " title) title)))

(defn- with-chime
  "Add the phase chime, if this view turned the phase and the player wants to
   hear it. `fx` already holds the db AFTER the view, `db` is the one before.

   Compared against what was accepted, not what arrived: a view dropped as out
   of order leaves `:view` as it was, and so rings nothing — an old frame must
   not announce a turn already past."
  [fx db]
  (let [old  (:view db)
        new  (get-in fx [:db :view])
        cue  (and (:sound/on? db) (chime/cue-for old new))
        turn (and (:sound/on? db) (sub/turn-started? old new))]
    (cond-> fx
      cue  (assoc ::chime/play cue)
      ;; Its own sound only when no phase chime rings in the same moment: the
      ;; two at once are a noise, and the phase chime already says "wake up".
      (and turn (not cue)) (assoc ::chime/play :turn)
      turn (assoc ::chime/buzz true)
      ;; Every accepted view, not only a turn starting: the mark must also go
      ;; away when the turn ends.
      new  (assoc ::chime/title (turn-title new)))))

(defn server-message-fx
  "Named and public so the effects of a message, not only its db change, are
   testable — the same reason `leave-fx` is."
  [{:keys [db]} [_ msg]]
  (cond-> {:db (handle-server db msg)}
    ;; The phase chime, when this view turned the phase — see `with-chime`.
    (= :room/view (:type msg))
    (with-chime db)

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

    ;; Still in the call across a reconnect: the server's record of it went with
    ;; the old socket, so say it again on the new one.
    (and (= :server/welcome (:type msg)) (= :joined (get-in db [:audio :state])))
    (assoc ::ws/send {:type :client/voice :joined? true})

    (fatal-errors (:code msg))
    (assoc ::ws/forget-seat true)

    ;; Released from another tab, or removed by the host: the same way out as
    ;; leaving from this one, minus the command — the seat is already gone. The
    ;; hang-up matters most for the removed: a live microphone in a room you
    ;; were taken out of is the worst kind of eavesdropping, the unwitting kind.
    ;; And the stored seat goes, though a resume would be refused anyway — so
    ;; the next load starts on the landing screen instead of on an error.
    (and (#{:server/left :server/kicked} (:type msg)) (released? db msg))
    (assoc ::ws/forget-seat true
           :werwolf.audio/disconnect true
           :dispatch [::refresh-rooms])))

(rf/reg-event-fx ::server-message server-message-fx)
