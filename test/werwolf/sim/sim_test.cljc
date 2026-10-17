(ns werwolf.sim.sim-test
  "The breadth test: play complete games at every table size and assert the
   engine's invariants after every single event.

   This is the highest-value test in the suite. It found three real bugs during
   development that no example-based test would have: the exhausted Seherin
   deadlocking the night, `legal-actions` offering targetless actions, and the
   dead host freezing the game."
  (:require [clojure.test :refer [are deftest is testing]]
            [werwolf.engine.events :as e]
            [werwolf.engine.roles :as roles]
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
      ;; Re-pinned once more for the Dieb, who is dealt from ten players up: the 5
      ;; and 9 rows are untouched again, and every row at 10+ moved.
      ;; And for the first day without a trial, which moves every row, like the
      ;; election did: it is a phase, so it reaches every table. The twelve-
      ;; player row is now the one the Weißer Werwolf wins, which keeps a pinned
      ;; case exercising the solo victory end to end.
      12 8675309 194 88  :weisser-werwolf
      9  4242    173 65  :werewolf
      18 777     568 358 :village
      5  31337   78  25  :werewolf
      14 90210   313 143 :village)))

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

;; ------------------------------------------------------------ narrated ---

(deftest narrated-games-complete-without-a-clock
  (testing "a Spielleiter and no clock is the configuration most likely to
            stall: every phase the clock used to end now waits for a human. The
            harness only ever does what `legal-actions` offers, so a phase with
            no way out shows up here as a deadlock — and every narrator
            invariant (no card, no move, never a target) is checked after every
            event, on top of the usual ones"
    (let [{:keys [failures summary]} (h/play-many 60 {:narrated? true})]
      (is (empty? failures) (str "failures: " (describe failures)))
      (is (= 60 (:games summary)))
      (is (> (:avg-turns summary) 10) "games are being played, not aborted")
      (testing "and both sides still win — the narrator moving things along
                must not hand the game to either of them"
        (is (pos? (get-in summary [:winners :village] 0)))
        (is (pos? (get-in summary [:winners :werewolf] 0)))))))

(deftest a-narrated-game-replays-and-keeps-its-schema
  (let [r (h/play-game 10 4711 {:narrated? true})]
    (is (:ok? r) (str "game failed: " (pr-str (select-keys r [:reason :phase :violations]))))
    (is (nil? (h/replay-mismatch r)))
    (is (some #{:begin-night} (map :type (:history r)))
        "the game passed through the Spielleiter's setup step")
    (loop [state nil, evs (:log r)]
      (when-let [ev (first evs)]
        (let [state' (e/apply-event state ev)]
          (is (schema/valid-state? state')
              (str "invalid after " (:type ev) ": " (pr-str (schema/explain-state state'))))
          (recur state' (rest evs)))))))

;; ------------------------------------------------------------ absent host ---

(deftest games-whose-host-went-missing-still-complete
  (testing "the seat is offered to everybody while he is gone, at every step of
            a game he played and in every lobby. Offered that widely, it must
            never hold up a night turn, never reach a narrated game under way,
            and never stop a game from replaying"
    (doseq [opts [{:host-absent? true} {:host-absent? true :narrated? true}]]
      (let [results (for [i (range 30)]
                      (h/play-game (+ 5 (mod i 14)) (+ 7 (* i 104729)) opts))
            failures (vec (remove :ok? results))]
        (is (empty? failures) (str opts " failures: " (describe failures)))
        (is (some (fn [r] (some #(= :claim-host (:type %)) (:history r))) results)
            (str opts ": somebody took the seat in at least one game"))
        (doseq [r (take 5 results)]
          (is (nil? (h/replay-mismatch r)) (str opts " seed " (:seed r))))))))

;; ----------------------------------------------------- the chosen deck ---

(deftest games-with-a-chosen-deck-complete
  (testing "the automatic composition is a handful of decks the rules were
            written against. A Spielleiter's choice is any deck that fits — a
            Dieb at five, a single wolf among fifteen, no Seherin — and the
            harness plays those through every invariant, narrated and not.
            A deck the engine let start and then could not finish shows up here
            as a deadlock"
    (doseq [opts [{:random-deck? true}
                  {:random-deck? true :narrated? true}]]
      (let [{:keys [failures summary]} (h/play-many 60 opts)]
        (is (empty? failures) (str (pr-str opts) " failures: " (describe failures)))
        (is (= 60 (:games summary)))
        (is (> (:avg-turns summary) 10) "games are being played, not aborted")))))

(deftest the-random-decks-are-varied-and-valid
  (let [decks (for [i (range 200)
                    :let [n (+ 3 (mod i 16))]]
                [n (h/random-deck n (+ 17 (* i 101)))])]
    (testing "every deck the harness plays is one the start rule allows"
      (doseq [[n deck] decks]
        (is (nil? (roles/deck-problem deck n)) (pr-str n deck))
        (is (nil? (roles/deck-errors deck 18)) (pr-str deck))))
    (testing "and between them they name every special, so no role goes untested"
      (is (= (set roles/special-ids) (into #{} (mapcat keys) (map second decks)))))))

(deftest a-chosen-deck-replays-and-keeps-its-schema
  (let [r (h/play-game 9 2024 {:random-deck? true})]
    (is (:ok? r) (str "game failed: " (pr-str (select-keys r [:reason :phase :violations]))))
    (is (nil? (h/replay-mismatch r)))
    (is (some #{:deck/set} (map :type (:log r))) "the deck is in the log")
    (loop [state nil, evs (:log r)]
      (when-let [ev (first evs)]
        (let [state' (e/apply-event state ev)]
          (is (schema/valid-state? state')
              (str "invalid after " (:type ev) ": " (pr-str (schema/explain-state state'))))
          (recur state' (rest evs)))))))
