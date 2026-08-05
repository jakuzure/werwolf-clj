(ns werwolf.sim.sim-test
  "The breadth test: play complete games at every table size and assert the
   engine's invariants after every single event.

   This is the highest-value test in the suite. It found three real bugs during
   development that no example-based test would have: the exhausted Seherin
   deadlocking the night, `legal-actions` offering targetless actions, and the
   dead host freezing the game."
  (:require [clojure.test :refer [are deftest is testing]]
            [werwolf.engine.events :as e]
            [werwolf.engine.schema :as schema]
            [werwolf.sim.harness :as h]))

(defn- describe [failures]
  (pr-str (mapv #(select-keys % [:reason :seed :players :turns :phase :cmd :violations])
                failures)))

(deftest games-complete-without-violating-invariants
  (let [{:keys [failures summary]} (h/play-many 120)]
    (is (empty? failures) (str "failures: " (describe failures)))
    (testing "and the games are actually being played, not short-circuited"
      (is (= 120 (:games summary)))
      (is (> (:avg-turns summary) 20)
          "a real game takes many turns; a tiny average means games are aborting")
      (is (pos? (:avg-events summary))))))

(deftest both-factions-can-win
  (testing "a balance sanity check — if one side never wins, a rule is broken"
    (let [{:keys [summary]} (h/play-many 60)
          winners (:winners summary)]
      (is (pos? (get winners :village 0)) "the village must sometimes win")
      (is (pos? (get winners :werewolf 0)) "the werewolves must sometimes win"))))

(deftest every-table-size-is-playable
  (testing "5 through 18 players all reach a conclusion"
    (doseq [n (range 5 19)]
      (let [r (h/play-game n (+ 1000 (* n 31)))]
        (is (:ok? r)
            (str n " players failed: "
                 (pr-str (select-keys r [:reason :phase :violations :cmd]))))
        (is (some? (:winner r)) (str n " players produced no winner"))))))

(deftest games-are-reproducible-from-their-seed
  (testing "same seed and size must replay identically, so CI failures are
            debuggable locally"
    (let [a (h/play-game 9 4242)
          b (h/play-game 9 4242)]
      (is (= (:winner a) (:winner b)))
      (is (= (:turns a) (:turns b)))
      (is (= (:log a) (:log b)) "identical event logs"))))

(deftest cross-platform-game-fingerprints
  (testing "a given seed must produce the SAME game on Clojure and ClojureScript.

            These numbers are pinned deliberately. They caught a real bug: the
            :vote target list was derived from the :nominations SET, and set
            iteration order differs between platforms, so the same seed played
            out differently on Node. Anything that makes a game depend on set or
            map iteration order will break this test — which is the point.

            If a legitimate rule change alters these, re-pin them from the JVM
            run and confirm Node agrees."
    (are [n seed events turns winner]
         (let [r (h/play-game n seed)]
           (and (= events (count (:log r)))
                (= turns (:turns r))
                (= winner (:winner r))))
      12 8675309 190 93  :werewolf
      9  4242    95  38  :village
      18 777     311 162 :werewolf
      5  31337   76  31  :village
      14 90210   214 105 :werewolf)))

(deftest state-matches-its-schema-at-every-step
  (testing "the closed GameState schema holds through a whole game, so state
            drift is caught rather than accumulating silently"
    (let [r (h/play-game 12 8675309)]
      (is (:ok? r) (str "game failed: " (pr-str (:reason r))))
      (loop [state nil, evs (:log r), n 0]
        (if-let [ev (first evs)]
          (let [state' (e/apply-event state ev)]
            (is (schema/valid-state? state')
                (str "state invalid after event " n " (" (:type ev) "): "
                     (pr-str (schema/explain-state state'))))
            (recur state' (rest evs) (inc n)))
          (is (pos? n) "the game produced events"))))))

(deftest event-log-folds-back-to-final-state
  (testing "the event-sourcing contract the SQLite log will depend on"
    (doseq [seed [7 1234 99999]]
      (let [r (h/play-game 11 seed)]
        (is (:ok? r) (str "game failed: " (pr-str (:reason r))))
        (is (nil? (h/replay-mismatch r))
            (str "replay diverged for seed " seed ": "
                 (pr-str (h/replay-mismatch r))))))))
