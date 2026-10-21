(ns werwolf.test-util
  "Helpers for building games with KNOWN role assignments.

   The engine deals roles from a seed, which is right for real games but useless
   for testing a specific interaction. These helpers inject an explicit
   assignment map so a test can say 'player 0 is the Jäger' and mean it."
  (:require [werwolf.engine.core :as engine]
            [werwolf.engine.events :as e]
            [werwolf.engine.phases :as phases]
            [werwolf.engine.roles :as roles]))

(defn- game-with* [assignments first-night-kill? first-day-trial? election?]
  (let [n (count assignments)
        s (engine/new-game {:room-id "TEST" :host 0 :seed 1 :min-players 5
                            :first-night-kill? first-night-kill?
                            :first-day-trial? first-day-trial?})
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
   wolves do have a turn — which is also what the server deals new rooms, as
   FIRST_NIGHT_KILL defaults to true. Almost every test in this suite is about
   night mechanics — the Hexe's heal, the Jäger's chain, the wolf tally — and
   under the variant it would have to walk a whole day to reach a night where
   anything happens.

   Pass `{:first-night-kill? false}` to exercise the peaceful first night.

   The first day likewise defaults to the CLASSIC rule, a trial on day 1
   (`:first-day-trial? true`): a lynch is almost always tested on the first
   day there is. Pass `{:first-day-trial? false}` for the shipped rule."
  ([assignments] (game-with assignments {}))
  ([assignments {:keys [first-night-kill? first-day-trial? election?]
                 :or   {first-night-kill? true first-day-trial? true election? false}}]
   (game-with* assignments first-night-kill? first-day-trial? election?)))

(defn narrated-lobby
  "A NARRATED lobby: the Spielleiter in seat 0 and `n` players in seats 1..n.
   The clock is off, as `room/create!` opens a narrated room."
  ([n] (narrated-lobby n {}))
  ([n {:keys [max-players first-night-kill? first-day-trial?]
        :or {first-night-kill? true first-day-trial? true}}]
   (e/replay (into [{:type :game/created :room-id "TEST" :host 0 :seed 1
                     :min-players 5 :max-players max-players
                     :first-night-kill? first-night-kill?
                     :first-day-trial? first-day-trial?
                     :narrated? true :timer? false}]
                   (map (fn [i] {:type :player/joined :player i
                                 :name (str "P" i) :locale :de}))
                   (range (inc n))))))

(defn narrated-setup-with
  "A narrated game in :setup with the given {player-id role-id} assignment for
   seats 1..n — the Spielleiter in seat 0 holds nothing."
  ([assignments] (narrated-setup-with assignments {}))
  ([assignments opts]
   (-> (narrated-lobby (count assignments) opts)
       (e/apply-all [{:type :game/started :assignments assignments
                      :centre (roles/centre-for (vals assignments))}
                     {:type :phase/changed :phase :setup :deadline nil}]))))

(defn narrated-game-with
  "The same, past setup: night 1 has fallen and settled. Like `game-with`, the
   election is marked held and the classic first night is on unless asked."
  ([assignments] (narrated-game-with assignments {}))
  ([assignments {:keys [election?] :as opts}]
   (first
    (phases/settle
     (-> (narrated-setup-with assignments opts)
         (cond-> (not election?) (assoc :election-held? true))
         (e/apply-event {:type :night/began :night 1}))))))

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

(defn day-with
  "A started game walked past night 1 to the first day's open ballot, with the
   given {player-id role-id} assignment. Night 1 is skipped role by role with
   the host's GM override, so no card has acted and nobody has died: the day
   under test starts with a full table and no knowledge beyond the deal."
  [assignments]
  (loop [s (game-with assignments), guard 0]
    (if (and (= :night (:phase s)) (< guard 12))
      (recur (exec! s {:type :gm/skip-role :player 0}) (inc guard))
      (do (assert (= :discussion (:phase s)) (str "expected the day, got " (:phase s)))
          s))))

(defn knowing
  "`pid` has learned `roles` ({player-id role-id}), as the Seherin does."
  [state pid roles]
  (update-in state [:players pid :knows] merge roles))

(defn in-love
  "Seats `a` and `b` are the lovers."
  [state a b]
  (assoc state :lovers #{a b}))

(defn advance-to-vote
  "The day's ballot opens with the day itself, so there is nowhere to walk to:
   kept so a test reads as 'and now the village votes'."
  [state]
  state)
