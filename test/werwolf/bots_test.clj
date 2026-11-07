(ns werwolf.bots-test
  "JVM-only: the server playing the bots.

   A bot's turn is armed after every commit and fired from the clock's tick,
   through the same `submit!` a socket uses. Like the timer suite, nothing here
   sleeps: every function takes `now-ms`, and the clock the hook re-arms from
   is pinned to a simulated instant."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [werwolf.bots :as bots]
            [werwolf.config :as config]
            [werwolf.room :as room]
            [werwolf.store :as store]
            [werwolf.timer :as timer]))

(def game-cfg
  {:election-secs 60 :discussion-secs 300 :night-secs 45 :reaction-secs 30 :verdict-secs 10
   :bot-delay-ms 1500 :bot-spread-ms 6500 :bot-floor-secs 10 :bot-patience-secs 45
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
  "Narrated: start, then the Spielleiter begins the night and calls its first
   role, as a room without a clock waits for him to (phases/paced?)."
  [ctx room-id]
  (room/submit! ctx room-id 0 {:type :start-game})
  (room/submit! ctx room-id 0 {:type :begin-night})
  (room/submit! ctx room-id 0 {:type :advance-phase}))

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
          ;; The role being called is skipped; between two calls and at the
          ;; morning there is nothing to skip, and his button moves the night.
          (when-not (:ok? (room/submit! ctx room-id 0 {:type :gm/skip-role}))
            (room/submit! ctx room-id 0 {:type :advance-phase}))
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

(defn- living-bots [ctx room-id]
  (let [s (state ctx room-id)]
    (filter #(get-in s [:players % :alive?]) (bot-ids ctx room-id))))

(defn- living-bot-votes [ctx room-id]
  (let [s (state ctx room-id)]
    (count (filter #(contains? (:votes s) %) (living-bots ctx room-id)))))

(defn- all-bots-voted? [ctx room-id]
  (= (count (living-bots ctx room-id)) (living-bot-votes ctx room-id)))

(defn- armed-at
  "The instants the bots are armed for, one per armed seat."
  [ctx room-id]
  (map :at (vals (:bots (room-map ctx room-id)))))

(def spread-ms
  "The delay plus the spread: the latest a night move is armed for, and how
   far apart the day's early ballots land."
  (+ (:bot-delay-ms game-cfg) (:bot-spread-ms game-cfg)))

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
  ;; The delay is the floor; on top of it each seat waits its own share of
  ;; the spread. Exactly the delay, every time, told the table which seats
  ;; were bots.
  (with-ctx [ctx]
    (let [{:keys [room-id]} (bot-table ctx 5)]
      (at! ctx t0)
      (begin! ctx room-id)
      (let [n0 (events ctx room-id)]
        (is (seq (:bots (room-map ctx room-id))) "somebody's turn is armed")
        (is (every? #(<= (+ t0 1500) % (+ t0 spread-ms)) (armed-at ctx room-id))
            "within the delay and the spread")
        (timer/tick! ctx (+ t0 1400))
        (is (= n0 (events ctx room-id)) "nothing before the delay")
        (tick-until ctx (+ t0 1500) (+ t0 spread-ms 1000) #(< n0 (events ctx room-id)))
        (is (< n0 (events ctx room-id)) "the first bot has moved by the end of the spread")))))

(deftest by-day-bots-wait-between-floor-and-patience
  ;; Each bot has its own instant in the window: nothing before the floor,
  ;; the first soon after it, everybody by the patience, and never all in
  ;; one tick. Five bots that voted in one tick, the first at random and
  ;; the rest behind it, read as a stampede, and the human had no time to
  ;; read the screen.
  (let [floor    (* 1000 (:bot-floor-secs game-cfg))
        patience (* 1000 (:bot-patience-secs game-cfg))
        one-each (fn [ctx room-id]
                   (let [ats (armed-at ctx room-id)]
                     (is (= 5 (count ats)) "every bot is armed")
                     (is (= 5 (count (distinct ats))) "no two in the same instant")
                     (is (> (- (apply max ats) (apply min ats)) 2000) "and not within one tick of each other")))]
    (testing "A: narrated, the human holds no vote: the floor applies, then the spread"
      (with-ctx [ctx]
        (let [{:keys [room-id]} (bot-table ctx 5)]
          (at! ctx t0)
          (begin! ctx room-id)
          (to-open-ballot! ctx room-id)
          (let [since (:phase-since (room-map ctx room-id))]
            (is (some? since))
            (one-each ctx room-id)
            (is (every? #(<= (+ since floor) % (+ since floor spread-ms)) (armed-at ctx room-id)))
            (timer/tick! ctx (+ since floor -100))
            (is (zero? (living-bot-votes ctx room-id)) "not before the floor")
            (tick-until ctx (+ since floor 100) (+ since floor 2000) #(pos? (living-bot-votes ctx room-id)))
            (is (pos? (living-bot-votes ctx room-id)) "from the floor on, the first bot votes")
            (is (not (all-bots-voted? ctx room-id)) "and not all of them")
            (tick-until ctx (+ since floor 2100) (+ since floor spread-ms 1000) #(all-bots-voted? ctx room-id))
            (is (all-bots-voted? ctx room-id) "all by the end of the spread")))))
    (testing "B: the human plays and never votes: the slots fill the window up to the patience"
      (with-ctx [ctx]
        (let [{:keys [room-id]} (bot-table ctx 5 {:narrated? false})]
          (at! ctx t0)
          (room/submit! ctx room-id 0 {:type :start-game})
          (to-open-ballot! ctx room-id)
          (let [since  (:phase-since (room-map ctx room-id))
                window (- patience floor)]
            (one-each ctx room-id)
            (is (every? #(<= (+ since floor) % (+ since patience)) (armed-at ctx room-id)))
            (timer/tick! ctx (+ since floor -100))
            (is (zero? (living-bot-votes ctx room-id)) "not before the floor")
            ;; Five slots of a fifth of the window each, the first within the
            ;; first half of its slot.
            (tick-until ctx (+ since floor 100) (+ since floor (quot window 10) 1000) #(pos? (living-bot-votes ctx room-id)))
            (is (pos? (living-bot-votes ctx room-id)) "the first soon after the floor")
            ;; The last slot is the window's last fifth.
            (timer/tick! ctx (+ since floor (quot (* 4 window) 5) -100))
            (is (not (all-bots-voted? ctx room-id)) "the last one waits for the human a while longer")
            (tick-until ctx (+ since floor (quot (* 4 window) 5)) (+ since patience 100) #(all-bots-voted? ctx room-id))
            (is (all-bots-voted? ctx room-id) "all by the patience")))))
    (testing "C: the human votes early: the bots follow after the floor, one by one"
      (with-ctx [ctx]
        (let [{:keys [room-id]} (bot-table ctx 5 {:narrated? false})]
          (at! ctx t0)
          (room/submit! ctx room-id 0 {:type :start-game})
          (to-open-ballot! ctx room-id)
          (let [since (:phase-since (room-map ctx room-id))]
            (at! ctx (+ since 3000))
            (is (:ok? (room/submit! ctx room-id 0 {:type :vote :target 1})))
            (one-each ctx room-id)
            (timer/tick! ctx (+ since floor -100))
            (is (zero? (living-bot-votes ctx room-id)) "still not before the floor")
            (tick-until ctx (+ since floor 100) (+ since floor 2000) #(pos? (living-bot-votes ctx room-id)))
            (is (pos? (living-bot-votes ctx room-id)) "right after the floor")
            (is (not (all-bots-voted? ctx room-id)) "and the rest spread out behind")
            (tick-until ctx (+ since floor 2100) (+ since floor spread-ms 1000) #(all-bots-voted? ctx room-id))
            (is (all-bots-voted? ctx room-id))))))
    (testing "D: the human votes late: an answer pulls the slots still to come earlier, never later"
      (with-ctx [ctx]
        (let [{:keys [room-id]} (bot-table ctx 5 {:narrated? false})]
          (at! ctx t0)
          (room/submit! ctx room-id 0 {:type :start-game})
          (to-open-ballot! ctx room-id)
          (let [since (:phase-since (room-map ctx room-id))
                human (+ since floor 1000)
                slots (into {} (map (fn [[pid b]] [pid (:at b)])) (:bots (room-map ctx room-id)))]
            (at! ctx human)
            (is (:ok? (room/submit! ctx room-id 0 {:type :vote :target 1})))
            (let [ats (into {} (map (fn [[pid b]] [pid (:at b)])) (:bots (room-map ctx room-id)))]
              (is (every? (fn [[pid at]] (<= at (slots pid))) ats) "never later than the slot")
              (is (some (fn [[pid at]] (< at (slots pid))) ats) "the late slots are pulled in")
              (is (every? #(<= % (+ human spread-ms)) (vals ats)) "to the delay and the spread after the answer")
              (is (= (count ats) (count (distinct (vals ats))))))
            (tick-until ctx (+ human 1000) (+ human spread-ms 1000) #(all-bots-voted? ctx room-id))
            (is (all-bots-voted? ctx room-id) "all by the end of the spread: the bots' own commits in between pushed nobody out")))))))

(deftest the-shipped-pacing-keeps-a-ballot-short
  ;; The tests above pin a floor and a patience of their own, so this one says
  ;; what ships: a human who waits to see where the bots lean sits out the
  ;; patience at every ballot, and at 45 s that was most of a day.
  (let [shipped (:game (config/load-config :test))]
    (is (= 5 (:bot-floor-secs shipped)))
    (is (= 20 (:bot-patience-secs shipped)))
    (is (= 1500 (:bot-delay-ms shipped)))
    (is (= 6500 (:bot-spread-ms shipped)) "so a night move lands 1.5 to 8 s after the offer")
    (testing "and a config without the keys falls back to the same pace"
      (is (= (:bot-floor-secs shipped) bots/default-floor-secs))
      (is (= (:bot-patience-secs shipped) bots/default-patience-secs))
      (is (= (:bot-delay-ms shipped) bots/default-delay-ms))
      (is (= (:bot-spread-ms shipped) bots/default-spread-ms)))))

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
        (tick-until ctx (+ t0 71500) (+ t0 70000 spread-ms 1000) #(< (inc n0) (events ctx room-id)))
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
          (tick-until ctx2 (+ t0 121000) (+ t0 120000 spread-ms 1000) #(< (+ 2 n0) (events ctx2 room-id)))
          (is (< (+ 2 n0) (events ctx2 room-id)) "the human is back, the bot's turn resumes")))
      (finally (cleanup! path)))))

(deftest bot-moves-are-not-activity
  (with-ctx [ctx]
    (let [{:keys [room-id sink]} (bot-table ctx 5)]
      (at! ctx t0)
      (begin! ctx room-id)
      (let [before (:last-activity (room-map ctx room-id))
            n0     (events ctx room-id)]
        (tick-until ctx (+ t0 1500) (+ t0 spread-ms 1000) #(< n0 (events ctx room-id)))
        (is (< n0 (events ctx room-id)) "a bot has moved")
        (is (= before (:last-activity (room-map ctx room-id))) "a bot moving is not somebody being there"))
      (room/detach! ctx sink)
      (testing "a room with only bots in it idles out like an empty one"
        ;; Activity is stamped from the wall clock, so the sweep is asked
        ;; from there too.
        (is (= [room-id] (room/sweep-idle! ctx (+ (System/currentTimeMillis)
                                                  (:game-idle-ms game-cfg) 1))))))))

(deftest a-table-every-person-left-is-closed-at-once
  ;; The bot test tables of 2026-11-07: dealt, left within seconds, and then
  ;; counted as games running until the idle limit, and loaded on every boot.
  (with-ctx [ctx]
    (let [{:keys [room-id]} (bot-table ctx 5)]
      (at! ctx t0)
      (begin! ctx room-id)
      (is (:ok? (room/submit! ctx room-id 0 {:type :leave})))
      (is (= [room-id] (room/sweep-idle! ctx (System/currentTimeMillis)))
          "no idle limit to wait out")
      (is (not (contains? @(:rooms ctx) room-id)))
      (is (not-any? #{room-id} (store/unfinished-room-ids (:ds ctx)))
          "closed in the log, so no boot brings it back"))))

(deftest a-lobby-left-to-the-bots-goes-too
  (with-ctx [ctx]
    (let [{:keys [room-id]} (bot-table ctx 3)]
      (is (:ok? (room/submit! ctx room-id 0 {:type :leave})))
      (is (= [room-id] (room/sweep-idle! ctx (System/currentTimeMillis)))))))

(deftest a-table-with-a-person-still-seated-waits
  (with-ctx [ctx]
    (let [{:keys [room-id sink]} (bot-table ctx 5)]
      (at! ctx t0)
      (begin! ctx room-id)
      (room/detach! ctx sink)
      (is (= [] (room/sweep-idle! ctx (System/currentTimeMillis)))
          "gone quiet is not gone: the phone may only have locked"))))

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
        (is (<= (+ t0 1500) at (+ t0 spread-ms)) "armed a delay and its share of the spread after the offer appeared")
        (testing "the same offer keeps its instant across a rearm"
          (bots/rearm! ctx game-cfg room-id (+ t0 20000))
          (is (= at (get-in (room-map ctx room-id) [:bots pid :at]))))
        (testing "a different offer for the same seat is armed afresh, the same share later"
          (room/schedule! ctx room-id {:bots {pid {:offers #{:hexe-pass} :at (- t0 5000)}}})
          (bots/rearm! ctx game-cfg room-id (+ t0 20000))
          (is (= offers (get-in (room-map ctx room-id) [:bots pid :offers])))
          (is (= (+ at 20000) (get-in (room-map ctx room-id) [:bots pid :at]))))))))

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
