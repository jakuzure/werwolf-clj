(ns werwolf.engine.table-rules-test
  "The rule variants the Spielleiter switches in the lobby: wolves on the first
   night, a trial on the first day, the dead seeing every card, and the
   Bürgermeister's election. They were fixed per deployment before, so a table
   that wanted the other rule had to ask whoever ran the server."
  (:require [clojure.test :refer [deftest is testing]]
            [werwolf.engine.core :as engine]
            [werwolf.engine.events :as e]
            [werwolf.engine.phases :as phases]
            [werwolf.engine.roles :as roles]
            [werwolf.engine.schema :as schema]
            [werwolf.engine.view :as view]
            [werwolf.test-util :as tu :refer [exec! try-exec]]))

(def ^:private cast-of-5
  {1 :dorfbewohner 2 :werwolf 3 :hexe 4 :seherin 5 :dorfbewohner})

(defn- set-rule [s rule on?]
  (exec! s {:type :gm/set-rule :player 0 :rule rule :on? on?}))

(defn- dealt
  "A narrated lobby with `rules` switched by the Spielleiter, dealt `cast` and
   past its setup into night 1."
  [rules cast]
  (let [s (reduce (fn [s [r on?]] (set-rule s r on?))
                  (tu/narrated-lobby (count cast) {:first-night-kill? false
                                                   :first-day-trial? false})
                  rules)]
    (first
     (phases/settle
      (e/apply-all s [{:type :game/started :assignments cast
                       :centre (roles/centre-for (vals cast))}
                      {:type :phase/changed :phase :setup :deadline nil}
                      {:type :night/began :night 1}])))))

(defn- to-day
  "The Spielleiter presses on until the village is awake: a narrated room has
   no clock, so each call and the morning wait on him."
  [s]
  (->> (iterate tu/advance! s)
       (take 20)
       (some #(when (#{:election :discussion} (:phase %)) %))))

(deftest the-spielleiter-switches-a-rule-in-the-lobby
  (let [s (tu/narrated-lobby 5)
        _ (is (= {:first-night-kill? true :first-day-trial? true
                  :reveal-to-dead? false :mayor? true}
                 (:rules (view/view-for s 3)))
              "every seat reads the rules the table plays by")
        s (set-rule s :mayor? false)]
    (is (false? (:mayor? s)))
    (is (= {:type :rules/set :rule :mayor? :on? false} (peek (:log s))))
    (is (false? (get-in (view/view-for s 3) [:rules :mayor?])))
    (is (schema/valid-state? s) (pr-str (schema/explain-state s)))
    (testing "the same value again is no second decision"
      (is (= s (set-rule s :mayor? false))))
    (testing "a rule that does not exist is refused"
      (is (= :invalid-rule (:error (second (try-exec s {:type :gm/set-rule :player 0
                                                        :rule :hexe-heals-twice? :on? true})))))
      (is (= :invalid-rule (:error (second (try-exec s {:type :gm/set-rule :player 0
                                                        :rule :mayor? :on? "yes"}))))))
    (testing "only the host, and only in the lobby"
      (is (= :not-allowed (:error (second (try-exec s {:type :gm/set-rule :player 1
                                                       :rule :mayor? :on? true})))))
      (let [s (exec! s {:type :start-game :player 0})]
        (is (= :not-allowed (:error (second (try-exec s {:type :gm/set-rule :player 0
                                                         :rule :mayor? :on? true})))))))))

(deftest an-older-log-keeps-its-election
  (testing "absent is how every log before the switch spells \"elect one\""
    (let [s (engine/new-game {:room-id "R" :host 0 :seed 1})]
      (is (not (contains? s :mayor?)))
      (is (true? (phases/mayor? s))))))

(deftest without-a-mayor-the-first-day-goes-straight-to-the-talk
  (testing "with the election on, a quiet first night ends in it"
    (let [s (to-day (dealt {} cast-of-5))]
      (is (= :election (:phase s)))
      (is (true? (:election-day? (view/view-for s 1))))))
  (testing "and with it off, the day begins without one"
    (let [s (to-day (dealt {:mayor? false} cast-of-5))]
      (is (= :discussion (:phase s)))
      (is (nil? (:mayor s)))
      (is (false? (:election-day? (view/view-for s 1)))))))

(deftest the-dead-see-every-card-only-when-the-table-said-so
  (let [kill  (fn [s] (exec! s {:type :gm/force-kill :player 0 :target 1}))
        wolf? (fn [s pid] (= :werwolf (get-in (view/view-for s pid) [:known-roles 2])))]
    (testing "off, the dead villager learns nothing from dying"
      (let [s (kill (dealt {} cast-of-5))]
        (is (not (wolf? s 1)))))
    (testing "on, he sees the whole table, and the living still do not"
      (let [s (kill (dealt {:reveal-to-dead? true} cast-of-5))]
        (is (wolf? s 1))
        (is (not (wolf? s 5)))))))

(deftest playing-again-keeps-the-rules
  (let [s (-> (tu/narrated-lobby 5)
              (set-rule :first-night-kill? false)
              (set-rule :first-day-trial? false)
              (set-rule :reveal-to-dead? true)
              (set-rule :mayor? false)
              (e/apply-event {:type :game/reset}))]
    (is (= {:first-night-kill? false :first-day-trial? false
            :reveal-to-dead? true :mayor? false}
           (:rules (view/view-for s 0))))))

(deftest the-spielleiter-sets-the-clocks-in-the-lobby
  (let [s (e/apply-event (tu/narrated-lobby 5)
                         {:type :clock/duration-set :phase :discussion :secs 300})
        s (exec! s {:type :gm/set-duration :player 0 :phase :discussion :secs 180})]
    (is (= 180 (get-in s [:durations :discussion])))
    (is (= {:type :clock/duration-set :phase :discussion :secs 180} (peek (:log s))))
    (is (= 180 (get-in (view/view-for s 3) [:durations :discussion]))
        "everybody reads how long the table may talk")
    (is (schema/valid-state? s) (pr-str (schema/explain-state s)))
    (testing "the same length again is no second decision"
      (is (= s (exec! s {:type :gm/set-duration :player 0 :phase :discussion :secs 180}))))
    (testing "only the steps the lobby offers, and only for the two clocks"
      (doseq [cmd [{:phase :discussion :secs 200}
                   {:phase :night :secs 300}
                   {:phase :election :secs 60}]]
        (is (= :invalid-duration
               (:error (second (try-exec s (merge {:type :gm/set-duration :player 0} cmd)))))
            (pr-str cmd))))
    (testing "only the host, and only in the lobby"
      (is (= :not-allowed (:error (second (try-exec s {:type :gm/set-duration :player 1
                                                       :phase :night :secs 60})))))
      (let [s (exec! s {:type :start-game :player 0})]
        (is (= :not-allowed (:error (second (try-exec s {:type :gm/set-duration :player 0
                                                         :phase :night :secs 60})))))
        (is (nil? (:duration-steps (view/view-for s 0)))
            "the choices are the lobby's and go no further")))
    (testing "and the next round keeps them"
      (is (= 180 (get-in (e/apply-event s {:type :game/reset}) [:durations :discussion]))))))

(deftest a-room-opened-with-lengths-replays-them
  (let [s (e/apply-event nil {:type :game/created :room-id "R" :host 0 :seed 1
                              :durations {:discussion 300 :night 45}})]
    (is (= {:discussion 300 :night 45} (:durations s)))
    (testing "and a log from before them has none"
      (is (not (contains? (engine/new-game {:room-id "R" :host 0 :seed 1}) :durations))))))
