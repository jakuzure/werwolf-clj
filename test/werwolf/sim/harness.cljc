(ns werwolf.sim.harness
  "Plays complete randomized games by driving ONLY the engine's own
   `legal-actions` / `legal-targets`, so the simulation can never make a move the
   engine doesn't already claim is legal. If a game deadlocks, the harness proves
   `legal-actions` is lying.

   Deterministic throughout: every game is a pure function of its seed, so a
   failure reported in CI is reproducible locally from the seed alone."
  (:require [werwolf.engine.core :as engine]
            [werwolf.engine.events :as e]
            [werwolf.engine.phases :as phases]
            [werwolf.engine.rng :as rng]
            [werwolf.engine.roles :as roles]
            [werwolf.engine.view :as view]
            [werwolf.proto :as proto]))

(def volume
  "How many of its random games the breadth tests play, as a share of the full
   run. SIM_VOLUME overrides it on either platform: SIM_VOLUME=1 on Node plays
   everything, SIM_VOLUME=4 on the JVM plays four times as many.

   The full run on the JVM and a quarter of it on Node. The random games are
   most of both suites' time, and they find rule bugs, which are the same bugs
   on either platform — what Node is there for is that a seed plays the SAME
   game there, and the pinned fingerprints check exactly that. Node runs the
   same games several times slower, and repeating all of them bought nothing
   but ten minutes of CI."
  (or (some-> #?(:clj (System/getenv "SIM_VOLUME")
                 :cljs (some-> js/process .-env .-SIM_VOLUME))
              parse-double)
      #?(:clj 1 :cljs 0.25)))

(defn games
  "`n` games scaled by `volume`, never fewer than one per table size."
  [n]
  (max 14 (int (* n volume))))

(def ^:const max-turns
  "A real game of 18 finishes well inside this. Hitting it means a livelock."
  2000)

(def ^:private never-issued
  "Actions the simulation never issues: they would end or distort the game
   rather than play it."
  #{:chat :leave :set-locale})

(defn- excluded?
  "Every GM override is excluded, by NAMESPACE rather than by name.

   This was a hand-kept list of seven, and it went stale the moment an eighth
   override existed: the simulation issued `:gm/reassign-role` with no role to
   assign and reported the engine's own refusal as thirty broken games. Same
   lesson as `no-target-actions` below, which is taken from the engine for
   exactly this reason — a test that keeps its own copy of a rule eventually
   tests the copy.

   Excluding them all is right on the merits too. An override is the narrator
   overruling the game; a random walk through them is not a game being played,
   and two of them (the clock ones) cannot do anything at all in a headless run
   where :deadline is always nil. `:advance-phase` is deliberately NOT excluded:
   the host advancing a phase is ordinary play."
  [action]
  (or (never-issued action) (= "gm" (namespace action))))

(defn sim-genesis
  "The events that create a lobby of n players. Returned as data so the whole
   game — setup included — can be replayed from its log.

   `:narrated? true` seats a Spielleiter in seat 0 AS WELL AS the n players, and
   switches the clock off the way a real narrated room starts. n is always the
   table that plays, so a narrated game of n deals the same deck as an
   unnarrated one and the two are comparable.

   `:host-absent? true` opens the room with its host already gone and flagged,
   as the server's ticker would leave it, so the random walk gets to take his
   seat — or not — at any point the engine offers it.

   `:deck` puts the Spielleiter's deck on the table before the start, as the
   event his command would have produced — so it is in the log and replays."
  ([n seed] (sim-genesis n seed {}))
  ([n seed {:keys [narrated? host-absent? deck]}]
   (cond-> (into [(cond-> {:type :game/created :room-id "SIM" :host 0 :seed seed :min-players 5}
                    narrated? (assoc :narrated? true :timer? false))]
                 (map (fn [i] {:type :player/joined :player i :name (str "P" i) :locale :de}))
                 (range (if narrated? (inc n) n)))
     ;; The deck first: chosen by the host before he went missing, which is the
     ;; only order a real room could produce both in.
     deck         (conj {:type :deck/set :deck deck})
     host-absent? (into [{:type :player/connection :player 0 :connected? false :at 0}
                         {:type :host/absent}]))))

(defn random-deck
  "A deck a Spielleiter might choose for n players, drawn from `seed`: a pack of
   one to a third of the table, then each other special on a coin flip.

   Valid by construction rather than by retry — every card is added only if the
   deck still fits — because a generator that sometimes produced unplayable
   decks would test the start rule's refusal, which the deck tests already do,
   instead of the games the refusal lets through. Decks the automatic
   composition never deals are the point: a Dieb at four, a lone wolf among
   fifteen, a Weißer Werwolf with a single packmate, no Seherin at all."
  [n seed]
  (let [hunter (first (filter roles/hunts? roles/special-ids))
        [s1 w] (rng/rand-int seed (max 1 (quot n 3)))
        start  {hunter (min (inc w) (dec n))}
        [s2 order] (rng/shuffle s1 (remove #{hunter} roles/special-ids))]
    (first
     (reduce (fn [[deck s] id]
               (let [[s' coin] (rng/rand-int s 2)
                     deck'     (update deck id (fnil inc 0))]
                 (if (and (= 1 coin)
                          (<= (get deck' id) (or (roles/max-count id) 1))
                          (nil? (roles/deck-problem deck' n)))
                   [deck' s']
                   [deck s'])))
             [start s2]
             order))))

(defn new-sim-game
  "A game in the lobby with n joined players, plus its genesis event log."
  ([n seed] (new-sim-game n seed {}))
  ([n seed opts]
   (let [evs (sim-genesis n seed opts)]
     [(e/replay evs) evs])))

(def ^:private no-target-actions
  "Commands that carry no :target at all.

   Taken from the engine rather than restated here: this was a private copy
   once, and the day `:nominate-pass` was added the simulation started reporting
   a legal action as a bug in itself. A test that keeps its own duplicate of a
   rule eventually tests the duplicate."
  phases/no-target-actions)

(defn- build-command
  "Turn (player, action) into a full command, drawing any target from
   legal-targets. Returns [seed' cmd] or [seed' nil] when no legal target exists."
  [state seed pid action]
  (let [targets (vec (phases/legal-targets state pid action))]
    (cond
      (no-target-actions action)
      [seed {:type action :player pid}]

      (= :amor-link action)
      (if (< (count targets) 2)
        [seed nil]
        (let [[s1 a] (rng/rand-nth seed targets)
              rest-t (vec (remove #{a} targets))
              [s2 b] (rng/rand-nth s1 rest-t)]
          [s2 {:type :amor-link :player pid :a a :b b}]))

      ;; An action that names a CHOICE rather than a seat: a card, a role, a phase.
      ;; Which key it travels under comes from `proto/choice-key` rather than being
      ;; decided here, for the same reason the client reads it from there — one
      ;; place owns that vocabulary.
      ;;
      ;; Until the Dieb there was no non-GM action of this shape, and the GM ones
      ;; are excluded, so this path had never run: the simulation reported his
      ;; perfectly legal turn as `legal-actions` offering an unusable action.
      (proto/choice-key action)
      (let [choices (vec (phases/legal-choices state action))]
        (if (empty? choices)
          [seed nil]
          (let [[s1 c] (rng/rand-nth seed choices)]
            [s1 {:type action :player pid (proto/choice-key action) c}])))

      ;; The day's ballot is hard-coded rather than random: everybody votes for
      ;; the lowest seat still alive that is not their own. A table voting at
      ;; random almost never reaches the majority a condemnation needs, so
      ;; random games ran on for hundreds of turns of wolves eating one
      ;; villager a night. Ties, abstentions and a ballot short of a majority
      ;; are the rules tests' business; here the day has to end in a verdict.
      (= :vote action)
      (if (empty? targets)
        [seed nil]
        [seed {:type :vote :player pid :target (apply min targets)}])

      ;; Everything else is "pick one legal target".
      :else
      (if (empty? targets)
        [seed nil]
        (let [[s1 t] (rng/rand-nth seed targets)]
          [s1 {:type action :player pid :target t}])))))

(defn- candidates
  "All [player action] pairs currently available, excluding the ones the
   simulation refuses to use."
  [state]
  (for [pid    (sort (keys (:players state)))
        action (sort (phases/legal-actions state pid))
        :when  (not (excluded? action))
        ;; See the :vote branch of `build-command`: one vote each, never an
        ;; abstention, and never the same ballot cast twice.
        :when  (not (and (#{:vote :vote-abstain} action)
                         (or (= :vote-abstain action) (phases/voted-today? state pid))))]
    [pid action]))

(defn step
  "Advance the game by one player action.
   Returns [seed' state' cmd events] where cmd is nil if nothing legal was
   available (a deadlock, unless the game is over)."
  [state seed]
  (let [opts (vec (candidates state))]
    (if (empty? opts)
      [seed state nil []]
      (let [[s1 [pid action]] (rng/rand-nth seed opts)
            [s2 cmd] (build-command state s1 pid action)]
        (if (nil? cmd)
          ;; Chosen action had no legal target — report it so the invariant
          ;; check can flag legal-actions offering an unusable action.
          [s2 state {:type action :player pid :no-target true} []]
          (let [result (engine/execute state cmd)]
            (if (engine/error? result)
              [s2 state (assoc cmd :rejected (:error result)) []]
              [s2 (:state result) cmd (:events result)])))))))

;; ------------------------------------------------------------ invariants ---

(def ^:private narrator-may
  "Everything a Spielleiter may hold beyond his host powers: his voice, his
   preferences, and the lobby's own buttons. Any game move here is a rule
   reaching him that should have stopped at `participant-ids`."
  #{:chat :leave :set-locale :start-game})

(defn- narrator-violations
  [state]
  (if-let [n (e/narrator state)]
    (cond-> []
      (some? (get-in state [:players n :role]))
      (conj {:violation :narrator-dealt-a-role
             :detail (get-in state [:players n :role])})

      (seq (remove (into narrator-may (phases/host-actions state n))
                   (phases/legal-actions state n)))
      (conj {:violation :narrator-can-play
             :detail (vec (remove (into narrator-may (phases/host-actions state n))
                                  (phases/legal-actions state n)))})

      ;; Never a target of anything that is a move in the game. Handing over the
      ;; host's seat is the one action that MAY name him — it names a seat.
      (some (fn [pid]
              (some (fn [a]
                      (and (not= :gm/transfer-host a)
                           (some #{n} (phases/legal-targets state pid a))))
                    (phases/legal-actions state pid)))
            (keys (:players state)))
      (conj {:violation :narrator-is-a-target}))
    []))

(defn check-invariants
  "Return a seq of violation maps (empty when healthy). Run after EVERY event."
  [state]
  (let [players (:players state)
        ;; Not `e/living-ids`, which leaves the narrator out: he is not a
        ;; living PARTICIPANT, but he is not dead either, and treating his seat
        ;; as a corpse reported his perfectly legal :start-game as a ghost acting.
        living  (into #{} (keep (fn [[id p]] (when (:alive? p) id))) players)]
    (cond-> []
      ;; --- redaction: the one that must never fail ---
      (some #(engine/redaction-leaks? state %) (keys players))
      (conj {:violation :role-leak
             :detail (into {} (keep (fn [pid]
                                      (when-let [l (engine/redaction-leaks? state pid)]
                                        [pid l]))
                                    (keys players)))})

      ;; --- conservation ---
      (not= (count players)
            (+ (count living) (count (remove living (keys players)))))
      (conj {:violation :player-count-mismatch})

      ;; A dead player must never retain an actionable move. Two documented
      ;; exceptions: the Jäger fires while already dead, and the host keeps its
      ;; GM powers (we reuse phases/host-actions so this can't drift).
      (some (fn [pid]
              (and (not (contains? living pid))
                   (seq (remove (into phases/dead-permitted-actions
                                      (phases/host-actions state pid))
                                (phases/legal-actions state pid)))))
            (keys players))
      (conj {:violation :dead-player-can-act
             :detail (into {} (keep (fn [pid]
                                      (let [extra (remove (into phases/dead-permitted-actions
                                                                (phases/host-actions state pid))
                                                          (phases/legal-actions state pid))]
                                        (when (seq extra) [pid (vec extra)])))
                                    (remove living (keys players))))})

      ;; Hexe potions can never go negative or exceed their starting count.
      (some (fn [[_ p]]
              (when-let [r (:resources p)]
                (some (fn [[_ v]] (or (neg? v) (> v 1))) r)))
            players)
      (conj {:violation :resource-out-of-range
             :detail (into {} (keep (fn [[id p]] (when (:resources p) [id (:resources p)])) players))})

      ;; Lovers are always exactly two distinct players, or absent.
      (and (:lovers state) (not= 2 (count (set (:lovers state)))))
      (conj {:violation :malformed-lovers :detail (:lovers state)})

      ;; A finished game must have a winner, and an unfinished one must not.
      (and (= :game-over (:phase state)) (nil? (:winner state)))
      (conj {:violation :game-over-without-winner})

      (and (not= :game-over (:phase state)) (some? (:winner state)))
      (conj {:violation :winner-without-game-over})

      ;; --- the Spielleiter is in the room and out of the game ---
      (seq (narrator-violations state))
      (into (narrator-violations state)))))

;; --------------------------------------------------------------- runner ---

(defn play-game
  "Play one complete game. Returns a result map; :ok? false explains the failure.
   Reproducible from :seed and :players alone.

   Also accumulates the full event log so callers can assert the replay property
   (= (replay log) final-state).

   `opts` is passed to `sim-genesis`; `{:narrated? true}` plays the game with a
   Spielleiter and no clock, which is the configuration most likely to stall —
   every phase the clock used to end now waits for a human — so it is the one
   the deadlock check is most worth running on."
  ([n seed] (play-game n seed {}))
  ([n seed opts]
   (let [opts (cond-> opts
                ;; `:random-deck? true` plays a deck the Spielleiter chose rather
                ;; than the one the table size deals — still a pure function of
                ;; the seed, so a failing custom game reproduces like any other.
                (:random-deck? opts) (assoc :deck (random-deck n seed)))
         [start genesis] (new-sim-game n seed opts)]
     (loop [state start, s seed, turns 0, history [], log genesis]
       (let [violations (check-invariants state)]
         (cond
           (seq violations)
           {:ok? false :reason :invariant :violations violations
            :seed seed :players n :turns turns :state state
            :history (vec (take-last 20 history)) :log log}

           (engine/over? state)
           ;; The FULL history, untruncated, on success too: it is the complete
           ;; set of commands the engine considered legal in a real game, which
           ;; makes it the natural corpus for testing the wire protocol against.
           {:ok? true :winner (:winner state) :seed seed :players n
            :turns turns :events (count log) :log log :state state
            :history history
            :survivors (e/living-ids state)}

           (> turns max-turns)
           {:ok? false :reason :livelock :seed seed :players n
            :turns turns :state state :history (vec (take-last 20 history)) :log log}

           :else
           (let [[s' state' cmd evs] (step state s)]
             (cond
               (nil? cmd)
               {:ok? false :reason :deadlock :seed seed :players n :turns turns
                :phase (:phase state) :state state
                :history (vec (take-last 20 history)) :log log}

               (:no-target cmd)
               {:ok? false :reason :action-without-target :cmd cmd
                :seed seed :players n :turns turns :phase (:phase state)
                :state state :history (vec (take-last 20 history)) :log log}

               (:rejected cmd)
               {:ok? false :reason :legal-action-rejected :cmd cmd
                :seed seed :players n :turns turns :phase (:phase state)
                :state state :history (vec (take-last 20 history)) :log log}

               :else
               (recur state' s' (inc turns) (conj history cmd)
                      (into log evs))))))))))

(defn replay-mismatch
  "nil when the game's event log folds back to exactly its final state.
   Guards the event-sourcing contract the SQLite log depends on."
  [{:keys [log state]}]
  (let [replayed (e/replay log)]
    (when (not= replayed state)
      {:violation :replay-divergence
       :differing-keys (vec (sort (keep (fn [k]
                                          (when (not= (get replayed k) (get state k)) k))
                                        (into (set (keys state)) (keys replayed)))))})))

(defn play-many
  "Play `count-games` games across a range of table sizes.
   Returns {:failures [...] :summary {...}}."
  ([count-games] (play-many count-games {}))
  ([count-games opts]
   (let [results (for [i (range count-games)
                       :let [n    (+ 5 (mod i 14))   ; 5..18 players
                             seed (+ 1 (* i 7919))]] ; distinct primes-ish seeds
                   (play-game n seed opts))
         ;; A completed game that cannot be replayed is also a failure.
         checked  (map (fn [r]
                         (if-let [m (and (:ok? r) (replay-mismatch r))]
                           (assoc r :ok? false :reason :replay :violations [m])
                           r))
                       results)
         failures (vec (remove :ok? checked))]
     {:failures failures
      :summary  {:games   (count checked)
                 :winners (frequencies (map :winner (filter :ok? checked)))
                 :avg-turns (when-let [ts (seq (map :turns (filter :ok? checked)))]
                              (quot (reduce + ts) (count ts)))
                 :avg-events (when-let [es (seq (map :events (filter :ok? checked)))]
                               (quot (reduce + es) (count es)))}})))
