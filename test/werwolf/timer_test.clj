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
  {:discussion-secs 300 :nomination-secs 90 :vote-secs 60
   :night-secs 45 :reaction-secs 30})

(def t0 1700000000000)

(defn- tmp-path []
  (let [f (java.io.File/createTempFile "werwolf-timer" ".db")]
    (.delete f) (.getAbsolutePath f)))

(defn- cleanup! [path]
  (doseq [suffix ["" "-wal" "-shm"]] (io/delete-file (str path suffix) true)))

(defn- ctx-on
  "A registry WITH a clock installed — the hook is what makes deadlines appear."
  [path]
  (room/registry {:ds (store/open {:dbtype "sqlite" :dbname path})
                  :send! (fn [s msg] (swap! s conj msg))
                  :after-commit (timer/hook game-cfg)}))

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
   on how the Seherin happens to play."
  [ctx room-id]
  (loop [guard 0]
    (when (and (= :night (phase ctx room-id)) (< guard 12))
      (room/submit! ctx room-id 0 {:type :gm/skip-role})
      (recur (inc guard)))))

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
           (timer/clock-events game-cfg t0 {:phase :vote})))
    (is (nil? (timer/clock-events game-cfg t0 {:phase :vote :deadline 123}))
        "an armed clock is left alone")
    (is (= [{:type :phase/deadline :at nil}]
           (timer/clock-events game-cfg t0 {:phase :lobby :deadline 123}))
        "a stale deadline on an untimed phase is cleared")
    (is (nil? (timer/clock-events game-cfg t0 {:phase :lobby})))
    (testing "a missing duration slows the game down; it does not crash it"
      (is (nil? (timer/clock-events {} t0 {:phase :vote}))))))

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
        ;; Discussion and nomination both time out, which walks us into the vote.
        (timer/tick! ctx (inc (deadline ctx room-id)))
        (is (= :nomination (phase ctx room-id)))
        (let [st (room/state-of ctx room-id)
              living (engine/living-ids st)
              nominee (second living)]
          (room/submit! ctx room-id (first living) {:type :nominate :target nominee})
          (timer/tick! ctx (inc (deadline ctx room-id)))
          (is (= :vote (phase ctx room-id)))
          ;; One voter, everybody else silent.
          (room/submit! ctx room-id (first living) {:type :vote :target nominee})
          (timer/tick! ctx (inc (deadline ctx room-id)))
          (let [st' (room/state-of ctx room-id)]
            (is (not= :vote (:phase st')) "the vote closed on time")
            (is (some #(= :vote/resolved (:type %)) (:log st')))
            (is (false? (get-in st' [:players nominee :alive?]))
                "a single vote against a lone nominee is a majority of the votes cast")))))))

(deftest a-lone-timed-out-vote-with-nobody-voting-kills-nobody
  (with-ctx [ctx]
    (let [room-id (table ctx 7)]
      (room/submit! ctx room-id 0 {:type :start-game})
      (skip-night! ctx room-id)
      (let [alive-before (set (engine/living-ids (room/state-of ctx room-id)))]
        ;; discussion -> nomination -> vote -> resolve, entirely on the clock
        (dotimes [_ 3] (timer/tick! ctx (inc (deadline ctx room-id))))
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
            (is (= [room-id] (timer/rearm-all! ctx2 game-cfg later)))
            (let [st (room/state-of ctx2 room-id)]
              (is (= phase-before (:phase st)) "the phase survived the outage")
              (is (> (:deadline st) later) "with a full fresh clock")
              (testing "and it does not immediately fire"
                (is (empty? (timer/tick! ctx2 later)))))))
        (finally (cleanup! path))))))

(deftest restart-events-leave-a-live-deadline-alone
  (is (nil? (timer/restart-events game-cfg t0 {:phase :vote :deadline (+ t0 5000)}))
      "a server that was only briefly down must not hand out extra time")
  (testing "and an expired one is measured from the instant we were handed,
            never from whenever a wall clock is next consulted"
    (is (= [{:type :phase/deadline :at (+ t0 60000)}]
           (timer/restart-events game-cfg t0 {:phase :vote :deadline (- t0 5000)}))))
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
                :state {:phase :vote :deadline 1}})
        (let [fired (timer/tick! ctx (inc (deadline ctx good)))]
          (is (contains? (set fired) good) "the healthy room still fired"))))))
