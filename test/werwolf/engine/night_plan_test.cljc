(ns werwolf.engine.night-plan-test
  "The night's list of roles a sleeping table is shown: where the night is and
   what is left, from public facts only."
  (:require [clojure.test :refer [deftest is testing]]
            [werwolf.engine.core :as engine]
            [werwolf.engine.view :as view]
            [werwolf.test-util :as tu :refer [exec!]]))

(defn- plan [s] (mapv (juxt :role :state) (view/night-plan s)))

(def ^:private seven
  "The automatic deck for seven: two wolves, Seherin, Hexe, Jäger, Amor, a
   villager."
  {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :amor 5 :jaeger 6 :werwolf})

(deftest the-night-is-listed-in-calling-order
  (let [s (tu/game-with seven)]
    (is (= [[:amor :current] [:seherin :todo] [:werwolf :todo] [:hexe :todo]] (plan s)))
    (let [s (-> s
                (exec! {:type :amor-link :player 4 :a 0 :b 5})
                (exec! {:type :seherin-inspect :player 3 :target 1}))]
      (is (= [[:amor :done] [:seherin :done] [:werwolf :current] [:hexe :todo]] (plan s))))
    (testing "and everybody sees the same list: it names roles, never players"
      (is (= (:night-plan (engine/view-for s 0)) (:night-plan (engine/view-for s 1))))
      (doseq [pid (range 7)] (is (nil? (engine/redaction-leaks? s pid)) (str pid))))))

(deftest a-peaceful-first-night-lists-who-wakes
  (is (= [[:amor :current] [:seherin :todo]]
         (plan (tu/game-with seven {:first-night-kill? false})))))

(deftest a-dead-role-is-not-called-and-not-listed
  (testing "a death turns the card over, so its absence from the night is public"
    (let [s (-> (tu/game-with seven)
                (exec! {:type :amor-link :player 4 :a 0 :b 5})
                (exec! {:type :seherin-inspect :player 3 :target 1})
                (exec! {:type :werewolf-vote :player 1 :target 3})
                (exec! {:type :werewolf-vote :player 6 :target 3})
                (exec! {:type :hexe-pass :player 2}))
          s (reduce #(exec! %1 {:type :vote-abstain :player %2}) s (tu/alive s))]
      (is (= 2 (:night s)))
      (is (= [[:werwolf :current] [:hexe :todo]] (plan s))
          "no Seherin, who died, and no Amor, who wakes on the first night only"))))

(deftest a-role-the-table-was-not-told-about-joins-when-called
  (testing "the Spielleiter dealt a Beschützer the announced deck does not hold:
            nobody learns it from the list before he is called"
    (let [s (-> (tu/narrated-setup-with {1 :werwolf 2 :beschuetzer 3 :seherin
                                         4 :dorfbewohner 5 :dorfbewohner})
                (exec! {:type :begin-night :player 0}))]
      (is (not (some #{:beschuetzer} (map first (plan s)))))
      (let [s (exec! s {:type :seherin-inspect :player 3 :target 1})]
        (is (= [:beschuetzer :current] (first (filter #(= :beschuetzer (first %)) (plan s)))))))))

(deftest only-at-night
  (is (nil? (view/night-plan (tu/narrated-lobby 5)))))
