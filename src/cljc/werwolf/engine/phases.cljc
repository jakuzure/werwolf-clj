(ns werwolf.engine.phases
  "The decision layer: what MAY happen, what MUST happen next, and who won.

   Division of labour with events.cljc:

     phases.cljc  decides  -> emits events
     events.cljc  applies  -> transitions state

   The core loop is `settle`: apply automatic transitions until the game is
   quiescent, i.e. blocked waiting for a human. `auto-events` returns nil at
   exactly those points. Everything a human narrator would do is `auto-events`;
   everything a player does arrives as a command.

   Interrupts (Jäger shooting on death, a lover dying of grief) are a LIFO
   reaction stack rather than special cases, which is what makes
   Jäger-shoots-Jäger and lover chains work without extra code.

   Two orderings here are load-bearing and easy to get wrong:

   1. Deaths queued DURING the night are not applied until the night ends.
      Otherwise the werewolves' victim would die before the Hexe is offered the
      chance to heal them.
   2. Entering :reaction records a :resume-phase, because an interrupt can fire
      from either day or night and must return to where it came from."
  (:require [werwolf.engine.events :as e]
            [werwolf.engine.roles :as roles]))

(def ^:const max-settle-steps
  "Backstop against a rule bug spinning forever. A real game settles in a
   handful of steps; hitting this is a bug, not a long game."
  512)

;; ------------------------------------------------------------ win check ---

