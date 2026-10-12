(ns werwolf.subs
  "re-frame subscriptions: projections of the server's redacted view.

   Note what is NOT here. There is no subscription that computes who the
   werewolves are, whether an action is legal, or whose turn it is. Those
   answers arrive in `:view` already decided, because the server is the only
   thing entitled to decide them."
  (:require [re-frame.core :as rf]
            [werwolf.engine.roles :as roles]
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
  "Whether the Chronik is unfolded: only when this player opened it, this visit.

   Closed by default at every table. It first stayed open where nobody narrates,
   and the choice was remembered across games, so one curious tap kept it open
   for good — and it is a column of transcript competing with the board for a
   phone screen, while the stage's 'Zuletzt' line already carries the one entry
   that matters."
  [open?]
  (true? open?))

(rf/reg-sub ::chronik-open? (fn [db _] (chronik-open? (:log/open? db))))

(rf/reg-sub ::sound-on? (fn [db _] (:sound/on? db)))

(rf/reg-sub ::rooms (fn [db _] (or (:rooms db) [])))
(rf/reg-sub ::seat-pending? (fn [db _] (boolean (:seat/pending? db))))
(rf/reg-sub ::join-password? (fn [db _] (boolean (:join/password? db))))

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
;; Whether a newcomer needs the room's password. Only ever this boolean — the
;; server never sends the hash, let alone the word.
(rf/reg-sub ::locked?   :<- [::view] (fn [v _] (boolean (:locked? v))))

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
;; Who the game is still waiting on. Built by the server for the Spielleiter
;; only, so for everybody else this is simply empty.
(rf/reg-sub ::owing :<- [::narrator-info] (fn [n _] (vec (:owing n))))

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

(def ^:private not-a-move
  "Offered, but never what the game is waiting for. Chat, language and the way
   out are always there. :claim-host is offered to everybody while the host is
   away, and nobody waits on it. :advance-phase is a Spielleitung power despite
   its plain name — `host-actions` grants it, and its label says \"(SL)\"."
  #{:chat :leave :set-locale :advance-phase :claim-host})

(defn player-moves
  "The actions that are this player's own moves in the game, sorted: what the
   action bar shows and what makes it 'your turn'.

   One definition for both, because they disagreed. The turn banner left the
   Spielleitung powers (`:gm/*`, :advance-phase) in, so a host who plays along
   was told \"Du bist dran\" all night — directly above an action bar that,
   leaving them out, said there was nothing to do. Those powers are always on
   offer and have a console of their own; nobody is ever waiting on them."
  [actions]
  (sort (remove #(or (= "gm" (namespace %)) (not-a-move %)) actions)))

(rf/reg-sub
 ::can-act?
 :<- [::actions]
 (fn [actions _] (boolean (seq (player-moves actions)))))

(defn host-absence
  "What to tell the table about a host who has gone missing, or nil while he is
   here: his name, whether the clock has taken over from him, and whether THIS
   player is offered his seat.

   Nothing here decides anything. That he is gone, that the clock took over and
   who may take the seat all arrive in the view — `:host-absent?`, `:timer?` and
   `:actions` — and this only gathers them for the banner. The clock line is
   shown for a narrated game under way because that is where the server
   switches it on; reading `:timer?` alone would claim a takeover in every room
   that simply had a clock all along. Pure and public so it is testable without
   a browser."
  [{:keys [host-absent? host players narrated? phase actions]}]
  (when host-absent?
    {:name   (get-in players [host :name])
     :clock? (boolean (and narrated? (not (#{:lobby :game-over} phase))))
     :claim? (contains? (set actions) :claim-host)}))

(rf/reg-sub ::host-absence :<- [::view] (fn [v _] (host-absence v)))

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

;; ---- the deck ----
;;
;; What is in the game, as the server composed it. Nothing below decides whether
;; a deck is playable — `:deck-problem` arrives decided — or what a role may
;; number: `:deck-roles` carries each limit. These only lay the answer out.

(defn deck-step
  "The deck after one tap of a stepper: `role` up or down by one, never below
   nought, never past the limit the server published. Zeros are dropped, the way
   the server stores them, so a deck that went up and back down is equal to
   the one it started as.

   The whole deck, not the delta, is what gets sent — see `:gm/set-deck`."
  [deck role delta limit]
  (let [n (+ (get deck role 0) delta)
        n (cond-> (max 0 n) limit (min limit))]
    (if (pos? n) (assoc (or deck {}) role n) (dissoc (or deck {}) role))))

(defn deck-from-preview
  "Where 'Eigene Auswahl' starts: the automatic deck for this table, specials
   only. A host who switches over sees the cards he would have had and changes
   what he wants, rather than starting from an empty table."
  [preview]
  (dissoc (:dealt preview) roles/villager))

(defn deck-panel
  "Everything the Rollen panel draws, from the view alone.

     :custom?   the host chose this deck (otherwise it is the automatic one)
     :rows      one per special the menu offers: role, count, limit, and whether
                each stepper still has somewhere to go
     :dealt     the full composition players are dealt, villagers included,
                as ordered [role n] pairs
     :fill      how many villagers fill the deck
     :centre    the Dieb's two cards, apart from the hands
     :villager  which card fills, so the view need not name it
     :problem   why the deck cannot be dealt, or nil — the server's word
     :specials  cards the host chose, and :table the seats they must fit"
  [{:keys [deck deck-preview deck-roles deck-problem players narrator]} draft]
  ;; A tap still in flight shows as the count it asked for — see
  ;; `events/deck-step-fx` — so the number under the host's thumb moves when he
  ;; taps. Everything else is the server's: the composition, the filler and the
  ;; problem wait for the view.
  (let [counts (or draft deck (deck-from-preview deck-preview))]
    {:custom?  (some? deck)
     :rows     (mapv (fn [{:keys [role] limit :max}]
                       (let [n (get counts role 0)]
                         {:role role :n n :max limit
                          :can-add? (or (nil? limit) (< n limit))
                          :can-remove? (pos? n)}))
                     deck-roles)
     :dealt    (i18n/deck-entries (:dealt deck-preview))
     :fill     (get-in deck-preview [:dealt roles/villager] 0)
     :centre   (i18n/deck-entries (:centre deck-preview))
     :problem  deck-problem
     :villager roles/villager
     :specials (reduce + 0 (vals counts))
     :table    (count (remove #(= % narrator) (keys players)))}))

(rf/reg-sub ::deck-draft (fn [db _] (:deck/draft db)))
(rf/reg-sub ::deck-panel :<- [::view] :<- [::deck-draft]
            (fn [[v draft] _] (deck-panel v draft)))
