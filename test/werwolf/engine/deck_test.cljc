(ns werwolf.engine.deck-test
  "The Spielleiter's deck: which roles are in the game, chosen in the lobby.

   Absent is automatic, and automatic must stay exactly what it was — every log
   written before the host could choose has to replay into the game it was. A
   chosen deck is specials only; the villagers fill whatever the table leaves,
   so a deck that fits six fits seven too. Whether it fits at all is asked of
   the live table, not of the moment it was chosen."
  (:require [clojure.test :refer [deftest is testing]]
            [werwolf.engine.core :as engine]
            [werwolf.engine.events :as e]
            [werwolf.engine.phases :as phases]
            [werwolf.engine.roles :as roles]
            [werwolf.engine.schema :as schema]
            [werwolf.engine.view :as view]
            [werwolf.test-util :as tu :refer [exec! try-exec]]))

(defn- genesis
  "A lobby of n players with seat 0 the host, as events — so a test can replay it."
  ([n] (genesis n {}))
  ([n {:keys [narrated?]}]
   (into [(cond-> {:type :game/created :room-id "DECK" :host 0 :seed 7 :min-players 5}
            narrated? (assoc :narrated? true :timer? false))]
         (map (fn [i] {:type :player/joined :player i :name (str "P" i) :locale :de}))
         (range n))))

(defn- lobby [n & [opts]] (e/replay (genesis n opts)))

(defn- dealt
  "The cards actually in players' hands, as counts."
  [state]
  (frequencies (keep (fn [[_ p]] (:role p)) (:players state))))

(defn- set-deck [state deck] (exec! state {:type :gm/set-deck :player 0 :deck deck}))

;; ------------------------------------------------------------- automatic ---

(deftest no-deck-is-the-automatic-deck
  (let [s (lobby 7)]
    (is (not (contains? s :deck)) "absent, not nil — the shape older logs replay into")
    (is (nil? (phases/deck-problem s)))
    (is (contains? (phases/legal-actions s 0) :start-game))
    (is (not (contains? (phases/legal-actions s 0) :gm/auto-deck))
        "nothing to go back from")
    (let [v (view/view-for s 3)]
      (is (nil? (:deck v)))
      (is (= (roles/composition 7 nil) (:deck-preview v)))
      (is (= {:werwolf 2 :seherin 1 :hexe 1 :jaeger 1 :amor 1 :dorfbewohner 1}
             (get-in v [:deck-preview :dealt]))))
    (testing "and the deal is the one the table size always dealt"
      (let [s (exec! s {:type :start-game :player 0})]
        (is (= (frequencies (roles/compose-roles 7)) (dealt s)))))))

;; ------------------------------------------------------------- choosing ---

(deftest the-host-chooses-specials-and-villagers-fill-the-rest
  (let [s (set-deck (lobby 6) {:werwolf 2 :seherin 1})]
    (is (= {:werwolf 2 :seherin 1} (:deck s)))
    (is (= {:type :deck/set :deck {:werwolf 2 :seherin 1}} (peek (:log s)))
        "public, with the deck in it — the table knows what is in the game")
    (is (= {:dealt {:werwolf 2 :seherin 1 :dorfbewohner 3} :centre {}}
           (:deck-preview (view/view-for s 4))))
    (let [s (exec! s {:type :start-game :player 0})]
      (is (= {:werwolf 2 :seherin 1 :dorfbewohner 3} (dealt s)))
      (is (= [] (:centre s)))
      (is (schema/valid-state? s) (pr-str (schema/explain-state s))))))

