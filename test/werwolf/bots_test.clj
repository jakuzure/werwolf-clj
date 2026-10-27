(ns werwolf.bots-test
  "JVM-only: the server playing the bots.

   A bot's turn is armed after every commit and fired from the clock's tick,
   through the same `submit!` a socket uses. Like the timer suite, nothing here
   sleeps: every function takes `now-ms`, and the clock the hook re-arms from
   is pinned to a simulated instant."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [werwolf.bots :as bots]
            [werwolf.room :as room]
            [werwolf.store :as store]
            [werwolf.timer :as timer]))

(def game-cfg
  {:election-secs 60 :discussion-secs 300 :night-secs 45 :reaction-secs 30 :verdict-secs 10
   :bot-delay-ms 1500 :bot-floor-secs 10 :bot-patience-secs 45
   :room-idle-ms 300000 :game-idle-ms 1800000})

(def t0 1700000000000)

(defn- tmp-path []
  (let [f (java.io.File/createTempFile "werwolf-bots" ".db")]
    (.delete f) (.getAbsolutePath f)))

(defn- cleanup! [path]
  (doseq [suffix ["" "-wal" "-shm"]] (io/delete-file (str path suffix) true)))

(defn- ctx-on
  "A registry with the clock's hook installed, which is what arms bots at all.
   The hook's clock is pinned with `at!`; the sweep reads the same durations."
  [path]
  (let [pinned (atom t0)]
    (assoc (room/registry {:ds (store/open {:dbtype "sqlite" :dbname path})
                           :send! (fn [s msg] (swap! s conj msg))
                           :game (assoc game-cfg :first-night-kill? true :first-day-trial? true)
                           :after-commit (timer/hook game-cfg #(deref pinned))})
           ::pinned pinned)))

(defn- at! [ctx ms] (reset! (::pinned ctx) ms))

(defmacro with-ctx [[binding] & body]
  `(let [path# (tmp-path), ~binding (ctx-on path#)]
     (try ~@body (finally (cleanup! path#)))))

(defn- bot-table
  "A human in seat 0, attached to a sink, plus `bots` bots. Narrated by default,
   so every card is a bot's and no test depends on what seed 7 was dealt; pass
   {:narrated? false} for a table where the human plays."
  ([ctx bots] (bot-table ctx bots {}))
  ([ctx bots {:keys [narrated?] :or {narrated? true}}]
   (let [{:keys [room-id]} (room/create! ctx {:name "P0" :narrated? narrated? :seed 7})
         sink (atom [])]
     (room/attach! ctx room-id 0 sink)
     (dotimes [_ bots] (room/submit! ctx room-id 0 {:type :gm/add-bot}))
     {:room-id room-id :sink sink})))

(defn- state [ctx room-id] (room/state-of ctx room-id))
(defn- phase [ctx room-id] (:phase (state ctx room-id)))
(defn- events
  "How many events the room has committed. Not the public log: a Seherin's
   look or the pack's vote is private and leaves no line there, and the first
   version of these tests waited for one."
  [ctx room-id]
  (:seq (get @(:rooms ctx) room-id)))
(defn- room-map [ctx room-id] (get @(:rooms ctx) room-id))

(defn- begin!
  "Narrated: start, then the Spielleiter begins the night."
  [ctx room-id]
  (room/submit! ctx room-id 0 {:type :start-game})
  (room/submit! ctx room-id 0 {:type :begin-night}))

(defn- tick-until
  "Tick second by second from `from` until `pred` holds or `limit-ms` is reached.
   Returns the instant it stopped at."
  [ctx from limit-ms pred]
  (loop [now from]
    (at! ctx now)
    (timer/tick! ctx now)
    (cond (pred) now
          (>= now limit-ms) now
          :else (recur (+ now 1000)))))

(defn- to-open-ballot!
  "Skip night 1 role by role with the host's override, and move every other
   phase along as the host would (or the clock, where he may not), so the
   room stands at the first day's open ballot. No tick runs here, so no bot
   has moved yet."
  [ctx room-id]
  (loop [guard 0]
    (let [ph (phase ctx room-id)]
      (when (and (not= :discussion ph) (< guard 30))
        (if (= :night ph)
          (room/submit! ctx room-id 0 {:type :gm/skip-role})
          (when-not (:ok? (room/submit! ctx room-id 0 {:type :advance-phase}))
            ;; The clock the hook re-arms from must move with the tick, or the
            ;; next phase begins, by the scheduler's clock, before this one
            ;; ended — and a later tick finds every bot long overdue.
            (let [due (inc (or (:deadline (state ctx room-id)) 0))]
              (at! ctx due)
              (timer/tick! ctx due))))
        (recur (inc guard)))))
  (assert (= :discussion (phase ctx room-id)) (str "expected the day, got " (phase ctx room-id))))

(defn- bot-ids [ctx room-id]
  (sort (keep (fn [[id p]] (when (:bot? p) id)) (:players (state ctx room-id)))))

(defn- living-bot-votes [ctx room-id]
  (let [s (state ctx room-id)]
    (count (filter #(contains? (:votes s) %)
                   (filter #(get-in s [:players % :alive?]) (bot-ids ctx room-id))))))

;; ------------------------------------------------------------------ tests ---

(deftest bots-fill-seats-and-the-spielleiter-starts
  (with-ctx [ctx]
    (let [{:keys [room-id]} (bot-table ctx 5)]
      (is (= 6 (count (:players (state ctx room-id)))))
      (is (= [1 2 3 4 5] (bot-ids ctx room-id)))
      (is (:ok? (room/submit! ctx room-id 0 {:type :start-game})))
      (is (= :setup (phase ctx room-id)))
      (is (:ok? (room/submit! ctx room-id 0 {:type :begin-night})))
      (is (= :night (phase ctx room-id))))))

(deftest a-bot-acts-after-the-delay-and-not-before
  (with-ctx [ctx]
    (let [{:keys [room-id]} (bot-table ctx 5)]
      (at! ctx t0)
      (begin! ctx room-id)
      (let [n0 (events ctx room-id)]
        (is (seq (:bots (room-map ctx room-id))) "somebody's turn is armed")
        (timer/tick! ctx (+ t0 1000))
        (is (= n0 (events ctx room-id)) "nothing yet")
        (timer/tick! ctx (+ t0 1600))
        (is (< n0 (events ctx room-id)) "the first bot has moved")))))

(deftest by-day-bots-wait-between-floor-and-patience
  (testing "A: narrated, the human holds no vote — the floor applies"
    (with-ctx [ctx]
      (let [{:keys [room-id]} (bot-table ctx 5)]
        (at! ctx t0)
        (begin! ctx room-id)
        (to-open-ballot! ctx room-id)
        (let [since (:phase-since (room-map ctx room-id))]
          (is (some? since))
          (timer/tick! ctx (+ since 9900))
          (is (zero? (living-bot-votes ctx room-id)) "not before the floor")
          (tick-until ctx (+ since 10100) (+ since 14000) #(pos? (living-bot-votes ctx room-id)))
          (is (pos? (living-bot-votes ctx room-id)) "from the floor on, the bots vote")))))
  (testing "B: the human plays and never votes — the cap applies"
    (with-ctx [ctx]
      (let [{:keys [room-id]} (bot-table ctx 5 {:narrated? false})]
        (at! ctx t0)
        (room/submit! ctx room-id 0 {:type :start-game})
        (to-open-ballot! ctx room-id)
        (let [since (:phase-since (room-map ctx room-id))]
          (timer/tick! ctx (+ since 44900))
          (is (zero? (living-bot-votes ctx room-id)) "waiting for the human")
          (tick-until ctx (+ since 45100) (+ since 50000) #(pos? (living-bot-votes ctx room-id)))
          (is (pos? (living-bot-votes ctx room-id)) "patience ran out")))))
  (testing "C: the human votes early — the bots follow after the floor"
    (with-ctx [ctx]
      (let [{:keys [room-id]} (bot-table ctx 5 {:narrated? false})]
        (at! ctx t0)
        (room/submit! ctx room-id 0 {:type :start-game})
        (to-open-ballot! ctx room-id)
        (let [since (:phase-since (room-map ctx room-id))]
          (at! ctx (+ since 3000))
          (is (:ok? (room/submit! ctx room-id 0 {:type :vote :target 1})))
          (timer/tick! ctx (+ since 9900))
          (is (zero? (living-bot-votes ctx room-id)) "still not before the floor")
          (tick-until ctx (+ since 10100) (+ since 14000) #(pos? (living-bot-votes ctx room-id)))
          (is (pos? (living-bot-votes ctx room-id)) "right after the floor"))))))

(deftest bots-do-nothing-while-nobody-is-here
  (with-ctx [ctx]
    (let [{:keys [room-id sink]} (bot-table ctx 5)]
      (at! ctx t0)
      (begin! ctx room-id)
      (room/detach! ctx sink)
      (let [n0 (events ctx room-id)]
        (timer/tick! ctx (+ t0 5000))
        (timer/tick! ctx (+ t0 60000))
        (is (= n0 (events ctx room-id)) "an empty table is not played")
        (at! ctx (+ t0 70000))
        (room/attach! ctx room-id 0 sink)
        (timer/tick! ctx (+ t0 71000))
        (is (= (inc n0) (events ctx room-id)) "the reconnect alone is logged, not a move")
        (timer/tick! ctx (+ t0 71600))
        (is (< (inc n0) (events ctx room-id)) "then the bot moves")))))

(deftest a-rehydrated-room-keeps-its-bots-and-waits-for-a-human
  (let [path (tmp-path)]
    (try
      (let [ctx (ctx-on path)
            {:keys [room-id]} (bot-table ctx 5)]
        (at! ctx t0)
        (begin! ctx room-id)
        (let [n0  (events ctx room-id)
              ctx2 (ctx-on path)]
          (room/rehydrate! ctx2 (+ t0 100000))
          (is (= [1 2 3 4 5] (bot-ids ctx2 room-id)) "still bots after a restart")
          (timer/tick! ctx2 (+ t0 110000))
          (is (= (inc n0) (events ctx2 room-id)) "only the human's disconnect was logged")
          (at! ctx2 (+ t0 120000))
          (room/attach! ctx2 room-id 0 (atom []))
          (timer/tick! ctx2 (+ t0 122000))
          (is (< (+ 2 n0) (events ctx2 room-id)) "the human is back, the bot's turn resumes")))
      (finally (cleanup! path)))))

(deftest bot-moves-are-not-activity
  (with-ctx [ctx]
    (let [{:keys [room-id sink]} (bot-table ctx 5)]
      (at! ctx t0)
      (begin! ctx room-id)
      (let [before (:last-activity (room-map ctx room-id))]
        (timer/tick! ctx (+ t0 1600))
        (is (= before (:last-activity (room-map ctx room-id))) "a bot moving is not somebody being there"))
      (room/detach! ctx sink)
      (testing "a room with only bots in it idles out like an empty one"
        ;; Activity is stamped from the wall clock, so the sweep is asked
        ;; from there too.
        (is (= [room-id] (room/sweep-idle! ctx (+ (System/currentTimeMillis)
                                                  (:game-idle-ms game-cfg) 1))))))))

(deftest the-heir-is-never-a-bot
  (with-ctx [ctx]
    (let [{:keys [room-id]} (room/create! ctx {:name "P0" :narrated? false :seed 7})]
      (room/submit! ctx room-id 0 {:type :gm/add-bot})
      (room/submit! ctx room-id 0 {:type :gm/add-bot})
      (is (= 3 (:player-id (room/join! ctx room-id {:name "P3"}))))
      (is (:ok? (room/submit! ctx room-id 0 {:type :leave})))
      (is (= 3 (:host (state ctx room-id))) "the lowest human seat, past two bots"))))

(deftest a-dead-human-watches-the-bots-finish
  (with-ctx [ctx]
    (let [{:keys [room-id]} (bot-table ctx 5 {:narrated? false})]
      (at! ctx t0)
      (room/submit! ctx room-id 0 {:type :start-game})
      (is (:ok? (room/submit! ctx room-id 0 {:type :gm/force-kill :target 0})))
      (let [end (tick-until ctx (+ t0 1000) (+ t0 600000) #(= :game-over (phase ctx room-id)))]
        (is (= :game-over (phase ctx room-id)) (str "still " (phase ctx room-id) " after " (- end t0) " ms"))
        (is (some? (:winner (state ctx room-id))))
        (is (contains? (:sinks (room-map ctx room-id)) 0) "the human stayed attached")))))

(deftest after-an-absence-the-floor-counts-from-the-return
  ;; The spec's own scenario: the human's phone goes in a pocket mid-day. The
  ;; floor exists so a watcher can read the screen, and a watcher coming back
  ;; has not read anything — the bots must not vote the instant the socket is
  ;; back because the day began long ago.
  (with-ctx [ctx]
    (let [{:keys [room-id sink]} (bot-table ctx 5)]
      (at! ctx t0)
      (begin! ctx room-id)
      (to-open-ballot! ctx room-id)
      (let [since (:phase-since (room-map ctx room-id))]
        (at! ctx (+ since 3000))
        (room/detach! ctx sink)
        (timer/tick! ctx (+ since 60000))
        (is (zero? (living-bot-votes ctx room-id)) "nobody here, nobody votes")
        (let [back (+ since 90000)]
          (at! ctx back)
          (room/attach! ctx room-id 0 sink)
          (timer/tick! ctx (+ back 1000))
          (is (zero? (living-bot-votes ctx room-id)) "the floor counts from the return, not from the phase")
          (tick-until ctx (+ back 10100) (+ back 14000) #(pos? (living-bot-votes ctx room-id)))
          (is (pos? (living-bot-votes ctx room-id)) "and then the bots vote"))))))

(deftest an-armed-turn-is-kept-for-the-same-offer-only
  ;; A seat whose turn has two moves — the Hexe's heal and then her pass —
  ;; must get a delay before each, not the first move's instant for both.
  (with-ctx [ctx]
    (let [{:keys [room-id]} (bot-table ctx 5)]
      (at! ctx t0)
      (begin! ctx room-id)
      (let [[pid {:keys [offers at]}] (first (:bots (room-map ctx room-id)))]
        (is (= (+ t0 1500) at) "armed a delay after the offer appeared")
        (testing "the same offer keeps its instant across a rearm"
          (bots/rearm! ctx game-cfg room-id (+ t0 20000))
          (is (= (+ t0 1500) (get-in (room-map ctx room-id) [:bots pid :at]))))
        (testing "a different offer for the same seat is armed afresh"
          (room/schedule! ctx room-id {:bots {pid {:offers #{:hexe-pass} :at (- t0 5000)}}})
          (bots/rearm! ctx game-cfg room-id (+ t0 20000))
          (is (= offers (get-in (room-map ctx room-id) [:bots pid :offers])))
          (is (= (+ t0 21500) (get-in (room-map ctx room-id) [:bots pid :at]))))))))

(deftest a-refused-move-for-a-disarmed-seat-keeps-the-room
  ;; A human's commit landed between the tick reading the schedule and the bot
  ;; submitting, closed the vote and re-armed the others without this seat. The
  ;; refused bot is then moved back by its patience — and that once replaced
  ;; the whole room with nil: a Bombadil run lost its game from memory mid-day,
  ;; and the sweep logged "dropping idle room" for it every second after.
  (with-ctx [ctx]
    (let [{:keys [room-id]} (bot-table ctx 5)]
      (at! ctx t0)
      (begin! ctx room-id)
      (let [armed  (set (keys (:bots (room-map ctx room-id))))
            pid    (first (remove armed (bot-ids ctx room-id)))
            before (room-map ctx room-id)]
        (is (some? pid) "a bot whose turn it is not")
        (room/reschedule! ctx room-id pid (+ t0 45000))
        (is (some? (room-map ctx room-id)) "the room is still in memory")
        (is (= (:state before) (state ctx room-id)))
        (is (= (:bots before) (:bots (room-map ctx room-id))) "nothing to move, so nothing moved")))))

(deftest a-late-write-to-a-dropped-room-leaves-nothing-behind
  ;; The tick reads the schedule and writes it back without the room's lock,
  ;; so the sweep can drop the room in between. A write that lands on an empty
  ;; slot must not bring the id back as nil: nothing ever removes that, and the
  ;; ticker would visit it every second for the rest of the process.
  (with-ctx [ctx]
    (let [{:keys [room-id]} (bot-table ctx 5)]
      (at! ctx t0)
      (begin! ctx room-id)
      (let [[pid] (first (:bots (room-map ctx room-id)))]
        (room/forget-room! ctx room-id)
        (room/schedule! ctx room-id {:phase :night :phase-since t0 :bots {}})
        (room/reschedule! ctx room-id pid (+ t0 45000))
        (is (not (contains? @(:rooms ctx) room-id)))
        (is (= [] (room/room-ids ctx)))
        (is (= [] (room/sweep-idle! ctx (+ t0 (:game-idle-ms game-cfg) 1)))
            "nothing left for the sweep to drop again")))))
