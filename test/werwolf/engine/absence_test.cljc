(ns werwolf.engine.absence-test
  "A host who has gone missing, and a game that must not freeze because of it.

   The server decides WHEN a host counts as gone (timer_test covers the grace
   period); everything here is what follows, and is pure: the absence recorded
   and cleared, the seat offered only where taking it reveals nothing, a
   narrated game handed to the clock, and nobody stuck in a game they cannot
   leave."
  (:require [clojure.test :refer [deftest is testing]]
            [werwolf.engine.core :as engine]
            [werwolf.engine.events :as e]
            [werwolf.engine.phases :as phases]
            [werwolf.engine.schema :as schema]
            [werwolf.engine.view :as view]
            [werwolf.test-util :as tu :refer [exec! try-exec]]))

(def ^:private cast-of-5
  "A narrated table: seats 1..5, the Spielleiter in 0 holds nothing."
  {1 :dorfbewohner 2 :werwolf 3 :hexe 4 :seherin 5 :dorfbewohner})

(def ^:private played-5
  "A table whose host plays: seats 0..4."
  {0 :dorfbewohner 1 :werwolf 2 :hexe 3 :seherin 4 :dorfbewohner})

(defn- drop-out
  "Seat `pid` loses its connection at `at`, the way room/detach! records it."
  ([s pid] (drop-out s pid 1000))
  ([s pid at]
   (e/apply-event s {:type :player/connection :player pid :connected? false :at at})))

(defn- come-back [s pid]
  (e/apply-event s {:type :player/connection :player pid :connected? true :at 9000}))

(defn- absent
  "The host gone and the room told so, exactly as the ticker would do it."
  [s]
  (first (phases/settle (e/apply-all (drop-out s (:host s))
                                     (phases/host-absent-events (drop-out s (:host s)))))))

(defn- unnarrated-lobby [n]
  (e/replay (into [{:type :game/created :room-id "T" :host 0 :seed 1 :min-players 5}]
                  (map (fn [i] {:type :player/joined :player i :name (str "P" i) :locale :de}))
                  (range n))))

;; ---------------------------------------------------------- measuring it ---

