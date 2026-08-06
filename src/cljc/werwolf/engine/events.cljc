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

(defn living-ids [state]
  (->> (:players state)
       (filter (fn [[_ p]] (:alive? p)))
       (map key)
       sort
       vec))

(defn living-with-role [state role-id]
  (->> (:players state)
       (filter (fn [[_ p]] (and (:alive? p) (= role-id (:role p)))))
       (map key)
       sort
       vec))

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
      (push-reaction {:type :reaction/lover-follows :player lover :cause :liebe}))))

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
  [_ {:keys [room-id host seed min-players max-players]}]
  {:room-id        room-id
   :phase          :lobby
   :night          0
   :players        {}
   :host           host
   :seed           seed
   ;; Kept in state rather than read from config, so the engine stays pure and
   ;; a replayed game uses the same thresholds it was created with. A config
   ;; change must never be able to make a game in progress unstartable.
   :min-players    (or min-players 5)
   :max-players    (or max-players 18)
   :lovers         nil
   :reactions      []
   :pending-deaths []
   :log            []})

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

(defmethod apply-event :player/connection
  [state {:keys [player connected?]}]
  (cond-> (assoc-in state [:players player :connected?] connected?)
    true (publish {:type (if connected?
                           :player/reconnected
                           :player/disconnected)
                   :player player})))

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
  [state {:keys [host]}]
  (-> state
      (assoc :host host)
      (publish {:type :host/changed :host host})))

(defmethod apply-event :game/started
  [state {:keys [assignments seed]}]
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
      (assoc :night 0 :winner nil)
      ;; Carry the advanced seed so any later randomness stays reproducible.
      (cond-> seed (assoc :seed seed))
      (publish {:type :game/started :players (count assignments)})))

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

(defmethod apply-event :night/ended
  [state _]
  (-> state
      (assoc :night-cursor nil)
      (publish {:type :night/ended :night (:night state)})))

(defmethod apply-event :day/began
  [state _]
  (-> state
      (assoc :phase :discussion
             :votes {}
             :nominations #{}
             :nomination-done #{}
             :ready #{}
             :vote-resolved? false)
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

(defmethod apply-event :werewolf/chose
  [state {:keys [target]}]
  (-> state
      (assoc-in [:night-actions :werewolf-target] target)
      (update :pending-deaths (fnil conj []) {:player target :cause :werwolf})))

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
        (death-reactions player))))

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

;; ---- day: nomination, voting, lynch ----

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
  [state {:keys [target tie?]}]
  (cond-> (-> state
              (assoc :vote-resolved? true)
              (publish {:type :vote/resolved :target target :tie? tie?}))
    target (update :pending-deaths (fnil conj []) {:player target :cause :lynch})))

(defmethod apply-event :game/ended
  [state {:keys [winner]}]
  (-> state
      (assoc :phase :game-over :winner winner :deadline nil)
      (publish {:type :game/ended :winner winner})))

;; ---- GM overrides (always logged, never silent) ----

(defmethod apply-event :gm/forced
  [state {:keys [actor action detail]}]
  (publish state {:type :gm/forced :actor actor :action action :detail detail}))

;; ------------------------------------------------------------- replay ---

(defn replay
  "Fold an event log into state. `(= (replay events) state)` is a property the
   test suite enforces."
  [events]
  (reduce apply-event nil events))

(defn apply-all [state events]
  (reduce apply-event state events))
