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
    (when (>= now-ms deadline)
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
   has to remember to touch the timer."
  [game-cfg]
  (fn [ctx room-id]
    (reconcile! ctx game-cfg room-id (System/currentTimeMillis))))

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
    (when (>= now-ms deadline)
      (or (clock-events game-cfg now-ms (assoc state :deadline nil))
          ;; A stale deadline on a phase that has no clock at all: just stop it.
          [{:type :phase/deadline :at nil}]))))

(defn rearm-all!
  "Re-arm every restored room's clock. Call once, after `room/rehydrate!`."
  [ctx game-cfg now-ms]
  (into []
        (keep (fn [room-id]
                (let [res (room/narrate! ctx room-id #(restart-events game-cfg now-ms %))]
                  (when-not (:noop? res) room-id))))
        (room/room-ids ctx)))

(defn tick!
  "Fire every expired deadline. Returns the room ids that timed out.

   Takes `now-ms` rather than reading a clock so the whole timer can be tested
   at simulated instants, instantly."
  [ctx now-ms]
  (into []
        (filter (fn [room-id]
                  (try
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
