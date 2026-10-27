(ns werwolf.engine.stats-test
  "The table's record across the games of one room."
  (:require [clojure.test :refer [deftest is testing]]
            [werwolf.engine.core :as engine]
            [werwolf.engine.events :as e]
            [werwolf.engine.stats :as stats]))

(def ^:private genesis
  (into [{:type :game/created :room-id "STAT" :host 0 :seed 1 :min-players 3
          :first-night-kill? true :first-day-trial? true :narrated? true :timer? false}]
        (map (fn [i] {:type :player/joined :player i :name (str "P" i) :locale :de}))
        (range 5)))

(defn- run [[s evs] & cmds]
  (reduce (fn [[s evs] cmd]
            (let [r (engine/execute s cmd)]
              (when (engine/error? r) (throw (ex-info "rejected" {:cmd cmd :r r})))
              [(:state r) (into evs (:events r))]))
          [s evs] cmds))

(defn- wolves [s] (sort (keep (fn [[pid p]] (when (= :werwolf (:role p)) pid)) (:players s))))

(defn- village-wins [[s _ :as game]]
  (apply run game (map (fn [w] {:type :gm/force-kill :player 0 :target w}) (wolves s))))

(defn- dealt [game] (run game {:type :begin-night :player 0}))

(deftest nothing-before-a-game-has-ended
  (is (nil? (stats/stats genesis))))

(deftest every-game-the-table-played-counts
  (let [one   (village-wins (dealt (run [(e/replay genesis) genesis] {:type :start-game :player 0})))
        two   (village-wins (dealt (run one {:type :play-again :player 0})))
        [s log] two
        {:keys [games players]} (stats/stats log)]
    (is (= 2 games))
    (testing "every player played both, the narrator neither"
      (is (= #{1 2 3 4} (set (keys players))))
      (is (every? #(= 2 (:played %)) (vals players))))
    (testing "the village won twice, so did everybody but whoever was a wolf"
      (doseq [[pid {:keys [won roles]}] players]
        (is (= (- 2 (get roles :werwolf 0)) won) (str pid))))
    (testing "two cards per player, counted"
      (is (every? #(= 2 (reduce + (vals (:roles %)))) (vals players))))
    (is (= :game-over (:phase s)))))

(deftest the-pack-never-wins-for-the-weisser-werwolf
  ;; Dealt to the pack, so their team is his on paper. But he plays for
  ;; himself, and once the pack has won it is because he did not — see
  ;; phases/winner. Only the deal and the end matter to the count.
  (let [log (conj genesis
                  {:type :game/started :assignments {1 :werwolf 2 :weisser-werwolf 3 :werwolf 4 :dorfbewohner}}
                  {:type :game/ended :winner :werewolf})
        won (into {} (map (fn [[pid p]] [pid (:won p)])) (:players (stats/stats log)))]
    (is (= {1 1 2 0 3 1 4 0} won))))
