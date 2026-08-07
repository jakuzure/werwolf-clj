(ns werwolf.subs
  "re-frame subscriptions: projections of the server's redacted view.

   Note what is NOT here. There is no subscription that computes who the
   werewolves are, whether an action is legal, or whose turn it is. Those
   answers arrive in `:view` already decided, because the server is the only
   thing entitled to decide them."
  (:require [re-frame.core :as rf]))

(rf/reg-sub ::conn-status (fn [db _] (:conn/status db)))
(rf/reg-sub ::screen      (fn [db _] (:screen db)))
(rf/reg-sub ::me          (fn [db _] (:me db)))
(rf/reg-sub ::view        (fn [db _] (:view db)))
(rf/reg-sub ::chat        (fn [db _] (:chat db)))
(rf/reg-sub ::error       (fn [db _] (:error db)))
(rf/reg-sub ::now         (fn [db _] (:now db)))
(rf/reg-sub ::form        (fn [db _] (:form db)))
(rf/reg-sub
 ::form-field
 :<- [::form]
 ;; Defaults to "" because these back controlled text inputs, which must never
 ;; be handed nil. Do NOT read non-text state through here — see ::pending.
 (fn [form [_ k]] (get form k "")))

(rf/reg-sub
 ::form-gen
 ;; How many times something OTHER than typing has written this field. A text
 ;; input keeps a local draft while you type — see `views/field`, and the Android
 ;; IME bug it exists for — and this counter is how it learns that app-db was
 ;; changed out from under it and the draft should step aside.
 (fn [db [_ k]] (get-in db [:form/gen k] 0)))

(defn armed-action
  "The action the player has armed for targeting, or nil.

   Deliberately not read through ::form-field: that defaults to \"\" so it can
   back controlled text inputs, and \"\" is TRUTHY in ClojureScript. Reading the
   armed action through it made `when-let` succeed on every render, so the
   'choose a target' bar appeared before anyone had clicked anything.

   `map?` rather than `some?`, so a stale value of the wrong shape cannot
   resurrect it either. Pure and public so the guard itself is testable."
  [form]
  (let [p (:pending form)]
    (when (map? p) p)))

(rf/reg-sub ::pending :<- [::form] (fn [form _] (armed-action form)))

(defn gm-console
  "The GM panel's own scratch state: which overrides are half-selected, and
   which one is waiting for its second click.

   Same guard as `armed-action` and for the same reason — ::form-field defaults
   to \"\", which is truthy in ClojureScript, so non-text state read through it
   comes back as a string that passes every `when-let` in sight."
  [form]
  (let [g (:gm form)] (if (map? g) g {})))

(rf/reg-sub ::gm :<- [::form] (fn [form _] (gm-console form)))

(rf/reg-sub ::show-all-log? (fn [db _] (boolean (:log/show-all? db))))

(rf/reg-sub ::rooms (fn [db _] (or (:rooms db) [])))

(rf/reg-sub ::phase    :<- [::view] (fn [v _] (:phase v)))
(rf/reg-sub ::players  :<- [::view] (fn [v _] (:players v)))
(rf/reg-sub ::me-view  :<- [::view] (fn [v _] (:me v)))
(rf/reg-sub ::log      :<- [::view] (fn [v _] (:log v)))
(rf/reg-sub ::votes    :<- [::view] (fn [v _] (or (:votes v) {})))
(rf/reg-sub ::nominations :<- [::view] (fn [v _] (or (:nominations v) #{})))
(rf/reg-sub ::known-roles :<- [::view] (fn [v _] (or (:known-roles v) {})))

;; Which ROLE the narrator is calling right now. Public by design — a human
;; narrator says it out loud ("Werwölfe, wacht auf") — and which player holds it
;; is not in the view. This is most of the answer to "whose turn is it?" at night.
(rf/reg-sub ::night-cursor :<- [::view] (fn [v _] (:night-cursor v)))

;; Set only when THIS player is the one who owes a reaction — the Jäger who has
;; just been told he is dead and still has a shot. The table is waiting on them
;; specifically, so it outranks every other banner.
(rf/reg-sub ::awaiting-reaction :<- [::view] (fn [v _] (:awaiting-reaction v)))

(rf/reg-sub ::db-locale (fn [db _] (:locale db)))

(rf/reg-sub
 ::locale
 :<- [::me-view]
 :<- [::db-locale]
 ;; The server's copy wins once seated: locale is per-player game state, set by
 ;; a logged command, so it survives a reload and follows you to another device.
 ;; The db value only covers the landing screen, before there is a seat.
 (fn [[me local] _] (or (:locale me) local :de)))

;; The affordances. These come straight from the engine's legal-actions /
;; legal-targets, which is why the UI needs no rules of its own: a button that
;; should not exist cannot be drawn, and one that is drawn will be accepted.
(rf/reg-sub ::actions :<- [::view] (fn [v _] (or (:actions v) #{})))
(rf/reg-sub ::targets :<- [::view] (fn [v _] (or (:targets v) {})))

(rf/reg-sub
 ::choices
 :<- [::view]
 ;; The non-player options an action may name: a phase, a role. Separate from
 ;; ::targets because a target is a seat and a choice is a word — the GM panel
 ;; renders them with different controls, and conflating the two is what made
 ;; :gm/set-phase arm the pick-a-player mode with nothing to pick.
 (fn [v _] (or (:choices v) {})))

(rf/reg-sub
 ::arity
 :<- [::view]
 ;; How many players each action wants picked. Read from the server so the UI
 ;; never has to know that Amor is the two-target one.
 (fn [v _] (or (:arity v) {})))

(rf/reg-sub
 ::waiting-for
 :<- [::view]
 (fn [{:keys [phase players ready nomination-done votes]} _]
   ;; Whose turn it still is, by name, for the phases where the answer is
   ;; public. Silence is the thing players find most confusing, so say who it
   ;; belongs to. Night is absent on purpose: naming the players still to act
   ;; would leak which roles are alive.
   (let [living (keep (fn [[id p]] (when (:alive? p) id)) players)
         done (case phase
                :discussion (set ready)
                :nomination (set nomination-done)
                :vote (set (keys votes))
                nil)]
     (when done
       (->> living
            (remove done)
            (map #(get-in players [% :name]))
            (remove nil?)
            sort
            vec)))))

(rf/reg-sub
 ::seconds-left
 :<- [::view]
 :<- [::now]
 (fn [[v now] _]
   ;; Counted FROM the server's absolute instant. The client never owns the
   ;; clock; it only renders the difference.
   (when-let [d (:deadline v)]
     (max 0 (js/Math.ceil (/ (- d now) 1000))))))

(rf/reg-sub
 ::can-act?
 :<- [::actions]
 (fn [actions _]
   ;; :chat is not "acting" — offering it as an action would make the UI claim
   ;; the game is waiting for you when it is not.
   (boolean (seq (remove #{:chat :leave :set-locale} actions)))))
