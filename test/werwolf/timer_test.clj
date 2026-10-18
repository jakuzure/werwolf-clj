(ns werwolf.timer-test
  "JVM-only: the clock. Not one of these tests sleeps.

   Every function under test takes `now-ms`, so time is an argument rather than
   an ambient fact — which is the whole reason a timer suite can run in
   milliseconds and never flake."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [werwolf.engine.core :as engine]
            [werwolf.room :as room]
            [werwolf.store :as store]
            [werwolf.timer :as timer]))

(def game-cfg
  {:election-secs 60 :discussion-secs 300
   :night-secs 45 :reaction-secs 30})

(def t0 1700000000000)

(defn- tmp-path []
  (let [f (java.io.File/createTempFile "werwolf-timer" ".db")]
    (.delete f) (.getAbsolutePath f)))

(defn- cleanup! [path]
  (doseq [suffix ["" "-wal" "-shm"]] (io/delete-file (str path suffix) true)))

(defn- ctx-on
  "A registry WITH a clock installed — the hook is what makes deadlines appear.

   The hook's clock is the wall clock unless a test pins it with `at!`, which a
   test must do when it ticks the same simulated instant twice: see timer/hook."
  [path]
  (let [pinned (atom nil)]
    (assoc (room/registry {:ds (store/open {:dbtype "sqlite" :dbname path})
                           :send! (fn [s msg] (swap! s conj msg))
                           ;; Classic first night: one test walks to the wolves'
                           ;; turn to check that a timed-out pack still eats.
                           ;; And the creator plays, with a clock: a narrated room
                           ;; starts with none, and a clock is what is under test.
                           :game {:first-night-kill? true :first-day-trial? true :narrated? false}
                           :after-commit (timer/hook game-cfg
                                                     #(or @pinned (System/currentTimeMillis)))})
           ::pinned pinned)))

(defn- at!
  "Pin the clock deadlines are re-armed from to a simulated instant."
  [ctx ms]
  (reset! (::pinned ctx) ms))

(defmacro with-ctx [[binding] & body]
  `(let [path# (tmp-path), ~binding (ctx-on path#)]
     (try ~@body (finally (cleanup! path#)))))

(defn- table [ctx n]
  (let [{:keys [room-id]} (room/create! ctx {:name "P0"})]
    (doseq [i (range 1 n)] (room/join! ctx room-id {:name (str "P" i)}))
    room-id))

(defn- deadline [ctx room-id] (:deadline (room/state-of ctx room-id)))
(defn- phase [ctx room-id] (:phase (room/state-of ctx room-id)))

(defn- skip-night!
  "Walk the night to its end with GM overrides, so day-phase tests do not depend
   on how the Seherin happens to play.

   Also walks through the Bürgermeister election, which sits between the first
   dawn and the first discussion. Every test below wants the day proper, and
   none of them is about the election — it is resolved by letting its clock
   expire, which is an abstention from everybody and elects nobody."
  [ctx room-id]
  (loop [guard 0]
    (when (and (= :night (phase ctx room-id)) (< guard 12))
      (room/submit! ctx room-id 0 {:type :gm/skip-role})
      (recur (inc guard))))
  (when (= :election (phase ctx room-id))
    (timer/tick! ctx (inc (deadline ctx room-id)))))

;; ------------------------------------------------------------- reconciliation ---
;;
;; What a timeout MEANS is pure and lives in werwolf.engine.phases, so those
;; rules are tested in rules_test.cljc and run on Node too. Everything here is
;; about the server half: when the clock fires, and what it does to a room.

(deftest a-timed-phase-gets-an-absolute-deadline
  (with-ctx [ctx]
    (let [room-id (table ctx 5)]
      (testing "the lobby has no clock — it waits for the host, not the second hand"
        (is (nil? (deadline ctx room-id))))
      (room/submit! ctx room-id 0 {:type :start-game})
      (is (= :night (phase ctx room-id)))
      (let [d (deadline ctx room-id)]
        (is (some? d) "the night is on the clock")
        (is (> d (System/currentTimeMillis)) "and it is in the future")
        (testing "an absolute instant, not a duration — a client has nothing to lie about"
          (is (int? d))
          (is (<= (- d (System/currentTimeMillis)) (* 1000 (:night-secs game-cfg)))))))))

(deftest the-deadline-is-part-of-every-players-view
  (with-ctx [ctx]
    (let [room-id (table ctx 5)
          s (atom [])]
      (room/attach! ctx room-id 1 s)
      (room/submit! ctx room-id 0 {:type :start-game})
      (let [v (:view (last (filter #(= :room/view (:type %)) @s)))]
        (is (= (deadline ctx room-id) (:deadline v))
            "clients count down FROM this number; they are never sent a duration")))))

(deftest reconciliation-is-idempotent
  (testing "it runs after every single commit, so it had better not re-arm the
            clock each time — a room where nothing happens would otherwise never
            reach its deadline"
    (with-ctx [ctx]
      (let [room-id (table ctx 5)]
        (room/submit! ctx room-id 0 {:type :start-game})
        (let [d (deadline ctx room-id)]
          (dotimes [_ 3] (timer/reconcile! ctx game-cfg room-id (System/currentTimeMillis)))
          (is (= d (deadline ctx room-id)) "the clock did not restart itself"))))))

(deftest clock-events-are-computed-not-guessed
  (testing "the policy in isolation, with no room and no threads"
    (is (= [{:type :phase/deadline :at (+ t0 60000)}]
           (timer/clock-events game-cfg t0 {:phase :election})))
    (is (nil? (timer/clock-events game-cfg t0 {:phase :election :deadline 123}))
        "an armed clock is left alone")
    (is (= [{:type :phase/deadline :at nil}]
           (timer/clock-events game-cfg t0 {:phase :lobby :deadline 123}))
        "a stale deadline on an untimed phase is cleared")
    (is (nil? (timer/clock-events game-cfg t0 {:phase :lobby})))
    (testing "a missing duration slows the game down; it does not crash it"
      (is (nil? (timer/clock-events {} t0 {:phase :election}))))))

;; -------------------------------------------------------------------- firing ---

(deftest nothing-fires-before-the-deadline
  (with-ctx [ctx]
    (let [room-id (table ctx 5)]
      (room/submit! ctx room-id 0 {:type :start-game})
      (let [before (room/state-of ctx room-id)]
        (is (empty? (timer/tick! ctx (- (deadline ctx room-id) 1))))
        (is (= before (room/state-of ctx room-id)) "state untouched")))))

(deftest an-expired-night-turn-is-skipped-and-the-clock-re-arms
  (with-ctx [ctx]
    (let [room-id (table ctx 7)]
      (room/submit! ctx room-id 0 {:type :start-game})
      (let [cursor (:night-cursor (room/state-of ctx room-id))
            d (deadline ctx room-id)]
        (is (some? cursor))
        (is (= [room-id] (timer/tick! ctx (inc d))))
        (let [st (room/state-of ctx room-id)]
          (is (not= cursor (:night-cursor st)) "the stalled role's turn ended")
          (testing "and a fresh clock is armed for whoever acts next — the phase
                    did not change, so nothing else would have re-armed it"
            (is (some? (:deadline st)))
            (is (> (:deadline st) d)))
          (is (some #(= :phase/timed-out (:type %)) (:log st))))))))

(deftest an-expired-deadline-fires-only-once
  (testing "the bug this guards: :night/role-completed does not change the
            phase, so an uncleared deadline would fire again every tick"
    (with-ctx [ctx]
      (let [room-id (table ctx 7)]
        (room/submit! ctx room-id 0 {:type :start-game})
        (let [d (deadline ctx room-id)
              now (inc d)]
          ;; Without this the next role's deadline is armed from the wall
          ;; clock, which on a fast CI runner is still the millisecond the game
          ;; started in: due at `now`, so the second tick fired it. Flaky in CI,
          ;; never locally.
          (at! ctx now)
          (is (= [room-id] (timer/tick! ctx now)))
          (is (empty? (timer/tick! ctx now)) "the same instant does not fire twice")
          (is (= 1 (count (filter #(= :phase/timed-out (:type %))
                                  (:log (room/state-of ctx room-id)))))))))))

(deftest the-wolves-still-eat-if-they-run-out-of-time
  (testing "a pack that cannot agree in time must not hand the village a free
            night for stalling"
    (with-ctx [ctx]
      (let [room-id (table ctx 7)]
        (room/submit! ctx room-id 0 {:type :start-game})
        ;; Walk to the wolves' turn.
        (loop [guard 0]
          (when (and (not= :werwolf (:night-cursor (room/state-of ctx room-id)))
                     (< guard 10))
            (room/submit! ctx room-id 0 {:type :gm/skip-role})
            (recur (inc guard))))
        (let [st (room/state-of ctx room-id)
              wolves (sort (filter #(= :werewolf (engine/team-of-player st %))
                                   (engine/living-ids st)))
              victim (first (engine/legal-targets st (first wolves) :werewolf-vote))]
          (is (= :werwolf (:night-cursor st)))
          ;; Exactly one wolf votes, then the clock runs out.
          (room/submit! ctx room-id (first wolves) {:type :werewolf-vote :target victim})
          (timer/tick! ctx (inc (deadline ctx room-id)))
          (let [st' (room/state-of ctx room-id)]
            (is (not= :werwolf (:night-cursor st')) "the turn ended")
            (is (or (some #(= victim (:player %)) (:pending-deaths st'))
                    (not (get-in st' [:players victim :alive?])))
                "the single vote that WAS cast decided the victim")))))))

(deftest an-expired-vote-resolves-with-the-votes-that-were-cast
  (testing "silence is an abstention, and an abstention counts as itself"
    (with-ctx [ctx]
      (let [room-id (table ctx 7)]
        (room/submit! ctx room-id 0 {:type :start-game})
        (skip-night! ctx room-id)
        (is (= :discussion (phase ctx room-id)))
        (let [st (room/state-of ctx room-id)
              living (engine/living-ids st)
              accused (second living)]
          (testing "a single vote, everybody else silent: no majority"
            (room/submit! ctx room-id (first living) {:type :vote :target accused})
            (timer/tick! ctx (inc (deadline ctx room-id)))
            (let [st' (room/state-of ctx room-id)]
              (is (not= :discussion (:phase st')) "the ballot closed on time")
              (is (:no-majority? (last (filter #(= :vote/resolved (:type %)) (:log st')))))
              (is (get-in st' [:players accused :alive?])
                  "the silent count against a condemnation, as abstentions do")))
          (testing "while the votes that WERE cast still decide, given a majority"
            (skip-night! ctx room-id)
            (let [st (room/state-of ctx room-id)
                  living (set (engine/living-ids st))
                  voters (take (inc (quot (count living) 2)) (remove #{accused} (sort living)))]
              (is (= :discussion (:phase st)))
              (is (living accused) "the skipped night killed nobody")
              (doseq [v voters] (room/submit! ctx room-id v {:type :vote :target accused}))
              (timer/tick! ctx (inc (deadline ctx room-id)))
              (is (false? (get-in (room/state-of ctx room-id) [:players accused :alive?]))))))))))

(deftest a-lone-timed-out-vote-with-nobody-voting-kills-nobody
  (with-ctx [ctx]
    (let [room-id (table ctx 7)]
      (room/submit! ctx room-id 0 {:type :start-game})
      (skip-night! ctx room-id)
      (let [alive-before (set (engine/living-ids (room/state-of ctx room-id)))]
        ;; the day's ballot closes on the clock, and the verdict follows at once
        (timer/tick! ctx (inc (deadline ctx room-id)))
        (let [st (room/state-of ctx room-id)]
          (is (some #(= :vote/resolved (:type %)) (:log st)))
          (is (= alive-before (set (engine/living-ids st)))
              "nobody voted, so nobody hangs")
          (testing "and the game moves on to the next night rather than stalling"
            (is (= :night (:phase st)))
            (is (= 2 (:night st)))))))))

(deftest a-jaeger-who-never-fires-does-not-fire
  (testing "the shot is his to take, not the server's to guess"
    (with-ctx [ctx]
      (let [room-id (table ctx 7)]
        (room/submit! ctx room-id 0 {:type :start-game})
        (let [st0 (room/state-of ctx room-id)
              jaeger (first (filter #(= :jaeger (get-in st0 [:players % :role]))
                                    (engine/living-ids st0)))]
          (is (some? jaeger) "a 7-player deck contains the Jäger")
          (room/submit! ctx room-id 0 {:type :gm/force-kill :target jaeger})
          (is (= :reaction (phase ctx room-id)))
          (let [living-before (set (engine/living-ids (room/state-of ctx room-id)))
                d (deadline ctx room-id)]
            (is (some? d) "even the reaction is on a clock, or a missing player
                           deadlocks the game")
            (timer/tick! ctx (inc d))
            (let [st (room/state-of ctx room-id)]
              (is (not= :reaction (:phase st)) "the game moved on")
              (is (empty? (:reactions st)) "the shot was dropped, not fabricated")
              (is (= living-before (set (engine/living-ids st)))
                  "nobody was shot by the server"))))))))

;; -------------------------------------------------------------- GM control ---

(deftest the-gm-can-stop-and-restart-the-clock
  (testing "the narrator's oldest privilege: 'hang on, let him finish'"
    (with-ctx [ctx]
      (let [room-id (table ctx 5)]
        (room/submit! ctx room-id 0 {:type :start-game})
        (is (some? (deadline ctx room-id)))

        (is (:ok? (room/submit! ctx room-id 0 {:type :gm/pause})))
        (let [st (room/state-of ctx room-id)]
          (is (true? (:paused? st)))
          (is (nil? (:deadline st)) "the clock is stopped, not merely hidden"))

        (testing "and reconciliation does not helpfully put it back — without the
                  :paused? flag the pause would last about one second"
          (timer/reconcile! ctx game-cfg room-id (System/currentTimeMillis))
          (is (nil? (deadline ctx room-id))))

        (testing "nor does the ticker fire anything while paused"
          (is (empty? (timer/tick! ctx (+ (System/currentTimeMillis) 999999)))))

        (testing "resuming arms a fresh clock rather than the expired one"
          (is (:ok? (room/submit! ctx room-id 0 {:type :gm/resume})))
          (let [st (room/state-of ctx room-id)]
            (is (not (:paused? st)))
            (is (some? (:deadline st)))
            (is (> (:deadline st) (System/currentTimeMillis)))))))))

(deftest pausing-twice-is-an-error-not-a-no-op
  (with-ctx [ctx]
    (let [room-id (table ctx 5)]
      (room/submit! ctx room-id 0 {:type :start-game})
      (room/submit! ctx room-id 0 {:type :gm/pause})
      (is (= :already-paused (:error (room/submit! ctx room-id 0 {:type :gm/pause}))))
      (room/submit! ctx room-id 0 {:type :gm/resume})
      (is (= :not-paused (:error (room/submit! ctx room-id 0 {:type :gm/resume})))))))

(deftest the-gm-can-buy-more-time
  (with-ctx [ctx]
    (let [room-id (table ctx 5)]
      (room/submit! ctx room-id 0 {:type :start-game})
      (let [before (deadline ctx room-id)]
        (is (:ok? (room/submit! ctx room-id 0 {:type :gm/add-time :secs 90})))
        (is (= (+ before 90000) (deadline ctx room-id))
            "extending is arithmetic on an absolute instant, so it needs no clock"))
      (testing "a slip is clamped rather than obeyed"
        (let [before (deadline ctx room-id)]
          (room/submit! ctx room-id 0 {:type :gm/add-time :secs 999999})
          (is (= (+ before (* 1000 600)) (deadline ctx room-id)))))
      (testing "and it is refused when there is no clock to extend"
        (room/submit! ctx room-id 0 {:type :gm/pause})
        (is (= :no-deadline (:error (room/submit! ctx room-id 0 {:type :gm/add-time}))))))))

(deftest only-the-host-may-touch-the-clock
  (with-ctx [ctx]
    (let [room-id (table ctx 5)]
      (room/submit! ctx room-id 0 {:type :start-game})
      (doseq [cmd [{:type :gm/pause} {:type :gm/resume} {:type :gm/add-time}]]
        (is (contains? #{:not-host :not-allowed} (:error (room/submit! ctx room-id 1 cmd)))
            (str cmd " must not be available to a player"))))))

(deftest clock-overrides-are-logged-like-every-other-override
  (testing "auditable by design — the whole point of logging GM powers"
    (with-ctx [ctx]
      (let [room-id (table ctx 5)]
        (room/submit! ctx room-id 0 {:type :start-game})
        (room/submit! ctx room-id 0 {:type :gm/add-time :secs 30})
        (room/submit! ctx room-id 0 {:type :gm/pause})
        (let [log (:log (room/state-of ctx room-id))
              forced (filter #(= :gm/forced (:type %)) log)]
          (is (= [:add-time :pause] (mapv :action forced)))
          (is (every? #(= 0 (:actor %)) forced))
          (is (some #(= :clock/paused (:type %)) log)
              "and the pause itself is public, so nobody thinks the game froze"))))))

;; ---------------------------------------------------------------- restart ---

(deftest a-deadline-that-expired-while-the-server-was-down-is-re-armed
  (testing "firing it would mean reconnecting to find the vote already closed
            because of an outage you had no part in"
    (let [path (tmp-path)]
      (try
        (let [ctx (ctx-on path)
              room-id (table ctx 7)]
          (room/submit! ctx room-id 0 {:type :start-game})
          (let [phase-before (phase ctx room-id)
                ctx2 (ctx-on path)
                _ (room/rehydrate! ctx2)
                ;; An hour of downtime.
                later (+ (System/currentTimeMillis) (* 60 60 1000))]
            (timer/rearm-all! ctx2 game-cfg later)
            (let [st (room/state-of ctx2 room-id)]
              (is (= phase-before (:phase st)) "the phase survived the outage")
              (is (nil? (:deadline st))
                  "everybody comes back from a restart disconnected, and nobody
                   is timed until somebody is there to be"))
            (testing "the first player back gets a full fresh clock"
              (at! ctx2 later)
              (room/attach! ctx2 room-id 1 (atom []))
              (is (= (+ later (* 1000 (:night-secs game-cfg))) (deadline ctx2 room-id)))
              (testing "and it does not immediately fire"
                (is (empty? (timer/tick! ctx2 later)))))))
        (finally (cleanup! path))))))

(deftest restart-events-leave-a-live-deadline-alone
  (is (nil? (timer/restart-events game-cfg t0 {:phase :election :deadline (+ t0 5000)}))
      "a server that was only briefly down must not hand out extra time")
  (testing "and an expired one is measured from the instant we were handed,
            never from whenever a wall clock is next consulted"
    (is (= [{:type :phase/deadline :at (+ t0 60000)}]
           (timer/restart-events game-cfg t0 {:phase :election :deadline (- t0 5000)}))))
  (testing "a stale deadline on a phase with no clock is simply stopped"
    (is (= [{:type :phase/deadline :at nil}]
           (timer/restart-events game-cfg t0 {:phase :lobby :deadline (- t0 5000)})))))

;; --------------------------------------------------------------- the ticker ---

(deftest the-ticker-is-a-daemon-and-stops-cleanly
  (with-ctx [ctx]
    (let [handle (timer/start! ctx {:period-ms 50})]
      (is (some? (:executor handle)))
      (is (nil? (timer/stop! handle)))
      (is (.isShutdown (:executor handle))))))

(deftest one-sick-room-does-not-stop-the-clock-for-the-others
  (testing "tick! walks every room; an exception in one must not abort the sweep"
    (with-ctx [ctx]
      (let [good (table ctx 5)]
        (room/submit! ctx good 0 {:type :start-game})
        ;; Plant a room whose state cannot be reasoned about at all.
        (swap! (:rooms ctx) assoc "BAD"
               {:room-id "BAD" :seq 0 :sinks {} :chat [] :lock (Object.)
                :state {:phase :election :deadline 1}})
        (let [fired (timer/tick! ctx (inc (deadline ctx good)))]
          (is (contains? (set fired) good) "the healthy room still fired"))))))

;; ------------------------------------------------------------- no clock ---

(deftest a-narrated-room-runs-without-a-clock-until-asked
  (testing "the Spielleiter paces the game, so a narrated room opens with no
            clock at all — and switching one on or off mid-game takes effect at
            the next reconcile, which is every commit"
    (with-ctx [ctx]
      (let [{:keys [room-id]} (room/create! ctx {:name "SL" :narrated? true})]
        (doseq [i (range 1 6)] (room/join! ctx room-id {:name (str "P" i)}))
        (room/submit! ctx room-id 0 {:type :start-game})
        (is (= :setup (phase ctx room-id)))
        (is (nil? (deadline ctx room-id)) "the setup step is never timed")
        (room/submit! ctx room-id 0 {:type :begin-night})
        (is (= :night (phase ctx room-id)))
        (is (nil? (deadline ctx room-id)) "a timed phase, and no deadline")
        (is (empty? (timer/tick! ctx (+ t0 (* 1000 60 60 24))))
            "and a whole day later nothing has fired")
        (testing "switched on, the clock arms at once"
          (room/submit! ctx room-id 0 {:type :gm/toggle-timer})
          (is (some? (deadline ctx room-id))))
        (testing "switched off, the deadline it left behind goes with it"
          (room/submit! ctx room-id 0 {:type :gm/toggle-timer})
          (is (nil? (deadline ctx room-id))))))))

(deftest clock-events-arms-nothing-in-an-untimed-room
  (is (nil? (timer/clock-events game-cfg t0 {:phase :election :timer? false})))
  (is (= [{:type :phase/deadline :at nil}]
         (timer/clock-events game-cfg t0 {:phase :election :timer? false :deadline (+ t0 5)}))
      "a deadline left from before the switch is cleared, not left to fire"))

;; ------------------------------------------------------------- an empty room ---

(defn- attach-all!
  "A socket per seat, so the room has somebody in it to time."
  [ctx room-id]
  (into {} (map (fn [pid] (let [s (atom [])] (room/attach! ctx room-id pid s) [pid s])))
        (keys (:players (room/state-of ctx room-id)))))

(deftest an-empty-room-stops-its-clock
  (testing "nobody is timed in a room nobody is in. A clock that ran on regardless
            was found ending phase after phase of an abandoned game, at night
            1384, months after the last player left"
    (with-ctx [ctx]
      (let [room-id (table ctx 5)
            socks (attach-all! ctx room-id)]
        (room/submit! ctx room-id 0 {:type :start-game})
        (is (some? (deadline ctx room-id)))
        (doseq [s (vals socks)] (room/detach! ctx s))
        (is (nil? (deadline ctx room-id)) "the last socket out stops the clock")
        (let [seq-before (:seq (get @(:rooms ctx) room-id))
              later (+ (System/currentTimeMillis) (* 10 60 1000))]
          (is (empty? (timer/tick! ctx later)))
          (is (= seq-before (:seq (get @(:rooms ctx) room-id)))
              "nothing at all was written: no timeout, no absence")
          (testing "the first player back gets the phase's full time"
            (at! ctx later)
            (room/attach! ctx room-id 2 (atom []))
            (is (= (+ later (* 1000 (:night-secs game-cfg))) (deadline ctx room-id)))))))))

(deftest clock-events-in-an-empty-room
  (let [gone {0 {:connected? false} 1 {:connected? false}}]
    (is (= [{:type :phase/deadline :at nil}]
           (timer/clock-events game-cfg t0 {:phase :election :deadline (+ t0 5) :players gone})))
    (is (nil? (timer/clock-events game-cfg t0 {:phase :election :players gone})))
    (is (nil? (timer/expiry-events (+ t0 10) {:phase :election :deadline t0 :players gone}))
        "and an expired deadline does not fire either")
    (is (= [{:type :phase/deadline :at nil}]
           (timer/restart-events game-cfg t0 {:phase :election :deadline (+ t0 5000) :players gone}))
        "a restart that finds nobody leaves no clock running, live or expired")
    (testing "one seat present is enough"
      (is (= [{:type :phase/deadline :at (+ t0 60000)}]
             (timer/clock-events game-cfg t0 {:phase :election
                                              :players (assoc-in gone [1 :connected?] true)}))))))

;; --------------------------------------------------------------- absence ---

(defn- with-host
  "A minimal state for `absence-events`: a host in seat 0, somebody in seat 1."
  [host & {:as more}]
  (merge {:phase :lobby :host 0
          :players {0 (merge {:connected? true} host) 1 {:connected? true}}}
         more))

(deftest absence-waits-out-the-grace-period
  (let [s (with-host {:connected? false :absent-since t0})]
    (is (nil? (timer/absence-events game-cfg (+ t0 44999) s))
        "a phone that blinked is not an absent host")
    (is (= [{:type :host/absent}] (timer/absence-events game-cfg (+ t0 45000) s))
        "45 seconds by default")
    (testing "the grace is config"
      (is (nil? (timer/absence-events {:host-grace-secs 120} (+ t0 60000) s)))
      (is (some? (timer/absence-events {:host-grace-secs 120} (+ t0 120000) s))))
    (testing "idempotent: once flagged there is nothing more to say"
      (is (nil? (timer/absence-events game-cfg (+ t0 999999) (assoc s :host-absent? true)))))
    (testing "a connected host is never absent"
      (is (nil? (timer/absence-events game-cfg (+ t0 999999) (with-host {})))))
    (testing "a host gone with no stamp — he LEFT, or the log predates the stamp —
              gets no grace"
      (is (some? (timer/absence-events game-cfg t0 (with-host {:connected? false})))))
    (testing "an empty room is nobody's business: there is nobody to tell"
      (is (nil? (timer/absence-events game-cfg (+ t0 999999)
                                      (assoc-in s [:players 1 :connected?] false)))))))

(deftest the-ticker-hands-a-silent-spielleiters-game-to-the-clock
  (with-ctx [ctx]
    (let [{:keys [room-id]} (room/create! ctx {:name "SL" :narrated? true})]
      (doseq [i (range 1 6)] (room/join! ctx room-id {:name (str "P" i)}))
      (let [socks (attach-all! ctx room-id)]
        (room/submit! ctx room-id 0 {:type :start-game})
        (room/submit! ctx room-id 0 {:type :begin-night})
        (is (nil? (deadline ctx room-id)) "a narrated night, with no clock")
        (let [gone (System/currentTimeMillis)]
          (room/detach! ctx (socks 0))
          (is (empty? (timer/tick! ctx (+ gone 40000))))
          (is (not (:host-absent? (room/state-of ctx room-id))) "still within his grace")
          (at! ctx (+ gone 50000))
          (timer/tick! ctx (+ gone 50000))
          (let [st (room/state-of ctx room-id)]
            (is (true? (:host-absent? st)))
            (is (engine/timed? st) "the clock took over")
            (is (= (+ gone 50000 (* 1000 (:night-secs game-cfg))) (:deadline st))
                "and is already running")
            (is (= [:host/absent :clock/timer-set]
                   (filterv #{:host/absent :clock/timer-set} (map :type (:log st))))))
          (testing "once"
            (let [n (count (:log (room/state-of ctx room-id)))]
              (timer/tick! ctx (+ gone 51000))
              (is (= n (count (:log (room/state-of ctx room-id)))))))
          (testing "and his return clears the flag, but leaves the clock to him"
            (room/attach! ctx room-id 0 (atom []))
            (let [st (room/state-of ctx room-id)]
              (is (not (:host-absent? st)))
              (is (engine/timed? st))
              (is (contains? (engine/legal-actions st 0) :gm/toggle-timer)))))))))

(deftest a-new-phase-never-inherits-the-last-ones-clock
  (testing "the first day's talk follows the election by :day/began, the night
            after it by :night/began; each starts with no deadline, so the
            timer arms its full length instead of the rest of the last one's"
    (is (nil? (:deadline (engine/apply-event {:phase :election :night 1 :deadline 123}
                                             {:type :day/began}))))
    (is (nil? (:deadline (engine/apply-event {:phase :discussion :night 1 :deadline 123}
                                             {:type :night/began :night 2}))))
    (is (= [{:type :phase/deadline :at (+ t0 300000)}]
           (timer/clock-events game-cfg t0 (engine/apply-event {:phase :election :night 1 :deadline 123
                                                                 :players {1 {:connected? true}}}
                                                                {:type :day/began}))))))
