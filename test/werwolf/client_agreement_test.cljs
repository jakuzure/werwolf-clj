(ns werwolf.client-agreement-test
  "The client's reading of a view against the engine's own answer, over every
   event of simulated games and every seat at the table.

   The client draws no rules of its own, but it still reads facts out of a
   view, and twice a reading drifted from the engine it was meant to mirror:
   the ballot was added up in five places (b2aff60), and the turn was decided
   in three, one of them a cond in a view nobody could test (215c33f, then
   b78fee7). The engine says who the game is waiting on, `phases/owing`; this
   checks that the client's \"you are on\" and its list of who is still
   missing say the same, wherever a game can go."
  (:require [cljs.test :refer [deftest is testing]]
            [werwolf.engine.events :as e]
            [werwolf.engine.phases :as phases]
            [werwolf.engine.view :as view]
            [werwolf.sim.harness :as h]
            [werwolf.subs :as sub]))

(def ^:private games
  "Small on purpose: every event times every seat is a view each, and the
   breadth is the sims' job. One of each kind of table."
  [[7 11 {}]
   [9 23 {:narrated? true}]
   [12 37 {:overrule? true}]
   [8 41 {:overrule? true :narrated? true}]])

(defn- states
  "Every state the game passed through, one per event of its log."
  [{:keys [log]}]
  (rest (reductions e/apply-event nil log)))

(defn- disagreements
  "What the client reads in `pid`'s view of `state` that the engine does not
   say. Empty when they agree."
  [state pid]
  (let [v        (view/view-for state pid)
        owed     (set (phases/owing state))
        turn     (sub/turn v)
        narrator (e/narrator state)
        names    #(vec (sort (keep (fn [id] (get-in state [:players id :name])) %)))]
    (cond-> []
      (and (= pid narrator) (some? turn) (not= :narrating turn))
      (conj {:what :narrator-has-a-turn :turn turn})

      (and (not= pid narrator) (contains? owed pid)
           (not (#{:yours :reaction :voted} turn)))
      (conj {:what :owed-but-not-on :turn turn})

      (and (not= pid narrator) (= :yours turn) (not (contains? owed pid)))
      (conj {:what :on-but-not-owed :actions (:actions v)})

      (and (= :discussion (:phase state))
           (not= (names owed) (or (sub/waiting-names v) [])))
      (conj {:what :waiting-names :client (sub/waiting-names v) :engine (names owed)}))))

(deftest the-client-reads-every-view-as-the-engine-means-it
  (doseq [[n seed opts] games
          :let [r (h/play-game n seed opts)]]
    (testing (pr-str [n seed opts])
      (is (:ok? r) (pr-str (select-keys r [:reason :violations])))
      (let [checked (for [s (states r)
                          :when (not (#{:lobby :game-over} (:phase s)))
                          pid (sort (keys (:players s)))]
                      [s pid])
            found (for [[s pid] checked
                        d (disagreements s pid)]
                    (assoc d :seat pid :phase (:phase s) :night (:night s)))]
        (is (empty? found) (pr-str (take 5 found)))
        (testing "and the game gave it something to read: turns, and a day
                  waiting on somebody"
          (is (some (fn [[s pid]] (= :yours (sub/turn (view/view-for s pid)))) checked))
          (is (some (fn [[s pid]] (seq (sub/waiting-names (view/view-for s pid)))) checked)))))))
