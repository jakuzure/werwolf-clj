(ns werwolf.engine.night-plan-test
  "The night's list of roles a sleeping table is shown: which roles the night
   calls, from public facts only, and for whom it also says where the night is."
  (:require [clojure.test :refer [deftest is testing]]
            [werwolf.engine.core :as engine]
            [werwolf.engine.view :as view]
            [werwolf.test-util :as tu :refer [exec!]]))

(defn- plan [s pid] (mapv (juxt :role :state) (view/night-plan s pid)))

(def ^:private seven
  "The automatic deck for seven: two wolves, Seherin, Hexe, Jäger, Amor, a
   villager."
  {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :amor 5 :jaeger 6 :werwolf})

(def ^:private seven-narrated
  "The same table with the Spielleiter in seat 0."
  {1 :dorfbewohner 2 :werwolf 3 :hexe 4 :seherin 5 :amor 6 :jaeger 7 :werwolf})

(deftest the-night-is-listed-in-calling-order
  (let [s (tu/game-with seven)]
    (is (= [[:amor :current] [:seherin :todo] [:werwolf :todo] [:hexe :todo]] (plan s 0)))
    (let [s (-> s
                (exec! {:type :amor-link :player 4 :a 0 :b 5})
                (exec! {:type :seherin-inspect :player 3 :target 1}))]
      (is (= [[:amor :done] [:seherin :done] [:werwolf :current] [:hexe :todo]] (plan s 0))))
    (testing "and everybody sees the same list: it names roles, never players"
      (is (= (:night-plan (engine/view-for s 0)) (:night-plan (engine/view-for s 1))))
      (doseq [pid (range 7)] (is (nil? (engine/redaction-leaks? s pid)) (str pid))))))

(deftest without-a-spielleiter-the-whole-table-watches-the-night-move
  (testing "otherwise the night is a black box: nobody at the table is calling
            the roles out loud"
    (let [s (tu/game-with seven)]
      (doseq [pid (range 7)]
        (let [v (engine/view-for s pid)]
          (is (= :amor (:night-cursor v)) (str "seat " pid))
          (is (= [:current :todo :todo :todo] (mapv :state (:night-plan v))) (str "seat " pid)))))))

(deftest with-a-spielleiter-he-alone-watches-the-night-move
  (testing "the progress used to be public, as a narrator says the role out loud.
            With bots at the table it told everyone which seats were human: a
            bot answers a second and a half after its role is called, a person
            takes longer, and the strip showed how long each role stayed lit"
    (let [s (tu/narrated-game-with seven-narrated)
          roster [{:role :amor} {:role :seherin} {:role :werwolf} {:role :hexe}]
          others (fn [s] (cons nil (remove #{0} (keys (:players s)))))]
      (is (= [[:amor :current] [:seherin :todo] [:werwolf :todo] [:hexe :todo]] (plan s 0)))
      (is (= :amor (:night-cursor (engine/view-for s 0))))
      (testing "a player gets the roles in calling order and nothing more:
                no state on any of them, and no cursor, not even a nil one"
        (doseq [pid (others s)]
          (let [v (engine/view-for s pid)]
            (is (= roster (:night-plan v)) (str "seat " pid))
            (is (not (contains? v :night-cursor)) (str "seat " pid)))))
      (testing "and the list does not change as the night moves on"
        ;; Each role is called by the Spielleiter in a room without a clock
        ;; (phases/paced?): `tu/advance!` is his call.
        (let [s (-> s
                    (exec! {:type :amor-link :player 5 :a 1 :b 6})
                    tu/advance!
                    (exec! {:type :seherin-inspect :player 4 :target 2})
                    tu/advance!)]
          (is (= [[:amor :done] [:seherin :done] [:werwolf :current] [:hexe :todo]] (plan s 0)))
          (doseq [pid (others s)]
            (let [v (engine/view-for s pid)]
              (is (= roster (:night-plan v)) (str "seat " pid))
              (is (not (contains? v :night-cursor)) (str "seat " pid))))
          (testing "the role being called still knows it is, from its moves"
            (is (contains? (:actions (engine/view-for s 2)) :werewolf-vote)))))
      (testing "and the dead are shown no more than the living"
        (let [s (exec! s {:type :gm/force-kill :player 0 :target 1})
              v (engine/view-for s 1)]
          (is (= :night (:phase s)))
          (is (false? (get-in v [:me :alive?])))
          (is (= roster (:night-plan v)))
          (is (not (contains? v :night-cursor)))))
      (testing "outside the night the key is absent too, so a key that is there is a fact"
        (let [s (tu/narrated-setup-with seven-narrated)]
          (is (= :setup (:phase s)))
          (is (contains? (engine/view-for s 0) :night-cursor))
          (is (not (contains? (engine/view-for s 3) :night-cursor))))))))

(deftest a-peaceful-first-night-lists-who-wakes
  (is (= [[:amor :current] [:seherin :todo]]
         (plan (tu/game-with seven {:first-night-kill? false}) 0))))

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
      (is (= [[:werwolf :current] [:hexe :todo]] (plan s 0))
          "no Seherin, who died, and no Amor, who wakes on the first night only"))))

(deftest a-role-the-table-was-not-told-about-joins-when-called
  (testing "the Spielleiter dealt a Beschützer the announced deck does not hold:
            nobody learns it from the list before he is called"
    (let [s (-> (tu/narrated-setup-with {1 :werwolf 2 :beschuetzer 3 :seherin
                                         4 :dorfbewohner 5 :dorfbewohner})
                (exec! {:type :begin-night :player 0})
                ;; The Spielleiter calls the Seherin; the room has no clock.
                tu/advance!)
          listed? (fn [s pid] (boolean (some #{:beschuetzer} (map :role (view/night-plan s pid)))))]
      (is (not (listed? s 0)))
      (is (not (listed? s 3)))
      (let [s (-> s
                  (exec! {:type :seherin-inspect :player 3 :target 1})
                  ;; And calls him next; his button already names him.
                  tu/advance!)]
        (is (= [:beschuetzer :current] (first (filter #(= :beschuetzer (first %)) (plan s 0))))
            "on the Spielleiter's list, who is calling him")
        (testing "and never on the players' list: joining it would say when he was called"
          (is (not (listed? s 3)))
          (is (not (listed? s 2)) "not even his own holder's, who knows from his moves"))))))

(deftest only-at-night
  (is (nil? (view/night-plan (tu/narrated-lobby 5) 0)))
  (is (nil? (view/night-plan (tu/narrated-lobby 5) 3))))
