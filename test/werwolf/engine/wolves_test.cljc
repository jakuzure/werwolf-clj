(ns werwolf.engine.wolves-test
  "The pack's vote: open until the wolves agree, and no victim while they do
   not. The Düsterwald rule, chosen after a playtest in which two human wolves
   could not change their minds after seeing each other's pick, so the night
   kept ending on a tiebreak nobody had asked for."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [werwolf.engine.events :as e]
            [werwolf.engine.phases :as phases]
            [werwolf.engine.view :as view]
            [werwolf.test-util :as tu :refer [exec! alive]]))

(def ^:private two-wolves
  {0 :dorfbewohner 1 :werwolf 2 :werwolf 3 :dorfbewohner 4 :dorfbewohner})

(defn- chose
  "Whom the pack chose tonight, or nil. Never in the log: the victim is a
   secret until dawn."
  [state]
  (some->> (get-in state [:night-actions :werewolf-target]) (hash-map :target)))

(defn- quiet? [state] (pos? (tu/count-log state :night/quiet)))

(deftest a-wolf-may-change-his-vote-until-the-pack-agrees
  (let [s (tu/game-with two-wolves)
        _ (is (= :werwolf (:night-cursor s)))
        s (exec! s {:type :werewolf-vote :player 1 :target 0})]
    (is (contains? (phases/legal-actions s 1) :werewolf-vote)
        "a vote cast is still open to change")
    (is (= [0 3 4] (phases/legal-targets s 1 :werewolf-vote))
        "with every villager still on the table")
    (let [s (exec! s {:type :werewolf-vote :player 1 :target 4})]
      (is (= {1 4} (get-in (view/view-for s 2) [:night-info :werewolf-votes]))
          "the pack sees where his vote stands now, not where it was")
      (is (= {1 4} (get-in (view/view-for s 1) [:night-info :werewolf-votes]))
          "and so does he"))))

