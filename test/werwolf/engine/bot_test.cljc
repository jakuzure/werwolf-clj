(ns werwolf.engine.bot-test
  "Seats the server plays.

   A bot is an ordinary seat with nobody behind it: dealt a card, counted for
   the minimum, removable with the kick, kept across a rematch. What the tests
   below pin is the one fact everything else leans on — a bot is never
   connected, because it has no socket, and the state must say so rather than
   pretend — and that seat numbers are issued by the engine from the log, so a
   kicked seat's number (and its reconnect token) is never handed to a bot."
  (:require [clojure.test :refer [deftest is testing]]
            [werwolf.engine.bot :as bot]
            [werwolf.engine.core :as engine]
            [werwolf.engine.events :as e]
            [werwolf.engine.phases :as phases]
            [werwolf.engine.schema :as schema]
            [werwolf.engine.view :as view]
            [werwolf.i18n.core :as i18n]
            [werwolf.proto :as proto]
            [werwolf.sim.harness :as h]
            [werwolf.test-util :as tu :refer [exec! try-exec]]))

(defn- lobby
  "Seats 0..n-1, host 0, the host playing."
  [n]
  (e/replay (into [{:type :game/created :room-id "TEST" :host 0 :seed 1 :min-players 5}]
                  (map (fn [i] {:type :player/joined :player i :name (str "P" i) :locale :de}))
                  (range n))))

