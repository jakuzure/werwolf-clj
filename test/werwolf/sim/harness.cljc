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
            [werwolf.engine.view :as view]))

(def ^:const max-turns
  "A real game of 18 finishes well inside this. Hitting it means a livelock."
  2000)

;; Actions the simulation never issues: they would end or distort the game
;; rather than play it.
;;
;; The clock overrides are here because there is no clock in a headless game —
;; :deadline is always nil — so pausing and resuming would burn turns and
;; consume RNG draws while changing nothing about the game being played.
(def ^:private excluded-actions
  #{:chat :leave :set-locale
    :gm/force-kill :gm/revive :gm/set-phase :gm/skip-role
    :gm/pause :gm/resume :gm/add-time})

(defn sim-genesis
  "The events that create a lobby of n players. Returned as data so the whole
   game — setup included — can be replayed from its log."
  [n seed]
  (into [{:type :game/created :room-id "SIM" :host 0 :seed seed :min-players 5}]
        (map (fn [i] {:type :player/joined :player i :name (str "P" i) :locale :de}))
        (range n)))

(defn new-sim-game
  "A game in the lobby with n joined players, plus its genesis event log."
  [n seed]
  (let [evs (sim-genesis n seed)]
    [(e/replay evs) evs]))

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
        :when  (not (excluded-actions action))]
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

(defn check-invariants
  "Return a seq of violation maps (empty when healthy). Run after EVERY event."
  [state]
  (let [players (:players state)
        living  (set (e/living-ids state))]
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
      (conj {:violation :winner-without-game-over}))))

;; --------------------------------------------------------------- runner ---

(defn play-game
  "Play one complete game. Returns a result map; :ok? false explains the failure.
   Reproducible from :seed and :players alone.

   Also accumulates the full event log so callers can assert the replay property
   (= (replay log) final-state)."
  [n seed]
  (let [[start genesis] (new-sim-game n seed)]
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
                     (into log evs)))))))))

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
  [count-games]
  (let [results (for [i (range count-games)
                      :let [n    (+ 5 (mod i 14))   ; 5..18 players
                            seed (+ 1 (* i 7919))]] ; distinct primes-ish seeds
                  (play-game n seed))
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
                              (quot (reduce + es) (count es)))}}))
