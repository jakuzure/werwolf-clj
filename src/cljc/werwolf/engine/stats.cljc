(ns werwolf.engine.stats
  "How a table has fared across the games of one room: how many each player
   played and won, and which cards they held.

   A room keeps playing after \"Nochmal mit dieser Runde\" (see :game/reset),
   so its log holds every game the table played together. Folded from that log
   at the end of a game, like the recap, and for the same reason: the state
   only holds the game under way.

   Facts the end of each game makes public anyway — every card is turned over
   once it is decided — so nothing here is a secret. Only games that ENDED
   count: one the server retired because nobody came back has no winner, and
   counting it would hand everybody a loss for leaving."
  (:require [werwolf.engine.events :as e]))

(defn- won?
  "Did `pid` win the game `state` has just ended? Lovers win as a pair over
   both teams, the Weißer Werwolf alone; otherwise a team wins together, the
   dead included.

   Alone means never with the pack he was dealt to: their win is the one he
   played against, and since phases/winner hands it to them when he lets his
   betrayal go by, counting it as his would credit him for losing."
  [state pid]
  (let [winner (:winner state)
        white? (= :weisser-werwolf (get-in state [:players pid :role]))]
    (case winner
      :lovers          (contains? (set (:lovers state)) pid)
      :weisser-werwolf white?
      :werewolf        (and (= :werewolf (e/team-of-player state pid)) (not white?))
      :village         (= :village (e/team-of-player state pid))
      false)))

(defn- finished
  "One ended game: each participant's card at the end, and who won."
  [state]
  (into {}
        (map (fn [pid] [pid {:role (get-in state [:players pid :role])
                             :won? (won? state pid)}]))
        (e/participant-ids state)))

(defn stats
  "Events in, {:games n :players {pid {:played :won :roles {role n}}}} out,
   or nil before any game has ended."
  [events]
  (let [games (:games (reduce (fn [{:keys [state] :as acc} ev]
                                (let [state (e/apply-event state ev)]
                                  (cond-> (assoc acc :state state)
                                    (= :game/ended (:type ev))
                                    (update :games conj (finished state)))))
                              {:state nil :games []}
                              events))]
    (when (seq games)
      {:games   (count games)
       :players (reduce (fn [acc game]
                          (reduce (fn [acc [pid {:keys [role won?]}]]
                                    (-> acc
                                        (update-in [pid :played] (fnil inc 0))
                                        (update-in [pid :won] (fnil + 0) (if won? 1 0))
                                        (update-in [pid :roles role] (fnil inc 0))))
                                  acc game))
                        {} games)})))
