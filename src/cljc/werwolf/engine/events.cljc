(ns werwolf.engine.events
  "The event reducer: `apply-event` is the ONLY function in the system that
   transitions game state.

   Rules of this namespace:

   * Events are facts in the past tense. They never decide anything — by the
     time an event exists the decision is made. Deciding lives in phases.cljc.
   * `apply-event` is total and idempotent-ish: applying an event that no longer
     makes sense (killing a dead player) is a no-op rather than an error, so a
     replay can never diverge from a live run.
   * There is NO randomness here. Role assignment arrives as an explicit
     :assignments map inside :game/started, so replay needs no RNG at all and
     determinism is structural rather than a property we have to maintain.
   * `:log` is the PUBLIC projection only. Private knowledge (a Seherin's
     inspection) is written to that player's :knows and never to :log, because
     :log is included in every player's view."
  (:require [werwolf.engine.roles :as roles]))

;; ------------------------------------------------------------- helpers ---

(defn- publish
  "Append a fact to the public log."
  [state entry]
  (update state :log (fnil conj []) entry))

(defn- alive? [state pid]
  (get-in state [:players pid :alive?]))

(defn narrator
  "The Spielleiter's seat, or nil when the host plays like everybody else.

   Not a field of its own: the narrator IS the host whenever the room is
   narrated. Succession and a deliberate hand-over both move `:host`, so they
   move the narrator with it and there is no second pointer to keep in step.

   The narrator stays in `:players` — seats, tokens, sockets, reconnects and chat
   all hang off that map, and a narrator who was not in it would need a parallel
   copy of each — but takes no part in the game: no card, no vote, no target, no
   win. Everything below that counts PLAYERS of the game goes through
   `participant-ids` or `living-ids` for exactly that reason."
  [state]
  (when (:narrated? state) (:host state)))

