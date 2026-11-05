(ns werwolf.engine.pacing-test
  "A room with a Spielleiter and no clock waits for him: each night role is
   called by him, the village wakes on his word, and the ballot closes when he
   says so. With bots at the table the phases flew past while he was still
   narrating; see `phases/paced?`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [werwolf.engine.events :as e]
            [werwolf.engine.phases :as phases]
            [werwolf.engine.view :as view]
            [werwolf.sim.harness :as h]
            [werwolf.test-util :as tu :refer [exec! advance! alive]]))

(def ^:private cast
  {1 :seherin 2 :werwolf 3 :hexe 4 :dorfbewohner 5 :dorfbewohner})

(defn- fallen
  "Night 1 has fallen in a paced room and nobody has been called yet."
  ([] (fallen {}))
  ([opts] (tu/narrated-game-with cast (assoc opts :called? false))))

(defn- next-step [s] (get-in (view/view-for s 0) [:narrator-info :next-step]))

(deftest the-night-waits-for-the-spielleiter-to-call-each-role
  (let [s (fallen)]
    (is (true? (phases/paced? s)))
    (is (= :night (:phase s)))
    (is (nil? (:night-cursor s)) "nobody is called until he calls them")
    (is (contains? (phases/legal-actions s 0) :advance-phase))
    (is (not (contains? (phases/legal-actions s 0) :gm/skip-role)) "nothing to skip yet")
    (is (= {:step :call :role :seherin} (phases/next-step s)))
    (is (= {:step :call :role :seherin} (next-step s)) "and his console is told so")
    (is (nil? (:narrator-info (view/view-for s 1))) "players are told nothing")
    (is (empty? (phases/owing s)) "nobody owes a move while the night waits on him")
    (testing "the call"
      (let [s (advance! s)]
        (is (= :seherin (:night-cursor s)))
        (is (zero? (tu/count-log s :gm/forced)) "a call is not an override")
        (is (= {:step :end-turn} (phases/next-step s)) "now his button cuts her short")
        (is (contains? (phases/legal-actions s 0) :gm/skip-role))
        (is (= [1] (phases/owing s)))
        (testing "her turn ends by itself, and the next call waits"
          (let [s (exec! s {:type :seherin-inspect :player 1 :target 2})]
            (is (nil? (:night-cursor s)))
            (is (= #{:seherin} (get-in s [:night-actions :completed])))
            (is (= {:step :call :role :werwolf} (phases/next-step s)))
            (is (not (contains? (phases/legal-actions s 2) :werewolf-vote))
                "the wolf is not awake until he is called")
            (let [s (advance! s)]
              (is (= :werwolf (:night-cursor s)))
              (is (contains? (phases/legal-actions s 2) :werewolf-vote)))))))))

(defn- morning-hold
  "Every role has acted; the night waits for him to wake the village."
  ([] (morning-hold {}))
  ([{:keys [heal? election?]}]
  (let [s (-> (fallen {:election? election?})
              advance!
              (exec! {:type :seherin-inspect :player 1 :target 2})
              advance!
              (exec! {:type :werewolf-vote :player 2 :target 4})
              advance!)]
    (-> (if heal? (exec! s {:type :hexe-heal :player 3 :target 4}) s)
        (exec! {:type :hexe-pass :player 3})))))

(deftest the-morning-waits-for-him-too
  (let [s (morning-hold)]
    (is (= :night (:phase s)) "every role has acted and it is still night")
    (is (nil? (:night-cursor s)))
    (is (empty? (phases/night-queue s)))
    (is (zero? (tu/count-log s :night/ended)))
    (is (= {:step :morning} (phases/next-step s)))
    (is (contains? (phases/legal-actions s 0) :advance-phase))
    (let [s (advance! s)]
      (is (= 1 (tu/count-log s :night/ended)))
      (is (zero? (tu/count-log s :gm/forced)) "waking the village is not an override")
      (is (= :discussion (:phase s)))
      (is (false? (get-in s [:players 4 :alive?])) "the night's death is applied at the dawn he calls")))
  (testing "a night nobody died in says so"
    (let [s (advance! (morning-hold {:heal? true}))]
      (is (= 1 (tu/count-log s :night/quiet)))
      (is (= [1 2 3 4 5] (alive s))))))

(deftest closing-the-vote-waits-for-him
  (let [s (advance! (morning-hold))
        _ (is (= :discussion (:phase s)))
        _ (is (= {:step :end-turn} (phases/next-step s)) "with ballots outstanding his button is the override")
        s (reduce #(exec! %1 {:type :vote :player %2 :target 5}) s [1 2 3])
        _ (is (= {:step :end-turn} (phases/next-step s)))
        s (exec! s {:type :vote-abstain :player 5})]
    (is (= :discussion (:phase s)) "everybody has voted and the day is still open")
    (is (= {:step :close-vote} (phases/next-step s)))
    (is (empty? (phases/owing s)))
    (is (contains? (phases/legal-actions s 0) :advance-phase))
    (let [s (advance! s)]
      (is (= :day-resolve (:phase s)))
      (is (= 5 (:condemned s)))
      (is (zero? (tu/count-log s :gm/forced)) "closing a ballot everybody cast is not an override")
      (is (= {:step :nightfall} (phases/next-step s)))
      (is (= :night (:phase (advance! s))))))
  (testing "and the first day's talk, where there is no ballot"
    (let [s (advance! (morning-hold))
          s (assoc s :first-day-trial? false)
          s (reduce #(exec! %1 {:type :ready :player %2}) s (alive s))]
      (is (= :discussion (:phase s)))
      (is (= {:step :close-day} (phases/next-step s)))
      (let [s (advance! s)]
        (is (= [:night 2] [(:phase s) (:night s)]))
        (is (zero? (tu/count-log s :gm/forced)))))))

(deftest the-election-resolves-by-itself
  (let [s (advance! (morning-hold {:election? true}))]
    (is (= :election (:phase s)))
    (let [s (reduce #(exec! %1 {:type :elect :player %2 :target 1}) s (alive s))]
      (is (= :discussion (:phase s)) "the election is not one of his steps")
      (is (= 1 (:mayor s))))))

(deftest with-a-clock-the-night-runs-itself
  (testing "switched on by him"
    (let [s (fallen)
          s (exec! s {:type :gm/toggle-timer :player 0})]
      (is (false? (phases/paced? s)))
      (is (= :seherin (:night-cursor s)) "the first role is called the moment the clock is on")
      (let [s (exec! s {:type :seherin-inspect :player 1 :target 2})]
        (is (= :werwolf (:night-cursor s)) "and the next without anybody pressing anything"))))
  (testing "switched on for him, because he went missing"
    (let [s (fallen)
          [s _] (phases/settle (e/apply-all s (phases/host-absent-events s)))]
      (is (true? (:host-absent? s)))
      (is (false? (phases/paced? s)))
      (is (= :seherin (:night-cursor s)))))
  (testing "a host who plays, with the clock off, is not pacing anybody"
    (let [s (tu/game-with {0 :dorfbewohner 1 :seherin 2 :werwolf 3 :hexe 4 :dorfbewohner})
          s (assoc s :timer? false)]
      (is (false? (phases/paced? s)))
      (is (nil? (phases/next-step (assoc s :night-cursor nil))))
      (is (= :seherin (:night-cursor (first (phases/settle (assoc s :night-cursor nil)))))))))

(deftest his-night-plan-reads-right-between-calls
  (let [s (-> (fallen) advance! (exec! {:type :seherin-inspect :player 1 :target 2}))
        state-of (fn [s pid] (into {} (map (juxt :role :state)) (view/night-plan s pid)))]
    (is (nil? (:night-cursor s)))
    (is (= {:seherin :done :werwolf :todo :hexe :todo} (state-of s 0))
        "the Seherin is done while he decides whom to call next")
    (is (= {:seherin :current :werwolf :todo :hexe :todo} (state-of (advance! (fallen)) 0)))
    (let [s (advance! s)]
      (is (= {:seherin :done :werwolf :current :hexe :todo} (state-of s 0))))
    (is (= [{:role :seherin} {:role :werwolf} {:role :hexe}] (view/night-plan s 1))
        "a player's list is the bare list, as before")))

(deftest skipping-the-pack-eats-what-the-clock-would
  (let [table {1 :werwolf 2 :werwolf 3 :hexe 4 :dorfbewohner 5 :dorfbewohner}
        at-pack (fn [] (let [s (tu/narrated-game-with table)]
                         (is (= :werwolf (:night-cursor s)))
                         s))]
    (testing "a pack that agreed, one packmate silent, still eats"
      (let [s (-> (at-pack) (exec! {:type :werewolf-vote :player 1 :target 4})
                  (exec! {:type :gm/skip-role :player 0 :role :werwolf}))]
        (is (= [{:player 4 :cause :werwolf}] (:pending-deaths s)))
        (is (= 1 (tu/count-log s :gm/forced)) "and the skip is logged as his")))
    (testing "a split pack eats nobody"
      (let [s (-> (at-pack) (exec! {:type :werewolf-vote :player 1 :target 4})
                  (exec! {:type :werewolf-vote :player 2 :target 5})
                  (exec! {:type :gm/skip-role :player 0 :role :werwolf}))]
        (is (empty? (:pending-deaths s)))
        (is (= #{:werwolf} (get-in s [:night-actions :completed])))))))

(deftest a-paced-game-replays-and-finishes
  (testing "the harness presses his button at every hold, and the log of a game
            so played folds back to its final state"
    (let [r (h/play-game 8 4711 {:narrated? true})]
      (is (:ok? r) (pr-str (select-keys r [:reason :phase :turns])))
      (is (nil? (h/replay-mismatch r)))
      (is (some #(= :advance-phase (:type %)) (:history r)))
      (is (some #(= :night/role-turn (:type %)) (:log r)) "the calls are in the log"))))
