(ns werwolf.engine.redaction-test
  "The tests that must never be allowed to fail.

   A leak here is not a cosmetic bug — it hands a player the whole game. The
   property test at the bottom drives `redaction-leaks?` over hundreds of
   randomised game states; the explicit tests above it pin the specific rules so
   a failure says WHICH rule broke."
  (:require [clojure.test :refer [deftest is testing]]
            [werwolf.engine.core :as engine]
            [werwolf.engine.view :as view]
            [werwolf.sim.harness :as h]
            [werwolf.test-util :as tu :refer [exec!]]))

(def cast-of-7
  {0 :dorfbewohner 1 :seherin 2 :werwolf 3 :hexe 4 :jaeger 5 :amor 6 :werwolf})

(deftest villager-learns-nothing
  (let [s (tu/game-with cast-of-7)
        v (view/view-for s 0)]
    (is (= {0 :dorfbewohner} (:known-roles v))
        "a plain villager knows only their own role")
    (is (nil? (engine/redaction-leaks? s 0)))
    (testing "and no other role appears anywhere in the payload"
      (is (empty? (view/foreign-roles-in-view v 0))))))

(deftest werewolves-know-each-other-but-nothing-else
  (let [s (tu/game-with cast-of-7)
        v (view/view-for s 2)]
    (is (= {2 :werwolf 6 :werwolf} (:known-roles v)))
    (is (not (contains? (:known-roles v) 1)) "the wolf does not see the Seherin")
    (is (nil? (engine/redaction-leaks? s 2)))))

(deftest seherin-learns-exactly-one-role-per-inspection
  (let [s (tu/game-with cast-of-7)
        _ (is (= {1 :seherin} (:known-roles (view/view-for s 1))))
        ;; Amor is first in the night order, so clear her turn first.
        s (exec! s {:type :amor-link :player 5 :a 0 :b 4})
        s (exec! s {:type :seherin-inspect :player 1 :target 2})
        v (view/view-for s 1)]
    (is (= {1 :seherin 2 :werwolf} (:known-roles v))
        "her own role plus precisely the one she inspected")
    (is (nil? (engine/redaction-leaks? s 1)))))

(deftest lovers-know-each-other-existence-is-public-identity-is-not
  (let [s (tu/game-with cast-of-7)
        s (exec! s {:type :amor-link :player 5 :a 0 :b 4})]
    (testing "the two lovers each learn the other"
      (is (contains? (:known-roles (view/view-for s 0)) 4))
      (is (contains? (:known-roles (view/view-for s 4)) 0))
      (is (= 4 (get-in (view/view-for s 0) [:me :lover]))))
    (testing "an uninvolved player sees only THAT lovers exist"
      (let [v (view/view-for s 3)]
        (is (true? (:lovers-exist? v)))
        (is (nil? (get-in v [:me :lover])))
        (is (not (contains? (:known-roles v) 0)))
        (is (nil? (engine/redaction-leaks? s 3)))))))

(deftest death-reveals-the-dead-players-role-to-everyone
  (let [s (tu/game-with cast-of-7)
        s (exec! s {:type :gm/force-kill :player 0 :target 3})]
    (is (= :hexe (get-in (view/view-for s 0) [:known-roles 3]))
        "the log publishes the role of the departed")
    (is (nil? (engine/redaction-leaks? s 0)))))

(deftest dead-players-get-no-ghost-view-by-default
  (testing "otherwise a chat-routing bug becomes catastrophic"
    (let [s (tu/game-with cast-of-7)
          s (exec! s {:type :gm/force-kill :player 0 :target 0})
          v (view/view-for s 0)]
      (is (not (contains? (:known-roles v) 2))
          "the dead host still cannot see the werewolf")
      (is (nil? (engine/redaction-leaks? s 0))))))

(deftest game-over-reveals-everything
  (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :dorfbewohner 3 :dorfbewohner 4 :dorfbewohner})
        s (exec! s {:type :gm/force-kill :player 0 :target 1})]
    (is (= :game-over (:phase s)))
    (is (= 5 (count (:known-roles (view/view-for s 0))))
        "all roles are public once the game is decided")
    (is (nil? (engine/redaction-leaks? s 0)))))

(deftest hexe-sees-pending-victim-only-on-her-turn
  (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :jaeger})
        s (exec! s {:type :seherin-inspect :player 3 :target 1})
        s (exec! s {:type :werewolf-vote :player 1 :target 0})]
    (is (= :hexe (:night-cursor s)))
    (is (= [{:player 0 :cause :werwolf}]
           (get-in (view/view-for s 2) [:night-info :pending-deaths]))
        "the Hexe must see who is dying — it is her entire decision")
    (testing "nobody else sees the pending kill"
      (is (nil? (get-in (view/view-for s 0) [:night-info :pending-deaths])))
      (is (nil? (get-in (view/view-for s 3) [:night-info :pending-deaths])))
      (is (nil? (engine/redaction-leaks? s 0)))
      (is (nil? (engine/redaction-leaks? s 3))))))