(defn participant-ids
  "Everyone seated who plays the game: every seat but the narrator's. Sorted."
  [state]
  (let [n (narrator state)]
    (->> (keys (:players state)) (remove #(= n %)) sort vec)))

(defn living-ids
  "Living PARTICIPANTS. The narrator is never among them: he is alive in the
   sense that he is in the room, and in no sense the game cares about — counting
   him here would put him on every ballot and keep the village alive forever."
  [state]
  (let [n (narrator state)]
    (->> (:players state)
         (filter (fn [[id p]] (and (:alive? p) (not= n id))))
         (map key)
         sort
         vec)))

(defn living-with-role [state role-id]
  (let [n (narrator state)]
    (->> (:players state)
         (filter (fn [[id p]] (and (:alive? p) (not= n id) (= role-id (:role p)))))
         (map key)
         sort
         vec)))

(defn team-of-player
  "The player's DEALT team: :village or :werewolf.

   Deliberately NOT overridden to :lovers when Amor has linked them. Being in
   love does not stop a werewolf being a werewolf:

   * for the win check, a wolf in love must still count as a surviving wolf —
     overriding it let the village win the moment Amor linked the last wolf;
   * for targeting, wolves must still not be able to eat their own.

   The lovers' alternate victory is a separate condition (exactly the two lovers
   left alive) and is evaluated in phases/winner, not here."
  [state pid]
  (roles/team-of (get-in state [:players pid :role])))

(defn- other-lover [state pid]
  (when-let [ls (:lovers state)]
    (when (contains? (set ls) pid)
      (first (disj (set ls) pid)))))

(defn- push-reaction [state reaction]
  (update state :reactions (fnil conj []) reaction))

(defn- death-reactions
  "Reactions triggered by pid dying. Order matters only in that both land on
   the stack; the stack is drained LIFO by the phase machine."
  [state pid]
  (let [role-id  (get-in state [:players pid :role])
        on-death (roles/on-death-reaction role-id)
        lover    (other-lover state pid)]
    (cond-> state
      ;; Jäger fires even though he is already dead.
      on-death
      (push-reaction {:type on-death :player pid})

      ;; A lover dies of grief. Automatic — needs no input.
      (and lover (alive? state lover))
      (push-reaction {:type :reaction/lover-follows :player lover :cause :liebe})

      ;; The Bürgermeister names who follows him. Keyed off the TITLE rather than
      ;; a role, because nobody is dealt this one — which is also why it is here
      ;; and not in the registry's :on-death.
      (= pid (:mayor state))
      (push-reaction {:type :reaction/mayor-succession :player pid}))))

;; --------------------------------------------------------- apply-event ---

(defmulti apply-event
  "Apply one event to state. Dispatches on :type."
  (fn [_state event] (:type event)))

(defmethod apply-event :default
  [state event]
  ;; Unknown events must not silently corrupt a replay.
  (throw (ex-info "unknown event type" {:event event})))

;; ---- lifecycle ----

(defmethod apply-event :game/created
  [_ {:keys [room-id host seed min-players max-players first-night-kill?
             first-day-trial? narrated? timer?]}]
  (cond-> {:room-id        room-id
           :phase          :lobby
           :night          0
           :players        {}
           :host           host
           :seed           seed
           ;; Kept in state rather than read from config, so the engine stays
           ;; pure and a replayed game uses the same thresholds it was created
           ;; with. A config change must never be able to make a game in
           ;; progress unstartable.
           :min-players    (or min-players 5)
           :max-players    (or max-players 18)
           ;; Rule variant, stamped in at creation so a replay plays the game
           ;; it was: false (the default) means the werewolves have no turn on
           ;; night 1.
           :first-night-kill? (boolean first-night-kill?)
           ;; The same for the day: false (the default) means nobody is accused
           ;; on day 1. See phases/trial-day?.
           :first-day-trial? (boolean first-day-trial?)
           :lovers         nil
           :reactions      []
           :pending-deaths []
           :log            []}
    ;; Both ABSENT unless the genesis event says so, rather than defaulted to
    ;; false: a log written before the Spielleiter existed must replay into the
    ;; very state it always did, and "absent" is how every reader spells "the
    ;; host plays, and the clock runs".
    (some? narrated?) (assoc :narrated? (boolean narrated?))
    (some? timer?)    (assoc :timer? (boolean timer?))))

(defmethod apply-event :player/joined
  [state {:keys [player name locale]}]
  (-> state
      (assoc-in [:players player]
                {:id player
                 :name name
                 :role nil
                 :alive? true
                 :connected? true
                 :locale (or locale :de)})
      (publish {:type :player/joined :player player})))

(defmethod apply-event :player/left
  [state {:keys [player]}]
  ;; Leaving the lobby removes you; leaving mid-game only disconnects you,
  ;; because your role still matters to the game's outcome.
  (if (= :lobby (:phase state))
    (-> state
        (update :players dissoc player)
        (publish {:type :player/left :player player}))
    (-> state
        (assoc-in [:players player :connected?] false)
        (publish {:type :player/disconnected :player player}))))

(defmethod apply-event :player/kicked
  ;; Removed by the host, which only happens in the lobby — so, like leaving
  ;; it, the seat goes entirely. Its number does not come back: the
  ;; `:player/joined` that issued it is still in the log, which is what the
  ;; room counts seats from (see room/next-player-id), and a recycled seat
  ;; would hand the removed player's reconnect token to the next arrival.
  ;;
  ;; The log entry carries the NAME, unlike every other: the seat is gone by
  ;; the time anyone reads the line, and the Chronik names players by looking
  ;; them up in the seats. A name is no secret — it sat on everyone's screen.
  ;;
  ;; A seat that is not there is a no-op, as a replay must never fail.
  [state {:keys [player]}]
  (if-let [p (get-in state [:players player])]
    (-> state
        (update :players dissoc player)
        (publish {:type :player/kicked :player player :name (:name p)}))
    state))

(defmethod apply-event :player/connection
  ;; `:at` is when the socket came or went, stamped by the server — the engine
  ;; has no clock. Only a DISCONNECT keeps it, as `:absent-since`, so the timer
  ;; can measure how long a seat has been empty. The earliest stamp wins: a
  ;; second "gone" for a seat already gone is not a new absence. Logs written
  ;; before the stamp existed carry no `:at` and replay exactly as they did.
  ;;
  ;; The host coming back ends his absence on the spot, here rather than on
  ;; the next tick: the table has been told he is gone, and must be told he is
  ;; back the moment he is. Whatever the absence switched on — the clock — is
  ;; left as it is; it is his to switch off again, and the table can see it.
  [state {:keys [player connected? at]}]
  (let [seated?    (contains? (:players state) player)
        host-back? (and connected? (= player (:host state)) (:host-absent? state))]
    (cond-> (assoc-in state [:players player :connected?] connected?)
      (and seated? connected?)
      (update-in [:players player] dissoc :absent-since)

      (and seated? (not connected?) at
           (nil? (get-in state [:players player :absent-since])))
      (assoc-in [:players player :absent-since] at)

      true (publish {:type (if connected?
                             :player/reconnected
                             :player/disconnected)
                     :player player})

      host-back? (-> (dissoc :host-absent?)
                     (publish {:type :host/returned :host player})))))

(defmethod apply-event :player/renamed
  [state {:keys [player name]}]
  (assoc-in state [:players player :name] name))

(defmethod apply-event :player/locale-set
  [state {:keys [player locale]}]
  (assoc-in state [:players player :locale] locale))

(defmethod apply-event :phase/deadline
  ;; The clock is server-authoritative and absolute: an epoch-ms instant, never
  ;; a duration. Clients count down FROM this number, so a client with a slow
  ;; frame rate, a backgrounded tab or a wrong clock offset cannot gain or lose
  ;; time. `:at nil` stops the clock.
  ;;
  ;; Not logged: it is not a fact about the game, it is a fact about the server,
  ;; and the log is what players read.
  [state {:keys [at]}]
  (assoc state :deadline at))

(defmethod apply-event :clock/paused
  ;; The GM stops the clock. Separate from clearing the deadline, because the
  ;; timer re-arms any timed phase whose deadline is nil — without a flag saying
  ;; "deliberately stopped", pausing would last about one second.
  [state _]
  (-> state
      (assoc :paused? true)
      (publish {:type :clock/paused})))

(defmethod apply-event :clock/resumed
  [state _]
  (-> state
      (dissoc :paused?)
      (publish {:type :clock/resumed})))

(defmethod apply-event :phase/timed-out
  ;; Logged, unlike the deadline itself: "the time ran out" is exactly the kind
  ;; of thing a player needs to see to understand why the vote closed.
  [state {:keys [phase]}]
  (publish state {:type :phase/timed-out :phase phase}))

(defmethod apply-event :host/changed
  ;; Succession. The host is the fallback narrator and the only player who can
  ;; start a game, so a room whose host walks out of the lobby would otherwise
  ;; be unstartable — a dead end with no command that can escape it.
  ;;
  ;; Public on purpose: everyone must know who holds the GM powers, and the
  ;; whole point of logging overrides is that they are auditable.
  ;;
  ;; Whoever takes the seat ends any absence: the flag describes the HOST, and
  ;; this is a new one. If the heir is himself long gone, the ticker will say so
  ;; again on its next pass. `:claimed?` marks a seat taken from an absent host
  ;; rather than handed over, so the Chronik can say which it was.
  [state {:keys [host claimed?]}]
  (-> state
      (assoc :host host)
      (dissoc :host-absent?)
      (publish (cond-> {:type :host/changed :host host}
                 claimed? (assoc :claimed? true)))))

(defmethod apply-event :host/absent
  ;; The host has been gone past the grace period. The fallbacks — a clock
  ;; taking over, the night falling, a seat others may claim — are separate
  ;; events or offers, decided in phases/host-absent-events; this only records
  ;; the fact. Public, because the table is the one left waiting.
  [state _]
  (-> state
      (assoc :host-absent? true)
      (publish {:type :host/absent :host (:host state)})))

(defmethod apply-event :game/narrated-set
  ;; The host chose, in the lobby, whether to narrate or to play. The clock
  ;; follows the choice to its own default — a Spielleiter at the table paces the
  ;; game himself, a table without one needs the clock to do it — and stays a
  ;; separate switch after that, see :clock/timer-set.
  ;;
  ;; Public: who is playing and who is narrating is the first thing a table
  ;; agrees on, and the seat count the lobby shows changes with it.
  [state {:keys [narrated?]}]
  (-> state
      (assoc :narrated? (boolean narrated?)
             :timer? (not narrated?))
      (publish {:type :game/narrated-set :narrated? (boolean narrated?)})))

(defmethod apply-event :clock/timer-set
  ;; Whether the room has a clock at all. Distinct from a pause: a pause is
  ;; "hang on a moment" inside a timed game, this is "nobody is timed in this
  ;; room". `timer/clock-events` reads it and arms nothing while it is off.
  ;;
  ;; `:cause :host-absent` when nobody flipped it: the clock taking over from a
  ;; Spielleiter who has gone missing. Said in the Chronik, or a table with no
  ;; clock would suddenly find one and not know why.
  [state {:keys [on? cause]}]
  (-> state
      (assoc :timer? (boolean on?))
      (publish (cond-> {:type :clock/timer-set :on? (boolean on?)}
                 cause (assoc :cause cause)))))

;; ---- the door: a room password ----
;;
;; Server-authored, like succession: the host asks over the wire, but the hash
;; is computed on the JVM (werwolf.password) and arrives here ready-made. The
;; engine stores it as an opaque string and decides nothing with it — checking
;; a guess is the room layer's job, and a .cljc reducer that could hash would be
;; one more place a plaintext password might pass through.
;;
;; In state so the event log carries it and a restart keeps the door locked.
;; NEVER published: the log is in every view, and even a salted hash of a short
;; spoken word is a thing to guess against offline. The Chronik gets the fact
;; that the door changed, which the table is entitled to, and nothing else.

(defmethod apply-event :room/password-set
  [state {:keys [hash]}]
  (-> state
      (assoc :password-hash hash)
      (publish {:type :room/locked})))

(defmethod apply-event :room/password-cleared
  [state _]
  (-> state
      (dissoc :password-hash)
      (publish {:type :room/unlocked})))

(defmethod apply-event :deck/set
  ;; The Spielleiter chose the roles. The whole deck every time rather than a
  ;; delta, so an entry is a fact that stands on its own: a replay, a reader of
  ;; the Chronik and a client that missed a frame all learn the same deck from
  ;; the last one.
  ;;
  ;; Public, and with the deck in it. At a real table the composition is said
  ;; out loud before anyone looks at a card — it is what everybody reasons from
  ;; — and it is role COUNTS keyed by role, which name no player at all.
  [state {:keys [deck]}]
  (let [deck (roles/normalise-deck deck)]
    (-> state
        (assoc :deck deck)
        (publish {:type :deck/set :deck deck}))))

(defmethod apply-event :deck/auto
  ;; Back to the composition the table size decides. Dissoc rather than nil:
  ;; absent is how every reader, and every older log, spells "automatic".
  [state _]
  (-> state
      (dissoc :deck)
      (publish {:type :deck/auto})))

(defmethod apply-event :game/started
  [state {:keys [assignments centre seed]}]
  (-> (reduce (fn [s [pid role-id]]
                (-> s
                    (assoc-in [:players pid :role] role-id)
                    (assoc-in [:players pid :alive?] true)
                    (assoc-in [:players pid :knows] {})
                    (cond-> (roles/initial-resources role-id)
                      (assoc-in [:players pid :resources]
                                (roles/initial-resources role-id)))))
              state
              assignments)
      (assoc :night 0 :winner nil :centre (vec centre))
      ;; Carry the advanced seed so any later randomness stays reproducible.
      (cond-> seed (assoc :seed seed))
      ;; The narrated flag rides along so the Chronik can say "the cards are
      ;; being dealt" rather than "the first night falls" — in a narrated room
      ;; the night does not fall until the Spielleiter says so.
      (publish (cond-> {:type :game/started :players (count assignments)}
                 (:narrated? state) (assoc :narrated? true)))))

;; ---- phase plumbing ----

(defmethod apply-event :phase/changed
  [state {:keys [phase deadline resume]}]
  (-> state
      (assoc :phase phase)
      (assoc :deadline deadline)
      ;; Entering :reaction records where to go once the stack drains, so an
      ;; interrupt can fire from day OR night without deadlocking.
      (cond-> resume (assoc :resume-phase resume)
              (not resume) (dissoc :resume-phase))))

(defmethod apply-event :night/began
  [state {:keys [night]}]
  (-> state
      (assoc :night night
             :phase :night
             :night-actions {}
             :night-cursor nil
             :pending-deaths [])
      (publish {:type :night/began :night night})))

(defmethod apply-event :night/role-turn
  [state {:keys [role]}]
  (assoc state :night-cursor role))

(defmethod apply-event :night/role-completed
  [state {:keys [role]}]
  (-> state
      (update-in [:night-actions :completed] (fnil conj #{}) role)
      (assoc :night-cursor nil)))

;; Removes a pending death that has now been applied. Separate from :player/died
;; because a death can be a no-op (already dead) yet must still leave the queue.
(defmethod apply-event :deaths/consumed
  [state {:keys [player]}]
  (update state :pending-deaths
          (fn [ds]
            (let [[before after] (split-with #(not= player (:player %)) ds)]
              (vec (concat before (rest after)))))))

(defmethod apply-event :night/quiet
  ;; Log-only: nobody died. See the note in phases/night-auto.
  [state {:keys [night]}]
  (publish state {:type :night/quiet :night night}))

(defmethod apply-event :night/ended
  [state _]
  (-> state
      (assoc :night-cursor nil)
      (publish {:type :night/ended :night (:night state)})))

(defmethod apply-event :election/cast
  [state {:keys [voter target]}]
  (assoc-in state [:election-votes voter] target))

(defmethod apply-event :election/resolved
  [state {:keys [mayor tie?]}]
  (-> state
      (assoc :mayor mayor
             :election-held? true
             :election-votes {})
      (publish {:type :election/resolved :mayor mayor :tie? (boolean tie?)})))

(defmethod apply-event :mayor/appointed
  [state {:keys [player by]}]
  (-> state
      (assoc :mayor player)
      (publish {:type :mayor/appointed :player player :by by})))

(defmethod apply-event :mayor/vacated
  [state {:keys [by]}]
  (-> state
      (assoc :mayor nil)
      (publish {:type :mayor/vacated :by by})))

(defmethod apply-event :day/began
  [state _]
  (-> state
      (assoc :phase :discussion
             :votes {}
             :ready #{}
             :vote-resolved? false)
      ;; Only a log from before the day was one step ever filled these.
      (dissoc :nominations :nomination-done)
      (publish {:type :day/began :day (:night state)})))

;; ---- night actions ----

(defmethod apply-event :amor/linked
  [state {:keys [a b]}]
  ;; Public knowledge: that lovers exist. Private: who they are.
  (-> state
      (assoc :lovers #{a b})
      (assoc-in [:night-actions :amor] {:a a :b b})
      (publish {:type :amor/linked})))

(defmethod apply-event :seherin/inspected
  [state {:keys [player target]}]
  (let [target-role (get-in state [:players target :role])]
    ;; PRIVATE: written to the Seherin's own knowledge, never to :log.
    (-> state
        (assoc-in [:players player :knows target] target-role)
        (assoc-in [:night-actions :seherin] {:player player :target target}))))

(defmethod apply-event :werewolf/voted
  [state {:keys [player target]}]
  (assoc-in state [:night-actions :werewolf-votes player] target))

;; Recorded BEFORE the wolves choose — see the Beschützer's :night-order — so the
;; guard is a bet placed blind rather than an answer to their pick.
;;
;; `:protected` is a GENERIC slot rather than a role-named one, which is what lets
;; `:werewolf/chose` below consult it without knowing that a Beschützer exists. The
;; registry is where roles live; a second role that guards someone needs no change
;; to the kill.
;;
;; `:last-protected` is on the PLAYER, not in `:night-actions`, because
;; `:night-actions` is cleared at dawn and the no-repeat rule has to remember
;; across nights.
(defmethod apply-event :beschuetzer/protected
  [state {:keys [player target]}]
  (-> state
      (assoc-in [:night-actions :protected] target)
      (assoc-in [:players player :last-protected] target)))

(defmethod apply-event :werewolf/chose
  [state {:keys [target]}]
  (-> state
      (assoc-in [:night-actions :werewolf-target] target)
      ;; A guarded victim produces NO pending death at all, rather than one that
      ;; is removed again later the way the Hexe's heal works. The difference
      ;; matters to everything downstream that reads `:pending-deaths` — the
      ;; Hexe sees who is dying, and she must not be shown a death that is
      ;; already prevented.
      (cond-> (not= target (get-in state [:night-actions :protected]))
        (update :pending-deaths (fnil conj []) {:player target :cause :werwolf}))))

(defmethod apply-event :dieb/took
  [state {:keys [player role]}]
  (let [old (get-in state [:players player :role])]
    (-> state
        (assoc-in [:players player :role] role)
        ;; The new role's one-shot resources come with the card. Nothing is taken
        ;; away: he was a Dieb, and a Dieb has nothing to lose.
        (cond-> (roles/initial-resources role)
          (assoc-in [:players player :resources] (roles/initial-resources role)))
        ;; His old card goes back to the middle, replacing the one he took. Not
        ;; bookkeeping for its own sake — it keeps `:centre` the same size, and it
        ;; is the honest record of a swap rather than a gift.
        (assoc :centre (vec (replace {role old} (:centre state []))))
        ;; NOT PUBLISHED AT ALL, and not merely stripped of the role.
        ;;
        ;; The log is inside every player's view, so an entry saying "somebody took
        ;; a card" names the Dieb as surely as one saying which card it was — and
        ;; at a table the narrator wakes him in silence. Every other night action
        ;; is private for the same reason: the Seherin's look, the Beschützer's
        ;; guard and the Hexe's potions publish nothing either.
        ;;
        ;; He is recorded in `:night-actions` so the phase machine can see the turn
        ;; is done, which is state and not narration.
        (assoc-in [:night-actions :dieb] player))))

(defmethod apply-event :dieb/kept
  [state {:keys [player]}]
  ;; Also unpublished — see above. Keeping the card is as much of a tell as
  ;; swapping it.
  (assoc-in state [:night-actions :dieb] player))

(defmethod apply-event :weisser/killed
  [state {:keys [target]}]
  (-> state
      (assoc-in [:night-actions :weisser-target] target)
      ;; Its own cause, deliberately not :werwolf. The Hexe's heal cancels a
      ;; werewolf death, and this is not one — a wolf torn apart by the white one
      ;; is not something the village's witch gets to undo.
      (update :pending-deaths (fnil conj []) {:player target :cause :weisser})))

(defmethod apply-event :weisser/passed
  [state {:keys [player]}]
  (assoc-in state [:night-actions :weisser-passed] player))

(defmethod apply-event :hexe/healed
  [state {:keys [player target]}]
  (-> state
      (update-in [:players player :resources :heiltrank] dec)
      (assoc-in [:night-actions :hexe-heal] target)
      ;; Remove the pending werewolf death for that target.
      (update :pending-deaths
              (fn [ds] (vec (remove #(and (= target (:player %))
                                          (= :werwolf (:cause %)))
                                    ds))))))

(defmethod apply-event :hexe/poisoned
  [state {:keys [player target]}]
  (-> state
      (update-in [:players player :resources :gifttrank] dec)
      (assoc-in [:night-actions :hexe-poison] target)
      (update :pending-deaths (fnil conj []) {:player target :cause :gift})))

(defmethod apply-event :hexe/passed
  [state {:keys [player]}]
  (assoc-in state [:night-actions :hexe-passed] player))

;; ---- deaths and reactions ----

(defmethod apply-event :player/died
  [state {:keys [player cause]}]
  (if-not (alive? state player)
    state ; already dead — no-op keeps replay and live runs identical
    (-> state
        (assoc-in [:players player :alive?] false)
        (publish {:type :player/died
                  :player player
                  :cause cause
                  ;; Classic Werwolf reveals the role of the departed.
                  :role (get-in state [:players player :role])})
        ;; BEFORE the office is emptied below, because `death-reactions` reads
        ;; `:mayor` to decide whether a succession is owed. Clearing it first is
        ;; the obvious-looking order and it silently skipped the reaction — the
        ;; dying Bürgermeister was simply never asked.
        (death-reactions player)
        ;; The office empties the moment he dies, ahead of the succession
        ;; resolving. Leaving it pointing at a corpse would show the table a dead
        ;; Bürgermeister and, worse, keep weighting a vote he cannot cast. The
        ;; test reads the ORIGINAL `state`, so this is the pre-death holder.
        (cond-> (= player (:mayor state)) (assoc :mayor nil)))))

;; GM-only. Deliberately does NOT undo reactions the death already triggered —
;; reviving is an escape hatch for a mistake, not a rules rewind.
(defmethod apply-event :player/revived
  [state {:keys [player]}]
  (-> state
      (assoc-in [:players player :alive?] true)
      (publish {:type :player/revived :player player})))

(defmethod apply-event :reaction/popped
  [state _]
  ;; `pop` throws on an empty vector; a replay must never die on that.
  (update state :reactions (fn [rs] (if (seq rs) (pop rs) (or rs [])))))

(defmethod apply-event :jaeger/shot
  [state {:keys [player target]}]
  (-> state
      (publish {:type :jaeger/shot :player player :target target})
      (update :pending-deaths (fnil conj []) {:player target :cause :jaeger})))

;; ---- day: voting, lynch ----

;; The accusation step is gone — the day is one open ballot now, see
;; phases/auto-events — but a log written before that still holds these two,
;; and a room rehydrated from one must replay rather than throw.
(defmethod apply-event :player/nominated
  ;; Two sets, two different questions. :nominations is who stands accused (it
  ;; becomes the ballot); :nomination-done is who has had their say. One
  ;; accusation each, so naming somebody also ends your turn to name anybody.
  [state {:keys [by target]}]
  (-> state
      (update :nominations (fnil conj #{}) target)
      (update :nomination-done (fnil conj #{}) by)
      (publish {:type :player/nominated :by by :target target})))

(defmethod apply-event :nominate/passed
  ;; "I accuse nobody" is a decision and is recorded as one. Left implicit, a
  ;; player who has decided would be indistinguishable from one who has not
  ;; clicked yet, and the day would always have to run its clock down.
  [state {:keys [player]}]
  (-> state
      (update :nomination-done (fnil conj #{}) player)
      (publish {:type :nominate/passed :player player})))

(defmethod apply-event :player/ready
  ;; "I have said what I wanted to say." Public, because seeing who is still
  ;; thinking is half of why a discussion phase has a length at all.
  [state {:keys [player]}]
  (-> state
      (update :ready (fnil conj #{}) player)
      (publish {:type :player/ready :player player})))

(defmethod apply-event :day/no-accusation
  ;; Log-only. Worth saying out loud: a day where the village accuses nobody
  ;; looks identical in the state to a day that has not happened yet, and
  ;; players deserve to be told which one they are living through.
  [state _]
  (publish state {:type :day/no-accusation :day (:night state)}))

(defmethod apply-event :day/no-trial
  ;; Log-only, like :day/no-accusation: the first day ends without a vote by
  ;; rule, and the table should read that it was the rule and not a day where
  ;; nobody dared — see phases/trial-day?.
  [state _]
  (publish state {:type :day/no-trial :day (:night state)}))

(defmethod apply-event :jaeger/held-fire
  [state {:keys [player]}]
  (publish state {:type :jaeger/held-fire :player player}))

(defmethod apply-event :vote/cast
  [state {:keys [voter target]}]
  ;; Votes are public in classic Werwolf — that is the whole social game.
  ;; A nil target is an abstention, recorded rather than dropped: `tally`
  ;; ignores it when counting, but its presence is what tells the phase machine
  ;; this player has spoken.
  (-> state
      (assoc-in [:votes voter] target)
      (publish {:type :vote/cast :voter voter :target target})))

(defmethod apply-event :vote/resolved
  [state {:keys [target tie? no-majority?]}]
  (cond-> (-> state
              (assoc :vote-resolved? true)
              (publish (cond-> {:type :vote/resolved :target target :tie? tie?}
                         no-majority? (assoc :no-majority? true))))
    target (update :pending-deaths (fnil conj []) {:player target :cause :lynch})))

(defmethod apply-event :game/ended
  [state {:keys [winner]}]
  (-> state
      (assoc :phase :game-over :winner winner :deadline nil)
      (publish {:type :game/ended :winner winner})))

;; The same table, again: back to the lobby the game was started from, with
;; everybody still in their seat. What belongs to the ROOM survives — the
;; seats, who hosts and who narrates, the clock, the door, the Spielleiter's
;; deck — and everything the last game wrote is gone, cards and lovers and the
;; Bürgermeister included.
;;
;; Built from `:game/created` rather than by dissoc-ing the game's keys one by
;; one: a list of what to forget would forget to forget the next key somebody
;; adds. The seed carries over, already advanced by the last deal, so the next
;; deal is a new one. The Chronik starts over with the game.
(defmethod apply-event :game/reset
  [state _]
  (let [fresh (apply-event nil (assoc (select-keys state [:room-id :host :seed :min-players
                                                          :max-players :first-night-kill?
                                                          :first-day-trial?])
                                      :type :game/created))]
    (-> fresh
        (merge (select-keys state [:narrated? :timer? :password-hash :deck :host-absent?]))
        (assoc :players
               (into {}
                     (map (fn [[pid p]]
                            [pid (-> (select-keys p [:id :name :locale :connected? :absent-since])
                                     (assoc :role nil :alive? true))]))
                     (:players state)))
        (publish {:type :game/reset}))))

;; Server-authored: nobody came back, and the server closed the room. An end
;; without a winner, logged rather than only flagged in the rooms table, so a
;; replay for a recap ends where the game really did instead of hanging in
;; some night forever. See room/abandoned?.
(defmethod apply-event :game/abandoned
  [state _]
  (-> state
      (assoc :phase :game-over :winner nil :deadline nil)
      (publish {:type :game/abandoned})))

;; ---- GM overrides (always logged, never silent) ----

(defmethod apply-event :gm/forced
  [state {:keys [actor action detail]}]
  (publish state {:type :gm/forced :actor actor :action action :detail detail}))

;; GM-only: rewrite a player's role mid-game, for a deal that was wrong or a
;; player who has to be written out of one and into another.
;;
;; NOT published. This is the one override whose detail cannot go in the log:
;; :log is included in every player's view, so naming the new role there would
;; hand the whole table a card. What IS public is that the GM changed somebody's
;; role — see the :gm/forced entry the command emits alongside this — which is
;; the auditable part. Who they now are is between them and the narrator.
;;
;; Resources come from the new role, so a freshly-made Hexe has her potions; an
;; ex-Hexe loses hers rather than keeping a vial she can no longer use. This is
;; also the one way to refill a spent potion, by reassigning away and back.
;; Deliberate: an override that cannot undo the mistake it was reached for is
;; not much of an override, and it is logged both times.
;;
;; `:knows` is deliberately kept. A Seherin demoted to Dorfbewohner does not
;; unlearn what she saw, and her inspections were true when she made them. The
;; redaction boundary agrees: `revealed-roles` reads that same map, so she keeps
;; seeing exactly what she is still entitled to.
;;
;; `:centre`, when present, replaces the two spare cards. Only the Spielleiter's
;; setup step sends it: rearranging the deal before night 1 can add a Dieb to a
;; table that was dealt none, or take away the only one, and his two cards have
;; to appear or vanish with him. The command decides which; this only records it.
(defmethod apply-event :player/role-changed
  [state {:keys [player role] :as event}]
  (if-not (get-in state [:players player])
    state ; a replay must not resurrect a player who left
    (-> state
        (assoc-in [:players player :role] role)
        (update-in [:players player] dissoc :resources)
        (cond-> (roles/initial-resources role)
          (assoc-in [:players player :resources] (roles/initial-resources role)))
        (cond-> (contains? event :centre)
          (assoc :centre (vec (:centre event)))))))

;; ------------------------------------------------------------- replay ---

(defn replay
  "Fold an event log into state. `(= (replay events) state)` is a property the
   test suite enforces."
  [events]
  (reduce apply-event nil events))

(defn apply-all [state events]
  (reduce apply-event state events))
