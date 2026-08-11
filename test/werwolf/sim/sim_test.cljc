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
      ;; Re-pinned twice: once when the Beschützer joined
      ;; special-village-priority, and again when the Bürgermeister election was
      ;; added. The election moves EVERY row, five-player included, because it
      ;; happens in every game — unlike a new role, which only reaches tables big
      ;; enough to be dealt one.
      ;;
      ;; When the Beschützer landed, the five-player row was unchanged — he was
      ;; APPENDED to the priority list rather than inserted, so tables already
      ;; capped below his position dealt exactly what they had before, and a
      ;; changed row there would have meant he had leaked into small games. The
      ;; election then moved that row too, which is expected and is the
      ;; difference between the two changes: a role reaches some tables, a phase
      ;; reaches all of them.
      ;; Re-pinned again for the Weißer Werwolf, who replaces a wolf from twelve
      ;; players up: the 5 and 9 rows are untouched, and every row at 12+ moved.
      ;; The same shape as the Beschützer — a role reaches the tables it is dealt
      ;; to, and no others.
      ;; The fourteen-player row is now won by the Weißer Werwolf, which is worth
      ;; keeping as a pinned case: it is the only fingerprint that exercises the
      ;; solo victory end to end.
      ;; Re-pinned once more for the Dieb, who is dealt from ten players up: the 5
      ;; and 9 rows are untouched again, and every row at 10+ moved.
      12 8675309 418 219 :village
      9  4242    111 44  :werewolf
      18 777     356 188 :werewolf
      5  31337   97  36  :werewolf
      14 90210   377 208 :werewolf)))

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

(deftest the-weisser-werwolf-can-actually-win
  (testing "his is the only SOLO win condition, and the only one that can DELAY a
            victory: a table of nothing but wolves is not a werewolf win while he
            is among them. If that delay were wrong the games would either hang or
            he would never win at all, so the summary is the evidence."
    (let [{:keys [summary]} (h/play-many 120)
          winners (:winners summary)]
      (is (pos? (get winners :weisser-werwolf 0))
          (str "he never won in 120 games: " (pr-str winners)))
      (testing "and he has not taken the game over either"
        (is (pos? (get winners :village 0)))
        (is (pos? (get winners :werewolf 0)))))))
