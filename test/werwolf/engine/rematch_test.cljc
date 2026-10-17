(ns werwolf.engine.rematch-test
  "Playing again with the same table: the host's one tap at :game-over that
   deals the room a new game without everybody leaving and joining again."
  (:require [clojure.test :refer [deftest is testing]]
            [werwolf.engine.core :as engine]
            [werwolf.engine.events :as e]
            [werwolf.engine.phases :as phases]
            [werwolf.engine.recap :as recap]
            [werwolf.engine.schema :as schema]
            [werwolf.test-util :as tu :refer [exec!]]))

(def ^:private genesis
  "A narrated lobby: the Spielleiter in seat 0, five players in seats 1..5."
  (into [{:type :game/created :room-id "TEST" :host 0 :seed 1 :min-players 5
          :first-night-kill? true :narrated? true :timer? false}]
        (map (fn [i] {:type :player/joined :player i :name (str "P" i) :locale :de}))
        (range 6)))

(defn- run
  "Commands on top of [state events], keeping every event, as the room's log
   does. Throws on a refusal."
  [[s evs] & cmds]
  (reduce (fn [[s evs] cmd]
            (let [r (engine/execute s cmd)]
              (when (engine/error? r)
                (throw (ex-info "command rejected" {:cmd cmd :error r})))
              [(:state r) (into evs (:events r))]))
          [s evs]
          cmds))

(defn- wolves [s]
  (sort (keep (fn [[pid p]] (when (= :werwolf (:role p)) pid)) (:players s))))

(defn- end-it
  "The Spielleiter removes every wolf, and the village has won."
  [[s _ :as game]]
  (apply run game (map (fn [w] {:type :gm/force-kill :player 0 :target w}) (wolves s))))

(defn- finished-game []
  (end-it (run [(e/replay genesis) genesis]
               {:type :start-game :player 0}
               {:type :begin-night :player 0})))

(defn- finished [] (first (finished-game)))

(deftest only-the-host-may-deal-again-and-only-at-the-end
  (let [s (finished)]
    (is (= :game-over (:phase s)))
    (is (contains? (phases/legal-actions s 0) :play-again))
    (is (not (contains? (phases/legal-actions s 1) :play-again)))
    (is (= :not-allowed (:error (engine/execute s {:type :play-again :player 1}))))
    (testing "not while a game is under way"
      (let [running (-> (tu/narrated-lobby 5) (exec! {:type :start-game :player 0}))]
        (is (not (contains? (phases/legal-actions running 0) :play-again)))))))

(deftest the-same-table-is-dealt-a-new-game
  (let [over (assoc (finished) :password-hash "h" :timer? true)
        s    (exec! over {:type :play-again :player 0})]
    (testing "a narrated room goes back to the Spielleiter's setup step"
      (is (= :setup (:phase s))))
    (testing "everybody is still seated, alive and dealt a card"
      (is (= (set (keys (:players over))) (set (keys (:players s)))))
      (is (every? :alive? (vals (:players s))))
      (is (nil? (get-in s [:players 0 :role])) "the narrator still holds none")
      (is (every? #(get-in s [:players % :role]) (range 1 6))))
    (testing "the room's settings survive, the last game does not"
      (is (= 0 (:host s)))
      (is (:narrated? s))
      (is (:timer? s))
      (is (= "h" (:password-hash s)))
      (is (nil? (:winner s)))
      (is (nil? (:mayor s)))
      (is (nil? (:lovers s)))
      (is (= [:game/reset :game/started] (mapv :type (:log s)))
          "the Chronik starts over with the new game"))
    (testing "a new deal, not the old one: the seed has moved on"
      (is (not= (:seed over) (:seed s))))
    (is (schema/valid-state? s) (pr-str (schema/explain-state s)))))

(deftest a-table-that-no-longer-makes-a-game-waits-in-the-lobby
  (testing "the deck no longer fits, so the host is shown why in the lobby
            rather than handed a deal that cannot be played"
    (let [over (assoc (finished) :deck {:werwolf 1 :hexe 1 :seherin 1 :jaeger 1 :amor 1 :dieb 1})
          s    (exec! over {:type :play-again :player 0})]
      (is (= :lobby (:phase s)))
      (is (some? (phases/deck-problem s))))))

(deftest the-recap-tells-only-the-last-game
  (let [[again _ :as game] (run (finished-game)
                                {:type :play-again :player 0}
                                {:type :begin-night :player 0})
        [done log] (end-it game)
        rc (recap/recap log)]
    (is (= :game-over (:phase done)))
    (is (= (wolves again) (map :id (filter #(= :werwolf (:role %)) (:players rc))))
        "the cast is the second deal's")
    (is (= 1 (count (filter #(= :night (:kind %)) (:rounds rc))))
        "one night: the first game's is not retold")))

(deftest replay-arrives-where-the-rematch-did
  (let [[s log] (run (finished-game) {:type :play-again :player 0})]
    (is (= s (e/replay log)))))
