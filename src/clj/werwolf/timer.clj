(ns werwolf.timer
  "The clock. Server-authoritative, absolute, and boring on purpose.

   Three decisions shape this namespace:

   **Deadlines are absolute epoch-ms, broadcast in the view.** Clients count
   down *from* that number; they are never sent a duration and never asked how
   much time is left. A backgrounded tab, a slow frame rate or a wrong system
   clock therefore cannot buy or lose a player a single second, and there is
   nothing for a modified client to lie about.

   **One ticker, not a future per room.** A scheduled task per deadline means
   cancellation races, orphaned futures after a phase change, and a rescheduling
   bug for every path that moves a phase. A single thread that wakes once a
   second and asks 'which rooms have expired?' has one code path, needs no
   bookkeeping, and handles a deadline restored from disk that is already in the
   past without a special case. The cost is up to a second of imprecision in a
   party game, which is not a cost.

   **What a timeout means is a rule, not a policy.** `engine/timeout-events`
   decides that; this namespace only decides *when*, and reads the durations
   from config. That split is why the timeout rules are tested on both platforms
   with no timer, no threads and no clock.

   `tick!` and `reconcile!` both take `now-ms` explicitly, so every test below
   runs instantly at whatever simulated instant it likes. Nothing here sleeps."
  (:require [clojure.tools.logging :as log]
            [werwolf.engine.core :as engine]
            [werwolf.room :as room])
  (:import (java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit)))

(def default-period-ms
  "How often the ticker looks for expired deadlines. One second is well inside
   what anyone notices on a countdown."
  1000)

;; ---------------------------------------------------------------- policy ---

(defn- secs-for [game-cfg phase]
  (get game-cfg (engine/timed-phases phase)))

(defn- nobody-here?
  "Is every seat in the room disconnected?

   Read from state rather than from the room's sockets, which it mirrors — a
   seat goes absent when its LAST socket closes and comes back with its first —
   so that the clock's policy stays a pure function of state, and so the commit
   that records the first player returning is also the one that re-arms the
   clock, through the ordinary reconcile, with no special case."
  [state]
  (and (seq (:players state))
       (not-any? :connected? (vals (:players state)))))

(defn clock-events
  "Events that make the deadline agree with the phase. Idempotent: given a state
   whose clock is already correct it returns nil, which is what lets it be
   called after every single commit.

   A timed phase with no configured duration gets no deadline rather than a
   crash — dropping a key from config should slow the game down, not break it."
  [game-cfg now-ms state]
  (let [secs     (secs-for game-cfg (:phase state))
        deadline (:deadline state)]
    (cond
      ;; A GM pause has to win here. Pausing clears the deadline, and without
      ;; this clause the very next reconcile would helpfully put it back — the
      ;; pause would last about one second.
      (:paused? state)           (when deadline [{:type :phase/deadline :at nil}])
      ;; A room without a clock — the Spielleiter paces it. Same shape as the
      ;; pause and for the same reason: whatever deadline is left over from
      ;; before it was switched off has to go, and none may come back.
      (not (engine/timed? state)) (when deadline [{:type :phase/deadline :at nil}])
      ;; Nobody is in the room, so nobody is being timed. A clock that ran on
      ;; regardless ended phase after phase in front of an empty table — one
      ;; abandoned game was found at night 1384 — and handed whoever came back a
      ;; game that had played itself. Same shape again: drop the deadline, arm
      ;; none, and the reconcile after the first seat reconnects gives the phase
      ;; a full fresh clock. Time nobody could play through is not time.
      (nobody-here? state)        (when deadline [{:type :phase/deadline :at nil}])
      (and secs (nil? deadline)) [{:type :phase/deadline :at (+ now-ms (* 1000 secs))}]
      (and (not secs) deadline)  [{:type :phase/deadline :at nil}]
      :else nil)))

(defn expiry-events
  "Events for a deadline that has passed, or nil.

   The deadline is always cleared as part of firing, never left behind for the
   next tick to find. `:night` is why this matters: a role's turn ending does
   not change the phase, so without an explicit clear the same expired instant
   would fire again a second later, and again. Clearing it lets `clock-events`
   arm a fresh clock for whoever acts next — one mechanism, no special case."
  [now-ms state]
  (when-let [deadline (:deadline state)]
    ;; Belt and braces: `clock-events` already clears the deadline of an empty
    ;; room on the commit that emptied it.
    (when (and (>= now-ms deadline) (not (nobody-here? state)))
      (conj (vec (engine/timeout-events state))
            {:type :phase/deadline :at nil}))))

;; ------------------------------------------------------------- operations ---

