(ns werwolf.engine.kick-test
  "The host removing somebody from the lobby.

   Small on purpose: one command, offered to one seat in one phase. What makes it
   worth pinning is what it must NOT do — reach past the lobby into a dealt game,
   remove the host himself, or free up a seat number that a reconnect token
   still names."
  (:require [clojure.test :refer [deftest is testing]]
            [werwolf.engine.core :as engine]
            [werwolf.engine.events :as e]
            [werwolf.engine.phases :as phases]
            [werwolf.engine.schema :as schema]
            [werwolf.i18n.core :as i18n]
            [werwolf.test-util :as tu :refer [exec! try-exec]]))

(defn- lobby
  "Seats 0..n-1, host 0, the host playing."
  [n]
  (e/replay (into [{:type :game/created :room-id "TEST" :host 0 :seed 1
                    :min-players 5}]
                  (map (fn [i] {:type :player/joined :player i
                                :name (str "P" i) :locale :de}))
                  (range n))))

(deftest the-host-is-offered-every-other-seat-in-the-lobby
  (let [s (lobby 4)]
    (is (contains? (phases/legal-actions s 0) :gm/kick))
    (is (= [1 2 3] (phases/legal-targets s 0 :gm/kick)) "never himself")
    (testing "and nobody else is offered it"
      (doseq [pid [1 2 3]]
        (is (not (contains? (phases/legal-actions s pid) :gm/kick)))))
    (testing "an absent seat may be removed — it is the commonest one to"
      (let [s (e/apply-event s {:type :player/connection :player 2 :connected? false :at 1})]
        (is (= [1 2 3] (phases/legal-targets s 0 :gm/kick)))))))

(deftest removing-a-seat-takes-it-away-and-says-so
  (let [s (exec! (lobby 4) {:type :gm/kick :player 0 :target 2})]
    (is (not (contains? (:players s) 2)))
    (is (= [0 1 3] (sort (keys (:players s)))))
    (is (= {:type :player/kicked :player 2 :name "P2"} (peek (:log s)))
        "public, and by name: the seat is gone by the time anyone reads it")
    (is (schema/valid-state? s) (pr-str (schema/explain-state s)))
    (testing "the seat number stays issued, so it is never handed out again"
      (is (some #(and (= :player/joined (:type %)) (= 2 (:player %))) (:log s))))
    (testing "a replay of the same events lands in the same place"
      (let [r (engine/execute (lobby 4) {:type :gm/kick :player 0 :target 2})]
        (is (= s (e/apply-all (lobby 4) (:events r))))))
    (testing "the Chronik names the player, in both languages"
      (is (= "P2 wurde aus der Runde entfernt"
             (i18n/log-line :de (:players s) (peek (:log s)))))
      (is (= "P2 was removed from the game"
             (i18n/log-line :en (:players s) (peek (:log s)))))
      (is (= :gm (i18n/log-tone (peek (:log s))))
          "loud: the host decided it, unlike somebody leaving"))))

(deftest what-is-refused
  (let [s (lobby 4)]
    (testing "himself"
      (is (= :invalid-target
             (:error (second (try-exec s {:type :gm/kick :player 0 :target 0}))))))
    (testing "a seat that is not there"
      (is (= :invalid-target
             (:error (second (try-exec s {:type :gm/kick :player 0 :target 9}))))))
    (testing "anybody but the host"
      (is (= :not-allowed
             (:error (second (try-exec s {:type :gm/kick :player 1 :target 2}))))))
    (testing "once the cards are dealt — removing a seat would remove a card"
      (let [g (exec! (lobby 5) {:type :start-game :player 0})]
        (is (not= :lobby (:phase g)))
        (is (not (contains? (phases/legal-actions g 0) :gm/kick)))
        (is (= :not-allowed
               (:error (second (try-exec g {:type :gm/kick :player 0 :target 1})))))))))

(deftest a-narrated-room-the-same-and-never-in-setup
  (let [s (tu/narrated-lobby 4)]
    (is (= [1 2 3 4] (phases/legal-targets s 0 :gm/kick))
        "the Spielleiter is the host, so he is the one seat never offered")
    (let [s (exec! s {:type :gm/kick :player 0 :target 3})]
      (is (= [0 1 2 4] (sort (keys (:players s)))))
      (is (= 0 (e/narrator s))))
    (let [setup (tu/narrated-setup-with {1 :werwolf 2 :seherin 3 :hexe 4 :dorfbewohner
                                         5 :dorfbewohner})]
      (is (= :setup (:phase setup)))
      (is (not (contains? (phases/legal-actions setup 0) :gm/kick))))))

(deftest the-heir-of-an-absent-host-removes-the-old-one
  (testing "the case that asked for this: a host who went missing, somebody
            took the seat, and the old host would otherwise be dealt in"
    (let [s (-> (lobby 4)
                (e/apply-event {:type :player/connection :player 0 :connected? false :at 1})
                (e/apply-event {:type :host/absent}))
          s (exec! s {:type :claim-host :player 1})]
      (is (= 1 (:host s)))
      (is (= [0 2 3] (phases/legal-targets s 1 :gm/kick)))
      (let [s (exec! s {:type :gm/kick :player 1 :target 0})]
        (is (= [1 2 3] (sort (keys (:players s)))))
        (is (= 1 (:host s)))))))

(deftest it-is-a-gm-power-not-a-turn
  (testing "like the hand-over, a GM action: it must never make the host look
            busy, and it names a seat, so it is not a targetless action"
    (is (not (contains? phases/no-target-actions :gm/kick)))
    (is (contains? (phases/host-actions (lobby 4) 0) :gm/kick))
    (is (empty? (phases/host-actions (lobby 4) 1)))))

(deftest an-orphaned-event-is-a-no-op
  (testing "apply-event is total: a seat already gone stays gone, quietly"
    (let [s (lobby 3)]
      (is (= s (e/apply-event s {:type :player/kicked :player 7}))))))