(deftest werewolf-votes-visible-only-to-wolves
  (let [s (tu/game-with cast-of-7)
        s (exec! s {:type :amor-link :player 5 :a 0 :b 4})
        s (exec! s {:type :seherin-inspect :player 1 :target 3})
        s (exec! s {:type :werewolf-vote :player 2 :target 0})]
    (is (seq (get-in (view/view-for s 2) [:night-info :werewolf-votes]))
        "wolves coordinate openly among themselves")
    (is (nil? (get-in (view/view-for s 1) [:night-info :werewolf-votes]))
        "the Seherin sees no part of it")
    (is (nil? (get-in (view/view-for s 0) [:night-info :werewolf-votes])))))

;; ------------------------------------------------------ leak detection ---

(deftest leak-detector-actually-fires
  (testing "a detector that never fails is worthless — plant leaks and confirm"
    (is (= #{[2 :werwolf]} (view/foreign-roles-in-view {:x {2 :werwolf}} 0))
        "map of id->role")
    (is (= #{[3 :hexe]} (view/foreign-roles-in-view {:x {:player 3 :role :hexe}} 0))
        "map with :player/:role")
    (is (= #{[4 :seherin]} (view/foreign-roles-in-view {:x {:id 4 :role :seherin}} 0))
        "map with :id/:role")
    (is (= #{[5 :jaeger]} (view/foreign-roles-in-view {:x [[5 :jaeger]]} 0))
        "vector of tuples")
    (is (= #{[7 :werwolf]} (view/foreign-roles-in-view {:a {:b [{:c {7 :werwolf}}]}} 0))
        "deeply nested")
    (is (= #{[6 :hexe]} (view/foreign-roles-in-view {:x {:target 6 :role :hexe}} 0))
        "map with :target/:role — the shape a GM override's :detail takes, and
         that detail is published to the log")
    (is (empty? (view/foreign-roles-in-view {:me {:id 0 :role :werwolf}} 0))
        "the viewer's own role is not a leak")))

;; ------------------------------------------------------- the GM's overrides ---

(deftest reassigning-a-role-tells-the-table-nothing-about-it
  (testing "the log is in every player's view, so the one override that changes
            who somebody IS must not name the card it dealt them. That an
            override happened is public and auditable; what it did is not"
    (let [s (tu/game-with cast-of-7)
          s (exec! s {:type :gm/reassign-role :player 0 :target 4 :role :werwolf})
          entry (last (:log s))]
      (is (= :gm/forced (:type entry)))
      (is (= {:target 4} (:detail entry)) "the seat, never the role")
      (testing "so an ordinary villager still knows only their own role"
        (let [v (view/view-for s 1)]
          (is (not (contains? (:known-roles v) 4)))
          (is (empty? (view/foreign-roles-in-view v 1)))
          (is (nil? (engine/redaction-leaks? s 1)))))
      (testing "while the wolves do learn their new packmate — that is what
                being one means, and it comes from the role, not the log"
        (is (= :werwolf (get-in (view/view-for s 2) [:known-roles 4])))
        (is (nil? (engine/redaction-leaks? s 2))))
      (testing "and the player themself is simply told"
        (is (= :werwolf (get-in (view/view-for s 4) [:me :role]))))))

  (testing "a demoted wolf stops being visible to the pack"
    (let [s (tu/game-with cast-of-7)
          _ (is (= :werwolf (get-in (view/view-for s 2) [:known-roles 6])))
          s (exec! s {:type :gm/reassign-role :player 0 :target 6 :role :dorfbewohner})]
      (is (not (contains? (:known-roles (view/view-for s 2)) 6)))
      (is (nil? (engine/redaction-leaks? s 2))))))

;; ------------------------------------------------- the property that counts ---

(deftest no-view-ever-leaks-a-role-across-full-games
  (testing "redaction holds at EVERY state of many complete randomised games"
    (let [games (for [i (range 30)
                      :let [n (+ 5 (mod i 14))
                            seed (+ 3 (* i 4231))]]
                  (h/play-game n seed))
          bad (remove :ok? games)]
      (is (empty? bad)
          (str "simulation failures: "
               (pr-str (map #(select-keys % [:reason :seed :players :violations]) bad))))
      ;; check-invariants already asserts redaction after every event, so a
      ;; clean run here is a clean run for redaction across ~5000 states.
      (is (every? :ok? games)))))

(deftest the-pack-knows-him-but-not-what-he-is
  (testing "the Weißer Werwolf hunts with the wolves and shares their chat, so
            pretending they cannot see him would be transparent. What they must
            NOT learn is that he is the one who will turn on them — that asymmetry
            is the whole role."
    (let [s (tu/game-with {0 :werwolf 1 :weisser-werwolf 2 :dorfbewohner 3 :seherin})
          wolf-sees  (view/revealed-roles s 0)
          white-sees (view/revealed-roles s 1)]
      (testing "the ordinary wolf sees a wolf where the white one sits"
        (is (= :werwolf (get wolf-sees 1))
            (str "he was shown " (get wolf-sees 1))))
      (testing "while the white one sees the pack for what it is"
        (is (= :werwolf (get white-sees 0))))
      (testing "and neither of them learns anything about the village"
        (is (nil? (get wolf-sees 2)))
        (is (nil? (get wolf-sees 3)))
        (is (nil? (get white-sees 2)))
        (is (nil? (get white-sees 3))))
      (testing "the village learns nothing either way"
        (is (empty? (dissoc (view/revealed-roles s 2) 2)))))))