(defn- teams-alive [state]
  (into #{} (map #(e/team-of-player state %)) (e/living-ids state)))

(defn winner
  "Which team has won, or nil if the game continues.

   Order matters: the lovers' win overrides everything, because a wolf and a
   villager in love beat both of their original teams.

   The Weißer Werwolf is the third exception, and the only one that can DELAY a
   victory rather than grant one. He is dealt as a werewolf — the village does not
   win while he lives, and the pack cannot eat him — but he wins alone. So a table
   of nothing but wolves is NOT a werewolf victory while he is among them: he
   still owes them a betrayal, and declaring the pack winners would rob him of the
   game he was playing for."
  [state]
  (let [living (e/living-ids state)
        lovers (set (:lovers state))
        whites (set (e/living-with-role state :weisser-werwolf))]
    (cond
      (empty? living) :village ; mutual annihilation — the village is not overrun

      (and (seq lovers)
           (= 2 (count living))
           (= (set living) lovers))
      :lovers

      :else
      (let [ts (teams-alive state)]
        (cond
          (not (contains? ts :werewolf)) :village

          (= ts #{:werewolf})
          (cond
            ;; Last one standing: exactly what he was after.
            (and (= 1 (count living)) (seq whites)) :weisser-werwolf
            ;; Wolves only, but he is still in the room — the night is not over.
            (seq whites) nil
            :else :werewolf)

          :else nil)))))

;; ------------------------------------------------- night turn ordering ---

(defn- role-can-act? [state role-id]
  (let [{:keys [first-night-only? skips-first-night? acts-on-even-nights?]}
        (roles/role role-id)
        first-night? (= 1 (:night state))]
    (and (seq (e/living-with-role state role-id))
         (or (not first-night-only?) first-night?)
         ;; Every second night, from the second. Named in the registry rather
         ;; than here, so this reads as a property of a role and not as a rule
         ;; about one.
         (or (not acts-on-even-nights?) (even? (:night state 0)))
         ;; Roles that sleep through night 1, unless this room opted back into
         ;; the classic rule. No role is named here on purpose — the fact that
         ;; the wolves are the ones who sleep lives in the registry.
         (not (and skips-first-night?
                   first-night?
                   (not (:first-night-kill? state)))))))

(defn night-queue
  "Roles still to act tonight, in night-order."
  [state]
  (let [done (set (get-in state [:night-actions :completed]))]
    (->> (roles/night-acting-ids)
         (filter #(role-can-act? state %))
         (remove done)
         vec)))

(defn- hexe-exhausted?
  "A Hexe with no potions left has nothing to decide, so we must not wait on her."
  [state]
  (every? (fn [pid]
            (let [r (get-in state [:players pid :resources])]
              (and (zero? (get r :heiltrank 0))
                   (zero? (get r :gifttrank 0)))))
          (e/living-with-role state :hexe)))

;; ------------------------------------------------------- day bookkeeping ---

(defn- ready-done?
  "Every living player has said they are finished talking."
  [state]
  (let [living (e/living-ids state)
        ready (:ready state #{})]
    (and (seq living) (every? ready living))))

(defn- nominations-done?
  "Has every living player either named someone or explicitly declined?

   Requires an explicit decline rather than treating silence as one: a table
   where two people have not clicked yet is not a finished nomination, and
   guessing otherwise would rush the fastest players' choice through."
  [state]
  (let [living (e/living-ids state)
        done (:nomination-done state #{})]
    (and (seq living) (every? done living))))

(defn- votes-done?
  "Every living player has cast a vote or a recorded abstention."
  [state]
  (let [living (e/living-ids state)]
    (and (seq living) (every? #(contains? (:votes state) %) living))))

(defn- election-done?
  "Every living player has cast a ballot for Bürgermeister or abstained."
  [state]
  (let [living (e/living-ids state)]
    (and (seq living) (every? #(contains? (:election-votes state {}) %) living))))

(defn- pack
  "Everyone who hunts with the wolves: the werewolf TEAM, not the werewolf role.

   The Weißer Werwolf is dealt as a werewolf and wakes with them, so he votes on
   the victim like any of them. Keying this on the role instead left him unable to
   act during their turn while the night waited for a vote he was never offered."
  [state]
  (filterv #(= :werewolf (e/team-of-player state %)) (e/living-ids state)))

(defn- wolves-all-voted? [state]
  (let [wolves (pack state)
        votes  (get-in state [:night-actions :werewolf-votes] {})]
    (and (seq wolves) (every? #(contains? votes %) wolves))))

;; ------------------------------------------------------- vote counting ---

(defn tally
  "Count votes. Returns {:target id :tie? bool}; nil target on a tie or when
   nobody voted. Abstentions (nil target) are ignored.

   Ties deliberately kill nobody: that is the classic rule, and it keeps an
   arbitrary tiebreak — which players would find unfair — out of the engine.

   `mayor`, when given, votes twice. That is the entire power of the office, and
   it is applied HERE rather than by stuffing a second ballot into `:votes`,
   which would make the vote counts shown to players disagree with the tally and
   the log disagree with both."
  ([votes] (tally votes nil))
  ([votes mayor]
   (let [counts (reduce (fn [m [voter target]]
                          (if (nil? target)
                            m
                            (update m target (fnil + 0) (if (= voter mayor) 2 1))))
                        {}
                        votes)]
     (if (empty? counts)
       {:target nil :tie? false}
       (let [best (apply max (vals counts))
             top  (->> counts (filter #(= best (val %))) (map key) sort vec)]
         (if (= 1 (count top))
           {:target (first top) :tie? false}
           {:target nil :tie? true}))))))

(defn wolf-tally
  "Werewolves must converge on one victim. Unlike the village lynch, a tie here
   cannot pass — the night must produce a decision — so ties break to the lowest
   player id: deterministic and replay-safe."
  [votes]
  (let [counts (->> (vals votes) (remove nil?) frequencies)]
    (when (seq counts)
      (let [best (apply max (vals counts))]
        (->> counts (filter #(= best (val %))) (map key) sort first)))))

;; --------------------------------------------------------- auto-events ---

(defn- pending-alive
  "Queued deaths that still refer to living players."
  [state]
  (filterv #(get-in state [:players (:player %) :alive?])
           (:pending-deaths state)))

(declare legal-actions)

(def always-allowed
  "Preferences, not moves. Offered in every phase, to the living and the dead,
   because a player must be able to change their language mid-game — asking them
   to wait for the lobby that will never come again is absurd."
  #{:set-locale})

(defn- not-really-acting?
  "Does this action count as 'this player still has something to do'? No, for
   chat, preferences and GM powers.

   Load-bearing for the night. `nobody-can-act?` uses it, and the moment
   :set-locale became available in every phase, a night role with nothing left to
   decide would have looked busy forever — the game would hang waiting for
   somebody to pick a language."
  [action]
  (or (= :chat action)
      (contains? always-allowed action)
      (= "gm" (namespace action))))

(defn- nobody-can-act?
  "True when nobody who acts on `role-id`'s turn has any usable action left.

   This is the general safety net behind the night: ANY role whose turn can
   become impossible would otherwise stall the game forever. Relying on a
   per-role special case is what let the exhausted-Seherin deadlock through.

   Holders are whoever ACTS on that turn, which for the wolves means the whole
   pack. Keyed on the role instead, the wolves' turn ended the moment the
   ordinary wolves had voted — the Weißer Werwolf was still owed a vote, was
   still being offered one, and the night closed with no kill at all."
  [state role-id]
  (let [holders (if (= :werwolf role-id)
                  (pack state)
                  (e/living-with-role state role-id))]
    (or (empty? holders)
        (every? (fn [pid]
                  (empty? (remove not-really-acting? (legal-actions state pid))))
                holders))))

(defn- night-auto [state]
  (let [q (night-queue state)]
    (cond
      (empty? q)
      (into [{:type :night/ended}]
            ;; A night where nobody died has to say so. Otherwise the Chronik
            ;; jumps from "the night falls" to "the village gathers" and players
            ;; cannot tell a peaceful night from a missing entry. True on the
            ;; first night by default, and whenever the Hexe's heal cancels the
            ;; only death.
            (if (empty? (pending-alive state))
              [{:type :night/quiet :night (:night state)}
               {:type :phase/changed :phase :day-reveal :deadline nil}]
              [{:type :phase/changed :phase :day-reveal :deadline nil}]))

      ;; Point the cursor at whoever is next.
      (not= (:night-cursor state) (first q))
      [{:type :night/role-turn :role (first q)}]

      :else
      (let [cursor (first q)]
        (or
         ;; Role-specific resolution first, because these produce a decision
         ;; (a victim) and not merely the end of a turn.
         (case cursor
           ;; Collective decision: once every living wolf has voted, resolve it.
           :werwolf
           (when (wolves-all-voted? state)
             (let [target (wolf-tally (get-in state [:night-actions :werewolf-votes]))]
               (cond-> [{:type :night/role-completed :role :werwolf}]
                 target (conj {:type :werewolf/chose :target target}))))

           ;; Nothing to decide with no potions — skip her rather than deadlock.
           :hexe
           (when (hexe-exhausted? state)
             [{:type :night/role-completed :role :hexe}])

           nil)

         ;; General fallback: nobody can act, so end the turn.
         (when (nobody-can-act? state cursor)
           [{:type :night/role-completed :role cursor}]))))))

(defn auto-events
  "The narrator's move: events that must fire without human input.
   nil means the game is waiting for a player, or is over."
  [state]
  (let [phase (:phase state)]
    (cond
      (= :game-over phase) nil

      ;; A decided game ends immediately, whatever phase we are in.
      (and (not= :lobby phase) (winner state))
      [{:type :game/ended :winner (winner state)}]

      ;; Deaths queue up during the night and are applied at dawn — see the
      ;; namespace docstring. Outside the night, apply them one at a time so
      ;; each can push its own reactions.
      (and (not= :night phase) (seq (pending-alive state)))
      (let [{:keys [player cause]} (first (pending-alive state))]
        [{:type :player/died :player player :cause cause}
         {:type :deaths/consumed :player player}])

      ;; Drain the reaction stack (LIFO).
      (seq (:reactions state))
      (let [{:keys [type player cause]} (peek (:reactions state))]
        (case type
          ;; Grief is automatic — nothing to decide.
          :reaction/lover-follows
          [{:type :reaction/popped}
           {:type :player/died :player player :cause (or cause :liebe)}]

          ;; The Jäger's shot needs input. If he is disconnected we still must
          ;; not deadlock, so the server/GM can force it; here we just park.
          ;; Both of these need a human. Parking is identical, so they share a
          ;; branch: the Jäger picks who he takes with him, the outgoing
          ;; Bürgermeister picks who succeeds him.
          (:reaction/jaeger-shot :reaction/mayor-succession)
          (when (not= :reaction phase)
            [{:type :phase/changed
              :phase  :reaction
              :resume (if (= :day-reveal phase) :day-reveal phase)
              :deadline nil}])))

      ;; Stack drained but still parked in :reaction — go back where we came from.
      (= :reaction phase)
      [{:type :phase/changed :phase (or (:resume-phase state) :day-reveal)
        :deadline nil}]

      :else
      (case phase
        :lobby nil ; the host starts the game

        :night (night-auto state)

        ;; The election happens once, on the first day, before anybody has
        ;; argued — which is how it goes at a table, where it is half a formality
        ;; and half the first read on the room. `:election-held?` rather than
        ;; "is it day 1" alone, so a GM who rewinds the phase cannot re-run it.
        :day-reveal
        (if (and (= 1 (:night state)) (not (:election-held? state)))
          [{:type :phase/changed :phase :election :deadline nil}]
          [{:type :day/began}])

        ;; Nobody is elected on a tie. Consistent with the lynch, where a tie
        ;; kills nobody: an arbitrary tiebreak is exactly what players find
        ;; unfair, and the village simply goes without.
        :election
        (when (election-done? state)
          (let [{:keys [target tie?]} (tally (:election-votes state {}))]
            [{:type :election/resolved :mayor target :tie? tie?}
             {:type :day/began}]))

        ;; Discussion has nothing to resolve, so it ends when everybody says
        ;; they are finished. Without this the table talks itself out in forty
        ;; seconds and then watches a five-minute clock.
        :discussion
        (when (ready-done? state)
          [{:type :phase/changed :phase :nomination :deadline nil}])

        ;; The other two end themselves the moment nobody is still being waited
        ;; for. Sitting out a 90-second timer that everyone has already finished
        ;; with is the single most tedious thing a digital adaptation can do.
        :nomination
        (when (nominations-done? state)
          (if (seq (filter (set (e/living-ids state)) (:nominations state)))
            [{:type :phase/changed :phase :vote :deadline nil}]
            ;; Nobody was accused, so there is nothing to vote on. The village
            ;; is allowed to condemn no one — `tally` already lets a tie kill
            ;; nobody, so a day with no lynch is not a new kind of outcome.
            [{:type :day/no-accusation}
             {:type :phase/changed :phase :day-resolve :deadline nil}]))

        :vote
        (when (votes-done? state)
          [{:type :phase/changed :phase :day-resolve :deadline nil}])

        :day-resolve
        (if (:vote-resolved? state)
          ;; Lynch death has been applied by now (branch above), so night can begin.
          [{:type :night/began :night (inc (:night state))}]
          (let [{:keys [target tie?]} (tally (:votes state) (:mayor state))]
            [{:type :vote/resolved :target target :tie? tie?}]))

        nil))))

(defn settle
  "Run automatic transitions until the game needs input. Returns [state events]."
  [state]
  (loop [s state, acc [], n 0]
    (if (> n max-settle-steps)
      (throw (ex-info "settle did not converge — rule bug"
                      {:phase (:phase s)
                       :reactions (:reactions s)
                       :pending (:pending-deaths s)}))
      (if-let [evs (seq (auto-events s))]
        (recur (e/apply-all s evs) (into acc (vec evs)) (inc n))
        [s acc]))))

;; ------------------------------------------------------- legal actions ---

(def next-phase
  "The host/timer-driven progression through the day. Lives here rather than in
   commands.cljc so legal-actions can consult it without a circular dependency."
  {:discussion :nomination
   :nomination :vote
   :vote       :day-resolve})

;; ------------------------------------------------------------ the clock ---

(def timed-phases
  "Phases the clock owns, mapped to the config key holding their length.

   Which phases are timed is a RULE and lives here; how long they last is
   deployment policy and lives in config. Phases absent from this map have no
   deadline by design: the lobby waits for the host, :day-reveal and
   :day-resolve are instantaneous narrator steps, and :game-over waits for
   nobody."
  {:election   :election-secs
   :night      :night-secs
   :discussion :discussion-secs
   :nomination :nomination-secs
   :vote       :vote-secs
   :reaction   :reaction-secs})

(defn timeout-events
  "What it MEANS for the clock to run out here. Pure, so the rule is testable
   on both platforms and the server only has to decide *when*.

   The principle throughout: a timeout must never stall the game and must never
   invent a decision nobody made. Silence is an abstention, and an abstention
   counts as itself — `tally` already treats a tie as killing nobody, so a room
   that says nothing simply survives the day."
  [state]
  (let [phase (:phase state)]
    (when (contains? timed-phases phase)
      (into [{:type :phase/timed-out :phase phase}]
            (case phase
              ;; End the current role's turn. The wolves are the exception: they
              ;; are making a collective decision, so a pack that cannot agree
              ;; in time still eats whoever led the vote. Deciding nothing would
              ;; hand the village a free night for stalling.
              :night
              (let [cursor (:night-cursor state)]
                (when cursor
                  (cond-> [{:type :night/role-completed :role cursor}]
                    (= :werwolf cursor)
                    (as-> evs
                          (if-let [t (wolf-tally (get-in state [:night-actions :werewolf-votes]))]
                            (conj evs {:type :werewolf/chose :target t})
                            evs)))))

              ;; The Jäger who never fires simply does not fire — the shot is
              ;; his to take, not the server's to guess. Same for a
              ;; Bürgermeister who names nobody: the office falls vacant.
              :reaction [{:type :reaction/popped}]

              ;; Uncast ballots are abstentions, exactly as in the lynch, so the
              ;; election resolves on whatever was actually cast.
              :election
              (let [{:keys [target tie?]} (tally (:election-votes state {}))]
                [{:type :election/resolved :mayor target :tie? tie?}
                 {:type :day/began}])

              ;; The day marches on regardless. Uncast votes are abstentions,
              ;; which is what `tally` already does with them.
              [{:type :phase/changed :phase (next-phase phase) :deadline nil}])))))

(defn- night-actor?
  "Is pid one of the players whose role is currently on the night cursor?

   With one exception, and it is a rule rather than a convenience: the wolves'
   turn belongs to the whole PACK, which includes the Weißer Werwolf. He hunts
   with them — his own turn, two hours later in the night order, is where he turns
   on them."
  [state pid]
  (let [cursor (:night-cursor state)
        role   (get-in state [:players pid :role])]
    (and cursor
         (get-in state [:players pid :alive?])
         (or (= cursor role)
             (and (= :werwolf cursor)
                  (= :werewolf (e/team-of-player state pid)))))))

(defn host-actions
  "GM powers, which belong to the room's host and are NOT lost on death.

   Public so tests can exempt exactly these when asserting that dead players
   cannot act, rather than duplicating the list and drifting from it.

   A dead host must keep them: they are the fallback narrator, and stripping
   the powers on death would deadlock any phase that only the host can advance."
  [state pid]
  (if-not (and (= pid (:host state))
               (not= :game-over (:phase state))
               (not= :lobby (:phase state)))
    #{}
    (cond-> #{:gm/force-kill :gm/revive :gm/set-phase :gm/reassign-role}
      (contains? next-phase (:phase state)) (conj :advance-phase)
      (= :night (:phase state))             (conj :gm/skip-role)
      ;; The narrator's oldest privilege: "hang on, let him finish."
      (:paused? state)                      (conj :gm/resume)
      (not (:paused? state))                (conj :gm/pause)
      (:deadline state)                     (conj :gm/add-time))))

(defn- player-actions
  "What pid may do as a *player* (ignoring GM powers)."
  [state pid]
  (let [phase  (:phase state)
        player (get-in state [:players pid])
        host?  (= pid (:host state))]
    (cond
      ;; The game is decided, so there is nothing left to play — but there is
      ;; something left to DO, and it used to be nothing at all: the final
      ;; screen offered no way out, so the only exit from a finished game was
      ;; closing the tab. Leaving is not a move; it is the door.
      (= :game-over phase) #{:leave}

      ;; The lobby is where people arrange the game, so of course they may talk.
      ;;
      ;; The host may start BELOW :min-players. That number is the recommended
      ;; table size — it is what the lobby counts up to and what the UI nags
      ;; about — but a group of four who want to play now should not be held
      ;; back by a config value set for a bigger evening. The one floor that
      ;; does bind is the deck's: below three there is no game to start, and
      ;; that limit belongs to the role composition, not to a preference.
      (= :lobby phase)
      (cond-> #{:chat :leave :set-locale}
        (and host? (>= (count (:players state)) roles/absolute-min-players))
        (conj :start-game))

      ;; The Jäger fires his shot while ALREADY DEAD, so this must be checked
      ;; before the general dead-players-may-only-chat rule below.
      (= :reaction phase)
      (let [r (peek (:reactions state))]
        (if (and (= :reaction/mayor-succession (:type r)) (= pid (:player r)))
          ;; He is dead and choosing his successor. Declining is allowed for the
          ;; same reason the Jäger may hold his fire: the clock lets him off
          ;; anyway, so refusing a button would only punish whoever is paying
          ;; attention.
          #{:mayor-appoint :mayor-decline :chat}
          (if (and (= :reaction/jaeger-shot (:type r)) (= pid (:player r)))
          ;; He may talk while he aims. The whole table is waiting on him and
          ;; everyone else is chatting; silencing exactly the player being
          ;; waited for is arbitrary. Chat routing still sends him to the dead
          ;; channel, since he is one of them now.
          ;;
          ;; And he may decline. The clock already lets him off if he says
          ;; nothing, so refusing him a button to say the same thing on purpose
          ;; would only punish the player who is paying attention.
            #{:jaeger-shoot :jaeger-pass :chat}
            #{:chat})))

      ;; Otherwise the dead may watch and talk, never act.
      (not (:alive? player)) #{:chat}

      (= :night phase)
      (if (night-actor? state pid)
        (case (:night-cursor state)
          :amor    (if (= 1 (:night state)) #{:amor-link} #{})
          :seherin #{:seherin-inspect}
          :beschuetzer #{:beschuetzer-protect}
          ;; He may decline, like the Hexe and the Jäger: removing a wolf tonight
          ;; is a judgement about timing, and a role whose only option is to act
          ;; has no decision to make.
          :weisser-werwolf #{:weisser-kill :weisser-pass :chat}
          ;; Wolves may talk among themselves at night (private room at M7).
          ;; The whole pack votes, the white one included — see `night-actor?`.
          :werwolf (if (contains? (get-in state [:night-actions :werewolf-votes] {}) pid)
                     #{:chat}
                     #{:werewolf-vote :chat})
          :hexe    (cond-> #{:hexe-pass}
                     (pos? (get-in player [:resources :heiltrank] 0)) (conj :hexe-heal)
                     (pos? (get-in player [:resources :gifttrank] 0)) (conj :hexe-poison))
          #{})
        #{})

      ;; Abstaining is a real answer here too, and for the same reason as in the
      ;; lynch: without a button the only way to decline is silence, which is
      ;; indistinguishable from a lost connection.
      (= :election phase)
      (cond-> #{:chat}
        (not (contains? (:election-votes state {}) pid)) (conj :elect :elect-abstain))

      (= :discussion phase)
      (cond-> #{:chat}
        (not (contains? (:ready state #{}) pid)) (conj :ready))

      ;; One accusation each, and declining is one of the two answers. Having
      ;; had your say removes both buttons, which is also what makes the phase
      ;; able to end itself.
      (= :nomination phase)
      (if (contains? (:nomination-done state #{}) pid)
        #{:chat}
        #{:chat :nominate :nominate-pass})

      ;; Abstaining is a real answer, not the absence of one. Without a button
      ;; for it the only way to condemn nobody is to say nothing, which is
      ;; indistinguishable from having lost your connection.
      (= :vote phase)
      (cond-> #{:chat}
        (not (contains? (:votes state) pid)) (conj :vote :vote-abstain))

      :else #{:chat})))

(def target-arity
  "How many players an action needs the client to pick. Default 1.

   Amor is the only role that points at two people at once, and this map exists
   so the UI can learn that from the server instead of hard-coding a special
   case for one role — the next two-target role then needs no client change at
   all. It is also why `:amor-link` carries :a and :b rather than :target."
  {:amor-link 2})

(def dead-permitted-actions
  "The only actions a dead player may legitimately still hold.

   Public for the same reason `host-actions` is: the simulation asserts that the
   dead cannot act, and it must exempt exactly these rather than keep its own
   copy of the list. It kept one once, and adding `:jaeger-pass` then looked
   like 274 invariant violations.

   The Jäger is the whole reason this set is not empty — he fires, or declines
   to fire, while already dead. The rest are not acting at all: talking,
   choosing what language to read the game in, and walking out of a game that
   is already over."
  (into #{:chat :jaeger-shoot :jaeger-pass :leave
          ;; A dead Bürgermeister names who follows him. Like the Jäger's shot,
          ;; this is an act performed while already dead.
          :mayor-appoint :mayor-decline}
        always-allowed))

(def no-target-actions
  "Commands that carry no :target. Everything else needs at least one legal
   target to be offerable at all."
  #{:start-game :leave :set-locale :chat :advance-phase :ready
    :gm/pause :gm/resume :gm/add-time
    ;; The ways to decline. Each is a deliberate 'no', which is why they are
    ;; commands at all rather than the absence of one.
    :hexe-pass :nominate-pass :vote-abstain :jaeger-pass
    :elect-abstain :mayor-decline :weisser-pass})

(declare legal-targets legal-choices)

(defn legal-actions
  "What pid may do right now. Drives both the UI's affordances and the
   simulation harness, so it must be exhaustive and honest.

   'Honest' specifically means: an action is never offered when it has nothing
   to point at. Otherwise the UI shows a dead button and, worse, the night can
   deadlock — the Seherin who has already inspected everyone still being offered
   :seherin-inspect was a real bug the simulation harness caught.

   'Nothing to point at' means neither a legal target nor a legal choice: a GM
   override that names a phase rather than a player is still perfectly usable."
  [state pid]
  (if-not (get-in state [:players pid])
    #{}
    (let [candidates (-> (player-actions state pid)
                         (into (host-actions state pid))
                         (into always-allowed))]
      (into #{}
            (remove (fn [action]
                      (and (not (contains? no-target-actions action))
                           (empty? (legal-targets state pid action))
                           (empty? (legal-choices state action)))))
            candidates))))

(defn legal-targets
  "Valid targets for an action, so the UI never offers an illegal click and the
   simulation never generates one."
  [state pid action]
  (let [living (e/living-ids state)
        others (vec (remove #{pid} living))]
    (case action
      ;; No point re-inspecting someone she already knows.
      :seherin-inspect (vec (remove #(contains? (get-in state [:players pid :knows] {}) %) others))
      ;; Wolves cannot eat their own.
      :werewolf-vote   (vec (remove #(= :werewolf (e/team-of-player state %)) living))
      ;; She can only heal someone actually dying tonight.
      :hexe-heal       (mapv :player (filter #(= :werwolf (:cause %)) (:pending-deaths state)))
      ;; Everyone living, HIMSELF INCLUDED — guarding your own door is a real
      ;; choice and the rules allow it — except whoever he guarded last night.
      ;; The ban is read from the registry rather than named here, so a second
      ;; guarding role would inherit it.
      :beschuetzer-protect
      (let [last-target (when (:no-repeat-target? (roles/role :beschuetzer))
                          (get-in state [:players pid :last-protected]))]
        (vec (remove #(= % last-target) living)))

      ;; His own pack, himself excepted. He is the only role that targets
      ;; werewolves, which is the whole of his threat.
      :weisser-kill    (vec (remove #(= % pid)
                                    (filter #(= :werewolf (e/team-of-player state %))
                                            living)))
      :hexe-poison     others
      :jaeger-shoot    others
      :amor-link       (vec living)
      ;; Anyone living, HIMSELF INCLUDED — standing for the office is a normal
      ;; thing to do and hiding it would just push the vote to a proxy.
      :elect           (vec living)
      ;; Anyone living but himself: he is dead, and naming a corpse would leave
      ;; the office empty in a way that reads as a bug rather than a choice.
      :mayor-appoint   others
      :nominate        others
      ;; MUST be sorted: :nominations is a set, and set iteration order differs
      ;; between Clojure and ClojureScript. Returning it unsorted made the
      ;; target list platform-dependent, which in turn made replay
      ;; platform-dependent — the same seed played out differently on Node.
      :vote            (let [noms (sort (filter (set living) (:nominations state)))]
                         (vec (if (seq noms) noms others)))

      ;; GM overrides need targets too, or the host's UI has nothing to offer.
      :gm/force-kill   (vec living)
      :gm/revive       (vec (sort (keep (fn [[id p]] (when-not (:alive? p) id))
                                       (:players state))))
      ;; Everyone, dead included: the reason to rewrite a dead player's role is
      ;; usually that the deal was wrong, and reviving them first would put a
      ;; second correction in the log to undo the first.
      :gm/reassign-role (vec (sort (keys (:players state))))
      [])))

(defn legal-choices
  "Valid NON-PLAYER options for an action: a phase, a role.

   Separate from `legal-targets` because the two are not interchangeable, and
   conflating them was a real bug — `:gm/set-phase` and `:gm/skip-role` returned
   keywords from `legal-targets`, the client dutifully entered its pick-a-player
   mode, and no player card matched. Both GM controls looked present and neither
   could be used. A target is a seat; a choice is a word.

   Takes no pid: what a phase or a role IS does not depend on who is asking.
   Whether the action is offered at all is `host-actions`' business."
  [state action]
  (case action
    :gm/set-phase     (vec (sort (keys next-phase)))
    ;; Only the role currently being called — skipping a role that is not
    ;; speaking would silently mark its turn done for the rest of the night.
    :gm/skip-role     (if-let [c (:night-cursor state)] [c] [])
    :gm/reassign-role roles/assignable-ids
    []))