(deftest zeros-are-not-cards
  (let [s (set-deck (lobby 6) {:werwolf 1 :hexe 0 :amor 0})]
    (is (= {:werwolf 1} (:deck s)))
    (testing "and sending the deck the room already has says nothing twice"
      (let [s' (set-deck s {:werwolf 1 :jaeger 0})]
        (is (= (:log s) (:log s')))))))

(deftest a-chosen-dieb-brings-his-two-cards
  (let [s (set-deck (lobby 5) {:werwolf 1 :dieb 1})
        v (view/view-for s 2)]
    (is (= {:werwolf 1 :dorfbewohner 1} (get-in v [:deck-preview :centre]))
        "the middle is listed, and listed apart from the hands")
    (is (= {:werwolf 1 :dieb 1 :dorfbewohner 3} (get-in v [:deck-preview :dealt])))
    (let [s (exec! s {:type :start-game :player 0})]
      (is (= {:werwolf 1 :dieb 1 :dorfbewohner 3} (dealt s)))
      (is (= roles/centre-extras (:centre s))))
    (testing "and a table the size alone would give a Dieb loses him when the
              host leaves him out"
      (let [s (-> (lobby 10) (set-deck {:werwolf 2 :seherin 1})
                  (exec! {:type :start-game :player 0}))]
        (is (= [] (:centre s)))
        (is (not (contains? (dealt s) :dieb)))))))

(deftest only-the-host-only-in-the-lobby
  (let [s (lobby 6)]
    (is (= :not-allowed (:error (second (try-exec s {:type :gm/set-deck :player 2
                                                     :deck {:werwolf 1}})))))
    (is (not (contains? (phases/legal-actions s 2) :gm/set-deck)))
    (is (contains? (phases/legal-actions s 0) :gm/set-deck))
    (let [s (exec! s {:type :start-game :player 0})]
      (is (= :not-allowed (:error (second (try-exec s {:type :gm/set-deck :player 0
                                                       :deck {:werwolf 1}})))))
      (is (= :not-allowed (:error (second (try-exec s {:type :gm/auto-deck :player 0}))))))))

(deftest a-deck-names-specials-within-their-limits
  (let [s (lobby 8)
        refused (fn [deck] (second (try-exec s {:type :gm/set-deck :player 0 :deck deck})))]
    (testing "the villager is the filler, not a choice"
      (is (= {:error :unknown-role :detail {:role :dorfbewohner}}
             (refused {:werwolf 1 :dorfbewohner 3}))))
    (is (= :unknown-role (:error (refused {:vampir 1}))))
    (testing "the registry's :max-count binds a composition, which is what it describes"
      (is (= {:error :too-many-of-role :detail {:role :seherin :max 1}}
             (refused {:werwolf 1 :seherin 2}))))
    (is (= :invalid-count (:error (refused {:werwolf -1}))))
    (is (= :invalid-count (:error (refused {:werwolf 1.5}))))
    (is (= :invalid-deck (:error (refused [[:werwolf 1]]))))
    (testing "no deck holds more cards than the room has seats"
      (is (= {:error :deck-too-large :detail {:max 18}} (refused {:werwolf 19}))))
    (testing "wolves have no limit but the table"
      (is (= {:werwolf 5} (:deck (set-deck s {:werwolf 5})))))))

;; ------------------------------------------------------------- the start ---

(defn- refusal [s]
  [(phases/deck-problem s)
   (contains? (phases/legal-actions s 0) :start-game)
   (:deck-problem (view/view-for s 3))
   (second (try-exec s {:type :start-game :player 0}))])

(deftest a-deck-that-does-not-fit-cannot-start-and-says-why
  (testing "more specials than seats"
    (let [s (set-deck (lobby 5) {:werwolf 2 :seherin 1 :hexe 1 :jaeger 1 :amor 1})]
      (is (= [:too-many-roles false :too-many-roles
              {:error :deck-unplayable :detail {:problem :too-many-roles}}]
             (refusal s)))))
  (testing "nobody hunts the village"
    (is (= :no-werewolf (first (refusal (set-deck (lobby 5) {:seherin 1})))))
    (is (= :no-werewolf (first (refusal (set-deck (lobby 5) {}))))
        "a deck of nothing but villagers is a deck, and an unplayable one")
    (testing "and the Weißer Werwolf is not a pack on his own"
      (is (= :no-werewolf (first (refusal (set-deck (lobby 5) {:weisser-werwolf 1})))))
      (is (nil? (first (refusal (set-deck (lobby 5) {:weisser-werwolf 1 :werwolf 1})))))))
  (testing "nobody but the wolves"
    (is (= :no-villager (first (refusal (set-deck (lobby 4) {:werwolf 4})))))
    (is (= :no-villager
           (first (refusal (set-deck (lobby 4) {:werwolf 3 :weisser-werwolf 1}))))))
  (testing "a pack as big as everybody else: the village could never reach a
            majority against it"
    (is (= :too-many-werewolves (first (refusal (set-deck (lobby 4) {:werwolf 2})))))
    (is (nil? (first (refusal (set-deck (lobby 5) {:werwolf 2}))))))
  (testing "a deck that fits is offered like any other"
    (let [s (set-deck (lobby 5) {:werwolf 1 :seherin 1})]
      (is (= [nil true nil nil] (refusal s)))))
  (testing "nobody else is told a reason they cannot act on — but everyone
            sees it, because everyone sees the deck"
    (let [s (set-deck (lobby 5) {:seherin 1})]
      (is (= :no-werewolf (:deck-problem (view/view-for s 4))))
      (is (= :not-allowed (:error (second (try-exec s {:type :start-game :player 4}))))
          "a player's start is refused as it always was, not with the host's reason"))))

(deftest the-fit-follows-the-table
  (let [deck {:werwolf 1 :seherin 1 :hexe 1 :jaeger 1 :amor 1}
        s (set-deck (lobby 4) deck)]
    (is (= :too-many-roles (phases/deck-problem s)))
    (let [s (exec! s {:type :join :player 4 :name "P4"})]
      (is (= deck (:deck s)) "a player sitting down does not touch the deck")
      (is (nil? (phases/deck-problem s)) "five seats, five specials: it fits now")
      (is (= {} (dissoc (get-in (view/view-for s 0) [:deck-preview :dealt])
                        :werwolf :seherin :hexe :jaeger :amor))
          "and no villager is needed to fill it")
      (let [s (exec! s {:type :leave :player 4})]
        (is (= deck (:deck s)))
        (is (= :too-many-roles (phases/deck-problem s)) "and the next one leaving undoes it")))
    (testing "the host deciding to narrate is a seat leaving the table"
      (let [s (-> (lobby 5) (set-deck deck))]
        (is (nil? (phases/deck-problem s)))
        (let [s (exec! s {:type :gm/toggle-narrated :player 0})]
          (is (= :too-many-roles (phases/deck-problem s))))))))

(deftest the-narrators-table-is-dealt-the-chosen-deck
  (let [s (-> (lobby 6 {:narrated? true})
              (set-deck {:werwolf 1 :hexe 1})
              (exec! {:type :start-game :player 0}))]
    (is (= :setup (:phase s)))
    (is (nil? (get-in s [:players 0 :role])))
    (is (= {:werwolf 1 :hexe 1 :dorfbewohner 3} (dealt s)))))

;; ------------------------------------------------------------ automatic again ---

(deftest auto-restores-the-default
  (let [s (-> (lobby 7) (set-deck {:werwolf 3}))
        _ (is (contains? (phases/legal-actions s 0) :gm/auto-deck))
        s (exec! s {:type :gm/auto-deck :player 0})]
    (is (not (contains? s :deck)))
    (is (= {:type :deck/auto} (peek (:log s))))
    (is (not (contains? (phases/legal-actions s 0) :gm/auto-deck)))
    (is (= (roles/composition 7 nil) (:deck-preview (view/view-for s 1))))
    (let [s (exec! s {:type :start-game :player 0})]
      (is (= (frequencies (roles/compose-roles 7)) (dealt s))))))

;; ------------------------------------------------------------- replay ---

(deftest a-chosen-deck-replays
  (let [start (lobby 6)
        steps [{:type :gm/set-deck :player 0 :deck {:werwolf 1 :dieb 1 :seherin 1}}
               {:type :gm/set-deck :player 0 :deck {:werwolf 2 :dieb 1}}
               {:type :start-game :player 0}]
        [final log] (reduce (fn [[s log] cmd]
                              (let [r (engine/execute s cmd)]
                                (is (not (engine/error? r)) (pr-str cmd r))
                                [(:state r) (into log (:events r))]))
                            [start (genesis 6)]
                            steps)]
    (is (= final (e/replay log)) "the event log folds back to the same game")
    (is (schema/valid-state? final) (pr-str (schema/explain-state final)))
    (is (= {:werwolf 2 :dieb 1 :dorfbewohner 3} (dealt final)))
    (is (= roles/centre-extras (:centre final)))))

(deftest the-deck-stays-public-during-the-game
  (testing "the composition is computed from the DECK, so nothing that moves
            after the shuffle — the Dieb's swap, a card the Spielleiter
            rewrote — shows up in it"
    (let [s (-> (lobby 5) (set-deck {:werwolf 1 :dieb 1})
                (exec! {:type :start-game :player 0}))
          before (:deck-preview (view/view-for s 1))
          dieb (some (fn [[id p]] (when (= :dieb (:role p)) id)) (:players s))
          s (exec! s {:type :dieb-take :player dieb :role :werwolf})]
      (is (= before (:deck-preview (view/view-for s 1))))
      (is (= {:werwolf 1 :dieb 1} (:deck (view/view-for s 1))))
      (is (not (contains? (view/view-for s 1) :deck-problem))
          "the problem and the menu are the lobby's, and the lobby is over")
      (is (not (contains? (view/view-for s 1) :deck-roles))))))

(deftest the-menu-is-every-special-with-its-limit
  (let [roles-menu (:deck-roles (view/view-for (lobby 5) 0))]
    (is (= roles/special-ids (mapv :role roles-menu)))
    (is (not (some #{:dorfbewohner} (map :role roles-menu))))
    (is (= (set (remove #{:dorfbewohner} roles/all-ids)) (set (map :role roles-menu))))
    (is (= {:role :werwolf :max nil} (first roles-menu)) "the pack first")
    (is (= 1 (:max (first (filter #(= :seherin (:role %)) roles-menu)))))))

;; ------------------------------------------------- the Spielleiter's deal ---

(defn- setup-with [assignments]
  (tu/narrated-setup-with assignments))

(deftest a-deal-rearranged-into-no-game-cannot-begin
  (let [ok {1 :werwolf 2 :seherin 3 :dorfbewohner 4 :dorfbewohner 5 :hexe}
        s  (setup-with ok)]
    (is (nil? (phases/deal-problem s)))
    (is (contains? (phases/legal-actions s 0) :begin-night))
    (testing "the Spielleiter turns the wolf into a villager"
      (let [s (exec! s {:type :gm/reassign-role :player 0 :target 1 :role :dorfbewohner})]
        (is (= :no-werewolf (phases/deal-problem s)))
        (is (not (contains? (phases/legal-actions s 0) :begin-night))
            "the night is not offered")
        (is (= {:error :deal-unplayable :detail {:problem :no-werewolf}}
               (second (try-exec s {:type :begin-night :player 0})))
            "and refused with the reason")
        (testing "which only he is told: to the table it would be a role leak"
          (is (= :no-werewolf (get-in (view/view-for s 0) [:narrator-info :deal-problem])))
          (is (nil? (:narrator-info (view/view-for s 2)))))
        (testing "and putting a wolf back makes it a game again"
          (let [s (exec! s {:type :gm/reassign-role :player 0 :target 3 :role :werwolf})]
            (is (nil? (phases/deal-problem s)))
            (is (= :night (:phase (exec! s {:type :begin-night :player 0}))))))))
    (testing "every card a wolf, or a pack as big as the rest"
      (is (= :no-villager (phases/deal-problem (setup-with {1 :werwolf 2 :werwolf 3 :werwolf}))))
      (is (= :too-many-werewolves
             (phases/deal-problem (setup-with {1 :werwolf 2 :werwolf 3 :seherin 4 :hexe})))))))
