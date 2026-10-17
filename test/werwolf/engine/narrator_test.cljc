(ns werwolf.engine.narrator-test
  "The Spielleiter: a seat in the room that is not a seat at the table.

   Narrated mode puts the host outside the game — dealt nothing, seeing every
   card, running the night by hand and, by default, without a clock. Each of
   those is a place an ordinary player rule could reach him by accident: a deal
   that counts him, a ballot that lists him, a win check that keeps the village
   alive because he is. These pin each one by name; the simulation's narrated
   run covers the breadth."
  (:require [clojure.test :refer [deftest is testing]]
            [werwolf.engine.core :as engine]
            [werwolf.engine.events :as e]
            [werwolf.engine.phases :as phases]
            [werwolf.engine.roles :as roles]
            [werwolf.engine.schema :as schema]
            [werwolf.engine.view :as view]
            [werwolf.test-util :as tu :refer [exec! try-exec]]))

(def ^:private cast-of-5
  "Seats 1..5. Seat 0 is the Spielleiter and is deliberately absent."
  {1 :dorfbewohner 2 :werwolf 3 :hexe 4 :seherin 5 :dorfbewohner})

;; ------------------------------------------------------------ the deal ---

(deftest the-narrator-is-dealt-nothing-and-the-deck-fits-the-table
  (let [s (tu/narrated-lobby 5)
        s (exec! s {:type :start-game :player 0})]
    (is (= :setup (:phase s)) "a narrated game waits for its Spielleiter")
    (is (zero? (:night s)) "and the night has not fallen")
    (is (nil? (get-in s [:players 0 :role])) "the narrator holds no card")
    (is (= (sort (roles/compose-roles 5))
           (sort (keep (fn [[id p]] (when (pos? id) (:role p))) (:players s))))
        "five players, a five-player deck — he is not a sixth")
    (is (= {:type :game/started :players 5 :narrated? true}
           (first (filter #(= :game/started (:type %)) (:log s)))))
    (is (schema/valid-state? s) (pr-str (schema/explain-state s)))))

(deftest the-start-threshold-counts-players-not-the-narrator
  (testing "three PLAYERS is the deck's floor; a narrator and two is a wolf, his
            dinner and somebody watching"
    (let [s (tu/narrated-lobby 2)]
      (is (not (contains? (phases/legal-actions s 0) :start-game)))
      (let [s (e/apply-event s {:type :player/joined :player 3 :name "P3" :locale :de})]
        (is (contains? (phases/legal-actions s 0) :start-game))))))

(deftest the-room-limit-counts-players-not-the-narrator
  (let [s (tu/narrated-lobby 4 {:max-players 4})]
    (is (= 5 (count (:players s))) "four players and their narrator are seated")
    (is (= :room-full (:error (engine/execute s {:type :join :player 9 :name "Late"}))))
    (testing "and in a room the host plays in, the same seats are one too many"
      (let [s (assoc s :narrated? false)]
        (is (= :room-full (:error (engine/execute s {:type :join :player 9 :name "Late"}))))))))

(deftest an-unnarrated-room-is-the-room-it-always-was
  (testing "a log written before the Spielleiter existed must replay into the
            same state — the flags are absent, not false"
    (let [s (engine/new-game {:room-id "R" :host 0 :seed 1})]
      (is (not (contains? s :narrated?)))
      (is (not (contains? s :timer?)))
      (is (nil? (e/narrator s)))
      (is (phases/timed? s)))))

;; ------------------------------------------------------- what he sees ---

(deftest the-narrator-sees-every-role-and-nobody-else-does
  (doseq [[label s] [["in setup" (tu/narrated-setup-with cast-of-5)]
                     ["at night" (tu/narrated-game-with cast-of-5)]]]
    (testing label
      (is (= cast-of-5 (:known-roles (view/view-for s 0))))
      (is (true? (get-in (view/view-for s 0) [:me :narrator?])))
      (is (= {1 :dorfbewohner} (:known-roles (view/view-for s 1)))
          "a villager still knows exactly himself")
      (is (= 0 (:narrator (view/view-for s 1))) "who narrates is public")
      (doseq [pid (range 6)]
        (is (nil? (engine/redaction-leaks? s pid)) (str "seat " pid))))))

(deftest setup-shows-a-player-his-own-card-and-nothing-it-implies
  (testing "the deal is not final until the night falls — a wolf who met the
            pack during setup and was then made a villager would carry their
            names into the game"
    (let [cast {1 :werwolf 2 :werwolf 3 :seherin 4 :dorfbewohner 5 :dorfbewohner}
          s (tu/narrated-setup-with cast)]
      (is (= {1 :werwolf} (:known-roles (view/view-for s 1))))
      (let [s (exec! s {:type :begin-night :player 0})]
        (is (= {1 :werwolf 2 :werwolf} (:known-roles (view/view-for s 1)))
            "and the pack meets once the night has fallen")))))

(deftest the-narrator-sees-the-night-as-it-happens
  (let [s (tu/narrated-game-with cast-of-5)
        s (exec! s {:type :seherin-inspect :player 4 :target 2})
        s (exec! s {:type :werewolf-vote :player 2 :target 5})
        sl (:night-info (view/view-for s 0))]
    (is (= {2 5} (:werewolf-votes sl)))
    (is (= [{:player 5 :cause :werwolf}] (:pending-deaths sl)))
    (testing "while a villager sees none of it"
      (is (= {} (:night-info (view/view-for s 1))))
      (is (nil? (:narrator-info (view/view-for s 1)))))))

(deftest the-narrator-knows-the-lovers
  (let [cast {1 :amor 2 :werwolf 3 :seherin 4 :dorfbewohner 5 :dorfbewohner}
        s (tu/narrated-game-with cast)
        s (exec! s {:type :amor-link :player 1 :a 3 :b 5})]
    (is (= [3 5] (get-in (view/view-for s 0) [:narrator-info :lovers])))
    (is (nil? (engine/redaction-leaks? s 0)))))

;; ----------------------------------------------- out of every rule's way ---

(deftest the-narrator-is-never-a-target-or-a-voter
  (let [s (tu/narrated-game-with cast-of-5)]
    (testing "at night"
      (is (= #{:chat :set-locale :leave} (set (remove (phases/host-actions s 0)
                                                      (phases/legal-actions s 0))))
          "his voice, his language, the door — and nothing the game counts")
      (doseq [pid (range 1 6), a (phases/legal-actions s pid)]
        (is (not (some #{0} (phases/legal-targets s pid a))) (str pid " " a))))
    (testing "and by day, nobody waits on him"
      (let [s (-> s
                  (exec! {:type :seherin-inspect :player 4 :target 2})
                  (exec! {:type :werewolf-vote :player 2 :target 5})
                  (exec! {:type :hexe-pass :player 3}))
            _ (is (= :discussion (:phase s)))
            _ (is (not (contains? (phases/legal-actions s 0) :vote)))
            _ (doseq [pid (engine/living-ids s)]
                (is (not (some #{0} (phases/legal-targets s pid :vote)))))
            s (reduce #(exec! %1 {:type :vote-abstain :player %2}) s (engine/living-ids s))]
        (is (not= :discussion (:phase s))
            "every PLAYER voted, and that was enough")))))

(deftest the-gm-cannot-kill-or-recast-the-narrator
  (let [s (tu/narrated-game-with cast-of-5)]
    (is (= :invalid-target
           (:error (second (try-exec s {:type :gm/force-kill :player 0 :target 0})))))
    (is (= :invalid-target
           (:error (second (try-exec s {:type :gm/reassign-role :player 0
                                        :target 0 :role :werwolf})))))))

(deftest the-win-check-ignores-the-narrator
  (testing "the village wins when the last wolf dies, though the narrator — who
            is not a wolf, and not a villager either — is still in the room"
    (let [s (exec! (tu/narrated-game-with cast-of-5)
                   {:type :gm/force-kill :player 0 :target 2})]
      (is (= :village (:winner s)))))
  (testing "and the wolves win when nobody is left to eat; the narrator does
            not keep the village alive"
    (let [s (reduce #(exec! %1 {:type :gm/force-kill :player 0 :target %2})
                    (tu/narrated-game-with {1 :dorfbewohner 2 :werwolf 3 :dorfbewohner})
                    [1 3])]
      (is (= :werewolf (:winner s))))))

;; ------------------------------------------------------------- setup ---

(deftest the-narrator-rearranges-the-deal-in-silence
  (let [s (tu/narrated-setup-with cast-of-5)
        log-before (:log s)
        s (exec! s {:type :gm/reassign-role :player 0 :target 1 :role :hexe})]
    (is (= :hexe (tu/role-of s 1)))
    (is (= {:heiltrank 1 :gifttrank 1} (get-in s [:players 1 :resources]))
        "a freshly-made Hexe has her potions")
    (is (= log-before (:log s))
        "nothing public: there is no game yet to audit, and a list of the seats
         he touched would be a map of the deal")
    (testing "a Dieb brings his two spare cards with him, and takes them away"
      (is (= [] (:centre s)))
      (let [s (exec! s {:type :gm/reassign-role :player 0 :target 5 :role :dieb})]
        (is (= roles/centre-extras (:centre s)))
        (is (= (vec (sort roles/centre-extras))
               (phases/legal-choices s :dieb-take)))
        (let [s (exec! s {:type :gm/reassign-role :player 0 :target 5 :role :dorfbewohner})]
          (is (= [] (:centre s))))))
    (testing "only the Spielleiter deals"
      (is (= :not-host (:error (second (try-exec s {:type :gm/reassign-role :player 1
                                                    :target 2 :role :hexe}))))))))

(deftest a-dealt-table-is-not-a-decided-one
  (testing "while he is arranging the cards, a table he has momentarily left
            without a wolf has not been won by the village"
    (let [s (tu/narrated-setup-with cast-of-5)
          s (exec! s {:type :gm/reassign-role :player 0 :target 2 :role :dorfbewohner})]
      (is (= :setup (:phase s)))
      (is (nil? (:winner s))))))

(deftest the-narrator-begins-the-night
  (let [s (tu/narrated-setup-with cast-of-5)]
    (is (= #{:begin-night :gm/reassign-role :gm/toggle-timer}
           (phases/host-actions s 0))
        "and nothing that presumes a game in progress")
    (testing "and nobody else may"
      (is (= :not-allowed (:error (second (try-exec s {:type :begin-night :player 1}))))))
    (testing "no killing, reviving or phase-jumping before the first night"
      (is (= :no-game-in-progress
             (:error (second (try-exec s {:type :gm/force-kill :player 0 :target 1})))))
      (is (= :no-game-in-progress
             (:error (second (try-exec s {:type :gm/set-phase :player 0 :phase :vote}))))))
    (let [s (exec! s {:type :begin-night :player 0})]
      (is (= :night (:phase s)))
      (is (= 1 (:night s)))
      (is (some? (:night-cursor s)) "the night is running")
      (is (= :not-allowed (:error (second (try-exec s {:type :begin-night :player 0}))))
          "once"))))

(deftest an-unnarrated-game-still-goes-straight-to-night
  (let [s (e/replay (into [{:type :game/created :room-id "R" :host 0 :seed 1}]
                          (map (fn [i] {:type :player/joined :player i
                                        :name (str "P" i) :locale :de}))
                          (range 5)))
        s (exec! s {:type :start-game :player 0})]
    (is (= :night (:phase s)))
    (is (some? (tu/role-of s 0)) "the host plays")))

;; ------------------------------------------------- the table itself ---

(deftest the-host-hands-over-the-seat-in-the-lobby
  (let [s (tu/narrated-lobby 4)]
    (is (= [1 2 3 4] (phases/legal-targets s 0 :gm/transfer-host)))
    (let [s (exec! s {:type :gm/transfer-host :player 0 :target 2})]
      (is (= 2 (:host s)))
      (is (= 2 (e/narrator s)) "the Spielleitung moves with the seat")
      (is (= {:type :host/changed :host 2} (peek (:log s))) "and the table is told")
      (testing "the old host is an ordinary player now"
        (is (not (contains? (phases/legal-actions s 0) :gm/transfer-host)))
        (is (= :not-allowed (:error (second (try-exec s {:type :gm/transfer-host
                                                         :player 0 :target 1}))))))
      (testing "and the deal counts him in and the new narrator out"
        (let [s (exec! s {:type :start-game :player 2})]
          (is (some? (tu/role-of s 0)))
          (is (nil? (tu/role-of s 2))))))
    (testing "not to himself, and not once the cards are dealt"
      (is (= :invalid-target (:error (second (try-exec s {:type :gm/transfer-host
                                                          :player 0 :target 0})))))
      (let [s (exec! s {:type :start-game :player 0})]
        (is (not (contains? (phases/legal-actions s 0) :gm/transfer-host)))))))

(deftest narrating-is-a-lobby-switch-and-the-clock-follows-it
  (let [s (tu/narrated-lobby 4)
        s (exec! s {:type :gm/toggle-narrated :player 0})]
    (is (false? (:narrated? s)))
    (is (true? (phases/timed? s)) "a table without a narrator needs the clock")
    (is (= {:type :game/narrated-set :narrated? false} (peek (:log s))))
    (let [s (exec! s {:type :gm/toggle-narrated :player 0})]
      (is (true? (:narrated? s)))
      (is (false? (phases/timed? s))))
    (testing "only in the lobby, and only by the host"
      (is (= :not-allowed (:error (second (try-exec s {:type :gm/toggle-narrated :player 1})))))
      (let [s (exec! s {:type :start-game :player 0})]
        (is (= :not-allowed (:error (second (try-exec s {:type :gm/toggle-narrated
                                                         :player 0})))))))))

;; -------------------------------------------------------- no clock ---

(deftest the-clock-is-a-switch-in-and-out-of-the-game
  (let [s (tu/narrated-game-with cast-of-5)
        _ (is (false? (phases/timed? s)))
        s (exec! s {:type :gm/toggle-timer :player 0})]
    (is (true? (phases/timed? s)))
    (is (= {:type :clock/timer-set :on? true} (peek (:log s))))
    (testing "a pause only means something while there is a clock"
      (is (contains? (phases/legal-actions s 0) :gm/pause))
      (let [s (exec! s {:type :gm/toggle-timer :player 0})]
        (is (not (contains? (phases/legal-actions s 0) :gm/pause)))))))

(deftest without-a-clock-the-narrator-can-end-every-phase-the-clock-would-have
  (testing "every phase only the clock used to end now has a button, or one
            absent player holds the whole table forever"
    (testing "the night: he ends a turn exactly as the timeout would, so a pack
              that half-voted still eats"
      (let [s (tu/narrated-game-with cast-of-5)
            s (exec! s {:type :seherin-inspect :player 4 :target 2})
            _ (is (= :werwolf (:night-cursor s)))
            s (exec! s {:type :advance-phase :player 0})]
        (is (not= :werwolf (:night-cursor s)))
        (is (= {:type :gm/forced :actor 0 :action :advance-phase :detail {:phase :night}}
               (first (filter #(= :gm/forced (:type %)) (:log s))))
            "and it says the Spielleiter did it, not the clock")
        (is (zero? (tu/count-log s :phase/timed-out)))))
    (testing "the election"
      (let [s (tu/narrated-game-with cast-of-5 {:first-night-kill? false :election? true})
            ;; Nobody acts on a peaceful first night with a passive Hexe.
            s (exec! s {:type :seherin-inspect :player 4 :target 2})
            s (exec! s {:type :hexe-pass :player 3})]
        (is (= :election (:phase s)))
        (let [s (exec! s {:type :elect :player 1 :target 4})
              s (exec! s {:type :advance-phase :player 0})]
          (is (= :discussion (:phase s)))
          (is (= 4 (:mayor s)) "the ballots that WERE cast still count"))))
    (testing "a Jäger who never fires"
      (let [s (tu/narrated-game-with {1 :jaeger 2 :werwolf 3 :dorfbewohner
                                      4 :dorfbewohner 5 :dorfbewohner})
            s (exec! s {:type :werewolf-vote :player 2 :target 1})]
        (is (= :reaction (:phase s)))
        (is (contains? (phases/legal-actions s 0) :advance-phase))
        (let [s (exec! s {:type :advance-phase :player 0})]
          (is (not= :reaction (:phase s)))
          (is (empty? (:reactions s))))))
    (testing "and with the clock on, those phases are the clock's again"
      (let [s (-> (tu/narrated-game-with cast-of-5)
                  (exec! {:type :gm/toggle-timer :player 0}))]
        (is (not (contains? (phases/legal-actions s 0) :advance-phase)))))))