(defn reconcile!
  "Arm or disarm a room's clock to match its phase. Safe to call at any time."
  [ctx game-cfg room-id now-ms]
  (room/narrate! ctx room-id #(clock-events game-cfg now-ms %)))

(defn hook
  "The `:after-commit` fn a registry needs in order to have a clock at all.

   Passing this to `room/registry` is what makes deadlines appear: every
   committed change re-reconciles the clock, so no code path that moves a phase
   has to remember to touch the timer.

   `clock` returns the current instant in ms. Production passes none and gets
   the wall clock. A test that drives `tick!` with simulated instants must pass
   the same simulated clock: otherwise a deadline re-armed after a timeout is
   measured from real time while the test ticks in simulated time, and on a fast
   machine, where all of it fits in one millisecond, the fresh deadline is
   already due at the simulated instant."
  ([game-cfg] (hook game-cfg #(System/currentTimeMillis)))
  ([game-cfg clock]
   (fn [ctx room-id]
     (reconcile! ctx game-cfg room-id (clock)))))

(defn fire!
  "Resolve one room if its deadline has passed. Returns true if it fired."
  [ctx room-id now-ms]
  (let [res (room/narrate! ctx room-id #(expiry-events now-ms %))]
    (boolean (and (:ok? res) (not (:noop? res))))))

(defn restart-events
  "A deadline that expired while the server was DOWN is re-armed to a full fresh
   duration instead of fired.

   Firing it would be technically defensible and practically awful: everyone
   reconnects to find the vote already closed because of an outage they had no
   part in. Time that nobody could play through is not time.

   Arms the fresh deadline directly rather than clearing it and letting
   reconciliation pick it up: the new instant must be measured from the `now-ms`
   passed in, not from whenever the hook next happens to look at a wall clock."
  [game-cfg now-ms state]
  (when-let [deadline (:deadline state)]
    (cond
      ;; Everybody comes back from a restart disconnected, so this is the usual
      ;; case: no clock until somebody is there to be timed. Live or expired.
      (nobody-here? state) [{:type :phase/deadline :at nil}]

      (>= now-ms deadline)
      (or (clock-events game-cfg now-ms (assoc state :deadline nil))
          ;; A stale deadline on a phase that has no clock at all: just stop it.
          [{:type :phase/deadline :at nil}]))))

;; ---------------------------------------------------------------- absence ---

(def default-host-grace-secs
  "How long the host's seat may stand empty before the room stops waiting for
   him. Long enough for a phone to lose its signal on the way to the kitchen and
   find it again; short enough that a table does not sit staring at a frozen
   night. Config: `:host-grace-secs`."
  45)

(defn absence-events
  "Events for a host who has been gone too long, or nil.

   This decides only WHEN: the host's seat disconnected for longer than the
   grace period. What follows — the flag, a clock taking over, the night
   falling, his seat offered to the others — is a rule, and lives in
   `engine/host-absent-events`, which also makes this idempotent: once the room
   is flagged it has nothing more to say.

   A disconnected seat with no `:absent-since` gets no grace at all. That is
   either a host who LEFT mid-game, which is a decision rather than a dropout,
   or a log from before absences were measured, where how long he has been
   gone is unknown and waiting longer would not make it known.

   An empty room is left alone. Nobody is there to take the seat or to be timed,
   and the first player back is measured afresh on the next tick."
  [game-cfg now-ms state]
  (let [host  (get-in state [:players (:host state)])
        grace (* 1000 (or (:host-grace-secs game-cfg) default-host-grace-secs))
        since (:absent-since host)]
    (when (and host
               (false? (:connected? host))
               (not (nobody-here? state))
               (or (nil? since) (>= (- now-ms since) grace)))
      (engine/host-absent-events state))))

(defn notice-absence!
  "Flag one room's host as absent if he has been gone too long. Returns true if
   it did."
  [ctx room-id now-ms]
  (let [res (room/narrate! ctx room-id #(absence-events (:game ctx) now-ms %))]
    (boolean (and (:ok? res) (not (:noop? res))))))

(defn rearm-all!
  "Re-arm every restored room's clock. Call once, after `room/rehydrate!`."
  [ctx game-cfg now-ms]
  (into []
        (keep (fn [room-id]
                (let [res (room/narrate! ctx room-id #(restart-events game-cfg now-ms %))]
                  (when-not (:noop? res) room-id))))
        (room/room-ids ctx)))

(defn tick!
  "Fire every expired deadline, and notice every host who has been gone too
   long. Returns the room ids that timed out.

   Takes `now-ms` rather than reading a clock so the whole timer can be tested
   at simulated instants, instantly."
  [ctx now-ms]
  ;; Housekeeping first, so a room dropped this tick is not also fired at.
  ;; Failures here must not stop the clock: an unswept room is untidy, a stopped
  ;; clock is a frozen game.
  (try
    (room/sweep-idle! ctx now-ms)
    (catch Throwable t (log/error t "idle sweep failed")))
  (into []
        (filter (fn [room-id]
                  (try
                    ;; Absence first: in a narrated game it may switch the clock
                    ;; on, and the reconcile that follows arms it at once.
                    (notice-absence! ctx room-id now-ms)
                    (fire! ctx room-id now-ms)
                    (catch Throwable t
                      ;; One sick room must never stop the clock for the others.
                      (log/error t "timer failed for room" room-id)
                      false))))
        (room/room-ids ctx)))

;; ------------------------------------------------------------- lifecycle ---

(defn- daemon-factory []
  (reify ThreadFactory
    (newThread [_ r]
      (doto (Thread. r "werwolf-timer")
        ;; Daemon: the clock must never be the reason the JVM refuses to exit.
        (.setDaemon true)))))

(defn start!
  "Start the ticker. Returns a handle for `stop!`."
  [ctx {:keys [period-ms]}]
  (let [^ScheduledExecutorService ex (Executors/newSingleThreadScheduledExecutor (daemon-factory))
        period (or period-ms default-period-ms)]
    ;; scheduleWithFixedDelay cancels the whole schedule if the task ever
    ;; throws, so the catch-all is not defensive decoration — it is the only
    ;; thing keeping the clock alive for the rest of the night.
    (.scheduleWithFixedDelay ex
                             (fn []
                               (try
                                 (tick! ctx (System/currentTimeMillis))
                                 (catch Throwable t
                                   (log/error t "timer tick failed"))))
                             period period TimeUnit/MILLISECONDS)
    (log/info "phase timer started, period" period "ms")
    {:executor ex}))

(defn stop! [{:keys [^ScheduledExecutorService executor]}]
  (when executor
    (.shutdownNow executor)
    (log/info "phase timer stopped"))
  nil)
