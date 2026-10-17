(ns werwolf.engine.recap-test
  "The Rückblick: the whole game told from its event log, and only at the end.

   One scripted game that walks nearly every role through its night, so a
   failure names the fact that went missing; then simulated games, so the
   builder meets logs nobody thought to script. Three things are held on every
   one of them: nothing comes back before :game-over, no string — so no hash, and
   no prose — is ever in a recap, and the leak detector still finds nothing in a
   finished game's view once the recap rides along."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [werwolf.engine.core :as engine]
            [werwolf.engine.events :as e]
            [werwolf.engine.recap :as recap]
            [werwolf.engine.roles :as roles]
            [werwolf.engine.view :as view]
            [werwolf.sim.harness :as h]))

;; ------------------------------------------------------------- the script ---

(def ^:private hash-marker
  "Stands in for a real password hash. Distinctive, so finding it anywhere in a
   recap is unambiguous."
  "$2a$12$RECAP-MUST-NEVER-CARRY-THIS")

(def ^:private dealt
  ;; Seat 9 is dealt a wolf and turned into a villager during setup: the
  ;; Spielleiter arranging the deal is not part of the story, and the recap
  ;; must not tell it.
  {1 :werwolf 2 :weisser-werwolf 3 :seherin 4 :hexe 5 :jaeger
   6 :amor 7 :beschuetzer 8 :dieb 9 :werwolf})

(def ^:private genesis
  (into [{:type :game/created :room-id "RCAP" :host 0 :seed 1 :min-players 5
          :first-night-kill? true :first-day-trial? true :narrated? true :timer? false}
         {:type :room/password-set :hash hash-marker}]
        (map (fn [i] {:type :player/joined :player i :name (str "P" i) :locale :de}))
        (range 10)))

(def ^:private dealt-events
  [{:type :game/started :assignments dealt :centre (roles/centre-for (vals dealt))}
   {:type :phase/changed :phase :setup :deadline nil}])

(def ^:private script
  [;; setup
   {:type :gm/reassign-role :player 0 :target 9 :role :dorfbewohner}
   {:type :begin-night :player 0}
   ;; night 1: every role with a first-night turn
   {:type :dieb-take :player 8 :role :werwolf}
   {:type :amor-link :player 6 :a 4 :b 9}
   {:type :seherin-inspect :player 3 :target 1}
   {:type :beschuetzer-protect :player 7 :target 3}
   {:type :werewolf-vote :player 1 :target 3}
   {:type :werewolf-vote :player 2 :target 3}
   {:type :werewolf-vote :player 8 :target 3}
   {:type :hexe-pass :player 4}
   ;; day 1: an election and a lynch
   {:type :elect :player 1 :target 5} {:type :elect :player 2 :target 5}
   {:type :elect :player 3 :target 5} {:type :elect :player 4 :target 5}
   {:type :elect :player 5 :target 5} {:type :elect :player 6 :target 5}
   {:type :elect :player 7 :target 5} {:type :elect :player 8 :target 5}
   {:type :elect :player 9 :target 5}
   {:type :advance-phase :player 0}
   {:type :nominate :player 3 :target 1}
   {:type :advance-phase :player 0}
   {:type :vote :player 2 :target 1} {:type :vote :player 3 :target 1}
   {:type :vote :player 4 :target 1} {:type :vote :player 5 :target 1}
   {:type :vote :player 6 :target 1} {:type :vote :player 7 :target 1}
   {:type :vote :player 9 :target 1}
   {:type :vote-abstain :player 1} {:type :vote-abstain :player 8}
   ;; night 2: the Weißer turns on the pack, the Hexe saves the wolves' pick
   {:type :seherin-inspect :player 3 :target 2}
   {:type :beschuetzer-protect :player 7 :target 4}
   {:type :werewolf-vote :player 2 :target 9}
   {:type :werewolf-vote :player 8 :target 9}
   {:type :weisser-kill :player 2 :target 8}
   {:type :hexe-heal :player 4 :target 9}
   {:type :hexe-pass :player 4}
   ;; day 2: a card rewritten mid-game, and a lover lynched
   {:type :gm/reassign-role :player 0 :target 7 :role :dorfbewohner}
   {:type :advance-phase :player 0}
   {:type :nominate :player 2 :target 4}
   {:type :advance-phase :player 0}
   {:type :vote :player 2 :target 4} {:type :vote :player 3 :target 4}
   {:type :vote :player 5 :target 4} {:type :vote :player 6 :target 4}
   {:type :vote :player 7 :target 4}
   {:type :vote-abstain :player 4} {:type :vote-abstain :player 9}
   ;; night 3: the Spielleiter skips the Seherin
   {:type :gm/skip-role :player 0}
   ;; day 3: the Bürgermeister, who is the Jäger, is taken out of the game
   {:type :gm/force-kill :player 0 :target 5}
   {:type :mayor-appoint :player 5 :target 3}
   {:type :jaeger-shoot :player 5 :target 2}])

