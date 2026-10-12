(ns werwolf.subs
  "re-frame subscriptions: projections of the server's redacted view.

   Note what is NOT here. There is no subscription that computes who the
   werewolves are, whether an action is legal, or whose turn it is. Those
   answers arrive in `:view` already decided, because the server is the only
   thing entitled to decide them."
  (:require [re-frame.core :as rf]
            [werwolf.i18n.core :as i18n]))

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

(defn leave-armed?
  "Whether 'leave' has had its first tap and is waiting for the second.

   The form holds the PHASE that first tap happened in, not a flag, so the
   question goes stale by itself: once the game starts, a 'really?' armed in the
   lobby matches nothing, and cannot greet whoever next sees a leave button.
   `keyword?` for the same reason as `armed-action` — \"\" is truthy."
  [form view]
  (let [p (:leave form)]
    (and (keyword? p) (= p (:phase view)))))

(rf/reg-sub ::leave-armed? :<- [::form] :<- [::view]
            (fn [[form view] _] (leave-armed? form view)))

(rf/reg-sub ::show-all-log? (fn [db _] (boolean (:log/show-all? db))))

(defn chronik-open?
  "Whether the Chronik is unfolded. A choice the player made wins; otherwise it
   starts closed at a narrated table, where the Spielleiter tells the story out
   loud and a scrolling transcript of it is a second, quieter narrator competing
   for the same screen. Without one the Chronik IS the narrator, so it opens."
  [stored narrated?]
  (if (boolean? stored) stored (not narrated?)))

(rf/reg-sub ::chronik-open?
            (fn [db _] (chronik-open? (:log/open? db) (:narrated? (:view db)))))

(rf/reg-sub ::rooms (fn [db _] (or (:rooms db) [])))
(rf/reg-sub ::seat-pending? (fn [db _] (boolean (:seat/pending? db))))

;; ---- voice ----
;;
;; A summary of the LiveKit connection, which itself lives in werwolf.audio: a
;; Room with a microphone attached cannot sit in app-db.

(rf/reg-sub ::audio (fn [db _] (:audio db)))

(rf/reg-sub ::audio-available? :<- [::audio] (fn [a _] (boolean (:available? a))))
(rf/reg-sub ::audio-state      :<- [::audio] (fn [a _] (or (:state a) :idle)))
(rf/reg-sub ::audio-muted?     :<- [::audio] (fn [a _] (boolean (:muted? a))))

(defn speaking?
  "Is the player in seat `pid` talking right now?

   The SFU reports participant IDENTITIES, and identity is `werwolf-<code>-p<n>`
   — see `werwolf.media/identity-for`. Matching on the suffix keeps that rule in
   one place on each side rather than reconstructing the room code here. Pure and
   public so the parsing is testable without a browser."
  [speakers pid]
  (boolean (and pid (some #(= (str "-p" pid) (re-find (re-pattern (str "-p" pid "$")) %))
                          speakers))))

