(ns werwolf.test-util
  "Helpers for building games with KNOWN role assignments.

   The engine deals roles from a seed, which is right for real games but useless
   for testing a specific interaction. These helpers inject an explicit
   assignment map so a test can say 'player 0 is the Jäger' and mean it."
  (:require [werwolf.engine.core :as engine]
            [werwolf.engine.events :as e]
            [werwolf.engine.phases :as phases]))

(defn- game-with* [assignments first-night-kill? election?]
  (let [n (count assignments)
        s (engine/new-game {:room-id "TEST" :host 0 :seed 1 :min-players 5
                            :first-night-kill? first-night-kill?})
        s (reduce (fn [s i]
                    (e/apply-event s {:type :player/joined
                                      :player i :name (str "P" i) :locale :de}))
                  s (range n))]
    (first
     (phases/settle
      (-> s
          (e/apply-event {:type :game/started :assignments assignments})
          (cond-> (not election?)
            ;; Mark the Bürgermeister election as already held, so day 1 goes
            ;; straight to discussion. Almost every test here is about night
            ;; mechanics or the lynch, and making each of them run an election
            ;; first would be noise. Pass {:election? true} to exercise it.
            (assoc :election-held? true))
          (e/apply-event {:type :night/began :night 1}))))))

(defn game-with
  "A started game at night 1 with the given {player-id role-id} assignment.
   Player 0 is the host. Returns settled state, so :night-cursor is live.

   Defaults to the CLASSIC first night (`:first-night-kill? true`), i.e. the
   wolves do have a turn. The product default is the opposite, but almost every
   test in this suite is about night mechanics — the Hexe's heal, the Jäger's
   chain, the wolf tally — and under the variant it would have to walk a whole
   day to reach a night where anything happens.

   Pass `{:first-night-kill? false}` to exercise the shipped rule."
  ([assignments] (game-with assignments {}))
  ([assignments {:keys [first-night-kill? election?]
                 :or   {first-night-kill? true election? false}}]
   (game-with* assignments first-night-kill? election?)))

(defn exec!
  "Run a command, THROWING on rejection. Tests must not silently no-op — that is
   how a broken test passes."
  [state cmd]
  (let [r (engine/execute state cmd)]
    (if (engine/error? r)
      (throw (ex-info (str "command rejected: " (:error r))
                      {:cmd cmd :error r :phase (:phase state)
                       :cursor (:night-cursor state)}))
      (:state r))))

(defn try-exec
  "Run a command, returning [state error]. For testing that something IS rejected."
  [state cmd]
  (let [r (engine/execute state cmd)]
    (if (engine/error? r) [state r] [(:state r) nil])))

(defn alive [state] (engine/living-ids state))

(defn role-of [state pid] (get-in state [:players pid :role]))

(defn wolves [state]
  (vec (sort (keep (fn [[id p]] (when (= :werwolf (:role p)) id)) (:players state)))))

(defn log-types [state] (mapv :type (:log state)))

(defn count-log [state type] (count (filter #(= type (:type %)) (:log state))))

(defn advance-to-vote
  "Walk the day from :discussion to :vote using host powers."
  [state]
  (-> state
      (exec! {:type :advance-phase :player 0})   ; discussion -> nomination
      (exec! {:type :advance-phase :player 0}))) ; nomination -> vote
