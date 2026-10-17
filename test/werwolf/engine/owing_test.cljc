(ns werwolf.engine.owing-test
  "Who the game is still waiting on — the Spielleiter's list."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [werwolf.engine.core :as engine]
            [werwolf.engine.phases :as phases]
            [werwolf.test-util :as tu]))

(def ^:private table
  {1 :werwolf 2 :seherin 3 :hexe 4 :dorfbewohner 5 :dorfbewohner})

(deftest at-night-only-the-role-being-called-is-owed
  (let [s (tu/narrated-game-with table)
        cursor (:night-cursor s)]
    (testing "whoever holds the called role, and nobody else"
      (is (seq (phases/owing s)))
      (is (every? #(= cursor (tu/role-of s %)) (phases/owing s))
          (str "cursor " cursor ", owing " (phases/owing s))))
    (testing "never the Spielleiter, whatever he is offered"
      (is (not (contains? (set (phases/owing s)) 0))))))

(deftest acting-takes-you-off-the-list
  (let [s (tu/narrated-game-with table)
        s (loop [s s, n 0]
            (if (or (= :seherin (:night-cursor s)) (> n 6))
              s
              (recur (tu/exec! s {:type :gm/skip-role :player 0 :role (:night-cursor s)})
                     (inc n))))]
    (is (= :seherin (:night-cursor s)))
    (is (= [2] (phases/owing s)))
    (let [s' (tu/exec! s {:type :seherin-inspect :player 2 :target 1})]
      (is (not (contains? (set (phases/owing s')) 2))))))

(deftest by-day-everybody-who-has-not-answered
  (testing "on a day with a ballot, everybody who has not voted"
    (let [s (-> (tu/narrated-game-with table)
                (assoc :phase :discussion :votes {} :night-cursor nil))]
      (is (= (engine/living-ids s) (phases/owing s)))
      (is (not (contains? (set (phases/owing (assoc-in s [:votes 4] nil))) 4))
          "an abstention is an answer")))
  (testing "on the first day, without one, everybody who is not done talking"
    (let [s (-> (tu/narrated-game-with table {:first-day-trial? false})
                (assoc :phase :discussion :ready #{} :night-cursor nil))]
      (is (= (engine/living-ids s) (phases/owing s)))
      (is (not (contains? (set (phases/owing (update s :ready conj 4))) 4))))))

(deftest nobody-is-owed-where-nothing-is-asked
  (is (empty? (phases/owing (tu/narrated-lobby 5))))
  (is (empty? (phases/owing (tu/narrated-setup-with table)))))
