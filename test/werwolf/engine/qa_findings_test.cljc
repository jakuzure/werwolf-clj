(ns werwolf.engine.qa-findings-test
  "What a QA pass of a full game with every role found wrong in the engine and
   the views it builds, one test each, so none of it comes back unnoticed."
  (:require [clojure.test :refer [deftest is testing]]
            [werwolf.engine.events]
            [werwolf.engine.phases :as phases]
            [werwolf.engine.view :as view]
            [werwolf.test-util :as tu :refer [exec! try-exec alive]]))

(defn- second-night
  "Wolf 1, white wolf 2, villagers 0 and 3: night one eats 0, nobody is
   condemned, and it is night two — the white wolf's first turn."
  []
  (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :weisser-werwolf 3 :dorfbewohner 4 :dorfbewohner})
        s (exec! s {:type :werewolf-vote :player 1 :target 0})
        s (exec! s {:type :werewolf-vote :player 2 :target 0})
        s (tu/advance-to-vote s)]
    (reduce (fn [st pid] (exec! st {:type :vote-abstain :player pid})) s (alive s))))

(deftest the-white-wolf-sees-the-pack-vote-he-is-part-of
  (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :weisser-werwolf 3 :dorfbewohner})
        s (exec! s {:type :werewolf-vote :player 1 :target 0})]
    (is (= {1 0} (get-in (view/view-for s 2) [:night-info :werewolf-votes]))
        "he votes with them, so he sees where the votes stand")
    (is (nil? (get-in (view/view-for s 0) [:night-info :werewolf-votes]))
        "and a villager still does not")))

(deftest the-white-wolf-cannot-talk-in-his-own-turn
  (let [s (second-night)
        s (exec! s {:type :werewolf-vote :player 1 :target 3})
        s (exec! s {:type :werewolf-vote :player 2 :target 3})]
    (is (= :weisser-werwolf (:night-cursor s)) "his turn")
    (is (not (contains? (phases/legal-actions s 2) :chat))
        "anything said now would go to the pack while the night strip names him")
    (is (contains? (phases/legal-actions s 2) :weisser-kill))))

(deftest the-hexe-is-told-whom-she-saved
  (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :jaeger})
        s (exec! s {:type :seherin-inspect :player 3 :target 1})
        s (exec! s {:type :werewolf-vote :player 1 :target 0})
        _ (is (nil? (get-in (view/view-for s 2) [:night-info :healed])))
        s (exec! s {:type :hexe-heal :player 2 :target 0})
        info (:night-info (view/view-for s 2))]
    (is (= 0 (:healed info)) "her own act, said back to her")
    (is (empty? (:pending-deaths info)) "the victim is off the list")
    (is (nil? (get-in (view/view-for s 3) [:night-info :healed])) "and nobody else learns it")))

(deftest walking-out-of-a-game-is-leaving-not-absence
  (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :jaeger})
        s (exec! s {:type :leave :player 4})]
    (is (true? (get-in s [:players 4 :left?])))
    (is (true? (get-in (view/view-for s 0) [:players 4 :left?])) "everyone can see it")
    (is (= :player/left (:type (last (filter #(#{:player/left :player/disconnected} (:type %))
                                             (:log s)))))
        "the Chronik says left, not disconnected")))

(deftest a-name-is-taken-once-per-table
  (let [s (tu/narrated-lobby 3)]
    (testing "case and spaces do not make a name different"
      (is (= :name-taken (:error (second (try-exec s {:type :join :player 9 :name " p1 " :locale :de}))))))
    (is (nil? (second (try-exec s {:type :join :player 9 :name "Pia" :locale :de}))))))

(deftest the-last-wolf-standing-is-not-called-to-pass
  ;; Night 1 the pack eats villager 0 and the village condemns the plain
  ;; wolf; on night 2 the Weißer Werwolf has nobody of his own left to take.
  (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :weisser-werwolf 3 :dorfbewohner 4 :dorfbewohner 5 :dorfbewohner})
        s (exec! s {:type :werewolf-vote :player 1 :target 0})
        s (exec! s {:type :werewolf-vote :player 2 :target 0})
        s (tu/advance-to-vote s)
        s (reduce (fn [st pid] (exec! st {:type :vote :player pid :target 1}))
                  s (remove #{1} (alive s)))
        s (exec! s {:type :vote-abstain :player 1})]
    (is (false? (get-in s [:players 1 :alive?])) "the plain wolf was condemned")
    (is (= 2 (:night s)))
    (let [s (exec! s {:type :werewolf-vote :player 2 :target 3})]
      (is (not= :weisser-werwolf (:night-cursor s))
          "his turn is over before it starts: there is nobody for him to take")
      (is (contains? (get-in s [:night-actions :completed]) :weisser-werwolf)))))

(deftest walking-out-leaves-the-game
  (testing "a player who leaves a game under way is out, card turned over"
    (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :jaeger})
          s (exec! s {:type :leave :player 4})]
      (is (false? (get-in s [:players 4 :alive?])))
      (is (some #(and (= :player/died (:type %)) (= 4 (:player %)) (= :left (:cause %))
                      (= :jaeger (:role %)))
                (:log s)))
      (is (empty? (:reactions s)) "a Jäger who walked out does not shoot")
      (is (not (contains? (set (alive s)) 4)) "and nobody waits on him or counts him")))
  (testing "a lover still follows"
    (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :amor 4 :jaeger 5 :dorfbewohner})
          s (exec! s {:type :amor-link :player 3 :a 0 :b 5})
          s (exec! s {:type :leave :player 0})]
      (is (false? (get-in s [:players 5 :alive?])))
      (is (some #(and (= :player/died (:type %)) (= 5 (:player %)) (= :liebe (:cause %)))
                (:log s)))))
  (testing "and is not dealt into the rematch"
    (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :jaeger})
          s (exec! s {:type :leave :player 4})
          s (werwolf.engine.events/apply-all s [{:type :game/reset}])]
      (is (not (contains? (:players s) 4)))
      (is (= #{0 1 2 3} (set (keys (:players s))))))))
