(ns werwolf.engine.bot-test
  "Seats the server plays.

   A bot is an ordinary seat with nobody behind it: dealt a card, counted for
   the minimum, removable with the kick, kept across a rematch. What the tests
   below pin is the one fact everything else leans on — a bot is never
   connected, because it has no socket, and the state must say so rather than
   pretend — and that seat numbers are issued by the engine from the log, so a
   kicked seat's number (and its reconnect token) is never handed to a bot."
  (:require [clojure.test :refer [deftest is testing]]
            [werwolf.engine.core :as engine]
            [werwolf.engine.events :as e]
            [werwolf.engine.schema :as schema]
            [werwolf.engine.view :as view]
            [werwolf.test-util :as tu :refer [exec! try-exec]]))

(defn- lobby
  "Seats 0..n-1, host 0, the host playing."
  [n]
  (e/replay (into [{:type :game/created :room-id "TEST" :host 0 :seed 1 :min-players 5}]
                  (map (fn [i] {:type :player/joined :player i :name (str "P" i) :locale :de}))
                  (range n))))

(deftest a-bot-seat-is-a-player-with-no-socket
  (let [s (e/apply-event (lobby 2) {:type :player/joined :player 2 :name "Agnes (Bot)" :locale :de :bot? true})]
    (is (true? (get-in s [:players 2 :bot?])))
    (is (false? (get-in s [:players 2 :connected?])) "no socket, so not connected, and the state says so")
    (is (schema/valid-state? s) (pr-str (schema/explain-state s)))
    (testing "a human joined the old way is unchanged"
      (is (nil? (get-in s [:players 1 :bot?])))
      (is (true? (get-in s [:players 1 :connected?]))))
    (testing "everyone may know who is a bot, and still no role"
      (let [p (get-in (view/view-for s 0) [:players 2])]
        (is (true? (:bot? p)))
        (is (not (contains? p :role)))))
    (testing "a rematch keeps the bot a bot"
      (let [s' (e/apply-event (assoc s :phase :game-over) {:type :game/reset})]
        (is (true? (get-in s' [:players 2 :bot?])))
        (is (false? (get-in s' [:players 2 :connected?])))))))

(deftest seats-are-issued-from-the-log-never-recycled
  (let [s (lobby 3)]
    (is (= 3 (e/next-seat s)))
    (is (= 0 (e/next-seat (e/replay [{:type :game/created :room-id "T" :host 0 :seed 1 :min-players 5}]))))
    (testing "a kicked seat's number stays issued"
      (is (= 3 (e/next-seat (exec! s {:type :gm/kick :player 0 :target 2})))))))

(deftest nobody-here-means-no-connected-seat
  (let [s (lobby 2)]
    (is (false? (e/nobody-here? s)))
    (is (true? (e/nobody-here? (-> s (assoc-in [:players 0 :connected?] false)
                                     (assoc-in [:players 1 :connected?] false)))))
    (is (true? (e/nobody-here? (e/apply-event (-> s (assoc-in [:players 0 :connected?] false)
                                                   (assoc-in [:players 1 :connected?] false))
                                               {:type :player/joined :player 2 :name "Agnes (Bot)" :locale :de :bot? true})))
        "a table of bots is an empty table")))
