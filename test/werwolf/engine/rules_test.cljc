(ns werwolf.engine.rules-test
  "Targeted tests for the interactions that are actually hard.

   The simulation harness covers breadth; these cover the specific rule
   collisions that a random walk hits rarely or reports unhelpfully."
  (:require [clojure.test :refer [deftest is testing]]
            [werwolf.engine.core :as engine]
            [werwolf.engine.events :as e]
            [werwolf.engine.phases :as phases]
            [werwolf.test-util :as tu :refer [exec! try-exec alive]]))

;; ------------------------------------------------------- night ordering ---

(deftest werewolf-victim-survives-until-dawn
  (testing "the wolves' victim must still be alive while the Hexe decides —
            otherwise the heal is meaningless"
    (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :jaeger})
          ;; Seherin acts first in the queue after Amor (absent here).
          s (exec! s {:type :seherin-inspect :player 3 :target 1})
          s (exec! s {:type :werewolf-vote :player 1 :target 0})]
      (is (= :hexe (:night-cursor s))
          "the Hexe acts after the wolves, by design")
      (is (true? (get-in s [:players 0 :alive?]))
          "victim must NOT be dead yet")
      (is (= [{:player 0 :cause :werwolf}] (:pending-deaths s))
          "the kill is queued, not applied"))))

(deftest hexe-heal-saves-the-victim
  (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :jaeger})
        s (exec! s {:type :seherin-inspect :player 3 :target 1})
        s (exec! s {:type :werewolf-vote :player 1 :target 0})
        _ (is (= [0] (phases/legal-targets s 2 :hexe-heal))
              "she may only heal someone actually dying tonight")
        s (exec! s {:type :hexe-heal :player 2 :target 0})
        s (exec! s {:type :hexe-pass :player 2})]
    (is (true? (get-in s [:players 0 :alive?])) "healed player survives the night")
    (is (zero? (get-in s [:players 2 :resources :heiltrank])) "potion consumed")
    (is (= 0 (tu/count-log s :player/died)) "nobody died")))

(deftest hexe-poison-kills
  (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :jaeger})
        s (exec! s {:type :seherin-inspect :player 3 :target 1})
        s (exec! s {:type :werewolf-vote :player 1 :target 0})
        s (exec! s {:type :hexe-poison :player 2 :target 1})
        s (exec! s {:type :hexe-pass :player 2})]
    (is (false? (get-in s [:players 0 :alive?])) "wolf victim still dies")
    (is (false? (get-in s [:players 1 :alive?])) "poisoned wolf dies")
    (is (zero? (get-in s [:players 2 :resources :gifttrank])))
    (is (= :village (:winner s)) "last wolf poisoned -> village wins")))

(deftest hexe-turn-auto-completes-once-both-potions-are-gone
  (testing "using the second potion ends her turn without a :hexe-pass —
            she has nothing left to decide"
    ;; No Jäger in this cast, so the poisoning cannot trigger a reaction and
    ;; muddy what we are measuring.
    (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin
                           4 :dorfbewohner 5 :dorfbewohner})
          s (exec! s {:type :seherin-inspect :player 3 :target 1})
          s (exec! s {:type :werewolf-vote :player 1 :target 0})
          s (exec! s {:type :hexe-heal :player 2 :target 0})
          _ (is (= :hexe (:night-cursor s)) "still her turn — one potion left")
          s (exec! s {:type :hexe-poison :player 2 :target 5})]
      (is (= 0 (get-in s [:players 2 :resources :heiltrank])))
      (is (= 0 (get-in s [:players 2 :resources :gifttrank])))
      (is (not= :night (:phase s))
          "the night ended automatically once she was exhausted")
      (is (true? (get-in s [:players 0 :alive?])) "the healed player lived")
      (is (false? (get-in s [:players 5 :alive?])) "the poisoned player died"))))

(deftest exhausted-hexe-is-skipped-on-later-nights
  (testing "a potionless Hexe must never be waited on again"
    (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin
                           4 :dorfbewohner 5 :dorfbewohner})
          s (exec! s {:type :seherin-inspect :player 3 :target 1})
          s (exec! s {:type :werewolf-vote :player 1 :target 0})
          s (exec! s {:type :hexe-heal :player 2 :target 0})
          s (exec! s {:type :hexe-poison :player 2 :target 5})
          ;; Day 1: no lynch (everyone abstains), straight through to night 2.
          s (tu/advance-to-vote s)
          s (exec! s {:type :advance-phase :player 0})]
      (is (= 2 (:night s)) "we are on night two")
      (is (not= :hexe (:night-cursor s))
          "the exhausted Hexe is skipped rather than blocking the night"))))

;; ------------------------------------------------------ reaction stack ---

(deftest jaeger-shoots-while-dead
  (testing "the Jäger's whole point is firing after he dies"
    (let [s (tu/game-with {0 :jaeger 1 :werwolf 2 :dorfbewohner 3 :dorfbewohner 4 :dorfbewohner})
          s (exec! s {:type :werewolf-vote :player 1 :target 0})]
      (is (false? (get-in s [:players 0 :alive?])) "the Jäger is dead")
      (is (= :reaction (:phase s)))
      (is (contains? (phases/legal-actions s 0) :jaeger-shoot)
          "and may nonetheless shoot")
      (let [s (exec! s {:type :jaeger-shoot :player 0 :target 1})]
        (is (false? (get-in s [:players 1 :alive?])) "his target dies")
        (is (= :village (:winner s)))))))