(deftest a-bot-seat-is-a-player-with-no-socket
  (let [s (e/apply-event (lobby 2) {:type :player/joined :player 2 :name "Agnes (Bot)" :locale :de :bot? true})]
    (is (true? (get-in s [:players 2 :bot?])))
    (is (false? (get-in s [:players 2 :connected?])) "no socket, so not connected, and the state says so")
    (is (schema/valid-state? s) (pr-str (schema/explain-state s)))
    (testing "a human joined the old way is unchanged"
      (is (nil? (get-in s [:players 1 :bot?])))
      (is (true? (get-in s [:players 1 :connected?]))))
    (testing "everyone may know who is a bot, and still no role"
      (let [p (get-in (view/view-for s 0) [:players 2])]
        (is (true? (:bot? p)))
        (is (not (contains? p :role)))))
    (testing "a rematch keeps the bot a bot"
      (let [s' (e/apply-event (assoc s :phase :game-over) {:type :game/reset})]
        (is (true? (get-in s' [:players 2 :bot?])))
        (is (false? (get-in s' [:players 2 :connected?])))))))

(deftest seats-are-issued-from-the-log-never-recycled
  (let [s (lobby 3)]
    (is (= 3 (e/next-seat s)))
    (is (= 0 (e/next-seat (e/replay [{:type :game/created :room-id "T" :host 0 :seed 1 :min-players 5}]))))
    (testing "a kicked seat's number stays issued"
      (is (= 3 (e/next-seat (exec! s {:type :gm/kick :player 0 :target 2})))))))

(deftest nobody-here-means-no-connected-seat
  (let [s (lobby 2)]
    (is (false? (e/nobody-here? s)))
    (is (true? (e/nobody-here? (-> s (assoc-in [:players 0 :connected?] false)
                                     (assoc-in [:players 1 :connected?] false)))))
    (is (true? (e/nobody-here? (e/apply-event (-> s (assoc-in [:players 0 :connected?] false)
                                                   (assoc-in [:players 1 :connected?] false))
                                               {:type :player/joined :player 2 :name "Agnes (Bot)" :locale :de :bot? true})))
        "a table of bots is an empty table")))

;; ------------------------------------------------------------ :gm/add-bot ---

(deftest the-host-may-add-a-bot-while-a-seat-is-free
  (let [s (lobby 3)]
    (is (contains? (phases/legal-actions s 0) :gm/add-bot))
    (is (contains? phases/no-target-actions :gm/add-bot))
    (doseq [pid [1 2]]
      (is (not (contains? (phases/legal-actions s pid) :gm/add-bot)) "host only"))
    (let [s' (exec! s {:type :gm/add-bot :player 0})]
      (is (= {:type :player/joined :player 3 :name "Agnes (Bot)" :locale :de :bot? true}
             (first (:events (engine/execute s {:type :gm/add-bot :player 0})))))
      (is (true? (get-in s' [:players 3 :bot?])))
      (testing "the next one gets the next name and the next seat"
        (let [s'' (exec! s' {:type :gm/add-bot :player 0})]
          (is (= "Bruno (Bot)" (get-in s'' [:players 4 :name])))))
      (testing "a kicked bot's name comes back, its seat number does not"
        (let [s'' (-> s' (exec! {:type :gm/kick :player 0 :target 3}) (exec! {:type :gm/add-bot :player 0}))]
          (is (= "Agnes (Bot)" (get-in s'' [:players 4 :name])))
          (is (not (contains? (:players s'') 3)))))
      (testing "a human already called Agnes (Bot) is skipped, case and spaces aside"
        (let [s2 (e/apply-event (lobby 2) {:type :player/joined :player 2 :name " agnes (bot) " :locale :de})]
          (is (= "Bruno (Bot)" (get-in (exec! s2 {:type :gm/add-bot :player 0}) [:players 3 :name])))))
      (is (schema/valid-state? s') (pr-str (schema/explain-state s'))))))

(deftest a-full-table-offers-no-bot
  (let [full (reduce (fn [s _] (exec! s {:type :gm/add-bot :player 0}))
                     (assoc (lobby 3) :max-players 6) (range 3))]
    (is (= 6 (count (e/participant-ids full))))
    (is (not (contains? (phases/legal-actions full 0) :gm/add-bot)))
    (is (= :not-allowed (:error (second (try-exec full {:type :gm/add-bot :player 0})))))
    (testing "the Spielleiter holds no seat at the table, so a narrated room has one more"
      (let [n (assoc (tu/narrated-lobby 5) :max-players 6)]
        (is (contains? (phases/legal-actions n 0) :gm/add-bot))
        (is (not (contains? (phases/legal-actions (exec! n {:type :gm/add-bot :player 0}) 0) :gm/add-bot)))))))

(deftest only-in-the-lobby
  (let [s (tu/game-with {0 :dorfbewohner 1 :seherin 2 :werwolf 3 :hexe 4 :jaeger})]
    (is (not (contains? (phases/legal-actions s 0) :gm/add-bot)))))

(deftest the-host-seat-is-never-handed-to-a-bot
  (let [s (exec! (lobby 3) {:type :gm/add-bot :player 0})]
    (is (= [1 2] (phases/legal-targets s 0 :gm/transfer-host)))
    (is (= [1 2 3] (phases/legal-targets s 0 :gm/kick)) "but a bot may be removed")))

(deftest the-action-is-on-the-wire-with-a-label
  (is (contains? proto/command-types :gm/add-bot))
  (is (= "Bot hinzufügen" (i18n/action-name :de :gm/add-bot)))
  (is (= "Add a bot" (i18n/action-name :en :gm/add-bot))))

;; ----------------------------------------------------------------- policy ---
;;
;; `decide` takes the seat's redacted view and a seed, never state. That is the
;; whole fairness guarantee, and it is structural: whatever the view does not
;; carry, the bot cannot know.

(def cast {0 :dorfbewohner 1 :seherin 2 :werwolf 3 :hexe 4 :jaeger 5 :amor 6 :werwolf})

(deftest decide-draws-only-on-the-view
  (let [s (tu/game-with cast) v (view/view-for s 5)]
    (is (= :amor-link (:type (bot/decide v 1))))
    (is (= 5 (:player (bot/decide v 1))))))

(deftest nothing-to-do-means-nil
  (let [s (tu/game-with cast)]
    (is (nil? (bot/decide (view/view-for s 0) 1)) "a villager at night holds only chat")))

(deftest wolves-coordinate-and-never-eat-their-own
  (let [pack (-> (tu/game-with cast) (exec! {:type :amor-link :player 5 :a 0 :b 4})
                 (exec! {:type :seherin-inspect :player 1 :target 0}))]   ; the wolves' turn
    (testing "the first wolf picks, and never a wolf"
      (doseq [seed (range 20)]
        (let [c (bot/decide (view/view-for pack 2) seed)]
          (is (= :werewolf-vote (:type c)))
          (is (not (#{2 6} (:target c)))))))
    (testing "the second wolf follows the first"
      (let [s (exec! pack {:type :werewolf-vote :player 2 :target 3})]
        (is (= {:type :werewolf-vote :player 6 :target 3} (bot/decide (view/view-for s 6) 1)))))))

(deftest the-hexe-saves-herself-and-her-lover-always
  (let [at-hexe (fn [victim] (-> (tu/game-with cast)
                                 (exec! {:type :amor-link :player 5 :a 3 :b 4})
                                 (exec! {:type :seherin-inspect :player 1 :target 0})
                                 (exec! {:type :werewolf-vote :player 2 :target victim})
                                 (exec! {:type :werewolf-vote :player 6 :target victim})))]
    (doseq [seed (range 10)]
      (is (= {:type :hexe-heal :player 3 :target 3} (bot/decide (view/view-for (at-hexe 3) 3) seed)))
      (is (= {:type :hexe-heal :player 3 :target 4} (bot/decide (view/view-for (at-hexe 4) 3) seed))))
    (testing "otherwise she heals about a third of the time and never poisons on night 1"
      (let [cmds (map #(bot/decide (view/view-for (at-hexe 0) 3) %) (range 60))]
        (is (< 5 (count (filter #(= :hexe-heal (:type %)) cmds)) 40))
        (is (empty? (filter #(= :hexe-poison (:type %)) cmds)))
        (is (every? #(#{:hexe-heal :hexe-pass} (:type %)) cmds))))))

(deftest the-day-vote-follows-knowledge-then-the-crowd-then-chance
  (let [day (tu/day-with cast)]
    (testing "the Seherin votes the wolf she found"
      (let [s (tu/knowing day 1 {2 :werwolf})]
        (is (= {:type :vote :player 1 :target 2} (bot/decide (view/view-for s 1) 7)))))
    (testing "a villager follows the leading target"
      (let [s (-> day (exec! {:type :vote :player 1 :target 4}) (exec! {:type :vote :player 2 :target 4}))]
        (is (= {:type :vote :player 0 :target 4} (bot/decide (view/view-for s 0) 7)))))
    (testing "with nothing to go on, a lone accusation is joined rather than a second one opened"
      (let [s (exec! day {:type :vote :player 1 :target 4})]
        (doseq [seed (range 10)]
          (is (= {:type :vote :player 0 :target 4} (bot/decide (view/view-for s 0) seed))))))
    (testing "a wolf never follows the crowd onto a wolf"
      (let [s (-> day (exec! {:type :vote :player 1 :target 6}) (exec! {:type :vote :player 3 :target 6}))]
        (is (not= 6 (:target (bot/decide (view/view-for s 2) 7))))
        (is (not= 2 (:target (bot/decide (view/view-for s 2) 7))))))
    (testing "never the lover, never abstain, never twice"
      (let [s (tu/in-love day 0 4)]
        (doseq [seed (range 20)]
          (let [c (bot/decide (view/view-for s 0) seed)]
            (is (= :vote (:type c)))
            (is (not= 4 (:target c)))))
        (is (nil? (bot/decide (view/view-for (exec! s {:type :vote :player 0 :target 1}) 0) 1)))))))

(deftest every-offer-becomes-a-legal-command
  ;; Across many seeds and many seats, whatever decide returns, the engine accepts.
  (doseq [seed (range 30) pid (range 7)]
    (let [s (tu/game-with cast) v (view/view-for s pid)]
      (when-let [c (bot/decide v seed)]
        (is (not (engine/error? (engine/execute s c))) (pr-str c))))))

;; ------------------------------------------------------------- suspicion ---
;;
;; A view is plain data, so what the bots reason from can be built by hand:
;; the public log, today's ballot, the cards this seat knows, who is alive.

(defn- table
  "A hand-built view of a table of `n` seats for seat `me`. Everything is as
   given and otherwise empty; `dead` seats are not alive, `bots` carry the
   public flag."
  [n me & {:keys [role lover dead bots log known votes actions targets night-info night]
           :or   {dead #{} bots #{} log [] known {} votes {} actions #{} targets {}}}]
  {:me         {:id me :role role :lover lover}
   :players    (into {} (map (fn [i] [i {:id i :alive? (not (contains? dead i)) :bot? (contains? bots i)}])) (range n))
   :known-roles known
   :log        log
   :votes      votes
   :actions    actions
   :targets    targets
   :night-info night-info
   :night      night})

(defn- cast-all
  "Log entries for one day's ballot: `:day/began`, a cast per [voter target],
   and the resolution, `:verdict` the condemned or nil."
  [day votes & {:keys [verdict open?]}]
  (cond-> (into [{:type :day/began :day day}]
                (map (fn [[voter target]] {:type :vote/cast :voter voter :target target}))
                votes)
    (not open?) (conj {:type :vote/resolved :target verdict :tie? false})
    verdict     (conj {:type :player/died :player verdict :cause :lynch})))

(deftest suspicion-reads-the-ballots-against-revealed-cards
  (testing "whoever voted to condemn a villager looks guilty"
    (let [v (table 6 0 :role :dorfbewohner :dead #{3} :known {0 :dorfbewohner 3 :dorfbewohner}
                   :log (cast-all 2 [[0 3] [1 3] [2 3] [3 1] [4 3] [5 1]] :verdict 3))]
      (is (= {0 1 1 1 2 1 4 1 5 0} (bot/suspicion v)) "the dead are not scored, nor the votes they got")))
  (testing "whoever voted against a wolf looks honest"
    (let [v (table 6 0 :role :dorfbewohner :dead #{2} :known {0 :dorfbewohner 2 :werwolf}
                   :log (cast-all 2 [[1 2] [2 1] [3 2] [4 2] [5 1]] :verdict 2))]
      (is (= {0 0 1 -1 3 -1 4 -1 5 0} (bot/suspicion v)))))
  (testing "on a day the table decided nothing, the seats that said nothing look guilty"
    (let [v (table 6 0 :role :dorfbewohner :known {0 :dorfbewohner}
                   :log (cast-all 2 [[1 2] [2 1] [3 nil]]))]
      (is (= {0 1 1 0 2 0 3 0 4 1 5 1} (bot/suspicion v)) "an abstention is an answer; silence is not")))
  (testing "a decided day blames nobody for silence, and an open ballot not yet"
    (let [v (table 6 0 :role :dorfbewohner :dead #{2} :known {0 :dorfbewohner 2 :werwolf}
                   :log (concat (cast-all 2 [[1 2] [3 2]] :verdict 2) (cast-all 3 [[1 4]] :open? true)))]
      (is (= {0 0 1 -1 3 -1 4 0 5 0} (bot/suspicion v)))))
  (testing "a voter's last ballot of the day is the one that counts"
    (let [v (table 6 0 :role :dorfbewohner :dead #{3} :known {0 :dorfbewohner 3 :dorfbewohner}
                   :log (cast-all 2 [[1 3] [1 2] [2 3] [4 3]] :verdict 3))]
      (is (= 0 (get (bot/suspicion v) 1)))))
  (testing "a villager's own card is not evidence: an accuser is not a wolf for having accused me"
    (let [v (table 6 0 :role :dorfbewohner :dead #{1} :known {0 :dorfbewohner 1 :dorfbewohner}
                   :log (cast-all 2 [[1 0] [2 0] [0 1] [3 1] [4 1] [5 1]] :verdict 1))]
      (is (= {0 1 2 0 3 1 4 1 5 1} (bot/suspicion v)))))
  (testing "a wolf's own card is: whoever voted against it is after the pack"
    (let [v (table 6 0 :role :werwolf :known {0 :werwolf 5 :werwolf}
                   :log (cast-all 2 [[1 0] [2 5] [3 1]] :open? true))]
      (is (= {0 0 1 -1 2 -1 3 0 4 0 5 0} (bot/suspicion v)))))
  (testing "what the Seherin found counts for her, alive or not"
    (let [v (table 6 1 :role :seherin :known {1 :seherin 4 :dorfbewohner 2 :werwolf}
                   :log (cast-all 2 [[3 4] [5 2] [0 1]] :open? true))]
      (is (= {0 0 1 0 2 0 3 1 4 0 5 -1} (bot/suspicion v))))))

(deftest a-lone-accusation-does-not-sway-a-bot-with-a-suspect-of-its-own
  (let [history (cast-all 2 [[1 3] [2 3] [4 3] [5 1] [3 5] [0 5]] :verdict 3)
        at-day  (fn [votes] (table 6 0 :role :dorfbewohner :dead #{3} :known {0 :dorfbewohner 3 :dorfbewohner}
                                   :log (into (vec history) (cast-all 3 (vec votes) :open? true))
                                   :votes (into {} votes)
                                   :actions #{:vote :vote-abstain :chat} :targets {:vote [1 2 4 5]}))]
    (testing "its suspect, with nobody or one voter on the board"
      (doseq [seed (range 10)]
        (is (#{1 2 4} (:target (bot/decide (at-day []) seed))) "one of the three who condemned a villager")
        (is (#{1 2 4} (:target (bot/decide (at-day [[5 1]]) seed))))))
    (testing "the suspects being equal, the one already accused"
      (doseq [seed (range 10)]
        (is (= 2 (:target (bot/decide (at-day [[5 2]]) seed))))))
    (testing "two votes behind an accusation, and the bot joins it"
      (doseq [seed (range 10)]
        (is (= 5 (:target (bot/decide (at-day [[1 5] [2 5]]) seed))))))))

(deftest at-night-the-same-suspicion-picks-the-target
  (let [history (cast-all 2 [[1 3] [2 3] [4 3] [5 4] [3 5] [0 5]] :verdict 3)
        known   {3 :dorfbewohner}]
    (testing "the Seherin looks at her top suspect among those she has not seen"
      (let [v (table 6 0 :role :seherin :dead #{3} :known (assoc known 0 :seherin 1 :dorfbewohner)
                     :log history :night 2 :actions #{:seherin-inspect} :targets {:seherin-inspect [2 4 5]})]
        (doseq [seed (range 10)]
          (is (#{2 4} (:target (bot/decide v seed)))))))
    (testing "the Hexe, when she poisons, poisons her top suspect"
      (let [v    (table 6 0 :role :hexe :dead #{3} :known (assoc known 0 :hexe)
                        :log history :night 2 :actions #{:hexe-poison :hexe-pass}
                        :targets {:hexe-poison [1 2 4 5]})
            cmds (map #(bot/decide v %) (range 60))
            poisons (filter #(= :hexe-poison (:type %)) cmds)]
        (is (seq poisons) "a quarter of the nights, so some of sixty seeds")
        (is (every? #(#{1 2 4} (:target %)) poisons))))
    (testing "the Jäger shoots a wolf he knows of, else his top suspect"
      (let [v (table 6 0 :role :jaeger :dead #{3} :known (assoc known 0 :jaeger)
                     :log history :actions #{:jaeger-shoot :jaeger-pass} :targets {:jaeger-shoot [1 2 4 5]})]
        (doseq [seed (range 10)]
          (is (#{1 2 4} (:target (bot/decide v seed)))))
        (is (= 5 (:target (bot/decide (assoc v :known-roles (assoc known 0 :jaeger 5 :werwolf)) 1))))))
    (testing "the wolves eat the villager the table would trust: whoever voted against a wolf"
      (let [pack (fn [log known dead]
                   (table 7 6 :role :werwolf :known known :log log :night 2 :dead dead
                          :actions #{:werewolf-vote :chat}
                          :targets {:werewolf-vote (vec (remove (into dead [2 6]) (range 7)))}
                          :night-info {:werewolf-votes {}}))]
        (testing "a wolf turned over since"
          (let [v (pack (cast-all 2 [[1 2] [3 2] [4 2] [5 1] [0 1] [2 1]] :verdict 2)
                        {6 :werwolf 2 :werwolf} #{2})]
            (doseq [seed (range 10)]
              (is (#{1 3 4} (:target (bot/decide v seed)))))))
        (testing "else the day's accuser of a wolf still at the table, itself included"
          (let [v (pack (cast-all 2 [[1 6] [3 4] [4 3] [5 3] [0 3] [2 3]] :verdict 3)
                        {6 :werwolf 2 :werwolf 3 :dorfbewohner} #{3})]
            (doseq [seed (range 10)]
              (is (= 1 (:target (bot/decide v seed)))))))))))

;; -------------------------------------------------------- the pack agrees ---
;;
;; Built against the view ahead of the engine: once `:werewolf-vote` stays
;; offered until every living wolf names the same victim, a bot that re-voted
;; on every tick would never let the pack settle.

(deftest a-wolf-whose-vote-is-the-packs-lead-is-settled
  (let [pack (fn [me votes]
               (table 8 me :role :werwolf :bots #{4 6} :known {2 :werwolf 4 :werwolf 6 :werwolf}
                      :actions #{:werewolf-vote :chat} :targets {:werewolf-vote [0 1 3 5 7]}
                      :night-info {:werewolf-votes votes}))]
    (testing "the human wolf's choice leads, whatever seat he holds"
      (is (not (contains? (bot/offers (pack 6 {2 3 6 3})) :werewolf-vote)) "agreed, nothing to do")
      (is (contains? (bot/offers (pack 6 {2 3 6 1})) :werewolf-vote) "disagreed, so still offered")
      (is (= {:type :werewolf-vote :player 6 :target 3} (bot/decide (pack 6 {2 3 6 1}) 1)))
      (is (= {:type :werewolf-vote :player 4 :target 3} (bot/decide (pack 4 {2 3 4 1 6 1}) 1))
          "even against the other bot"))
    (testing "among bots, the lowest seat leads"
      (is (not (contains? (bot/offers (pack 4 {4 1 6 3})) :werewolf-vote)) "the lowest seat is settled")
      (is (= {:type :werewolf-vote :player 6 :target 1} (bot/decide (pack 6 {4 1 6 3}) 1))))
    (testing "a lead the engine would not accept is not followed, and settles a wolf that has voted"
      (is (not (contains? (bot/offers (pack 6 {2 9 6 1})) :werewolf-vote))
          "nothing to follow, so no vote cast again on every tick")
      (let [c (bot/decide (pack 6 {2 9}) 1)]
        (is (= :werewolf-vote (:type c)))
        (is (#{0 1 3 5 7} (:target c)))))
    (testing "nobody has voted: the wolf picks for itself"
      (is (contains? (bot/offers (pack 6 {})) :werewolf-vote))
      (is (#{0 1 3 5 7} (:target (bot/decide (pack 6 {}) 1)))))))

;; -------------------------------------------------------------- the order ---

(deftest knowledge-takes-the-floor-first
  (let [seer (fn [known & {:keys [lover]}]
               (table 6 1 :role :seherin :lover lover :known known
                      :actions #{:vote :vote-abstain :chat} :targets {:vote [0 2 3 4 5]}))]
    (is (true? (bot/informed? (seer {1 :seherin 2 :werwolf}))))
    (is (false? (bot/informed? (seer {1 :seherin 2 :dorfbewohner}))) "a villager found is nothing to vote")
    (is (false? (bot/informed? (seer {1 :seherin 2 :werwolf} :lover 2))) "a wolf she may not name")
    (is (false? (bot/informed? (assoc (seer {1 :seherin 2 :werwolf}) :players
                                      (assoc-in (:players (seer {})) [2 :alive?] false))))
        "a wolf already dead")
    (is (false? (bot/informed? (table 6 2 :role :werwolf :known {2 :werwolf 4 :werwolf}
                                      :actions #{:vote :vote-abstain :chat} :targets {:vote [0 1 3 4 5]})))
        "the pack takes its ordinary turn"))
  (testing "the order: the informed first, the rest by the seed, stable within a day"
    (let [informed {1 false 2 true 3 false 4 false 5 true}
          order    (bot/day-order 42 3 informed)]
      (is (= #{2 5} (set (take 2 order))))
      (is (= #{1 3 4} (set (drop 2 order))))
      (is (= order (bot/day-order 42 3 informed)))
      (is (some #(not= order (bot/day-order 42 % informed)) (range 1 20)) "another day, another order")))
  (testing "the shares: one slot each, in order, no two alike, all inside the window"
    (let [informed {1 false 2 true 3 false 4 false 5 true}
          shares   (bot/day-shares 42 3 informed)
          order    (bot/day-order 42 3 informed)]
      (is (= (set (keys informed)) (set (keys shares))))
      (is (apply < (map shares order)) "in the order of the floor")
      (is (every? #(and (<= 0 %) (< % 1)) (vals shares)))
      (is (< (shares (first order)) 0.1) "the first is at the window's start")
      (is (= shares (bot/day-shares 42 3 informed)))
      (is (every? #(and (<= 0 %) (< % 1)) (map #(bot/share 42 3 %) (range 10)))))))

;; ---------------------------------------------------------- all-bot games ---
;;
;; The one property that matters for a human testing alone: whatever the bots
;; are dealt, the game ends. The host seat never moves — narrated, it is the
;; Spielleiter; otherwise a human absent all game, the worst case for a table —
;; and where only the clock or the Spielleiter could move the game along, the
;; loop does what they would.

(defn- movers
  "The bots in the order the server fires them: by day those who know a wolf
   first, the rest as the seed orders them (bot/day-order, which werwolf.bots
   spreads over the day's window); at night the seat order, which is also the
   pack's order of precedence."
  [s game-seed bots]
  (if (#{:discussion :election} (:phase s))
    (let [living   (set (engine/living-ids s))
          informed (into {} (map (fn [pid] [pid (bot/informed? (view/view-for s pid))])) (filter living bots))
          order    (bot/day-order game-seed (:night s 0) informed)]
      (into order (remove (set order) bots)))
    bots))

(defn- play-bots
  [n seed opts]
  (let [[s0 log0] (h/new-sim-game n seed opts)
        bots (vec (sort (remove #{0} (keys (:players s0)))))]
    (loop [s s0, turn 0, log log0]
      (cond
        (seq (h/check-invariants s))
        {:ok? false :reason :invariant :seed seed :players n :turn turn
         :violations (h/check-invariants s)}

        (engine/over? s)
        {:ok? true :winner (:winner s) :seed seed :players n :turns turn :log log :state s}

        (> turn 2000)
        {:ok? false :reason :livelock :seed seed :players n :turn turn :phase (:phase s)}

        :else
        (let [moves (keep (fn [pid] (bot/decide (view/view-for s pid) (bot/seed-for seed turn pid)))
                          (movers s (:seed s0) bots))]
          (if-let [cmd (first moves)]
            (let [r (engine/execute s cmd)]
              (if (engine/error? r)
                {:ok? false :reason :rejected :cmd cmd :error (:error r) :seed seed :players n
                 :turn turn :phase (:phase s)}
                (recur (:state r) (inc turn) (into log (:events r)))))
            ;; Nobody can move: only the clock or the Spielleiter could. Stand
            ;; in for them with what they would do — the host starts the game
            ;; and begins the night; everywhere else the clock's own expiry.
            (case (:phase s)
              (:lobby :setup)
              (let [r (engine/execute s {:type (if (= :lobby (:phase s)) :start-game :begin-night) :player 0})]
                (if (engine/error? r)
                  {:ok? false :reason :deadlock :seed seed :players n :turn turn :phase (:phase s)
                   :error (:error r)}
                  (recur (:state r) (inc turn) (into log (:events r)))))
              (let [evs (engine/timeout-events s)]
                (if (empty? evs)
                  {:ok? false :reason :deadlock :seed seed :players n :turn turn :phase (:phase s)}
                  (let [[s' auto] (engine/settle (engine/apply-all s evs))]
                    (recur s' (inc turn) (-> log (into evs) (into auto)))))))))))))

(deftest bots-alone-finish-every-game
  ;; The sixteen-player unnarrated game here once ran for ever: the pack and
  ;; the Weißer Werwolf were all that was left, and he was the absent host
  ;; and the Bürgermeister. See phases/winner for why it ends now.
  (doseq [n (range 5 19) narrated? [true false]
          :let [r (play-bots n (+ 500 (* 13 n)) {:narrated? narrated?})]]
    (is (:ok? r) (pr-str (dissoc r :log :state)))
    (is (some? (:winner r)) (str n " players, narrated " narrated? ": no winner"))
    (when (:ok? r)
      (is (nil? (h/replay-mismatch r)) "the log replays to the same state"))))

(deftest both-sides-win-against-bots
  (let [winners (frequencies (map #(:winner (play-bots 9 % {})) (range 1 41)))]
    (is (pos? (get winners :village 0)) (pr-str winners))
    (is (pos? (get winners :werewolf 0)) (pr-str winners))))
