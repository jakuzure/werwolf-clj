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
   villager in love beat both of their original teams."
  [state]
  (let [living (e/living-ids state)
        lovers (set (:lovers state))]
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
          (= ts #{:werewolf})            :werewolf
          :else nil)))))

;; ------------------------------------------------- night turn ordering ---

(defn- role-can-act? [state role-id]
  (let [{:keys [first-night-only?]} (roles/role role-id)]
    (and (seq (e/living-with-role state role-id))
         (or (not first-night-only?) (= 1 (:night state))))))

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

(defn- wolves-all-voted? [state]
  (let [wolves (e/living-with-role state :werwolf)
        votes  (get-in state [:night-actions :werewolf-votes] {})]
    (and (seq wolves) (every? #(contains? votes %) wolves))))

;; ------------------------------------------------------- vote counting ---

(defn tally
  "Count votes. Returns {:target id :tie? bool}; nil target on a tie or when
   nobody voted. Abstentions (nil target) are ignored.

   Ties deliberately kill nobody: that is the classic rule, and it keeps an
   arbitrary tiebreak — which players would find unfair — out of the engine."
  [votes]
  (let [counts (->> (vals votes) (remove nil?) frequencies)]
    (if (empty? counts)
      {:target nil :tie? false}
      (let [best (apply max (vals counts))
            top  (->> counts (filter #(= best (val %))) (map key) sort vec)]
        (if (= 1 (count top))
          {:target (first top) :tie? false}
          {:target nil :tie? true})))))

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

(defn- nobody-can-act?
  "True when no living holder of `role-id` has any usable action.

   This is the general safety net behind the night: ANY role whose turn can
   become impossible would otherwise stall the game forever. Relying on a
   per-role special case is what let the exhausted-Seherin deadlock through."
  [state role-id]
  (let [holders (e/living-with-role state role-id)]
    (or (empty? holders)
        (every? (fn [pid]
                  (empty? (remove #(or (= :chat %) (= "gm" (namespace %)))
                                  (legal-actions state pid))))
                holders))))

(defn- night-auto [state]
  (let [q (night-queue state)]
    (cond
      (empty? q)
      [{:type :night/ended}
       {:type :phase/changed :phase :day-reveal :deadline nil}]

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
          :reaction/jaeger-shot
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

        :day-reveal [{:type :day/began}]

        ;; Human-paced phases: a timer or the host moves these along.
        :discussion nil
        :nomination nil
        :vote       nil

        :day-resolve
        (if (:vote-resolved? state)
          ;; Lynch death has been applied by now (branch above), so night can begin.
          [{:type :night/began :night (inc (:night state))}]
          (let [{:keys [target tie?]} (tally (:votes state))]
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
  {:night      :night-secs
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
              ;; his to take, not the server's to guess.
              :reaction [{:type :reaction/popped}]

              ;; The day marches on regardless. Uncast votes are abstentions,
              ;; which is what `tally` already does with them.
              [{:type :phase/changed :phase (next-phase phase) :deadline nil}])))))

(defn- night-actor?
  "Is pid one of the players whose role is currently on the night cursor?"
  [state pid]
  (let [cursor (:night-cursor state)]
    (and cursor
         (= cursor (get-in state [:players pid :role]))
         (get-in state [:players pid :alive?]))))

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
    (cond-> #{:gm/force-kill :gm/revive :gm/set-phase}
      (contains? next-phase (:phase state)) (conj :advance-phase)
      (= :night (:phase state))             (conj :gm/skip-role))))

(defn- player-actions
  "What pid may do as a *player* (ignoring GM powers)."
  [state pid]
  (let [phase  (:phase state)
        player (get-in state [:players pid])
        host?  (= pid (:host state))]
    (cond
      (= :game-over phase) #{}

      ;; The lobby is where people arrange the game, so of course they may talk.
      (= :lobby phase)
      (cond-> #{:chat :leave :set-locale}
        (and host? (>= (count (:players state)) (:min-players state 5)))
        (conj :start-game))

      ;; The Jäger fires his shot while ALREADY DEAD, so this must be checked
      ;; before the general dead-players-may-only-chat rule below.
      (= :reaction phase)
      (let [r (peek (:reactions state))]
        (if (and (= :reaction/jaeger-shot (:type r)) (= pid (:player r)))
          ;; He may talk while he aims. The whole table is waiting on him and
          ;; everyone else is chatting; silencing exactly the player being
          ;; waited for is arbitrary. Chat routing still sends him to the dead
          ;; channel, since he is one of them now.
          #{:jaeger-shoot :chat}
          #{:chat}))

      ;; Otherwise the dead may watch and talk, never act.
      (not (:alive? player)) #{:chat}

      (= :night phase)
      (if (night-actor? state pid)
        (case (:night-cursor state)
          :amor    (if (= 1 (:night state)) #{:amor-link} #{})
          :seherin #{:seherin-inspect}
          ;; Wolves may talk among themselves at night (private room at M7).
          :werwolf (if (contains? (get-in state [:night-actions :werewolf-votes] {}) pid)
                     #{:chat}
                     #{:werewolf-vote :chat})
          :hexe    (cond-> #{:hexe-pass}
                     (pos? (get-in player [:resources :heiltrank] 0)) (conj :hexe-heal)
                     (pos? (get-in player [:resources :gifttrank] 0)) (conj :hexe-poison))
          #{})
        #{})

      (= :discussion phase) #{:chat}
      (= :nomination phase) #{:chat :nominate}
      (= :vote phase)       (cond-> #{:chat}
                              (not (contains? (:votes state) pid)) (conj :vote))

      :else #{:chat})))

(def no-target-actions
  "Commands that carry no :target. Everything else needs at least one legal
   target to be offerable at all."
  #{:start-game :leave :set-locale :chat :hexe-pass :advance-phase})

(declare legal-targets)

(defn legal-actions
  "What pid may do right now. Drives both the UI's affordances and the
   simulation harness, so it must be exhaustive and honest.

   'Honest' specifically means: an action is never offered when it has no legal
   target. Otherwise the UI shows a dead button and, worse, the night can
   deadlock — the Seherin who has already inspected everyone still being offered
   :seherin-inspect was a real bug the simulation harness caught."
  [state pid]
  (if-not (get-in state [:players pid])
    #{}
    (let [candidates (into (player-actions state pid) (host-actions state pid))]
      (into #{}
            (remove (fn [action]
                      (and (not (contains? no-target-actions action))
                           (empty? (legal-targets state pid action)))))
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
      :hexe-poison     others
      :jaeger-shoot    others
      :amor-link       (vec living)
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
      :gm/skip-role    (if-let [c (:night-cursor state)] [c] [])
      :gm/set-phase    (vec (sort (keys next-phase)))
      [])))
