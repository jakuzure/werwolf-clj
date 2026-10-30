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
      chance to heal them. A reaction that interrupts the night is still the
      night; see `night?`.
   2. Entering :reaction records a :resume-phase, because an interrupt can fire
      from either day or night and must return to where it came from.
   3. Nobody but the village wins while a death is still owed: the queued
      ones, and the ones a reaction on the stack can still cause. See
      `deaths-outstanding?`."
  (:require [werwolf.engine.events :as e]
            [werwolf.engine.roles :as roles]))

(def ^:const max-settle-steps
  "Backstop against a rule bug spinning forever. A real game settles in a
   handful of steps; hitting this is a bug, not a long game."
  512)

;; ------------------------------------------------------------ win check ---

(defn- teams-alive [state]
  (into #{} (map #(e/team-of-player state %)) (e/living-ids state)))

(defn- betrayal-let-go?
  "Did the Weißer Werwolf's turn tonight end without a wolf taken — passed,
   timed out or skipped alike, since silence counts as itself? Only during
   that night: a pass made while a villager still stood was his to make, and
   the table becoming wolves-only by day or at dawn must not turn it into a
   forfeit after the fact. He gets his next night instead."
  [state]
  (and (= :night (:phase state))
       (contains? (set (get-in state [:night-actions :completed])) :weisser-werwolf)
       (nil? (get-in state [:night-actions :weisser-target]))))

(defn- side
  "Whose win a player is playing for: his dealt team, except the Weißer
   Werwolf, who is dealt to the pack and wins alone."
  [state pid]
  (if (= :weisser-werwolf (get-in state [:players pid :role]))
    :weisser-werwolf
    (e/team-of-player state pid)))

(defn winner
  "Which team has won, or nil if the game continues.

   Order matters: the lovers' win overrides everything, because a wolf and a
   villager in love beat both of their original teams.

   Only a couple from two sides, though. Two wolves in love who outlive the
   village have won as wolves, and their dead packmates with them; called a
   win for the lovers, the pack's game went to the two of them alone and every
   other wolf took a loss. The same for two villagers. The Weißer Werwolf is a
   side of his own here: he and a wolf in love have nothing left to win but
   each other, and waiting on his betrayal would mean waiting for him to kill
   himself through his lover's grief.

   The Weißer Werwolf is the third exception, and the only one that can DELAY a
   victory rather than grant one. He is dealt as a werewolf — the village does not
   win while he lives, and the pack cannot eat him — but he wins alone. So a table
   of nothing but wolves is NOT a werewolf victory while he is among them: he
   still owes them a betrayal, and declaring the pack winners would rob him of the
   game he was playing for. Only until his turn comes and he lets it go by,
   though; see the clause below."
  [state]
  (let [living (e/living-ids state)
        lovers (set (:lovers state))
        whites (set (e/living-with-role state :weisser-werwolf))]
    (cond
      (empty? living) :village ; mutual annihilation — the village is not overrun

      (and (seq lovers)
           (= 2 (count living))
           (= (set living) lovers)
           (apply not= (map #(side state %) lovers)))
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
            ;; Unless his turn came tonight and he took nobody: with only
            ;; wolves left there is nobody else for him to wait out, so a turn
            ;; let go by is the betrayal not coming. Waiting on it regardless
            ;; had no end: in an all-bots game the absent host held the card,
            ;; so never hunted, and was Bürgermeister too, so the two wolves
            ;; left could never outvote him, and the pack cannot eat its own.
            ;; The game ran on for ever.
            (and (seq whites) (not (betrayal-let-go? state))) nil
            :else :werewolf)

          :else nil)))))

;; ------------------------------------------------- night turn ordering ---

(declare pack)

(defn- holders
  "Who acts on `role-id`'s turn. The wolves' turn belongs to the whole PACK —
   the Weißer Werwolf hunts with them — so it comes round while any of them
   lives. Keyed on the role, a Weißer Werwolf left alone with the village was
   never woken to eat again: the night passed without a kill, and with the vote
   needing a majority, two players left could never condemn each other either.
   The game ran on forever."
  [state role-id]
  (if (= :werwolf role-id)
    (pack state)
    (e/living-with-role state role-id)))

(defn- role-can-act? [state role-id]
  (let [{:keys [first-night-only? skips-first-night? acts-on-even-nights?]}
        (roles/role role-id)
        first-night? (= 1 (:night state))]
    (and (seq (holders state role-id))
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

(declare legal-targets)

(defn- white-wolf-alone?
  "No wolf left for the Weißer Werwolf to take: his only answer would be to
   pass, so — like the Hexe without potions — his turn ends as it starts. The
   QA game called him for a turn of \"Heute keinen Wolf reißen\" as the last
   wolf standing, and with a clock that cost the table 45 seconds a night."
  [state]
  (every? #(empty? (legal-targets state % :weisser-kill))
          (e/living-with-role state :weisser-werwolf)))

(defn- ready-done?
  "Every living player has said they are finished talking."
  [state]
  (let [living (e/living-ids state)
        ready (:ready state #{})]
    (and (seq living) (every? ready living))))

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

;; ------------------------------------------------------- the first day ---

(defn trial-day?
  "Does today end in an accusation and a vote? Every day but the first.

   On the first day nobody knows anything yet: the night was the cards being
   looked at, and whoever the village accused would be a guess — usually the
   quietest player, or the one who arrived last. So the first day is for
   talking and the Bürgermeister's election, and the night falls when the
   table is done. `:first-day-trial?`, stamped in at creation like
   `:first-night-kill?`, restores the classic rule."
  [state]
  (or (:first-day-trial? state) (> (:night state 0) 1)))

(declare next-phase)

(defn- nightfall [state]
  [{:type :night/began :night (inc (:night state 0))}])

(defn verdict-held?
  "Is the table looking at whom it has just condemned, with the night yet to
   fall?

   The verdict used to be one commit from the last ballot to the next night: the
   card a lynch turns over was in the Chronik, and the screen had already gone
   dark. The table asked to see it first. So :day-resolve rests here, once the
   condemned has died and any last move of his has been made — a lynched
   Jäger's victim belongs on the same card — until the clock or the
   Spielleiter lets the night fall.

   Only after a condemnation. A day that condemned nobody has no card to turn
   over, and holding the table for it would be waiting for nothing."
  [state]
  (and (= :day-resolve (:phase state))
       (some? (:condemned state))))

(defn- leave-day-phase
  "The events that end a day phase: the next one, or, at the end of the first
   day's talk, the night."
  [state phase]
  (if (and (= :discussion phase) (not (trial-day? state)))
    (into [{:type :day/no-trial}] (nightfall state))
    [{:type :phase/changed :phase (next-phase phase) :deadline nil}]))

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

(defn- vote-weight [mayor voter] (if (= voter mayor) 2 1))

(defn- living-ballots
  "The ballots that still count: cast by somebody alive, for somebody alive or
   for nobody. A voter who has walked out or been killed holds no vote any
   more, and a ballot naming a corpse can neither condemn nor elect it — it is
   left as an abstention.

   Three departed players once lynched the Seherin against the two living
   wolves, a candidate who died mid-election was elected anyway, and a wolf
   who had left still broke his pack's tie.

   Filtered where they are counted, not removed when the death is applied:
   stored games replay through that reducer, and a filter there rewrote the
   votes in 128 of 142 stored logs. The cast ballots stay in state and in the
   log, as what was cast."
  [state votes]
  (let [living (set (e/living-ids state))]
    (into {}
          (filter (fn [[voter target]]
                    (and (living voter) (or (nil? target) (living target)))))
          votes)))

(defn votes-needed
  "How many votes condemn somebody today: a strict majority of every vote the
   living hold, the Bürgermeister's counting twice. Abstaining, or not voting
   at all, therefore counts AGAINST a condemnation — which is the point.

   The old rule was the most votes among the accused, so three votes out of
   eleven could hang a player while eight people abstained or looked away. A
   village that kills one of its own should at least mean it."
  [state]
  (let [mayor (:mayor state)
        total (reduce + 0 (map #(vote-weight mayor %) (e/living-ids state)))]
    (inc (quot total 2))))

(defn verdict
  "Who the village condemns: `tally`'s leader, if they reach `votes-needed`.
   {:target id-or-nil :tie? bool :no-majority? bool}."
  [state]
  (let [votes (living-ballots state (:votes state {}))
        mayor (:mayor state)
        {:keys [target tie?]} (tally votes mayor)
        got (reduce + 0 (keep (fn [[voter t]] (when (and t (= t target))
                                                 (vote-weight mayor voter)))
                               votes))]
    (if (and target (< got (votes-needed state)))
      {:target nil :tie? false :no-majority? true}
      {:target target :tie? tie? :no-majority? false})))

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

(defn pending-alive
  "Queued deaths that still refer to living players. Public because the views
   show the same queue: whoever has walked out since is not dying any more."
  [state]
  (filterv #(get-in state [:players (:player %) :alive?])
           (:pending-deaths state)))

(defn- night?
  "Is it still night? A reaction that interrupts the night — the Jäger or the
   Bürgermeister dying mid-night, through a GM's kill or a lover walking out —
   parks the game in :reaction, but the night is not over. Asked of the phase
   alone, the parked night counted as day: the wolves' victim died on the spot,
   the Hexe woke to nobody to heal, and the Chronik called it a quiet night
   right under the victim's death."
  [state]
  (= :night (if (= :reaction (:phase state))
              (:resume-phase state)
              (:phase state))))

(def ^:private deadly-reactions
  "Reactions that can still kill somebody: the Jäger's shot, and grief."
  #{:reaction/jaeger-shot :reaction/lover-follows})

(defn- deaths-outstanding?
  "Is a death still owed that could change who has won? The queued ones, and
   the ones a reaction on the stack can still cause. At night the queue waits
   for dawn, and so does the verdict on the table it leaves.

   A real game ended on the first of two dawn deaths: the wolves had eaten the
   Hexe while she poisoned the last wolf, her death was applied first, and the
   table read as wolves only. Both died, so the village had won. And a Jäger
   eaten as the last villager never got the shot that could have taken the
   last wolf with him. A Bürgermeister's succession is not in it: naming an
   heir changes nobody's side, so a decided game does not wait for it."
  [state]
  (or (seq (pending-alive state))
      (some #(deadly-reactions (:type %)) (:reactions state))))

(declare legal-actions)

(def always-allowed
  "Preferences and the door, not moves. Offered in every phase, to the living
   and the dead, because a player must be able to change their language
   mid-game — asking them to wait for the lobby that will never come again is
   absurd.

   And `:leave`, for the same reason. It used to be lobby and game-over only, so
   once the cards were dealt nobody could walk out — not even the Spielleiter —
   and closing the tab did not help either: the page resumes its seat from the
   token on reload. Leaving mid-game keeps the seat and the card (see
   apply-event :player/left), so the game it leaves behind is one with an absent
   player, which every phase already survives."
  #{:set-locale :leave})

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
      (= "gm" (namespace action))
      ;; A GM power despite its plain name. It never used to be offered at
      ;; night, so it never mattered here — but an untimed room hands it to the
      ;; host in every phase the clock would otherwise end, and a host who also
      ;; holds a role would then look busy for the whole of that role's turn.
      (= :advance-phase action)
      ;; Offered to EVERY connected player while the host is missing, so with
      ;; it counted every night turn would wait for somebody to take a seat
      ;; nobody has to take.
      (= :claim-host action)))

(defn voted-today?
  "Has pid cast (or abstained) today's ballot, during the open vote?"
  [state pid]
  (and (= :discussion (:phase state))
       (trial-day? state)
       (contains? (:votes state {}) pid)))

(defn owing
  "The participants the game is still waiting on: everybody who holds at least
   one real move right now, by the same test the night uses to decide a role is
   done. Sorted seat ids, never the Spielleiter.

   For the Spielleiter's screen. At night this names the players behind the
   role being called, so it must never reach anybody else — view.cljc puts it
   in his notes only."
  [state]
  (into []
        (filter (fn [pid]
                  (and (some #(not (not-really-acting? %)) (legal-actions state pid))
                       ;; The day's ballot stays open to change, so the buttons
                       ;; are still offered after a vote — but whoever has cast
                       ;; one is not what the table is waiting for.
                       (not (voted-today? state pid)))))
        (e/participant-ids state)))

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
  (let [hs (holders state role-id)]
    (or (empty? hs)
        (every? (fn [pid]
                  (empty? (remove not-really-acting? (legal-actions state pid))))
                hs))))

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
             (let [target (wolf-tally (living-ballots
                                       state (get-in state [:night-actions :werewolf-votes])))]
               (cond-> [{:type :night/role-completed :role :werwolf}]
                 target (conj {:type :werewolf/chose :target target}))))

           ;; Nothing to decide with no potions — skip her rather than deadlock.
           :hexe
           (when (hexe-exhausted? state)
             [{:type :night/role-completed :role :hexe}])

           :weisser-werwolf
           (when (white-wolf-alone? state)
             [{:type :night/role-completed :role :weisser-werwolf}])

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

      ;; Deaths queue up during the night and are applied at dawn — see the
      ;; namespace docstring. Outside the night, apply them one at a time so
      ;; each can push its own reactions. Ahead of the win check, so a dawn is
      ;; judged whole rather than one death at a time.
      (and (not (night? state)) (seq (pending-alive state)))
      (let [{:keys [player cause]} (first (pending-alive state))]
        [{:type :player/died :player player :cause cause}
         {:type :deaths/consumed :player player}])

      ;; A decided game ends immediately, whatever phase we are in — except
      ;; before it has begun, and while a death that could change the result
      ;; is still owed. In :setup the Spielleiter is still arranging the
      ;; cards, and a table he has momentarily left without a wolf is a deal in
      ;; progress, not a village victory.
      ;;
      ;; The village's win is final the moment it is there: no death brings a
      ;; wolf back. Waiting on what is still owed could not change it, and a
      ;; Jäger asked for a shot that decides nothing would only hold the table.
      (and (not (#{:lobby :setup} phase))
           (winner state)
           (or (= :village (winner state)) (not (deaths-outstanding? state))))
      [{:type :game/ended :winner (winner state)}]

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
        :setup nil ; the Spielleiter begins the night

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
          (let [{:keys [target tie?]} (tally (living-ballots state (:election-votes state {})))]
            [{:type :election/resolved :mayor target :tie? tie?}
             {:type :day/began}]))

        ;; One step for the whole day: the table talks and votes at once, and
        ;; the step ends the moment nobody is still being waited for. Sitting
        ;; out a five-minute clock that everyone has already finished with is
        ;; the single most tedious thing a digital adaptation can do.
        ;;
        ;; It used to be three — talk, accuse, vote among the accused — each
        ;; waiting on every player's click. Online, where the ballot is on
        ;; screen and every vote shows on the accused's card as it is cast,
        ;; the accusation step only added a round of waiting: the running
        ;; tally does what naming the accused did. The first day has no ballot
        ;; at all (see trial-day?) and ends when everybody says they are done.
        :discussion
        (if (trial-day? state)
          (when (votes-done? state)
            [{:type :phase/changed :phase :day-resolve :deadline nil}])
          (when (ready-done? state)
            (leave-day-phase state :discussion)))

        ;; A game rehydrated from before the day was one step, caught between
        ;; its old accusation and its old ballot. Back into the open vote, with
        ;; whatever ballots were already cast.
        (:nomination :vote)
        [{:type :phase/changed :phase :discussion :deadline nil}]

        :day-resolve
        (cond
          ;; The lynch death has been applied by now (branch above), and the
          ;; table is reading whose it was. The clock or the Spielleiter ends it.
          (verdict-held? state) nil

          (:vote-resolved? state) (nightfall state)

          :else
          (let [{:keys [target tie? no-majority?]} (verdict state)]
            [(cond-> {:type :vote/resolved :target target :tie? tie?}
               no-majority? (assoc :no-majority? true))]))

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
  {:discussion :day-resolve})

;; ------------------------------------------------------------ the clock ---

(def timed-phases
  "Phases the clock owns, mapped to the config key holding their length.

   Which phases are timed is a RULE and lives here; how long they last is
   deployment policy and lives in config. Phases absent from this map have no
   deadline by design: the lobby waits for the host, :day-reveal is an
   instantaneous narrator step, and :game-over waits for nobody.

   :day-resolve is only ever at rest while `verdict-held?`, so its clock is the
   length of that moment and nothing else."
  {:election    :election-secs
   :night       :night-secs
   :discussion  :discussion-secs
   :reaction    :reaction-secs
   :day-resolve :verdict-secs})

(defn timeout-events
  "What it MEANS for the clock to run out here. Pure, so the rule is testable
   on both platforms and the server only has to decide *when*.

   The principle throughout: a timeout must never stall the game and must never
   invent a decision nobody made. Silence is an abstention, and an abstention
   counts as itself — `tally` already treats a tie as killing nobody, so a room
   that says nothing simply survives the day."
  [state]
  (let [phase (:phase state)]
    (cond
      ;; The night falls, and nothing more is said. That time ran out is logged
      ;; so a player can see why a vote or a turn closed on him; this clock
      ;; closes nothing anybody was in the middle of.
      (= :day-resolve phase)
      (when (verdict-held? state) (nightfall state))

      (contains? timed-phases phase)
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
                          (if-let [t (wolf-tally (living-ballots
                                                  state (get-in state [:night-actions :werewolf-votes])))]
                            (conj evs {:type :werewolf/chose :target t})
                            evs)))))

              ;; The Jäger who never fires simply does not fire — the shot is
              ;; his to take, not the server's to guess. Same for a
              ;; Bürgermeister who names nobody: the office falls vacant.
              :reaction [{:type :reaction/popped}]

              ;; Uncast ballots are abstentions, exactly as in the lynch, so the
              ;; election resolves on whatever was actually cast.
              :election
              (let [{:keys [target tie?]} (tally (living-ballots state (:election-votes state {})))]
                [{:type :election/resolved :mayor target :tie? tie?}
                 {:type :day/began}])

              ;; The day marches on regardless. Uncast votes are abstentions,
              ;; which is what `tally` already does with them.
              (leave-day-phase state phase))))))

(defn timed?
  "Does this room have a clock at all? Absent means yes — every room did before
   the Spielleiter could switch it off, and their logs must replay as they were."
  [state]
  (not (false? (:timer? state))))

(def ^:private clock-ended
  "Phases that only the clock (or the players finishing) ever ends, with no host
   button of their own while there is a clock."
  #{:night :election :reaction})

(defn advance-events
  "What `:advance-phase` does right now, or nil when it has nothing to do.

   In the day phases it is what it always was: the host moving the discussion
   along. In a room WITHOUT a clock it also covers every phase only the clock
   used to end, and there it does exactly what the clock would have done —
   `timeout-events`, minus the line saying time ran out, because it did not.

   That is what makes switching the clock off safe. Without it a Jäger who has
   wandered off, a ballot one player never casts, or a wolf asleep at his phone
   would hold the table forever: nothing else ends those phases. Reusing the
   timeout rule rather than writing a second one means the Spielleiter's button
   can never invent a decision the clock would not have."
  [state]
  (let [phase (:phase state)]
    (cond
      (contains? next-phase phase)
      (leave-day-phase state phase)

      ;; With a clock or without, like the day: calling the night after the
      ;; verdict is the narrator's line, and nobody is owed anything here. Only
      ;; the clock could end it otherwise, and a room whose clock is off would
      ;; never see the night again.
      (verdict-held? state)
      (nightfall state)

      (and (not (timed? state)) (contains? clock-ended phase))
      (some-> (seq (rest (timeout-events state))) vec))))

(defn host-absent-events
  "What it MEANS that the host has gone missing, or nil when there is nothing
   to say. Pure, like `timeout-events`: the server decides WHEN a host counts as
   gone — a grace period on the clock, or at once when he walks out — and this
   decides what follows.

   Always the flag itself, which is what offers the seat to the others (see
   `claim-actions`). Beyond that, a narrated game under way cannot hand the seat
   over — whoever took it holds a card and would see every other — so the room
   is made to run without him:

   * the clock comes on, and runs if it was paused, so every phase that waited
     for his button ends on a timeout instead. `timeout-events` never invents a
     decision, so a table driven by the clock plays the game it would have.
   * in :setup the deal becomes final and the night falls. Only he could say so,
     and with the clock now on the night then runs itself.

   nil once flagged, and at :game-over, where nobody is waiting for anything."
  [state]
  (let [phase  (:phase state)
        stuck? (and (e/narrator state) (not (#{:lobby :game-over} phase)))]
    (when-not (or (:host-absent? state) (= :game-over phase))
      (cond-> [{:type :host/absent}]
        (and stuck? (not (timed? state)))
        (conj {:type :clock/timer-set :on? true :cause :host-absent})
        (and stuck? (:paused? state))
        (conj {:type :clock/resumed})
        (and stuck? (= :setup phase))
        (conj {:type :night/began :night 1})))))

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

(defn deck-problem
  "Why this room's deck cannot be dealt to the table as it stands, or nil.

   Asked of the live state rather than stored, because the answer moves while
   nobody touches the deck: a seventh player sitting down can make a deck fit,
   the host deciding to narrate can make it stop fitting. See
   `roles/deck-problem` for the reasons themselves."
  [state]
  (roles/deck-problem (:deck state) (count (e/participant-ids state))))

(defn deal-problem
  "Why the Spielleiter's deal cannot be played as it stands, or nil. Only in
   :setup, where he rearranges the cards by hand: the night does not fall on a
   table of nothing but villagers — the game would be decided before anybody
   woke. The same reasons as the lobby's deck; see `roles/cards-problem`.

   The cards of the living only. A player who walks out in :setup is dead
   with his card turned over; counted, the only wolf leaving still looked
   like a game, the night was offered, and it fell on a village that had
   already won."
  [state]
  (when (= :setup (:phase state))
    (roles/cards-problem (keep #(get-in state [:players % :role])
                               (e/living-ids state)))))

(defn host-actions
  "GM powers, which belong to the room's host and are NOT lost on death.

   Public so tests can exempt exactly these when asserting that dead players
   cannot act, rather than duplicating the list and drifting from it.

   A dead host must keep them: they are the fallback narrator, and stripping
   the powers on death would deadlock any phase that only the host can advance.

   In a narrated room these are the Spielleiter's ENTIRE game — see
   `player-actions`, which gives him nothing else but his voice."
  [state pid]
  (let [phase (:phase state)]
    (cond
      (not= pid (:host state)) #{}

      ;; Nothing left to run but the next game: the same table, dealt again.
      (= :game-over phase) #{:play-again}

      ;; Arranging the table, not running a game: who narrates, whether there
      ;; is a clock, and who holds this seat. Handing the seat over belongs here
      ;; and nowhere later — once the cards are dealt the heir would be holding
      ;; one, and the old host would be holding none.
      ;;
      ;; And which roles are in the deck. Going back to the automatic deck is
      ;; offered only while there is a custom one to leave — a switch that does
      ;; nothing is a button somebody will press to see what happens.
      ;;
      ;; And who sits at it: a ghost seat, a typo, a host who was replaced while
      ;; away and would otherwise be dealt in. Lobby only, like the hand-over —
      ;; once the cards are out, removing a seat would remove a card from the
      ;; game, and an absent player is something every phase already survives.
      (= :lobby phase)
      (cond-> #{:gm/toggle-narrated :gm/toggle-timer :gm/transfer-host
                :gm/kick :gm/set-deck}
        (contains? state :deck) (conj :gm/auto-deck)
        ;; And seating a bot, only while there is a seat to put it in — a
        ;; button that cannot seat anybody is a button somebody will press to
        ;; see what happens. Counted the way `:join` counts: the Spielleiter
        ;; holds no seat at the table.
        (< (count (e/participant-ids state)) (:max-players state 18)) (conj :gm/add-bot))

      ;; The deal is on the table and not yet final. Deliberately NOT the
      ;; in-game overrides: there is nobody to kill or revive and no phase to
      ;; jump to before the first night has fallen.
      ;; The night falls only on a deal that is a game; see `deal-problem`.
      (= :setup phase)
      (cond-> #{:gm/reassign-role :gm/toggle-timer}
        (nil? (deal-problem state)) (conj :begin-night))

      :else
      (cond-> #{:gm/force-kill :gm/revive :gm/set-phase :gm/reassign-role
                :gm/toggle-timer}
        (advance-events state)                     (conj :advance-phase)
        (= :night phase)                           (conj :gm/skip-role)
        ;; The narrator's oldest privilege: "hang on, let him finish." Only
        ;; while there is a clock to stop; a resume is always offered once
        ;; paused, so switching the clock off mid-pause cannot strand the flag.
        (:paused? state)                           (conj :gm/resume)
        (and (not (:paused? state)) (timed? state)) (conj :gm/pause)
        (:deadline state)                          (conj :gm/add-time)))))

(defn- claim-actions
  "Taking the seat of a host who has gone missing.

   To anybody still at the table — a connected seat, alive or dead, since a
   dead host keeps his powers too — and only where the heir would not hold a
   secret worth having: in the lobby, where nobody holds a card yet, and in a
   game the host was PLAYING, where the seat carries GM powers but no view of
   the deal. In a narrated game under way the heir would hold a card and see
   every other one; `host-absent-events` keeps that game going on the clock
   instead."
  [state pid]
  (let [phase (:phase state)]
    (if (and (:host-absent? state)
             (not= pid (:host state))
             (get-in state [:players pid :connected?])
             (or (= :lobby phase)
                 (and (not (:narrated? state))
                      (not (#{:setup :game-over} phase)))))
      #{:claim-host}
      #{})))

(defn- player-actions
  "What pid may do as a *player* (ignoring GM powers)."
  [state pid]
  (let [phase  (:phase state)
        player (get-in state [:players pid])
        host?  (= pid (:host state))
        ;; Counted without the narrator: he is in the room but not in the deal.
        table  (count (e/participant-ids state))]
    (cond
      ;; The game is decided, so there is nothing left to play. The way out —
      ;; which this branch was once the only source of — is `:leave`, now in
      ;; `always-allowed` and so offered here like everywhere else.
      (= :game-over phase) #{}

      ;; The lobby is where people arrange the game, so of course they may talk.
      ;;
      ;; The host may start BELOW :min-players. That number is the recommended
      ;; table size — it is what the lobby counts up to and what the UI nags
      ;; about — but a group of four who want to play now should not be held
      ;; back by a config value set for a bigger evening. The one floor that
      ;; does bind is the deck's: below three there is no game to start, and
      ;; that limit belongs to the role composition, not to a preference.
      ;;
      ;; A deck the Spielleiter chose binds the same way: it must fit the table
      ;; that is actually sitting here. Not offering the start, rather than
      ;; offering it and failing, is what lets the lobby say WHY — the view
      ;; publishes `deck-problem` beside the missing button.
      (= :lobby phase)
      (cond-> #{:chat}
        (and host?
             (>= table roles/absolute-min-players)
             (nil? (deck-problem state)))
        (conj :start-game))

      ;; The Spielleiter. Past the lobby he plays no part — no card, no vote,
      ;; no night turn — so what is left is his voice, at any hour: a narrator
      ;; who could not speak at night could not wake anybody. Everything he
      ;; DOES comes from `host-actions`.
      (= pid (e/narrator state)) #{:chat}

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
          ;; Take a card or keep his own. He may always decline: the Düsterwald
          ;; clause forcing his hand when both cards are wolves cannot arise here,
          ;; because the centre is one wolf and one villager by construction — see
          ;; roles/centre-extras.
          :dieb        #{:dieb-take :dieb-keep}
          :beschuetzer #{:beschuetzer-protect}
          ;; He may decline, like the Hexe and the Jäger: removing a wolf tonight
          ;; is a judgement about timing, and a role whose only option is to act
          ;; has no decision to make.
          ;; No :chat in his own turn. His words would go to the pack's
          ;; channel while the night strip says the Weißer Werwolf is the one
          ;; awake — so anything said now gives him away to the wolves he is
          ;; about to turn on. He talks with them in the pack's turn.
          :weisser-werwolf #{:weisser-kill :weisser-pass}
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

      ;; The open ballot. Both buttons stay after a vote: the table is still
      ;; talking, and a vote nobody may change would make the first to vote
      ;; the least persuadable. It closes when the last player has voted.
      ;;
      ;; Abstaining is a real answer, not the absence of one. Without a button
      ;; for it the only way to condemn nobody is to say nothing, which is
      ;; indistinguishable from having lost your connection.
      (and (= :discussion phase) (trial-day? state))
      #{:chat :vote :vote-abstain}

      (= :discussion phase)
      (cond-> #{:chat}
        (not (contains? (:ready state #{}) pid)) (conj :ready))

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
          :mayor-appoint :mayor-decline
          ;; Not a move in the game: taking the seat of a host who has gone.
          ;; A dead host keeps his powers, so a dead heir may take them up.
          :claim-host}
        always-allowed))

(def no-target-actions
  "Commands that carry no :target. Everything else needs at least one legal
   target to be offerable at all."
  #{:start-game :leave :set-locale :chat :advance-phase :ready
    :gm/pause :gm/resume :gm/add-time
    ;; The ways to decline. Each is a deliberate 'no', which is why they are
    ;; commands at all rather than the absence of one.
    :hexe-pass :vote-abstain :jaeger-pass
    :elect-abstain :mayor-decline :weisser-pass
    ;; Keeping his own card is a decision, not the absence of one.
    :dieb-keep
    ;; Switches and a starting gun: the Spielleiter's, and none of them names
    ;; anybody.
    :begin-night :gm/toggle-narrated :gm/toggle-timer
    ;; Seating a bot names nobody either: the engine picks the seat and the name.
    :gm/add-bot
    ;; Names nobody: the seat it takes is the host's.
    :claim-host
    ;; The whole table again, whoever is sitting at it.
    :play-again
    ;; The deck travels as a map of its own, not as a seat or a word: the whole
    ;; deck in every command, validated against the registry by the handler.
    :gm/set-deck :gm/auto-deck})

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
                         (into (claim-actions state pid))
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
      ;; She can only heal someone actually dying tonight — not a victim who
      ;; has walked out since, on whom the heal was spent for nothing.
      :hexe-heal       (mapv :player (filter #(= :werwolf (:cause %)) (pending-alive state)))
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
      ;; Anybody living but himself.
      :vote            others

      ;; GM overrides need targets too, or the host's UI has nothing to offer.
      :gm/force-kill   (vec living)
      :gm/revive       (vec (sort (keep (fn [[id p]] (when-not (:alive? p) id))
                                       (:players state))))
      ;; Everyone, dead included: the reason to rewrite a dead player's role is
      ;; usually that the deal was wrong, and reviving them first would put a
      ;; second correction in the log to undo the first. Everyone but the
      ;; narrator, who holds no card and must not be dealt into his own game.
      :gm/reassign-role (e/participant-ids state)
      ;; Any other seat. The heir is chosen by the host, so it may be somebody
      ;; who is not connected right now — the seat is what is handed over.
      ;; Never a bot, though: a bot host could not advance a single phase, and
      ;; the table would be waiting on a seat with nobody behind it.
      :gm/transfer-host (vec (remove (fn [id] (or (= id pid) (get-in state [:players id :bot?])))
                                     (sort (keys (:players state)))))
      ;; The same seats, for the same reason: anybody but himself, connected or
      ;; not — a seat nobody is behind is the commonest thing to remove. Never
      ;; himself, because a room without its host has nobody to start it; he
      ;; leaves the way everybody else does, and succession takes over.
      :gm/kick         (vec (remove #{pid} (sort (keys (:players state)))))
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
    ;; The two cards nobody was dealt. This is how they reach the Dieb at all —
    ;; `:centre` is never published as room state, and `:choices` is built only
    ;; from the actions a player actually holds, so nobody else sees them.
    ;;
    ;; Sorted for the same reason `assignable-ids` is: this list crosses the wire
    ;; and is indexed into by a select control, so an unstable order would make two
    ;; clients disagree and could make a replay platform-dependent.
    :dieb-take        (vec (sort (:centre state [])))
    :gm/set-phase     (vec (sort (keys next-phase)))
    ;; Only the role currently being called — skipping a role that is not
    ;; speaking would silently mark its turn done for the rest of the night.
    :gm/skip-role     (if-let [c (:night-cursor state)] [c] [])
    :gm/reassign-role roles/assignable-ids
    []))