(defn- play
  "Run commands from a log, returning [state full-log]. Throws on a refusal —
   a script that silently stops halfway would test a different game."
  [events cmds]
  (reduce (fn [[s evs] cmd]
            (let [r (engine/execute s cmd)]
              (when (engine/error? r)
                (throw (ex-info "command rejected" {:cmd cmd :error r :phase (:phase s)})))
              [(:state r) (into evs (:events r))]))
          [(e/replay events) events]
          cmds))

(def ^:private game (delay (play (into genesis dealt-events) script)))
(defn- final-state [] (first @game))
(defn- log [] (second @game))
(defn- the-recap [] (recap/recap (log)))

(defn- round [rc kind n]
  (some #(when (and (= kind (:kind %)) (= n (:n %))) %) (:rounds rc)))

(defn- entries-of [rc kind n] (:entries (round rc kind n)))

(defn- player [rc pid] (some #(when (= pid (:id %)) %) (:players rc)))

(defn- strings-in
  "Every string anywhere in a structure."
  [x]
  (filter string? (tree-seq coll? seq x)))

;; ------------------------------------------------------------- the script ---

(deftest the-scripted-game-ends
  (is (= :game-over (:phase (final-state))))
  (is (= :village (:winner (final-state)))))

(deftest the-cast-says-who-everyone-was-and-how-it-ended
  (let [rc (the-recap)]
    (is (= :village (:winner rc)))
    (testing "every player of the game in seat order — the Spielleiter held no card"
      (is (= [1 2 3 4 5 6 7 8 9] (mapv :id (:players rc)))))
    (testing "the card a player ended on, and the one they started with when it differs"
      (is (= {:id 8 :role :werwolf :alive? false :dealt :dieb
              :death {:cause :weisser :kind :night :n 2}}
             (player rc 8)))
      (is (= :beschuetzer (:dealt (player rc 7))) "the mid-game rewrite shows")
      (is (= :dorfbewohner (:role (player rc 7)))))
    (testing "the setup is the deal, not a change to it"
      (is (= {:id 9 :role :dorfbewohner :alive? false :lover? true
              :death {:cause :liebe :kind :day :n 2}}
             (player rc 9))))
    (testing "lovers, the last Bürgermeister, survivors"
      (is (:lover? (player rc 4)))
      (is (:mayor? (player rc 3)))
      (is (= {:id 6 :role :amor :alive? true} (player rc 6))))
    (testing "deaths say how and when"
      (is (= {:cause :lynch :kind :day :n 1} (:death (player rc 1))))
      (is (= {:cause :gm :kind :day :n 3} (:death (player rc 5))))
      (is (= {:cause :jaeger :kind :day :n 3} (:death (player rc 2)))))))

(deftest the-rounds-run-night-day-night-in-order
  (is (= [[:night 1] [:day 1] [:night 2] [:day 2] [:night 3] [:day 3]]
         (mapv (juxt :kind :n) (:rounds (the-recap))))))

(deftest the-first-night-tells-every-secret
  (let [es (entries-of (the-recap) :night 1)]
    (is (= [{:type :dieb/took :player 8 :took :werwolf :from :dieb}
            {:type :amor/linked :lovers [4 9]}
            {:type :seherin/inspected :player 3 :target 1 :saw :werwolf}
            {:type :beschuetzer/protected :player 7 :target 3}
            {:type :wolves/chose :votes {1 3 2 3 8 3} :target 3 :guarded? true}
            {:type :night/quiet}]
           es))))

(deftest the-first-day-has-the-election-and-the-lynch-with-their-ballots
  (let [[election nominated lynch died] (entries-of (the-recap) :day 1)]
    (is (= :election/resolved (:type election)))
    (is (= 5 (:mayor election)))
    (is (= (zipmap (range 1 10) (repeat 5)) (:votes election))
        "ballots that were secret while the election ran")
    (is (= {:type :player/nominated :by 3 :target 1} nominated))
    (is (= {:type :vote/resolved :target 1 :tie? false
            :votes {1 nil 2 1 3 1 4 1 5 1 6 1 7 1 8 nil 9 1}}
           lynch)
        "abstentions included")
    (is (= {:type :player/died :player 1 :cause :lynch} died))))

(deftest the-second-night-has-the-white-wolf-and-the-witch
  (let [es (entries-of (the-recap) :night 2)]
    (is (= {:type :seherin/inspected :player 3 :target 2 :saw :weisser-werwolf}
           (first es))
        "what the card really was — not what the pack was shown")
    (is (some #{{:type :wolves/chose :votes {2 9 8 9} :target 9}} es))
    (is (some #{{:type :weisser/killed :player 2 :target 8}} es))
    (is (some #{{:type :hexe/healed :player 4 :target 9}} es))
    (is (= {:type :player/died :player 8 :cause :weisser} (last es))
        "the dawn's dead belong to the night that killed them")))

(deftest overrides-are-told-with-both-cards
  (let [rc (the-recap)]
    (is (= {:type :gm/reassigned :target 7 :from :beschuetzer :to :dorfbewohner}
           (first (entries-of rc :day 2))))
    (is (= [{:type :player/died :player 4 :cause :lynch}
            {:type :player/died :player 9 :cause :liebe}]
           (take-last 2 (entries-of rc :day 2))))
    (is (= [{:type :gm/forced :action :skip-role :skipped :seherin}
            {:type :night/quiet}]
           (entries-of rc :night 3)))
    (testing "the Spielleiter's setup is not in the story at all"
      (is (= [7] (keep #(when (= :gm/reassigned (:type %)) (:target %))
                       (mapcat :entries (:rounds rc))))))))

(deftest the-last-day-has-the-succession-and-the-shot
  (is (= [{:type :player/died :player 5 :cause :gm}
          {:type :mayor/appointed :player 3 :by 5}
          {:type :jaeger/shot :player 5 :target 2}
          {:type :player/died :player 2 :cause :jaeger}]
         (entries-of (the-recap) :day 3))))

;; ---------------------------------------------------------------- fences ---

(deftest nothing-is-told-before-the-end
  (testing "every prefix of the log is a game still in progress, and recaps to nil"
    (let [evs (log)]
      (doseq [n (range (count evs))]
        (is (nil? (recap/recap (subvec evs 0 n))) (str "a recap after " n " events"))))))

(deftest a-recap-holds-no-strings-so-no-hash
  (let [rc (the-recap)]
    (is (some? rc))
    (is (empty? (strings-in rc))
        "names come from the view and sentences from i18n — a string here is a leak or prose")
    (is (not (str/includes? (pr-str rc) "RECAP-MUST-NEVER")))
    (testing "the hash was in the log all along"
      (is (some #(= hash-marker (:hash %)) (log))))))

(deftest what-happens-after-the-end-changes-nothing
  (testing "players drifting away at the end — or a reboot dropping every
            socket — must not rewrite the story"
    (is (= (the-recap)
           (recap/recap (into (log) [{:type :player/connection :player 3 :connected? false :at 1}
                                     {:type :player/left :player 6}]))))))

(deftest the-leak-detector-still-finds-nothing-with-the-recap-attached
  (let [s (final-state) rc (the-recap)]
    (doseq [pid (keys (:players s))]
      (is (nil? (view/redaction-leaks? s pid (assoc (view/view-for s pid) :recap rc)))
          (str "seat " pid)))))

(deftest an-abandoned-game-is-told-up-to-where-it-stopped
  (testing "the server retires a game nobody came back to: over, with no winner"
    ;; Cut off just before the first death — the day-1 lynch — and retired.
    (let [evs (-> (vec (take-while #(not= :player/died (:type %)) (log)))
                  (conj {:type :game/abandoned}))
          rc  (recap/recap evs)]
      (is (some? rc))
      (is (nil? (:winner rc)))
      (is (= {:type :game/abandoned} (last (:entries (last (:rounds rc))))))
      (is (= [[:night 1] [:day 1]] (mapv (juxt :kind :n) (:rounds rc))))
      (is (every? :alive? (:players rc)) "nobody had died yet")
      (is (empty? (strings-in rc))))))

;; ------------------------------------------------------------ simulated ---

(defn- with-password
  "A simulated log with a locked door: the hash right after genesis, as
   `room/create!` writes it."
  [log]
  (into [(first log) {:type :room/password-set :hash hash-marker}] (rest log)))

(def ^:private sims
  (delay
    (vec (concat (for [[n seed] [[5 3] [7 17] [9 29] [12 41] [16 53]]]
                   (h/play-game n seed))
                 (for [[n seed] [[6 61] [10 71]]]
                   (h/play-game n seed {:narrated? true}))
                 (for [[n seed] [[8 83] [11 97]]]
                   (h/play-game n seed {:random-deck? true}))))))

(deftest simulated-games-recap-cleanly
  (doseq [{:keys [ok? log state seed players]} @sims
          :let [tag (str players " players, seed " seed)
                log (with-password log)
                rc  (recap/recap log)]]
    (is ok? tag)
    (is (some? rc) tag)
    (is (empty? (strings-in rc)) tag)
    (testing "every death the table saw is in the story, with its cause"
      (is (= (keep #(when (= :player/died (:type %)) [(:player %) (:cause %)]) (:log state))
             (keep #(when (= :player/died (:type %)) [(:player %) (:cause %)])
                   (mapcat :entries (:rounds rc))))
          tag))
    (testing "the cast is every player of the game, with the card they ended on"
      (is (= (into {} (map (fn [pid] [pid (get-in state [:players pid :role])]))
                   (e/participant-ids state))
             (into {} (map (juxt :id :role)) (:players rc)))
          tag)
      (is (= (set (remove #(get-in state [:players % :alive?]) (e/participant-ids state)))
             (set (keep #(when-not (:alive? %) (:id %)) (:players rc))))
          tag))
    (testing "and no seat finds a card in it that the end of the game did not grant"
      (doseq [pid (keys (:players state))]
        (is (nil? (view/redaction-leaks? state pid (assoc (view/view-for state pid) :recap rc)))
            (str tag ", seat " pid))))
    (testing "a game cut short anywhere is told to nobody"
      (doseq [n [1 (quot (count log) 3) (quot (count log) 2) (dec (count log))]]
        (is (nil? (recap/recap (subvec log 0 n))) (str tag ", after " n))))))
