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

;; ------------------------------------------------- the verdict's moment ---

(def ^:private seven
  {0 :dorfbewohner 1 :dorfbewohner 2 :werwolf 3 :dorfbewohner 4 :hexe 5 :seherin 6 :werwolf})

(defn- condemn
  "Everybody else votes against `target`, who abstains."
  [s target]
  (reduce (fn [s v] (exec! s (if (= target v)
                               {:type :vote-abstain :player v}
                               {:type :vote :player v :target target})))
          s (alive s)))

(deftest a-condemnation-holds-the-day-until-the-table-has-seen-the-card
  (let [s (condemn (tu/day-with seven) 3)]
    (testing "the condemned is dead and his card turned over, and it is still day"
      (is (false? (get-in s [:players 3 :alive?])))
      (is (some #(and (= :player/died (:type %)) (= 3 (:player %)) (= :dorfbewohner (:role %)))
                (:log s)))
      (is (= [:day-resolve 1] [(:phase s) (:night s)]))
      (is (phases/verdict-held? s)))
    (testing "nobody owes anything: the players may only talk, the host may go on"
      (is (empty? (phases/owing s)))
      (doseq [pid (remove #{0} (alive s))]
        (is (= #{:chat} (disj (phases/legal-actions s pid) :set-locale :leave))))
      (is (contains? (phases/legal-actions s 0) :advance-phase)))
    (testing "the clock running out lets the night fall and says nothing more:
              no vote and no turn was cut short"
      (is (= [{:type :night/began :night 2}] (phases/timeout-events s))))
    (testing "so does the host, and it is no override — nobody was owed a move"
      (let [n (exec! s {:type :advance-phase :player 0})]
        (is (= [:night 2] [(:phase n) (:night n)]))
        (is (= (tu/count-log s :gm/forced) (tu/count-log n :gm/forced)))
        (is (nil? (:condemned n)))))))

(deftest without-a-clock-the-host-still-calls-the-night
  (let [s (condemn (assoc (tu/day-with seven) :timer? false) 3)]
    (is (phases/verdict-held? s))
    (is (contains? (phases/legal-actions s 0) :advance-phase))
    (is (= :night (:phase (exec! s {:type :advance-phase :player 0}))))))

(deftest a-day-that-condemns-nobody-goes-straight-to-the-night
  (testing "there is no card to turn over, so nothing to wait for"
    (let [d (tu/day-with seven)
          s (reduce (fn [s v] (exec! s {:type :vote-abstain :player v})) d (alive d))]
      (is (= [:night 2] [(:phase s) (:night s)])))))

(deftest a-lynched-jaeger-shoots-before-the-verdict-rests
  (let [s (condemn (tu/day-with (assoc seven 3 :jaeger)) 3)]
    (is (= :reaction (:phase s)) "his shot comes first")
    (is (not (phases/verdict-held? s)))
    (let [s (exec! s {:type :jaeger-shoot :player 3 :target 5})]
      (testing "and the table reads both cards before the night"
        (is (phases/verdict-held? s))
        (is (= {3 :jaeger 5 :seherin}
               (into {} (keep #(when (= :player/died (:type %)) [(:player %) (:role %)]))
                     (:log s))))))))

(deftest a-condemned-mayor-names-his-heir-before-the-verdict-rests
  (let [s (condemn (assoc (tu/day-with seven) :mayor 3) 3)]
    (is (= :reaction (:phase s)) "his last move comes first, like the Jäger's")
    (let [s (exec! s {:type :mayor-appoint :player 3 :target 1})]
      (is (phases/verdict-held? s))
      (is (= 1 (:mayor s))))))

(deftest a-lynch-that-decides-the-game-ends-it-at-once
  (testing "the end turns every card over; it has no verdict to wait on"
    (let [s (condemn (tu/day-with {0 :dorfbewohner 1 :dorfbewohner 2 :werwolf
                                   3 :dorfbewohner 4 :hexe}) 2)]
      (is (= [:game-over :village] [(:phase s) (:winner s)])))))

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
      ;; And the day actually progresses: the ballot closes, nobody is
      ;; condemned, and the next night falls.
      (let [s (exec! s {:type :advance-phase :player 0})]
        (is (= 2 (:night s)))))))

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
        _ (is (not (contains? (set (phases/night-queue s)) :hexe))
              "nor is the Hexe: with nobody dying there is nobody to heal, and
               her poison would be the one death of a night meant to have none")
        s (exec! s {:type :seherin-inspect :player 3 :target 1})]
    (is (= :discussion (:phase s)) "the day begins once the Seherin has looked")
    (is (= [0 1 2 3 4] (alive s)) "with the whole village intact")
    (is (= 1 (tu/count-log s :night/quiet))
        "and the Chronik says so — otherwise a peaceful night is
         indistinguishable from a missing entry")))

(deftest the-hexe-on-a-first-night-with-a-kill
  (testing "the classic first night: she sees the wolves' victim and may heal
            and poison in the same night, herself included"
    (let [s (-> (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :jaeger})
                (exec! {:type :seherin-inspect :player 3 :target 1})
                (exec! {:type :werewolf-vote :player 1 :target 2}))]
      (is (= :hexe (:night-cursor s)))
      (is (= [2] (phases/legal-targets s 2 :hexe-heal)) "she may heal herself")
      (let [s (-> s
                  (exec! {:type :hexe-heal :player 2 :target 2})
                  (exec! {:type :hexe-poison :player 2 :target 1}))]
        (is (= [0 2 3 4] (alive s)) "both potions in one night: saved, and the wolf dead")
        (is (= :village (:winner s)))))))

(deftest the-wolves-do-kill-from-the-second-night
  (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :jaeger}
                        {:first-night-kill? false})
        s (exec! s {:type :seherin-inspect :player 3 :target 1})
        ;; Straight through day 1 with no lynch.
        s (reduce (fn [s p] (exec! s {:type :vote-abstain :player p})) s (alive s))]
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

(deftest nobody-is-accused-on-the-first-day
  (let [cast {0 :dorfbewohner 1 :dorfbewohner 2 :werwolf
              3 :dorfbewohner 4 :hexe 5 :seherin}
        day1 (fn [] (-> (tu/game-with cast {:first-night-kill? false :first-day-trial? false})
                        (exec! {:type :seherin-inspect :player 5 :target 2})))]
    (testing "the shipped rule: the first day's talk ends in the night"
      (let [s (day1)]
        (is (= :discussion (:phase s)))
        (is (false? (phases/trial-day? s)))
        (let [s (reduce #(exec! %1 {:type :ready :player %2}) s (range 6))]
          (is (= :night (:phase s)))
          (is (= 2 (:night s)))
          (is (= 1 (tu/count-log s :day/no-trial)) "and the Chronik says why"))))
    (testing "the clock running out does the same"
      (let [s (day1)]
        (is (= [:day/no-trial :night/began]
               (mapv :type (rest (phases/timeout-events s)))))))
    (testing "and so does the host moving the day along"
      (is (= [:day/no-trial :night/began]
             (mapv :type (phases/advance-events (day1))))))
    (testing "from the second day the village accuses again"
      (let [s (-> (day1)
                  (as-> s (reduce #(exec! %1 {:type :ready :player %2}) s (range 6)))
                  (exec! {:type :seherin-inspect :player 5 :target 0})
                  (exec! {:type :werewolf-vote :player 2 :target 3})
                  (exec! {:type :hexe-pass :player 4}))]
        (is (= :discussion (:phase s)))
        (is (= 2 (:night s)))
        (is (contains? (phases/legal-actions s 0) :vote))
        (is (not (contains? (phases/legal-actions s 0) :ready))
            "the talk ends with the ballot, not with a button")))))

(deftest the-first-days-talk-ends-when-everyone-says-they-are-done
  (testing "otherwise the table talks itself out in forty seconds and then
            watches a five-minute clock"
    (let [s (-> (tu/game-with {0 :dorfbewohner 1 :dorfbewohner 2 :werwolf
                               3 :dorfbewohner 4 :hexe 5 :seherin}
                              {:first-day-trial? false})
                (exec! {:type :seherin-inspect :player 5 :target 2})
                (exec! {:type :werewolf-vote :player 2 :target 3})
                (exec! {:type :hexe-heal :player 4 :target 3})
                (exec! {:type :hexe-pass :player 4}))]
      (is (= :discussion (:phase s)))
      (let [s (reduce (fn [s pid] (exec! s {:type :ready :player pid})) s (range 5))]
        (is (= :discussion (:phase s)) "one player has not spoken yet")
        (is (not (contains? (phases/legal-actions s 0) :ready))
            "and a player who is ready is not asked twice")
        (let [s (exec! s {:type :ready :player 5})]
          (is (= :night (:phase s)) "the last one closes it"))))))

(deftest the-day-is-one-open-ballot
  (let [s (at-day)]
    (is (= :discussion (:phase s)))
    (is (= #{:chat :vote :vote-abstain} (disj (phases/legal-actions s 1) :set-locale :leave))
        "talking and voting at once: there is no accusation step")
    (is (= [0 2 3 4 5] (phases/legal-targets s 1 :vote)) "anybody living but himself")
    (let [s (exec! s {:type :vote :player 1 :target 2})]
      (testing "a vote may be changed while the ballot is open"
        (is (contains? (phases/legal-actions s 1) :vote))
        (let [s (exec! s {:type :vote :player 1 :target 3})]
          (is (= 3 (get-in s [:votes 1])))))
      (testing "the same vote again changes nothing and logs nothing"
        (is (= s (exec! s {:type :vote :player 1 :target 2}))))
      (testing "but whoever has voted is no longer waited for"
        (is (not (contains? (set (phases/owing s)) 1)))
        (is (contains? (set (phases/owing s)) 0))))
    (testing "the ballot closes itself with the last vote"
      (let [s (reduce (fn [s pid] (exec! s {:type :vote :player pid :target (if (= 2 pid) 0 2)}))
                      s (range 6))]
        (is (false? (get-in s [:players 2 :alive?])) "five of six is a majority")))))

(deftest a-condemnation-needs-a-majority-of-the-living
  (testing "the most votes is not enough: abstaining counts against"
    (let [s (at-day)
          before (alive s)
          s (reduce (fn [s pid] (exec! s (if (#{0 1 3} pid)
                                           {:type :vote :player pid :target 2}
                                           {:type :vote-abstain :player pid})))
                    s (range 6))]
      (is (= 4 (phases/votes-needed (at-day))) "six living: four votes")
      (is (= before (alive s)) "three of six condemns nobody")
      (is (true? (:no-majority? (first (filter #(= :vote/resolved (:type %)) (:log s)))))
          "and the Chronik says why")
      (is (= :night (:phase s)))))
  (testing "four of six does"
    (let [s (reduce (fn [s pid] (exec! s (if (#{0 1 3 5} pid)
                                           {:type :vote :player pid :target 2}
                                           {:type :vote-abstain :player pid})))
                    (at-day) (range 6))]
      (is (false? (get-in s [:players 2 :alive?]))))))

(deftest abstaining-is-a-vote-that-counts-for-nobody
  (let [s (at-day)
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
  (let [s (at-day)
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
    (is (= #{:election :night :discussion :reaction :day-resolve}
           (set (keys phases/timed-phases))))
    (doseq [p [:lobby :day-reveal :game-over]]
      (is (nil? (phases/timeout-events {:phase p}))
          (str p " must have no timeout behaviour")))
    (is (nil? (phases/timeout-events {:phase :day-resolve :vote-resolved? true}))
        "nor the verdict, unless it condemned somebody: only then does it rest")))

(deftest a-timeout-is-always-a-public-fact
  (testing "a player has to be able to see why the vote closed"
    (doseq [p [:night :discussion :reaction]]
      (is (= :phase/timed-out
             (:type (first (phases/timeout-events {:phase p
                                                   :night-cursor :seherin
                                                   :reactions [{}]}))))
          (str p " timed out silently")))))

(deftest an-expired-day-phase-simply-advances
  (are [phase next*] (= next*
                        ;; Day 2: the first has no trial to advance into.
                        (:phase (second (phases/timeout-events {:phase phase :night 2}))))
    :discussion :day-resolve))

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
      (is (= [:discussion] (phases/legal-choices s :gm/set-phase)))
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

;; ----------------------------------------------------------- Beschützer ---
;;
;; He commits BEFORE the wolves choose — that ordering is the whole role. A guard
;; who acted after them would simply cancel the night.
;;
;; Night order in these games is Seherin (100), Beschützer (150), Werwolf (200),
;; Hexe (300), so the commands below run in that sequence.

(deftest a-guarded-victim-does-not-die
  (let [s (tu/game-with {0 :beschuetzer 1 :werwolf 2 :dorfbewohner 3 :seherin})
        s (exec! s {:type :seherin-inspect :player 3 :target 1})
        s (exec! s {:type :beschuetzer-protect :player 0 :target 2})
        s (exec! s {:type :werewolf-vote :player 1 :target 2})]
    (is (true? (get-in s [:players 2 :alive?])) "the guarded player lived")
    (is (= 0 (tu/count-log s :player/died)) "nobody died")
    (testing "and the night reads as quiet rather than as a missing entry"
      (is (pos? (tu/count-log s :night/quiet))))))

(deftest a-guard-on-the-wrong-player-saves-nobody
  (let [s (tu/game-with {0 :beschuetzer 1 :werwolf 2 :dorfbewohner 3 :seherin})
        s (exec! s {:type :seherin-inspect :player 3 :target 1})
        s (exec! s {:type :beschuetzer-protect :player 0 :target 3})
        s (exec! s {:type :werewolf-vote :player 1 :target 2})]
    (is (false? (get-in s [:players 2 :alive?])))))

(deftest the-hexe-is-never-shown-a-death-that-was-already-prevented
  (testing "a guarded victim produces NO pending death rather than one removed
            again later, because the Hexe reads :pending-deaths to decide on her
            heal — offering her a save for somebody already safe would waste the
            potion and tell her something untrue"
    (let [s (tu/game-with {0 :beschuetzer 1 :werwolf 2 :dorfbewohner 3 :hexe})
          s (exec! s {:type :beschuetzer-protect :player 0 :target 2})
          s (exec! s {:type :werewolf-vote :player 1 :target 2})]
      (is (empty? (:pending-deaths s)))
      (is (empty? (phases/legal-targets s 3 :hexe-heal))
          "she is offered nobody to heal"))))

(deftest the-guard-stops-wolves-and-nothing-else
  (testing "poison is not a werewolf attack, and a shield against everything
            would make the Hexe's second potion unusable"
    (let [s (tu/game-with {0 :beschuetzer 1 :werwolf 2 :dorfbewohner 3 :hexe})
          s (exec! s {:type :beschuetzer-protect :player 0 :target 2})
          s (exec! s {:type :werewolf-vote :player 1 :target 2})
          s (exec! s {:type :hexe-poison :player 3 :target 2})
          s (exec! s {:type :hexe-pass :player 3})]
      (is (false? (get-in s [:players 2 :alive?]))
          "guarded against the wolves, poisoned anyway"))))

(deftest he-may-guard-himself
  (testing "guarding your own door is a real choice and the rules allow it"
    (let [s (tu/game-with {0 :beschuetzer 1 :werwolf 2 :dorfbewohner 3 :seherin})]
      (is (contains? (set (phases/legal-targets s 0 :beschuetzer-protect)) 0)))))

(deftest he-may-not-guard-the-same-player-twice-running
  (testing "without the ban one player is permanently immune, which is the
            strongest position in the game"
    (let [s (tu/game-with {0 :beschuetzer 1 :werwolf 2 :dorfbewohner 3 :seherin
                           4 :dorfbewohner 5 :dorfbewohner})
          s (exec! s {:type :seherin-inspect :player 3 :target 1})
          s (exec! s {:type :beschuetzer-protect :player 0 :target 2})
          s (exec! s {:type :werewolf-vote :player 1 :target 4})
          s (tu/advance-to-vote s)
          s (reduce (fn [st pid] (exec! st {:type :vote-abstain :player pid}))
                    s (alive s))]
      (is (= :night (:phase s)) "we reached the second night")
      (is (not (contains? (set (phases/legal-targets s 0 :beschuetzer-protect)) 2))
          "last night's choice is off the list")
      (testing "but everyone else still is, himself included"
        (is (contains? (set (phases/legal-targets s 0 :beschuetzer-protect)) 0))
        (is (contains? (set (phases/legal-targets s 0 :beschuetzer-protect)) 3))))))

;; --------------------------------------------------------- Bürgermeister ---
;;
;; A TITLE, not a role: nobody is dealt it. The village elects one on the first
;; day and it passes by appointment when he dies. His vote counts double, which
;; is the whole of its power.
;;
;; `game-with` suppresses the election by default — see its docstring — so these
;; pass {:election? true} to actually hold one.

(defn- quiet-table
  "A game that reaches the election with nothing happening on the way: no night
   roles at all, and no first-night kill."
  []
  (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :dorfbewohner 3 :dorfbewohner}
                {:first-night-kill? false :election? true}))

(deftest the-village-elects-a-buergermeister-on-the-first-day
  (let [s (quiet-table)]
    (is (= :election (:phase s)) "the first day opens with the election")
    (let [s (-> s
                (exec! {:type :elect :player 0 :target 2})
                (exec! {:type :elect :player 1 :target 2})
                (exec! {:type :elect :player 2 :target 2})
                (exec! {:type :elect :player 3 :target 0}))]
      (is (= 2 (:mayor s)))
      (is (= :discussion (:phase s)) "and the day proper begins")
      (is (pos? (tu/count-log s :election/resolved))))))

(deftest a-tied-election-elects-nobody
  (testing "consistent with the lynch, where a tie kills nobody: an arbitrary
            tiebreak is exactly what players find unfair"
    (let [s (-> (quiet-table)
                (exec! {:type :elect :player 0 :target 1})
                (exec! {:type :elect :player 1 :target 0})
                (exec! {:type :elect :player 2 :target 3})
                (exec! {:type :elect :player 3 :target 2}))]
      (is (nil? (:mayor s)))
      (is (= :discussion (:phase s)) "the day still begins"))))

(deftest abstaining-in-the-election-is-a-real-answer
  (let [s (-> (quiet-table)
              (exec! {:type :elect :player 0 :target 3})
              (exec! {:type :elect-abstain :player 1})
              (exec! {:type :elect-abstain :player 2})
              (exec! {:type :elect-abstain :player 3}))]
    (is (= 3 (:mayor s)) "one real ballot decides it")))

(deftest the-buergermeister-votes-twice
  (testing "the entire power of the office, and the reason a tie is rarer with
            one than without"
    (let [s (-> (quiet-table)
                (exec! {:type :elect :player 0 :target 0})
                (exec! {:type :elect-abstain :player 1})
                (exec! {:type :elect-abstain :player 2})
                (exec! {:type :elect-abstain :player 3}))
          _ (is (= 0 (:mayor s)))
          s (tu/advance-to-vote s)
          ;; Four living hold five votes, so it takes three. Two players
          ;; against player 1 would fall short; with the mayor among them it
          ;; is three.
          s (exec! s {:type :vote :player 0 :target 1})
          s (exec! s {:type :vote :player 1 :target 0})
          s (exec! s {:type :vote :player 2 :target 1})
          s (exec! s {:type :vote-abstain :player 3})]
      (is (= 3 (phases/votes-needed s)))
      (is (false? (get-in s [:players 1 :alive?]))
          "the mayor's two votes carry the majority"))))

(deftest a-tie-without-a-mayor-still-kills-nobody
  (testing "the control for the test above — same votes, no office"
    (let [s (-> (quiet-table)
                (exec! {:type :elect-abstain :player 0})
                (exec! {:type :elect-abstain :player 1})
                (exec! {:type :elect-abstain :player 2})
                (exec! {:type :elect-abstain :player 3}))
          _ (is (nil? (:mayor s)))
          s (tu/advance-to-vote s)
          s (exec! s {:type :vote :player 0 :target 1})
          s (exec! s {:type :vote :player 1 :target 0})
          s (exec! s {:type :vote-abstain :player 2})
          s (exec! s {:type :vote-abstain :player 3})]
      (is (true? (get-in s [:players 1 :alive?])))
      (is (true? (get-in s [:players 0 :alive?]))))))

(deftest the-office-empties-the-moment-its-holder-dies
  (testing "leaving :mayor pointing at a corpse would show the table a dead
            Bürgermeister and keep weighting a vote he can no longer cast"
    (let [s (-> (quiet-table)
                (exec! {:type :elect :player 0 :target 1})
                (exec! {:type :elect-abstain :player 1})
                (exec! {:type :elect-abstain :player 2})
                (exec! {:type :elect-abstain :player 3}))
          _ (is (= 1 (:mayor s)))
          s (tu/advance-to-vote s)
          s (reduce (fn [st pid] (exec! st {:type :vote :player pid :target 1}))
                    s [0 2 3])
          s (exec! s {:type :vote-abstain :player 1})]
      (is (false? (get-in s [:players 1 :alive?])) "the mayor was lynched")
      (is (not= 1 (:mayor s)) "and the office is not still his"))))

(defn- mayor-lynched
  "Elect player 2 and hang him. Deliberately a VILLAGER: player 1 is the only
   wolf, and lynching him would end the game outright — the win check runs before
   the reaction stack, so there would be no succession to observe."
  []
  (-> (quiet-table)
      (exec! {:type :elect :player 0 :target 2})
      (exec! {:type :elect-abstain :player 1})
      (exec! {:type :elect-abstain :player 2})
      (exec! {:type :elect-abstain :player 3})
      (tu/advance-to-vote)
      (as-> s (reduce (fn [st pid] (exec! st {:type :vote :player pid :target 2}))
                      s [0 1 3]))
      (exec! {:type :vote-abstain :player 2})))

(deftest a-dying-buergermeister-names-his-successor
  (let [s (mayor-lynched)]
    (testing "the succession is a reaction, drained by the same stack as the
              Jäger's shot — he acts while already dead"
      (is (false? (get-in s [:players 2 :alive?])))
      (is (= :reaction (:phase s)))
      (is (contains? (phases/legal-actions s 2) :mayor-appoint))
      (let [s (exec! s {:type :mayor-appoint :player 2 :target 3})]
        (is (= 3 (:mayor s)))
        (is (pos? (tu/count-log s :mayor/appointed)))))))

(deftest he-may-name-nobody
  (testing "declining is allowed for the same reason the Jäger may hold his
            fire: the clock lets him off anyway"
    (let [s (exec! (mayor-lynched) {:type :mayor-decline :player 2})]
      (is (nil? (:mayor s)))
      (is (pos? (tu/count-log s :mayor/vacated))))))

(deftest the-election-happens-once
  (testing ":election-held? rather than 'is it day 1' alone, so a GM who rewinds
            the phase cannot re-run it"
    (let [s (-> (quiet-table)
                (exec! {:type :elect :player 0 :target 2})
                (exec! {:type :elect-abstain :player 1})
                (exec! {:type :elect-abstain :player 2})
                (exec! {:type :elect-abstain :player 3}))
          s (exec! s {:type :gm/set-phase :player 0 :phase :day-reveal})]
      (is (not= :election (:phase s)) "back through dawn, but no second election")
      (is (= 2 (:mayor s)) "and the office is untouched"))))

;; ------------------------------------------------------ Weißer Werwolf ---
;;
;; Dealt as a werewolf and playing for nobody. He wakes with the pack, they cannot
;; eat him, the village does not win while he lives — and every second night he
;; removes one of them.

(defn- white-table
  "Wolf 1, white wolf 2, two villagers. Classic first night so the pack acts."
  []
  (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :weisser-werwolf 3 :dorfbewohner}))

(deftest he-sleeps-on-odd-nights
  (testing "every SECOND night: a wolf-killer who acted nightly would dismantle
            his own pack before the village noticed anything"
    (let [s (white-table)]
      (is (= 1 (:night s)))
      (is (not (contains? (set (phases/night-queue s)) :weisser-werwolf))
          "no turn on night one"))))

(deftest he-wakes-on-even-nights-and-may-only-take-a-wolf
  (let [s (white-table)
        ;; Night 1: the pack eats a villager, and he has no turn.
        s (exec! s {:type :werewolf-vote :player 1 :target 0})
        s (exec! s {:type :werewolf-vote :player 2 :target 0})
        s (tu/advance-to-vote s)
        s (reduce (fn [st pid] (exec! st {:type :vote-abstain :player pid}))
                  s (alive s))]
    (is (= :night (:phase s)))
    (is (= 2 (:night s)))
    (testing "now it is his night"
      (is (contains? (set (phases/night-queue s)) :weisser-werwolf)))
    (testing "and only werewolves are on his list — himself excepted"
      (let [targets (set (phases/legal-targets s 2 :weisser-kill))]
        (is (= #{1} targets)
            (str "expected only the other wolf, got " targets))))))

(deftest his-kill-is-not-the-hexes-to-undo
  (testing "her heal cancels a WEREWOLF death, and his is not one — a wolf torn
            apart by the white one is not something the village's witch gets to
            undo"
    (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :weisser-werwolf 3 :hexe})
          ;; Night one: the pack eats the villager. He has no turn on odd nights.
          s (exec! s {:type :werewolf-vote :player 1 :target 0})
          s (exec! s {:type :werewolf-vote :player 2 :target 0})
          s (exec! s {:type :hexe-pass :player 3})
          s (tu/advance-to-vote s)
          s (reduce (fn [st pid] (exec! st {:type :vote-abstain :player pid}))
                    s (alive s))
          _ (is (= 2 (:night s)) "reached the second night")
          ;; Night two: the pack hunts first — his turn is after theirs.
          s (exec! s {:type :werewolf-vote :player 1 :target 3})
          s (exec! s {:type :werewolf-vote :player 2 :target 3})
          ;; And now he turns on the pack.
          s (exec! s {:type :weisser-kill :player 2 :target 1})]
      (is (some #(and (= 1 (:player %)) (= :weisser (:cause %))) (:pending-deaths s))
          "queued under his own cause, not the pack's")
      (is (not-any? #(= 1 (:player %)) (phases/legal-targets s 3 :hexe-heal))
          "so the Hexe is not offered him")
      (testing "while the pack's own victim IS hers to save"
        (is (some #{3} (phases/legal-targets s 3 :hexe-heal)))))))

(deftest the-pack-cannot-eat-him
  (testing "he is one of them as far as targeting goes — dealt :werewolf"
    (let [s (white-table)]
      (is (not (contains? (set (phases/legal-targets s 1 :werewolf-vote)) 2))))))

(deftest the-village-does-not-win-while-he-lives
  (let [s (white-table)
        ;; Lynch the ordinary wolf; the white one remains.
        s (exec! s {:type :werewolf-vote :player 1 :target 0})
        s (exec! s {:type :werewolf-vote :player 2 :target 0})
        s (tu/advance-to-vote s)
        s (reduce (fn [st pid] (exec! st {:type :vote :player pid :target 1}))
                  s (remove #{1} (alive s)))
        s (exec! s {:type :vote-abstain :player 1})]
    (is (false? (get-in s [:players 1 :alive?])))
    (is (nil? (:winner s)) "a wolf still walks, whatever colour he is")))

(deftest wolves-only-is-not-a-win-while-he-is-among-them
  (testing "he still owes them a betrayal, and calling the pack winners would rob
            him of the game he was playing for"
    (let [s (tu/game-with {0 :werwolf 1 :weisser-werwolf})]
      ;; A two-player table of nothing but wolves.
      (is (nil? (phases/winner s)) "the night is not over")
      (testing "and once he is alone, it is his"
        (let [s (assoc-in s [:players 0 :alive?] false)]
          (is (= :weisser-werwolf (phases/winner s))))))))

;; ----------------------------------------------------------------- Dieb ---
;;
;; Two extra cards go into the deck when he is dealt, and the two nobody receives
;; are what he chooses between on the first night. He acts before everyone.

(defn- thief-table []
  (-> (tu/game-with {0 :dieb 1 :werwolf 2 :dorfbewohner 3 :seherin})
      (assoc :centre [:werwolf :dorfbewohner])))

(deftest he-chooses-first-of-all
  (let [s (thief-table)]
    (is (= :dieb (:night-cursor s))
        "ahead of Amor and everyone else — he must know what he IS before anybody
         acts on it")))

(deftest he-is-offered-the-two-cards-nobody-was-dealt
  (let [s (thief-table)]
    (is (= [:dorfbewohner :werwolf] (phases/legal-choices s :dieb-take))
        "sorted, because this list crosses the wire")
    (testing "and both ways out are on the table"
      (is (contains? (phases/legal-actions s 0) :dieb-take))
      (is (contains? (phases/legal-actions s 0) :dieb-keep)))))

(deftest taking-a-card-changes-what-he-is
  (let [s (exec! (thief-table) {:type :dieb-take :player 0 :role :werwolf})]
    (is (= :werwolf (tu/role-of s 0)))
    (testing "and his own card goes back to the middle, replacing what he took"
      (is (= #{:dieb :dorfbewohner} (set (:centre s)))))
    (testing "so he is a wolf for every purpose that matters"
      (is (= :werewolf (engine/team-of-player s 0)))
      (is (not (contains? (set (phases/legal-targets s 1 :werewolf-vote)) 0))
          "the pack cannot eat him now"))))

(deftest taking-a-card-brings-its-resources
  (let [s (-> (tu/game-with {0 :dieb 1 :werwolf 2 :dorfbewohner 3 :seherin})
              (assoc :centre [:hexe :dorfbewohner])
              (exec! {:type :dieb-take :player 0 :role :hexe}))]
    (is (= :hexe (tu/role-of s 0)))
    (is (= 1 (get-in s [:players 0 :resources :heiltrank])))
    (is (= 1 (get-in s [:players 0 :resources :gifttrank])))))

(deftest he-may-keep-his-own-card
  (testing "the Düsterwald clause forcing his hand when both cards are wolves
            cannot arise here — the centre is one wolf and one villager by
            construction — so declining is always available"
    (let [s (exec! (thief-table) {:type :dieb-keep :player 0})]
      (is (= :dieb (tu/role-of s 0)))
      (is (= [:werwolf :dorfbewohner] (:centre s)) "the middle is untouched")
      (is (not= :dieb (:night-cursor s)) "and his turn is over either way"))))

(deftest his-choice-is-never-in-the-log
  (testing "the log is inside every player's view, so an entry saying he took a
            card names the Dieb as surely as one saying WHICH — and at a table the
            narrator wakes him in silence"
    (doseq [cmd [{:type :dieb-take :player 0 :role :werwolf}
                 {:type :dieb-keep :player 0}]]
      (let [s (exec! (thief-table) cmd)
            types (set (tu/log-types s))]
        (is (not (contains? types :dieb/took)) (str cmd))
        (is (not (contains? types :dieb/kept)) (str cmd))
        (is (not-any? #(= :dieb (:role %)) (:log s))
            "and no entry mentions the role at all")))))

(deftest nobody-else-sees-the-middle
  (testing ":centre is state, not something published — it reaches him only
            through his own :choices, which are built from the actions HE holds"
    (let [s (thief-table)]
      (is (nil? (:centre (engine/view-for s 1))) "no wolf sees it")
      (is (nil? (:centre (engine/view-for s 2))))
      (is (empty? (get-in (engine/view-for s 2) [:choices :dieb-take]))
          "and it is not smuggled in through somebody else's choices")
      (testing "while the Dieb himself is offered exactly the two cards"
        (is (= [:dorfbewohner :werwolf]
               (get-in (engine/view-for s 0) [:choices :dieb-take])))))))

(deftest a-card-not-in-the-middle-is-refused
  (testing "validated against legal-choices rather than against anything the
            client sends — a hand-crafted frame must not conjure a role"
    (let [[_ err] (tu/try-exec (thief-table)
                               {:type :dieb-take :player 0 :role :seherin})]
      (is (= :invalid-choice (:error err))))))
