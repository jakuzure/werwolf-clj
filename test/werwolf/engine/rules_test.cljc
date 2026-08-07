(ns werwolf.engine.rules-test
  "Targeted tests for the interactions that are actually hard.

   The simulation harness covers breadth; these cover the specific rule
   collisions that a random walk hits rarely or reports unhelpfully."
  (:require [clojure.test :refer [are deftest is testing]]
            [werwolf.engine.core :as engine]
            [werwolf.engine.events :as e]
            [werwolf.engine.phases :as phases]
            [werwolf.engine.roles :as roles]
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
          before (count (alive s))
          ;; No trailing :advance-phase: the vote resolves itself the moment the
          ;; last living player has spoken, so the host has nothing left to do.
          s (reduce (fn [s [voter target]] (exec! s {:type :vote :player voter :target target}))
                    s [[0 1] [1 0] [2 1] [3 0] [4 1] [5 0]])]
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
        ;; Player 2 must vote too, or the tally is still waiting on them.
        s (reduce (fn [s v] (exec! s (if (= 2 v)
                                       {:type :vote-abstain :player v}
                                       {:type :vote :player v :target 2})))
                  s (alive s))]
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
  (testing "every offered action must have something legal to point at —
            a player, or, for the GM overrides that name a phase or a role
            instead, a choice"
    (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :jaeger})]
      (doseq [pid (range 5)
              action (phases/legal-actions s pid)
              :when (not (contains? phases/no-target-actions action))]
        (is (or (seq (phases/legal-targets s pid action))
                (seq (phases/legal-choices s action)))
            (str "player " pid " offered " action " with nothing to point at"))))))