(deftest the-turn-stays-open-while-the-pack-is-split
  (let [s (tu/game-with two-wolves)
        s (exec! s {:type :werewolf-vote :player 1 :target 0})
        s (exec! s {:type :werewolf-vote :player 2 :target 4})]
    (is (= :werwolf (:night-cursor s)) "both have voted, and the night waits")
    (is (nil? (chose s)) "nobody has been chosen")
    (is (empty? (:pending-deaths s)))
    (is (every? #(contains? (phases/legal-actions s %) :werewolf-vote) [1 2])
        "either of them may still come round")
    (testing "and ends the moment they agree"
      (let [s (exec! s {:type :werewolf-vote :player 2 :target 0})]
        (is (not= :werwolf (:night-cursor s)))
        (is (= 0 (:target (chose s))))
        ;; Nobody wakes after the wolves in this cast, so the kill is applied
        ;; at the dawn that follows at once.
        (is (false? (get-in s [:players 0 :alive?])))))))

(deftest a-split-pack-that-runs-out-of-time-eats-nobody
  (testing "the Spielleiter never picks for the wolves: when they cannot agree
            there is no victim, and the morning says the night was quiet"
    (let [s (tu/game-with two-wolves)
          s (exec! s {:type :werewolf-vote :player 1 :target 0})
          s (exec! s {:type :werewolf-vote :player 2 :target 4})
          evs (phases/timeout-events s)]
      (is (= [:phase/timed-out :night/role-completed] (mapv :type evs)))
      (is (= :werwolf (:role (second evs))))
      (let [[s' _] (phases/settle (e/apply-all s evs))]
        (is (= (alive s) (alive s')) "nobody died")
        (is (quiet? s') "and the Chronik says so")
        (is (= :discussion (:phase s')))))))

(deftest a-pack-that-agreed-still-eats-when-time-runs-out
  (testing "two of three wolves name the same victim and the third says
            nothing: silence is not dissent, so the pack had agreed"
    (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :werwolf 3 :werwolf
                           4 :dorfbewohner 5 :dorfbewohner 6 :dorfbewohner})
          s (exec! s {:type :werewolf-vote :player 1 :target 0})
          s (exec! s {:type :werewolf-vote :player 2 :target 0})
          _ (is (= :werwolf (:night-cursor s)) "still waiting on the third")
          evs (phases/timeout-events s)]
      (is (= [:phase/timed-out :night/role-completed :werewolf/chose] (mapv :type evs)))
      (is (= 0 (:target (last evs))))
      (let [[s' _] (phases/settle (e/apply-all s evs))]
        (is (false? (get-in s' [:players 0 :alive?])))))))

(deftest the-spielleiter-ending-a-split-packs-turn-kills-nobody
  (let [table {1 :werwolf 2 :werwolf 3 :dorfbewohner 4 :dorfbewohner 5 :dorfbewohner}
        split (fn []
                (-> (tu/narrated-game-with table)
                    (exec! {:type :werewolf-vote :player 1 :target 3})
                    (exec! {:type :werewolf-vote :player 2 :target 4})))]
    ;; A narrated room has no clock, so the morning waits for the
    ;; Spielleiter's word as well (phases/paced?): `advance!` wakes the village.
    (testing "skipping the role"
      (let [s (split)
            s (exec! s {:type :gm/skip-role :player 0 :role :werwolf})
            _ (is (nil? (chose s)))
            s (tu/advance! s)]
        (is (= [1 2 3 4 5] (alive s)))
        (is (quiet? s))))
    (testing "and ending the turn as the clock would, in a room without one"
      (let [s (split)
            _ (is (contains? (phases/legal-actions s 0) :advance-phase))
            s (exec! s {:type :advance-phase :player 0})
            _ (is (nil? (chose s)))
            s (tu/advance! s)]
        (is (= [1 2 3 4 5] (alive s)))
        (is (quiet? s))))))

(deftest the-white-wolfs-vote-counts-towards-the-agreement
  (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :weisser-werwolf
                         3 :dorfbewohner 4 :dorfbewohner})
        s (exec! s {:type :werewolf-vote :player 1 :target 0})
        s (exec! s {:type :werewolf-vote :player 2 :target 3})]
    (is (= :werwolf (:night-cursor s)) "he hunts with them, so his dissent holds the pack")
    (let [s (exec! s {:type :werewolf-vote :player 2 :target 0})]
      (is (= 0 (:target (chose s))))
      (is (not= :werwolf (:night-cursor s))))))

(deftest the-hexe-has-nobody-to-heal-after-a-split-night
  (let [s (tu/game-with {0 :dorfbewohner 1 :werwolf 2 :werwolf 3 :hexe 4 :dorfbewohner})
        s (exec! s {:type :werewolf-vote :player 1 :target 0})
        s (exec! s {:type :werewolf-vote :player 2 :target 4})
        [s _] (phases/settle (e/apply-all s (phases/timeout-events s)))]
    (is (= :hexe (:night-cursor s)) "her turn still comes")
    (is (not (contains? (phases/legal-actions s 3) :hexe-heal)) "with nobody to heal")
    (is (contains? (phases/legal-actions s 3) :hexe-poison))
    (is (empty? (get-in (view/view-for s 3) [:night-info :pending-deaths])))
    (let [s (exec! s {:type :hexe-pass :player 3})]
      (is (quiet? s))
      (is (= [0 1 2 3 4] (alive s))))))

(deftest the-narrator-waits-on-the-whole-pack-until-it-agrees
  (testing "a wolf who has voted is still on the list: his vote stands, and
            the night waits on the pack as one, so the list says who the
            Spielleiter would be cutting short"
    (let [s (tu/narrated-game-with {1 :werwolf 2 :werwolf 3 :dorfbewohner
                                    4 :dorfbewohner 5 :dorfbewohner})
          _ (is (= [1 2] (phases/owing s)))
          s (exec! s {:type :werewolf-vote :player 1 :target 3})]
      (is (= [1 2] (phases/owing s)))
      (let [s (exec! s {:type :werewolf-vote :player 2 :target 4})]
        (is (= [1 2] (phases/owing s)) "split: both may still come round")))))