(deftest a-disconnect-records-when-and-a-reconnect-forgets-it
  (let [s (drop-out (tu/narrated-lobby 5) 3 1234)]
    (is (= 1234 (get-in s [:players 3 :absent-since])))
    (is (schema/valid-state? s) (pr-str (schema/explain-state s)))
    (testing "a second 'gone' for a seat already gone is not a new absence"
      (is (= 1234 (get-in (drop-out s 3 5678) [:players 3 :absent-since]))))
    (testing "coming back clears it"
      (is (not (contains? (get-in (come-back s 3) [:players 3]) :absent-since))))
    (testing "a log from before the stamp replays as it always did"
      (let [old (e/apply-event (tu/narrated-lobby 5)
                               {:type :player/connection :player 3 :connected? false})]
        (is (false? (get-in old [:players 3 :connected?])))
        (is (not (contains? (get-in old [:players 3]) :absent-since)))))
    (testing "and when it is is nobody's business but the clock's"
      (is (not (re-find #"1234" (pr-str (view/view-for s 1))))))))

(deftest the-room-is-told-the-host-is-gone-and-that-he-is-back
  (let [s (absent (tu/narrated-lobby 5))]
    (is (true? (:host-absent? s)))
    (is (= {:type :host/absent :host 0} (last (filter #(= :host/absent (:type %)) (:log s)))))
    (is (true? (:host-absent? (view/view-for s 3))) "public: the whole table is waiting")
    (is (schema/valid-state? s) (pr-str (schema/explain-state s)))
    (testing "he comes back: the flag goes, and the Chronik says so"
      (let [s (come-back s 0)]
        (is (not (contains? s :host-absent?)))
        (is (= {:type :host/returned :host 0} (last (:log s))))
        (is (false? (:host-absent? (view/view-for s 3))))))
    (testing "somebody else reconnecting changes nothing about the host"
      (is (true? (:host-absent? (come-back (drop-out s 2) 2)))))))

;; --------------------------------------------------- what absence means ---

(deftest absence-events-per-phase-and-mode
  (testing "in the lobby: only the flag — the seat is offered, nothing else moves"
    (is (= [{:type :host/absent}] (phases/host-absent-events (tu/narrated-lobby 5))))
    (is (= [{:type :host/absent}] (phases/host-absent-events (unnarrated-lobby 5)))))
  (testing "a game the host was playing: the flag, and his seat is up for grabs"
    (is (= [{:type :host/absent}] (phases/host-absent-events (tu/game-with played-5)))))
  (testing "a narrated game with no clock: the clock takes over, and says why"
    (is (= [{:type :host/absent}
            {:type :clock/timer-set :on? true :cause :host-absent}]
           (phases/host-absent-events (tu/narrated-game-with cast-of-5)))))
  (testing "a paused one runs again — a pause only he could lift is a freeze"
    (is (= [{:type :host/absent} {:type :clock/resumed}]
           (phases/host-absent-events (assoc (tu/narrated-game-with cast-of-5)
                                             :timer? true :paused? true)))))
  (testing "setup: the deal becomes final and the night falls"
    (is (= [{:type :host/absent}
            {:type :clock/timer-set :on? true :cause :host-absent}
            {:type :night/began :night 1}]
           (phases/host-absent-events (tu/narrated-setup-with cast-of-5)))))
  (testing "nothing to say once flagged, or when the game is over"
    (is (nil? (phases/host-absent-events (assoc (tu/narrated-lobby 5) :host-absent? true))))
    (is (nil? (phases/host-absent-events (assoc (tu/game-with played-5) :phase :game-over))))))

(deftest an-absent-spielleiter-does-not-hold-up-the-setup
  (let [s (absent (tu/narrated-setup-with cast-of-5))]
    (is (= :night (:phase s)) "the night fell without him")
    (is (= 1 (:night s)))
    (is (phases/timed? s) "and the clock is on, so it can end without him too")
    (is (= {:type :clock/timer-set :on? true :cause :host-absent}
           (first (filter #(= :clock/timer-set (:type %)) (:log s)))))
    (is (schema/valid-state? s) (pr-str (schema/explain-state s)))
    (doseq [pid (range 6)]
      (is (nil? (engine/redaction-leaks? s pid)) (str "seat " pid)))))

;; ------------------------------------------------------ claiming the seat ---

(defn- offered? [s pid] (contains? (phases/legal-actions s pid) :claim-host))

(deftest the-seat-is-offered-where-taking-it-reveals-nothing
  (testing "in a lobby, narrated or not, to every connected seat but his"
    (doseq [s [(absent (tu/narrated-lobby 5)) (absent (unnarrated-lobby 5))]]
      (is (every? #(offered? s %) [1 2 3 4]))
      (is (not (offered? s 0)) "not to the absent host himself")
      (is (not (offered? (drop-out s 2) 2)) "not to somebody who is not there either")))
  (testing "in a game the host was playing, to the living and the dead alike —
            a dead host keeps his powers, so a dead heir may take them up"
    (let [s (-> (tu/game-with played-5)
                (e/apply-event {:type :player/died :player 4 :cause :gm})
                absent)]
      (is (offered? s 1))
      (is (offered? s 4))))
  (testing "never in a narrated game under way: the heir holds a card and would
            see every other one"
    (doseq [s [(assoc (tu/narrated-setup-with cast-of-5) :host-absent? true)
               (absent (tu/narrated-game-with cast-of-5))]]
      (is (not-any? #(offered? s %) (range 6)))
      (is (= :not-allowed (:error (second (try-exec s {:type :claim-host :player 1})))))))
  (testing "nor when nobody is missing"
    (is (not-any? #(offered? (tu/narrated-lobby 5) %) (range 6)))
    (is (= :not-allowed (:error (second (try-exec (tu/narrated-lobby 5)
                                                  {:type :claim-host :player 1}))))))
  (testing "nor once the game is decided"
    (is (not (offered? (assoc (absent (tu/game-with played-5)) :phase :game-over) 1)))))

(deftest claiming-makes-you-the-host
  (testing "a narrated lobby: the claimant is now the Spielleiter, with no card"
    (let [s (exec! (absent (tu/narrated-lobby 5)) {:type :claim-host :player 2})]
      (is (= 2 (:host s)))
      (is (= 2 (e/narrator s)))
      (is (not (contains? s :host-absent?)))
      (is (= {:type :host/changed :host 2 :claimed? true} (last (:log s))))
      (is (not-any? #(offered? s %) (range 6)) "and the offer is gone")
      (let [s (exec! s {:type :start-game :player 2})]
        (is (= :setup (:phase s)))
        (is (nil? (get-in s [:players 2 :role])) "he was dealt nothing")
        (is (some? (get-in s [:players 0 :role])) "the absent ex-host now plays")
        (doseq [pid (range 6)]
          (is (nil? (engine/redaction-leaks? s pid)) (str "seat " pid))))))
  (testing "a game the host played: the GM powers move, the card stays"
    (let [s (exec! (absent (tu/game-with played-5)) {:type :claim-host :player 2})]
      (is (= 2 (:host s)))
      (is (= :hexe (get-in s [:players 2 :role])))
      (is (contains? (phases/legal-actions s 2) :gm/force-kill))
      (is (schema/valid-state? s) (pr-str (schema/explain-state s))))))

(deftest the-offer-never-holds-up-the-night
  (testing "it is offered to every seat, so counted as a move it would make
            every night turn wait for a seat nobody has to take"
    (let [s0 (tu/game-with {0 :dorfbewohner 1 :seherin 2 :werwolf 3 :dorfbewohner
                            4 :dorfbewohner})
          _  (is (= :seherin (:night-cursor s0)))
          ;; She has seen everybody: nothing left to do but take the seat.
          s  (-> s0
                 (assoc-in [:players 1 :knows] {0 :dorfbewohner 2 :werwolf
                                                3 :dorfbewohner 4 :dorfbewohner})
                 (assoc :host-absent? true)
                 (e/apply-event {:type :player/connection :player 0 :connected? false})
                 phases/settle
                 first)]
      (is (not= :seherin (:night-cursor s)) "her turn ended")
      (is (offered? s 1) "though the offer still stands"))))

;; --------------------------------------------------------------- leaving ---

(deftest anybody-may-leave-at-any-time
  (testing "once the cards were dealt nobody could walk out, and reloading the
            page only resumed the seat"
    (doseq [[label s] [["setup" (tu/narrated-setup-with cast-of-5)]
                       ["night" (tu/narrated-game-with cast-of-5)]
                       ["over"  (assoc (tu/game-with played-5) :phase :game-over)]]]
      (testing label
        (doseq [pid (keys (:players s))]
          (is (contains? (phases/legal-actions s pid) :leave) (str "seat " pid))))))
  (testing "leaving mid-game keeps the card: an absent player, not a missing one"
    (let [s (exec! (tu/game-with played-5) {:type :leave :player 3})]
      (is (= :seherin (get-in s [:players 3 :role])))
      (is (false? (get-in s [:players 3 :connected?]))))))

(deftest a-spielleiter-who-walks-out-is-absent-at-once
  (testing "no grace for a deliberate leave: the clock takes over in the same breath"
    (let [s (exec! (tu/narrated-game-with cast-of-5) {:type :leave :player 0})]
      (is (true? (:host-absent? s)))
      (is (phases/timed? s))))
  (testing "in setup the night falls"
    (let [s (exec! (tu/narrated-setup-with cast-of-5) {:type :leave :player 0})]
      (is (= :night (:phase s)))))
  (testing "a playing host's seat is offered at once"
    (let [s (exec! (tu/game-with played-5) {:type :leave :player 0})]
      (is (offered? s 1))))
  (testing "in the lobby the seat simply passes on, as it always has"
    (let [s (exec! (tu/narrated-lobby 5) {:type :leave :player 0})]
      (is (not (contains? s :host-absent?)))))
  (testing "an ordinary player leaving flags nothing"
    (is (not (contains? (exec! (tu/narrated-game-with cast-of-5) {:type :leave :player 3})
                        :host-absent?)))))