(rf/reg-sub
 ::speaking
 :<- [::audio]
 (fn [a _] (or (:speakers a) #{})))

(rf/reg-sub ::phase    :<- [::view] (fn [v _] (:phase v)))
(rf/reg-sub ::players  :<- [::view] (fn [v _] (:players v)))
(rf/reg-sub ::me-view  :<- [::view] (fn [v _] (:me v)))
(rf/reg-sub ::log      :<- [::view] (fn [v _] (:log v)))
;; Seats in the voice call. Sent alongside the view by the room, not by the
;; engine — see room/in-voice.
(rf/reg-sub ::in-voice :<- [::view] (fn [v _] (set (:voice v))))
(rf/reg-sub ::votes    :<- [::view] (fn [v _] (or (:votes v) {})))
(rf/reg-sub ::nominations :<- [::view] (fn [v _] (or (:nominations v) #{})))
(rf/reg-sub ::known-roles :<- [::view] (fn [v _] (or (:known-roles v) {})))

;; The Spielleiter's seat, or nil when the host plays. The server decides it —
;; it is the host of a narrated room — and the client only reads it.
(rf/reg-sub ::narrator  :<- [::view] (fn [v _] (:narrator v)))
(rf/reg-sub ::narrated? :<- [::view] (fn [v _] (boolean (:narrated? v))))
(rf/reg-sub ::narrator? :<- [::me-view] (fn [me _] (boolean (:narrator? me))))
(rf/reg-sub ::timer?    :<- [::view] (fn [v _] (not (false? (:timer? v)))))
(rf/reg-sub ::host      :<- [::view] (fn [v _] (:host v)))

;; Read from the VIEW, not from the welcome message. The welcome's :host? is a
;; snapshot from the moment you sat down, and the seat can be handed over — or
;; inherited — long after that.
(rf/reg-sub
 ::host?
 :<- [::view]
 (fn [v _] (and (some? (:host v)) (= (:host v) (get-in v [:me :id])))))

;; Only ever filled in for the Spielleiter: the night as it happens, and his
;; notes on the lovers and the Dieb's cards. Empty for everybody else, because
;; the server never builds it for them.
(rf/reg-sub ::night-info    :<- [::view] (fn [v _] (or (:night-info v) {})))
(rf/reg-sub ::narrator-info :<- [::view] (fn [v _] (:narrator-info v)))

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

(defn waiting-names
  "Whose turn it still is, by name, for the phases where the answer is public.
   Silence is the thing players find most confusing, so say who it belongs to.
   Night is absent on purpose: naming the players still to act would leak which
   roles are alive.

   Never the Spielleiter. He is in `:players` because he has a seat, and alive
   because nobody ever kills him, but the game is not waiting on him — listing
   him made every narrated day look one ballot short forever. Which seat is his
   arrives in the view; nothing here decides it. Pure and public so that is
   testable without a browser."
  [{:keys [phase players ready nomination-done votes narrator]}]
  (let [living (keep (fn [[id p]] (when (and (:alive? p) (not= id narrator)) id)) players)
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
           vec))))

(rf/reg-sub ::waiting-for :<- [::view] (fn [v _] (waiting-names v)))

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

;; ---- the stage ----
;;
;; Presentation only. Nothing below decides what happens next — the server does
;; that — it only decides how to DRAW where the table is, from :phase, :night and
;; the public log.

(defn round-steps
  "One day-night cycle as a strip of steps, each :done, :current or :todo.

   The election appears on the first day only, because that is the only day it
   can happen. A :reaction is an interrupt rather than a step of its own, so it
   lights up the step it broke into: the dawn if it came out of the night, the
   verdict if it came out of the day. The view does not say which, but the log
   does — whichever of night-ended or day-began came last."
  [{:keys [phase night log]}]
  (when (contains? #{:night :day-reveal :election :discussion :nomination :vote
                     :day-resolve :reaction}
                   phase)
    (let [steps (cond-> [:night :day-reveal]
                  (= 1 night) (conj :election)
                  true (into [:discussion :nomination :vote :day-resolve]))
          current (if (= :reaction phase)
                    (case (some #{:night/ended :day/began} (map :type (rseq (vec log))))
                      :day/began :day-resolve
                      :day-reveal)
                    phase)
          at (.indexOf steps current)]
      (vec (map-indexed (fn [i step]
                          {:step step
                           :state (cond (< i at) :done (= i at) :current :else :todo)
                           :interrupted? (and (= i at) (= :reaction phase))})
                        steps)))))

(rf/reg-sub ::round-steps :<- [::view] (fn [v _] (round-steps v)))

(defn ambience
  "Which light the room is in. Night is night, the day phases are day, the end
   is gold, and everything before the first night stays neutral — the cards are
   still being handed out."
  [phase]
  (case phase
    (:night) :night
    (:day-reveal :election :discussion :nomination :vote :day-resolve :reaction) :day
    :game-over :end
    :neutral))

(rf/reg-sub ::ambience :<- [::phase] (fn [p _] (ambience p)))

(def ^:private news-tones #{:death :verdict})

(defn latest-news
  "The last thing that MATTERED, as the run of log entries that said it: who
   died, how the vote went, that the night was quiet. Bookkeeping in between is
   stepped over, so a reconnect after the verdict does not bury it; any other
   kind of entry ends the run. At most three, oldest first — a Jäger's shot and
   a lover's grief belong on one line, a whole game does not.

   This is what keeps a folded Chronik honest: the one line nobody should miss
   stays on screen."
  [log]
  (let [tone #(i18n/log-tone %)
        newest-first (rseq (vec log))
        from-news (drop-while #(not (news-tones (tone %))) newest-first)]
    (->> from-news
         (remove #(= :muted (tone %)))
         (take-while #(news-tones (tone %)))
         (take 3)
         reverse
         vec
         not-empty)))

(rf/reg-sub ::latest-news :<- [::log] (fn [log _] (latest-news log)))