(deftest jaeger-chain-two-shots
  (testing "a Jäger who shoots another Jäger triggers a second shot"
    (let [s (tu/game-with {0 :jaeger 1 :jaeger 2 :werwolf 3 :dorfbewohner
                           4 :dorfbewohner 5 :dorfbewohner})
          s (exec! s {:type :werewolf-vote :player 2 :target 0})
          s (exec! s {:type :jaeger-shoot :player 0 :target 1})]
      (is (false? (get-in s [:players 1 :alive?])))
      (is (= :reaction (:phase s)) "still resolving interrupts")
      (is (contains? (phases/legal-actions s 1) :jaeger-shoot)
          "the second Jäger now owes a shot")
      (let [s (exec! s {:type :jaeger-shoot :player 1 :target 2})]
        (is (= 2 (tu/count-log s :jaeger/shot)) "exactly two shots fired")
        (is (= :village (:winner s)))))))

(deftest lover-dies-of-grief
  (let [s (tu/game-with {0 :amor 1 :dorfbewohner 2 :dorfbewohner 3 :werwolf
                         4 :dorfbewohner 5 :dorfbewohner})
        s (exec! s {:type :amor-link :player 0 :a 1 :b 2})
        _ (is (= #{1 2} (:lovers s)))
        s (exec! s {:type :werewolf-vote :player 3 :target 1})]
    (is (false? (get-in s [:players 1 :alive?])) "the eaten lover")
    (is (false? (get-in s [:players 2 :alive?])) "the grieving lover follows")
    (is (some #(= :liebe (:cause %)) (:log s)) "logged as death by love")))

(deftest grief-can-trigger-a-jaeger
  (testing "two different reaction types chaining: grief -> Jäger shot"
    (let [s (tu/game-with {0 :amor 1 :jaeger 2 :dorfbewohner 3 :werwolf
                           4 :dorfbewohner 5 :dorfbewohner})
          s (exec! s {:type :amor-link :player 0 :a 1 :b 2})
          s (exec! s {:type :werewolf-vote :player 3 :target 2})]
      (is (false? (get-in s [:players 2 :alive?])) "eaten")
      (is (false? (get-in s [:players 1 :alive?])) "Jäger died of grief")
      (is (contains? (phases/legal-actions s 1) :jaeger-shoot)
          "and still owes his shot")
      (let [s (exec! s {:type :jaeger-shoot :player 1 :target 3})]
        (is (= :village (:winner s)))))))

;; ------------------------------------------------------ win conditions ---

(deftest lovers-win-as-final-two
  (testing "a wolf and a villager in love beat both original teams"
    (let [s (tu/game-with {0 :amor 1 :werwolf 2 :dorfbewohner 3 :dorfbewohner 4 :dorfbewohner})
          s (exec! s {:type :amor-link :player 0 :a 1 :b 2})
          s (reduce (fn [s t] (exec! s {:type :gm/force-kill :player 0 :target t}))
                    s [3 4 0])]
      (is (= [1 2] (alive s)))
      (is (= :lovers (:winner s)))
      (is (= :game-over (:phase s))))))

(deftest village-wins-when-last-wolf-dies
  (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :dorfbewohner 3 :dorfbewohner 4 :dorfbewohner})
        s (exec! s {:type :gm/force-kill :player 0 :target 1})]
    (is (= :village (:winner s)))))

(deftest werewolves-win-when-no-villagers-remain
  (let [s (tu/game-with {0 :werwolf 1 :werwolf 2 :dorfbewohner 3 :dorfbewohner 4 :dorfbewohner})
        s (reduce (fn [s t] (exec! s {:type :gm/force-kill :player 0 :target t}))
                  s [2 3 4])]
    (is (= :werewolf (:winner s)))))

;; ---------------------------------------------------------- day voting ---

(deftest genuine-tie-kills-nobody
  (testing "an even split must not lynch anyone — the classic rule"
    (let [s (tu/game-with {0 :dorfbewohner 1 :dorfbewohner 2 :werwolf
                           3 :dorfbewohner 4 :hexe 5 :seherin})
          s (exec! s {:type :seherin-inspect :player 5 :target 2})
          s (exec! s {:type :werewolf-vote :player 2 :target 3})
          s (exec! s {:type :hexe-heal :player 4 :target 3})
          s (exec! s {:type :hexe-pass :player 4})
          _ (is (= 6 (count (alive s))) "everyone survived the night")
          s (tu/advance-to-vote s)
          ;; 3 votes for player 0, 3 votes for player 1 — a real tie.
          s (reduce (fn [s [voter target]] (exec! s {:type :vote :player voter :target target}))
                    s [[0 1] [1 0] [2 1] [3 0] [4 1] [5 0]])
          before (count (alive s))
          s (exec! s {:type :advance-phase :player 0})]
      (is (= before (count (alive s))) "nobody died")
      (let [resolved (first (filter #(= :vote/resolved (:type %)) (:log s)))]
        (is (true? (:tie? resolved)) "and it is logged as a tie")
        (is (nil? (:target resolved)))))))

(deftest majority-lynches-and-reveals-role
  (let [s (tu/game-with {0 :dorfbewohner 1 :dorfbewohner 2 :werwolf
                         3 :dorfbewohner 4 :hexe 5 :seherin})
        s (exec! s {:type :seherin-inspect :player 5 :target 2})
        s (exec! s {:type :werewolf-vote :player 2 :target 3})
        s (exec! s {:type :hexe-heal :player 4 :target 3})
        s (exec! s {:type :hexe-pass :player 4})
        s (tu/advance-to-vote s)
        ;; Player 2 cannot vote for themselves, so everyone else condemns them.
        s (reduce (fn [s v] (exec! s {:type :vote :player v :target 2}))
                  s (remove #{2} (alive s)))
        s (exec! s {:type :advance-phase :player 0})]
    (is (false? (get-in s [:players 2 :alive?])))
    (is (= :village (:winner s)))
    (let [death (first (filter #(and (= :player/died (:type %)) (= 2 (:player %))) (:log s)))]
      (is (= :werwolf (:role death)) "classic Werwolf reveals the lynched role")
      (is (= :lynch (:cause death))))))

;; ------------------------------------------------------------ GM powers ---

(deftest dead-host-keeps-gm-powers
  (testing "stripping GM powers on death would let a wolf freeze the game by
            eating the host on night one"
    (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :dorfbewohner
                           3 :dorfbewohner 4 :dorfbewohner 5 :dorfbewohner})
          s (exec! s {:type :werewolf-vote :player 1 :target 0})]
      (is (false? (get-in s [:players 0 :alive?])) "the host is dead")
      (is (contains? (phases/legal-actions s 0) :advance-phase)
          "and can still drive the game")
      ;; And the day actually progresses.
      (let [s (exec! s {:type :advance-phase :player 0})]
        (is (= :nomination (:phase s)))))))

(deftest gm-overrides-are-logged-with-their-actor
  (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :dorfbewohner 3 :dorfbewohner 4 :dorfbewohner})
        s (exec! s {:type :gm/force-kill :player 0 :target 2})
        entry (first (filter #(= :gm/forced (:type %)) (:log s)))]
    (is (some? entry) "the override is public, not silent")
    (is (= 0 (:actor entry)))
    (is (= :force-kill (:action entry)))))

;; -------------------------------------------------------- input hygiene ---

(deftest illegal-commands-are-rejected
  (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :jaeger})]
    (testing "a villager cannot perform a werewolf kill"
      (let [[_ err] (try-exec s {:type :werewolf-vote :player 0 :target 1})]
        (is (= :not-allowed (:error err)))))
    (testing "a wolf cannot act before the werewolf turn comes round"
      (let [[_ err] (try-exec s {:type :werewolf-vote :player 1 :target 0})]
        (is (= :not-allowed (:error err)))))
    (testing "wolves cannot eat their own, once it IS their turn"
      ;; Clear the Seherin so the cursor reaches :werwolf.
      (let [s (exec! s {:type :seherin-inspect :player 3 :target 1})
            _ (is (= :werwolf (:night-cursor s)))
            [_ err] (try-exec s {:type :werewolf-vote :player 1 :target 1})]
        (is (= :invalid-target (:error err))
            "a wolf in the target list would let the pack eat itself")))
    (testing "a role cannot act out of turn"
      (let [[_ err] (try-exec s {:type :hexe-poison :player 2 :target 0})]
        (is (= :not-allowed (:error err)))))
    (testing "unknown commands are refused rather than ignored"
      (let [[_ err] (try-exec s {:type :become-admin :player 0})]
        (is (= :unknown-command (:error err)))))))

(deftest legal-actions-never-offers-an-unusable-action
  (testing "every offered action must have at least one legal target"
    (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :jaeger})]
      (doseq [pid (range 5)
              action (phases/legal-actions s pid)
              :when (not (contains? phases/no-target-actions action))]
        (is (seq (phases/legal-targets s pid action))
            (str "player " pid " offered " action " with no targets"))))))

;; ------------------------------------------------------------- replay ---

(deftest replay-reproduces-state-exactly
  (let [genesis [{:type :game/created :room-id "R" :host 0 :seed 99 :min-players 5}
                 {:type :player/joined :player 0 :name "A" :locale :de}
                 {:type :player/joined :player 1 :name "B" :locale :en}
                 {:type :player/joined :player 2 :name "C" :locale :de}
                 {:type :player/joined :player 3 :name "D" :locale :de}
                 {:type :player/joined :player 4 :name "E" :locale :de}]
        s (e/replay genesis)
        r (engine/execute s {:type :start-game :player 0})
        log (into genesis (:events r))]
    (is (= (e/replay log) (:state r))
        "folding the log must reproduce the live state bit for bit")))