(deftest small-tables-still-compose-a-real-deck
  (testing "MIN_PLAYERS can be lowered for a quick test game, so the deck has
            to hold up below the documented sizes rather than merely not throw"
    (are [n deck] (= deck (roles/compose-roles n))
      3 [:werwolf :seherin :dorfbewohner]
      4 [:werwolf :seherin :hexe :dorfbewohner]
      5 [:werwolf :seherin :hexe :jaeger :dorfbewohner])
    (testing "every small table has exactly one wolf and at least one villager"
      (doseq [n [3 4]]
        (let [deck (roles/compose-roles n)]
          (is (= 1 (count (filter #{:werwolf} deck))))
          (is (< 1 (count (remove #{:werwolf} deck)))
              "a lone villager against a wolf is over before it starts"))))))

(deftest table-limits-live-in-state-not-in-a-literal
  (testing "a room keeps the limits it was opened with, so a config change
            cannot retroactively lock people out of a game in progress"
    (let [s (e/apply-event nil {:type :game/created :room-id "R" :host 0 :seed 1
                                :min-players 3 :max-players 4})
          s (reduce (fn [s i] (e/apply-event s {:type :player/joined :player i
                                                :name (str "P" i) :locale :de}))
                    s (range 4))]
      (is (= 3 (:min-players s)))
      (is (= 4 (:max-players s)))
      (is (= :room-full (:error (engine/execute s {:type :join :player 9 :name "Late"})))))))

(deftest a-preference-is-not-an-action
  (testing "language can be changed in any phase, alive or dead, which is a trap:
            if `nobody-can-act?` counted :set-locale as 'has something to do',
            a role with nothing left to decide would look busy forever and the
            night would hang waiting for somebody to pick a language"
    (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin
                           4 :dorfbewohner 5 :dorfbewohner})
          s (exec! s {:type :seherin-inspect :player 3 :target 1})
          s (exec! s {:type :werewolf-vote :player 1 :target 0})
          ;; Spend both potions, so the Hexe has nothing left but :set-locale.
          s (exec! s {:type :hexe-heal :player 2 :target 0})
          s (exec! s {:type :hexe-poison :player 2 :target 5})]
      (is (contains? (phases/legal-actions s 2) :set-locale)
          "she can still change language")
      (is (not= :night (:phase s))
          "and the night ended anyway rather than waiting on her")
      (testing "the dead keep it too"
        (is (contains? (phases/legal-actions s 5) :set-locale))))))

(deftest chat-is-allowed-in-the-lobby
  (testing "people arrange the game before it starts; a silent lobby was a bug"
    (let [s (e/replay [{:type :game/created :room-id "R" :host 0 :seed 1 :min-players 5}
                       {:type :player/joined :player 0 :name "A" :locale :de}
                       {:type :player/joined :player 1 :name "B" :locale :de}])]
      (is (contains? (phases/legal-actions s 1) :chat)))))

(deftest the-jaeger-may-speak-while-he-aims
  (testing "the whole table is waiting on him and everyone else is chatting"
    (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :jaeger})
          s (exec! s {:type :seherin-inspect :player 3 :target 1})
          s (exec! s {:type :werewolf-vote :player 1 :target 4})
          s (exec! s {:type :hexe-pass :player 2})]
      (is (= :reaction (:phase s)))
      (is (false? (get-in s [:players 4 :alive?])) "the Jäger is dead")
      (is (= (into #{:jaeger-shoot :jaeger-pass :chat} phases/always-allowed)
             (phases/legal-actions s 4))
          "and he may decline: the clock already lets him off if he says
           nothing, so refusing a button for the same choice would only punish
           the player who is paying attention"))))

;; --------------------------------------------- the peaceful first night ---

(deftest the-wolves-have-no-turn-on-the-first-night
  (testing "a player eliminated before they have said a word has watched a game,
            not played one — and on night 1 the wolves choose at random anyway"
    (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :jaeger}
                          {:first-night-kill? false})]
      (is (= 1 (:night s)))
      (is (not (contains? (set (phases/night-queue s)) :werwolf))
          "the wolves are not in the first night's queue")
      (is (= phases/always-allowed (phases/legal-actions s 1))
          "so the wolf has nothing to do but choose a language — not even a
           vote to cast"))))

(deftest the-first-night-passes-with-everyone-alive
  (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :jaeger}
                        {:first-night-kill? false})
        s (exec! s {:type :seherin-inspect :player 3 :target 1})
        ;; The Hexe has nothing to heal, because nobody is dying.
        _ (is (empty? (phases/legal-targets s 2 :hexe-heal)))
        _ (is (not (contains? (phases/legal-actions s 2) :hexe-heal)))
        s (exec! s {:type :hexe-pass :player 2})]
    (is (= :discussion (:phase s)) "the day begins")
    (is (= [0 1 2 3 4] (alive s)) "with the whole village intact")
    (is (= 1 (tu/count-log s :night/quiet))
        "and the Chronik says so — otherwise a peaceful night is
         indistinguishable from a missing entry")))

(deftest the-wolves-do-kill-from-the-second-night
  (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :jaeger}
                        {:first-night-kill? false})
        s (exec! s {:type :seherin-inspect :player 3 :target 1})
        s (exec! s {:type :hexe-pass :player 2})
        ;; Straight through day 1 with no lynch.
        s (reduce (fn [s p] (exec! s {:type :ready :player p})) s (alive s))
        s (reduce (fn [s p] (exec! s {:type :nominate-pass :player p})) s (alive s))]
    (is (= 2 (:night s)))
    (is (contains? (set (phases/night-queue s)) :werwolf)
        "night two is business as usual")
    (let [s (exec! s {:type :seherin-inspect :player 3 :target 0})
          s (exec! s {:type :werewolf-vote :player 1 :target 0})
          s (exec! s {:type :hexe-pass :player 2})]
      (is (false? (get-in s [:players 0 :alive?])) "and somebody dies"))))

(deftest the-classic-rule-can-be-restored
  (testing "the variant is a per-room flag stamped into :game/created, so a
            replay plays the game it actually was"
    (let [log [{:type :game/created :room-id "R" :host 0 :seed 1 :min-players 5
                :first-night-kill? true}
               {:type :player/joined :player 0 :name "A" :locale :de}
               {:type :player/joined :player 1 :name "B" :locale :de}
               {:type :player/joined :player 2 :name "C" :locale :de}
               {:type :player/joined :player 3 :name "D" :locale :de}
               {:type :player/joined :player 4 :name "E" :locale :de}
               {:type :game/started :assignments {0 :dorfbewohner 1 :werwolf
                                                  2 :hexe 3 :seherin 4 :jaeger}}
               {:type :night/began :night 1}]
          s (first (phases/settle (e/replay log)))]
      (is (true? (:first-night-kill? s)))
      (is (contains? (set (phases/night-queue s)) :werwolf))
      (testing "and the wolf really can act on night 1 under it"
        (let [s (exec! s {:type :seherin-inspect :player 3 :target 1})]
          (is (contains? (phases/legal-actions s 1) :werewolf-vote)))))))

;; ------------------------------------------------- saying no --------------
;;
;; Every one of these is a deliberate "no". They exist because the only other
;; way to decline was to say nothing, which is indistinguishable from having
;; lost your connection — and which made the table sit out a timer it had
;; already finished with.

(defn- at-day
  "A six-player game that has survived night one and is in :discussion."
  []
  (let [s (tu/game-with {0 :dorfbewohner 1 :dorfbewohner 2 :werwolf
                         3 :dorfbewohner 4 :hexe 5 :seherin})
        s (exec! s {:type :seherin-inspect :player 5 :target 2})
        s (exec! s {:type :werewolf-vote :player 2 :target 3})
        s (exec! s {:type :hexe-heal :player 4 :target 3})
        s (exec! s {:type :hexe-pass :player 4})]
    s))

(deftest discussion-ends-when-everyone-says-they-are-done
  (testing "otherwise the table talks itself out in forty seconds and then
            watches a five-minute clock"
    (let [s (at-day)]
      (is (= :discussion (:phase s)))
      (let [s (reduce (fn [s pid] (exec! s {:type :ready :player pid})) s (range 5))]
        (is (= :discussion (:phase s)) "one player has not spoken yet")
        (is (not (contains? (phases/legal-actions s 0) :ready))
            "and a player who is ready is not asked twice")
        (let [s (exec! s {:type :ready :player 5})]
          (is (= :nomination (:phase s)) "the last one closes it"))))))

(deftest one-accusation-each-and-declining-is-an-answer
  (let [s (reduce (fn [s pid] (exec! s {:type :ready :player pid})) (at-day) (range 6))]
    (is (= :nomination (:phase s)))
    (let [s (exec! s {:type :nominate :player 0 :target 2})]
      (is (not (contains? (phases/legal-actions s 0) :nominate))
          "having named someone, you have had your say")
      (is (contains? (phases/legal-actions s 1) :nominate-pass))
      (let [s (reduce (fn [s pid] (exec! s {:type :nominate-pass :player pid}))
                      s (range 1 6))]
        (is (= :vote (:phase s)) "the phase closes itself once nobody is waited for")
        (is (= [2] (phases/legal-targets s 0 :vote))
            "and the ballot holds exactly the accused")))))

(deftest a-village-that-accuses-nobody-lynches-nobody
  (testing "the whole point of being able to decline"
    (let [s (reduce (fn [s pid] (exec! s {:type :ready :player pid})) (at-day) (range 6))
          before (alive s)
          s (reduce (fn [s pid] (exec! s {:type :nominate-pass :player pid})) s (range 6))]
      (is (= before (alive s)) "nobody died")
      (is (= 1 (tu/count-log s :day/no-accusation))
          "and the day says so — a day with no accusation must not look like a
           day that has not happened")
      (is (= :night (:phase s)) "the game moves straight to the next night")
      (is (= 2 (:night s))))))

(deftest abstaining-is-a-vote-that-counts-for-nobody
  (let [s (reduce (fn [s pid] (exec! s {:type :ready :player pid})) (at-day) (range 6))
        s (exec! s {:type :nominate :player 0 :target 2})
        s (exec! s {:type :nominate :player 1 :target 3})
        s (reduce (fn [s pid] (exec! s {:type :nominate-pass :player pid})) s (range 2 6))
        _ (is (= :vote (:phase s)))
        before (alive s)
        ;; One vote each way, everyone else abstains: a tie, so nobody hangs.
        s (exec! s {:type :vote :player 0 :target 2})
        s (exec! s {:type :vote :player 1 :target 3})
        s (reduce (fn [s pid] (exec! s {:type :vote-abstain :player pid})) s (range 2 6))]
    (is (= before (alive s)) "a tie kills nobody, abstentions included")
    (let [resolved (first (filter #(= :vote/resolved (:type %)) (:log s)))]
      (is (true? (:tie? resolved))))
    (testing "an abstention is recorded, not dropped — it is what tells the
              phase machine this player has spoken"
      (is (= 6 (count (:votes s))))
      (is (nil? (get (:votes s) 2))))))

(deftest everyone-abstaining-kills-nobody-and-still-ends-the-day
  (let [s (reduce (fn [s pid] (exec! s {:type :ready :player pid})) (at-day) (range 6))
        s (exec! s {:type :nominate :player 0 :target 2})
        s (reduce (fn [s pid] (exec! s {:type :nominate-pass :player pid})) s (range 1 6))
        before (alive s)
        s (reduce (fn [s pid] (exec! s {:type :vote-abstain :player pid})) s (range 6))]
    (is (= before (alive s)))
    (is (= :night (:phase s)) "and the day is over rather than stuck")))

(deftest the-jaeger-can-hold-his-fire
  (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :jaeger})
        s (exec! s {:type :seherin-inspect :player 3 :target 4})
        s (exec! s {:type :werewolf-vote :player 1 :target 4})
        s (exec! s {:type :hexe-pass :player 2})
        _ (is (= :reaction (:phase s)))
        s (exec! s {:type :jaeger-pass :player 4})]
    (is (not= :reaction (:phase s)) "the game moved on")
    (is (= [0 1 2 3] (alive s)) "and he shot nobody")
    (is (= 1 (tu/count-log s :jaeger/held-fire)))))

;; --------------------------------------------------------- the clock ------

(deftest which-phases-have-a-clock-is-a-rule-not-a-config-value
  (testing "config decides how long a phase lasts; the engine decides whether it
            is timed at all"
    (is (= #{:night :discussion :nomination :vote :reaction}
           (set (keys phases/timed-phases))))
    (doseq [p [:lobby :day-reveal :day-resolve :game-over]]
      (is (nil? (phases/timeout-events {:phase p}))
          (str p " must have no timeout behaviour")))))

(deftest a-timeout-is-always-a-public-fact
  (testing "a player has to be able to see why the vote closed"
    (doseq [p [:night :discussion :nomination :vote :reaction]]
      (is (= :phase/timed-out
             (:type (first (phases/timeout-events {:phase p
                                                   :night-cursor :seherin
                                                   :reactions [{}]}))))
          (str p " timed out silently")))))

(deftest an-expired-day-phase-simply-advances
  (are [phase next*] (= next*
                        (:phase (second (phases/timeout-events {:phase phase}))))
    :discussion :nomination
    :nomination :vote
    :vote       :day-resolve))

(deftest an-expired-night-turn-ends-that-role-and-nothing-else
  (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :jaeger})]
    (is (= :seherin (:night-cursor s)))
    (let [evs (phases/timeout-events s)]
      (is (= [:phase/timed-out :night/role-completed] (mapv :type evs)))
      (is (= :seherin (:role (second evs))))
      (testing "and nobody dies just because the Seherin fell asleep"
        (let [s' (e/apply-all s evs)]
          (is (= (alive s) (alive (first (phases/settle s'))))))))))

(deftest wolves-who-run-out-of-time-still-eat
  (testing "deciding nothing would hand the village a free night for stalling"
    (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :werwolf 3 :seherin 4 :jaeger})
          s (exec! s {:type :seherin-inspect :player 3 :target 1})
          ;; Only ONE of the two wolves votes, then the clock runs out.
          s (exec! s {:type :werewolf-vote :player 1 :target 0})]
      (is (= :werwolf (:night-cursor s)) "still waiting on the other wolf")
      (let [evs (phases/timeout-events s)]
        (is (= [:phase/timed-out :night/role-completed :werewolf/chose]
               (mapv :type evs)))
        (is (= 0 (:target (last evs))) "the vote that WAS cast decided it")))))

(deftest wolves-who-vote-for-nobody-kill-nobody
  (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :werwolf 3 :seherin 4 :jaeger})
        s (exec! s {:type :seherin-inspect :player 3 :target 1})]
    (is (= :werwolf (:night-cursor s)))
    (is (= [:phase/timed-out :night/role-completed]
           (mapv :type (phases/timeout-events s)))
        "no votes at all means no victim — the timeout invents nothing")))

(deftest a-jaeger-who-runs-out-of-time-does-not-fire
  (testing "the shot is his to take, not the server's to guess"
    (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :jaeger})
          s (exec! s {:type :seherin-inspect :player 3 :target 4})
          s (exec! s {:type :werewolf-vote :player 1 :target 4})
          s (exec! s {:type :hexe-pass :player 2})]
      (is (= :reaction (:phase s)))
      (let [evs (phases/timeout-events s)
            [s' _] (phases/settle (e/apply-all s evs))]
        (is (= [:phase/timed-out :reaction/popped] (mapv :type evs)))
        (is (not= :reaction (:phase s')) "the game moved on")
        (is (empty? (:reactions s')))
        (is (= [0 1 2 3] (alive s')) "nobody was shot on his behalf")))))

(deftest the-deadline-is-server-authoritative-and-absolute
  (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :jaeger})
        s (e/apply-event s {:type :phase/deadline :at 1700000000000})]
    (is (= 1700000000000 (:deadline s)))
    (testing "it is not a public fact — it is a fact about the server"
      (is (not-any? #(= :phase/deadline (:type %)) (:log s))))
    (testing "and every phase change stops the clock, so a stale deadline can
              never leak across a transition"
      (is (nil? (:deadline (e/apply-event s {:type :phase/changed :phase :vote})))))
    (is (nil? (:deadline (e/apply-event s {:type :phase/deadline :at nil}))))))

;; ------------------------------------------------- succession -------------

(deftest host-succession-is-a-logged-public-fact
  (testing "the host is the fallback narrator, so who holds the powers can
            never be a private detail"
    (let [log [{:type :game/created :room-id "R" :host 0 :seed 1 :min-players 5}
               {:type :player/joined :player 0 :name "A" :locale :de}
               {:type :player/joined :player 1 :name "B" :locale :de}
               {:type :player/left :player 0}
               {:type :host/changed :host 1}]
          s (e/replay log)]
      (is (= 1 (:host s)))
      (is (= 1 (tu/count-log s :host/changed)) "public, so the override is auditable")
      (testing "and it folds like any other event"
        (is (= s (e/apply-event (e/replay (butlast log)) (last log))))))))

;; ------------------------------------------------- GM overrides -----------

(def ^:private gm-cast
  {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :jaeger})

(deftest the-gm-can-rewrite-a-role
  (testing "the escape hatch for a deal that was wrong"
    (let [s (tu/game-with gm-cast)
          s (exec! s {:type :gm/reassign-role :player 0 :target 4 :role :dorfbewohner})]
      (is (= :dorfbewohner (tu/role-of s 4)))
      (testing "and it is announced, because an override nobody can see is not
                an override, it is a secret"
        (is (= 1 (tu/count-log s :gm/forced)))))))

(deftest a-reassigned-role-brings-its-own-resources
  (testing "a Hexe with no potions is not a Hexe, and an ex-Hexe must not keep
            a vial she can no longer use"
    (let [s (tu/game-with gm-cast)
          s (exec! s {:type :gm/reassign-role :player 0 :target 4 :role :hexe})]
      (is (= {:heiltrank 1 :gifttrank 1} (get-in s [:players 4 :resources]))
          "the new Hexe is holding both potions")
      (let [s (exec! s {:type :gm/reassign-role :player 0 :target 2 :role :dorfbewohner})]
        (is (nil? (get-in s [:players 2 :resources]))
            "and the old one is not")))))

(deftest reassigning-does-not-unlearn-what-a-seherin-saw
  (testing "her inspections were true when she made them, and she cannot unsee
            them — the redaction boundary reads the same map, so what she keeps
            and what she may keep are the same thing by construction"
    (let [s (tu/game-with gm-cast)
          s (exec! s {:type :seherin-inspect :player 3 :target 1})
          s (exec! s {:type :gm/reassign-role :player 0 :target 3 :role :dorfbewohner})]
      (is (= :dorfbewohner (tu/role-of s 3)))
      (is (= {1 :werwolf} (get-in s [:players 3 :knows]))))))

(deftest reassigning-can-decide-the-game
  (testing "roles are what the win check counts, so rewriting one has to be
            settled like any other event rather than left for the next command"
    (let [s (tu/game-with gm-cast)
          s (exec! s {:type :gm/reassign-role :player 0 :target 1 :role :dorfbewohner})]
      (is (= :game-over (:phase s)))
      (is (= :village (:winner s)) "the last wolf stopped being one"))))

(deftest reassigning-the-acting-role-does-not-hang-the-night
  (testing "the night waits for whoever holds the cursor's role; take it away
            mid-turn and the turn must end, not stall forever"
    (let [s (tu/game-with gm-cast)
          _ (is (= :seherin (:night-cursor s)))
          s (exec! s {:type :gm/reassign-role :player 0 :target 3 :role :dorfbewohner})]
      (is (not= :seherin (:night-cursor s))
          "with nobody left to look, the night moved on"))))

(deftest the-gm-cannot-reassign-nonsense
  (let [s (tu/game-with gm-cast)]
    (are [cmd err] (= err (:error (second (try-exec s cmd))))
      {:type :gm/reassign-role :player 1 :target 4 :role :hexe}     :not-host
      {:type :gm/reassign-role :player 0 :target 9 :role :hexe}     :no-such-player
      {:type :gm/reassign-role :player 0 :target 4 :role :admin}    :unknown-role
      {:type :gm/reassign-role :player 0 :target 4 :role nil}       :unknown-role
      {:type :gm/reassign-role :player 0 :target 2 :role :hexe}     :already-that-role)))

(deftest gm-overrides-are-refused-before-a-game-exists
  (testing "there are no roles to rewrite in a lobby, and none worth rewriting
            once the game is decided"
    (let [s (engine/new-game {:room-id "R" :host 0 :seed 1 :min-players 5})
          s (reduce (fn [s i] (e/apply-event s {:type :player/joined :player i
                                                :name (str "P" i) :locale :de}))
                    s (range 5))]
      (is (= :no-game-in-progress
             (:error (second (try-exec s {:type :gm/reassign-role :player 0
                                          :target 1 :role :hexe}))))))))

(deftest an-override-that-names-a-word-offers-choices-not-targets
  (testing ":gm/set-phase names a phase and :gm/skip-role a role. Returning
            those from legal-targets made the client arm its pick-a-player mode
            with no player to pick — both controls looked present and neither
            could be used"
    (let [s (tu/game-with gm-cast)]
      (is (contains? (phases/legal-actions s 0) :gm/set-phase))
      (is (empty? (phases/legal-targets s 0 :gm/set-phase)))
      (is (= [:discussion :nomination :vote] (phases/legal-choices s :gm/set-phase)))
      (testing "skip-role offers exactly the role being called, and nothing
                when no role is"
        (is (= [:seherin] (phases/legal-choices s :gm/skip-role)))
        (is (empty? (phases/legal-choices (assoc s :night-cursor nil) :gm/skip-role))))
      (testing "reassign-role is the one that names BOTH"
        (is (= [0 1 2 3 4] (phases/legal-targets s 0 :gm/reassign-role))
            "the dead included — the usual reason to rewrite a card is that the
             deal was wrong, and reviving first would need a second correction")
        (is (= roles/assignable-ids (phases/legal-choices s :gm/reassign-role)))))))

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
