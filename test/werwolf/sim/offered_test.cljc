(ns werwolf.sim.offered-test
  "Whatever the engine accepts, it offered.

   The simulation asks the other half — every offered move is accepted — and
   only ever sends offered moves, so a handler that accepted MORE than it
   offered went unnoticed. Every such case the audit found was a GM override
   that checked only that the sender was the host: :gm/set-phase :game-over in
   the middle of a game, :gm/revive of a seat nobody held, :gm/skip-role of a
   role not being called, a pause in the lobby, a kill after the game was
   decided. A hand-built frame is all it takes, and the client offering only
   legal buttons is a convenience, not a control.

   So: walk real games, and at their states send commands built from anything
   at all — every seat, seats that do not exist, every role and phase and a few
   words that are neither. Whatever comes back accepted must have been on the
   sender's menu, with its seat and its word among the ones offered."
  (:require [clojure.test :refer [deftest is testing]]
            [werwolf.engine.commands :as commands]
            [werwolf.engine.core :as engine]
            [werwolf.engine.phases :as phases]
            [werwolf.engine.rng :as rng]
            [werwolf.engine.roles :as roles]
            [werwolf.proto :as proto]
            [werwolf.sim.harness :as h]))

(defn- states
  "Every settled state of one simulated game, start to end."
  [n seed opts]
  (let [{:keys [ok? history]} (h/play-game n seed opts)
        [start] (h/new-sim-game n seed opts)]
    (assert ok? (str "the simulated game " [n seed opts] " did not finish"))
    (reductions (fn [s cmd] (:state (engine/execute s cmd))) start history)))

(def ^:private command-types
  ;; :join is how a seat gets into the room at all, so it is on nobody's menu.
  (sort-by str (disj (set (keys (methods commands/handle))) :default :join)))

(def ^:private words
  (into (vec (sort roles/all-ids))
        [:lobby :setup :night :day-reveal :election :discussion :day-resolve
         :reaction :game-over :bogus nil]))

(defn- crafted
  "`k` commands built from anything at all, half of them from the host."
  [state seed k]
  (let [seats (into (vec (sort (keys (:players state)))) [-1 99 nil])]
    (loop [seed seed, acc []]
      (if (= k (count acc))
        acc
        (let [[seed t]      (rng/rand-nth seed command-types)
              [seed host?]  (rng/rand-nth seed [true false])
              [seed p]      (rng/rand-nth seed seats)
              [seed target] (rng/rand-nth seed seats)
              [seed b]      (rng/rand-nth seed seats)
              [seed word]   (rng/rand-nth seed words)]
          (recur seed (conj acc {:type t :player (if host? (:host state) p)
                                 :target target :a target :b b
                                 :role word :phase word :locale :en :secs 30})))))))

(defn- unoffered
  "Why an accepted command was not on the menu, or nil when it was."
  [state {:keys [type player target a b] :as cmd}]
  (let [offered  (phases/legal-actions state player)
        targets  (set (phases/legal-targets state player type))
        ck       (proto/choice-key type)
        choice   (cond-> (get cmd ck)
                   ;; No role means the one being called; see the handler.
                   (= :gm/skip-role type) (or (:night-cursor state)))]
    (cond
      (not (contains? offered type)) :action-not-offered

      (= :amor-link type)
      (when-not (and (targets a) (targets b)) :target-not-offered)

      (and ck (not (contains? (set (phases/legal-choices state type)) choice)))
      :choice-not-offered

      ;; A seat, where the action takes one. Choice-only actions ignore :target,
      ;; and a vote for nobody is an abstention.
      (and (not (contains? phases/no-target-actions type))
           (or (nil? ck) (= :gm/reassign-role type))
           (some? target)
           (not (targets target)))
      :target-not-offered)))

(deftest whatever-is-accepted-was-offered
  (let [tables [[7 101 {}] [6 202 {:narrated? true}] [9 303 {:host-absent? true}]]
        results (for [[n seed opts] tables
                      [i state] (map-indexed vector (states n seed opts))
                      :when (even? i)
                      cmd (crafted state (+ seed i) 40)
                      :let [r (commands/handle state cmd)]
                      :when (not (commands/error? r))]
                  {:cmd cmd :phase (:phase state) :why (unoffered state cmd)})]
    (is (empty? (filter :why results))
        (pr-str (take 5 (filter :why results))))
    (testing "and the walk reaches the overrides it is about, not only chat and
              the door"
      (is (some #(= "gm" (namespace (:type (:cmd %)))) results))
      (is (< 50 (count results))))))
