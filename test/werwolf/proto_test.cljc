(ns werwolf.proto-test
  "The wire contract, checked against the engine rather than against memory.

   This file exists because of a specific failure. `proto/Command` is a closed
   map, and it was written with `:target` but not Amor's `:a`/`:b`. Every other
   test passed: the engine was correct, the room layer was correct, the protocol
   layer dutifully rejected the message as malformed. The only symptom was that
   Amor could not be played, and nothing in the suite was looking at the seam
   between 'what the engine will accept' and 'what the wire will carry'.

   So the corpus here is generated: play real games, take every command the
   engine considered legal, and require that each one can cross the wire. Adding
   a role or an action cannot silently fail to be expressible again."
  (:require [clojure.test :refer [deftest is testing]]
            [werwolf.engine.commands :as commands]
            [werwolf.engine.phases :as phases]
            [werwolf.proto :as proto]
            [werwolf.sim.harness :as h]))

(defn- as-wire
  "Wrap an engine command the way a client must send it. `:player` is dropped
   because the server overwrites it with the authenticated seat, and the schema
   rejects it rather than ignore it."
  [cmd]
  {:type :client/command :cmd (dissoc cmd :player)})

(deftest every-command-a-real-game-produces-is-expressible-on-the-wire
  (let [cmds (into []
                   (comp (mapcat :history)
                         (remove :no-target)
                         (remove :rejected))
                   (concat
                    (for [[n seed] [[5 11] [7 22] [9 33] [12 44] [18 55]]]
                      (h/play-game n seed))
                    ;; The Spielleiter's own moves — beginning the night,
                    ;; ending the phases the clock used to — cross the wire too.
                    (for [[n seed] [[6 66] [10 77]]]
                      (h/play-game n seed {:narrated? true}))))]
    (is (> (count cmds) 200) "the corpus is real games, not a handful of moves")
    (doseq [cmd cmds]
      (is (proto/valid-client-message? (as-wire cmd))
          (str "the engine accepts this but the wire cannot carry it: "
               (pr-str cmd))))
    (testing "and the corpus actually covers the awkward shapes"
      (let [types (set (map :type cmds))]
        (is (contains? types :amor-link) "including Amor's two-target link")
        (is (some #(and (= :amor-link (:type %)) (:a %) (:b %)) cmds))))))

(deftest the-whitelist-covers-everything-the-engine-can-offer
  (testing "an action legal-actions can hand a player must be one the protocol
            will carry, or the UI shows a button whose click is rejected"
    (let [offered (into #{}
                        (comp (mapcat :history) (map :type))
                        (concat
                         (for [[n seed] [[5 11] [9 33] [18 55]]]
                           (h/play-game n seed))
                         (for [[n seed] [[6 66] [10 77]]]
                           (h/play-game n seed {:narrated? true}))))
          unlisted (remove proto/command-types offered)]
      (is (empty? unlisted) (str "not in the whitelist: " (pr-str unlisted))))))

(deftest the-whitelist-has-no-dead-entries
  (testing "every listed command is one the engine actually implements —
            a whitelist that names commands nobody handles is a lie about the
            protocol's surface"
    (let [handled (set (keys (methods commands/handle)))]
      (doseq [c proto/command-types]
        (is (contains? handled c) (str c " is on the whitelist but unhandled"))))))

(deftest the-spielleiters-switches-are-on-the-wire
  (testing "the simulation never issues GM overrides, so the corpus above cannot
            vouch for these; say it out loud instead"
    (doseq [cmd [{:type :begin-night}
                 {:type :gm/toggle-narrated}
                 {:type :gm/toggle-timer}
                 {:type :gm/transfer-host :target 2}
                 {:type :gm/reassign-role :target 2 :role :hexe}
                 {:type :gm/set-deck :deck {:werwolf 2 :seherin 1 :dieb 0}}
                 {:type :gm/set-deck :deck {}}
                 {:type :gm/auto-deck}
                 {:type :gm/add-bot}]]
      (is (proto/valid-client-message? (as-wire cmd)) (pr-str cmd)))
    (doseq [a [:begin-night :gm/toggle-narrated :gm/toggle-timer
               :gm/set-deck :gm/auto-deck :gm/add-bot]]
      (is (contains? phases/no-target-actions a)
          (str a " names nobody, so it must be offerable without a target")))))

(deftest declining-is-always-targetless
  (testing "every 'no' action must be sendable without a target, or the UI has
            to invent one to say no"
    (doseq [a [:hexe-pass :vote-abstain :jaeger-pass :ready]]
      (is (contains? phases/no-target-actions a))
      (is (proto/valid-client-message? (as-wire {:type a}))))))

(deftest amor-is-the-only-two-target-action
  (testing "if that stops being true, the client needs no change — it reads
            arity from the view — but this test should say so out loud"
    (is (= {:amor-link 2} phases/target-arity))))

(deftest a-password-is-optional-bounded-and-never-echoed
  (testing "create and join take one, and do not need one"
    (doseq [msg [{:type :client/create :name "Anna"}
                 {:type :client/create :name "Anna" :password "Vollmond"}
                 {:type :client/join :room-id "ABCD" :name "Bob"}
                 {:type :client/join :room-id "ABCD" :name "Bob" :password "Vollmond"}]]
      (is (proto/valid-client-message? msg) (pr-str msg))))
  (testing "the host sets one, or clears it with nil"
    (is (proto/valid-client-message? {:type :client/set-password :password "Vollmond"}))
    (is (proto/valid-client-message? {:type :client/set-password :password nil}))
    (is (not (proto/valid-client-message? {:type :client/set-password}))
        "saying nothing is not the same as saying 'open the door'"))
  (testing "bounded, so a frame cannot hand the hash a megabyte to chew on"
    (doseq [bad ["" (apply str (repeat 65 "x")) 42]]
      (is (not (proto/valid-client-message? {:type :client/join :room-id "ABCD"
                                             :name "Bob" :password bad})))
      (is (not (proto/valid-client-message? {:type :client/set-password :password bad})))))
  (testing "a rejection names the place, never the value"
    (let [secret (apply str "Geheim" (repeat 70 "x"))
          why    (proto/explain-client-message {:type :client/create :name "Anna"
                                                :password secret})]
      (is (= [{:in [:password]}] why))
      (is (not (re-find #"Geheim" (pr-str why)))))))

(deftest a-rejection-does-not-grow-with-the-junk
  (testing "each error carried its printed schema, so 5000 keys too many came
            back as 5000 copies of the command whitelist: 54 KB in, 4.7 MB out"
    (let [why (proto/explain-client-message
               {:type :client/command
                :cmd (into {:type :vote} (map (fn [i] [(keyword (str "k" i)) i])) (range 5000))})]
      (is (= 3 (count why)))
      (is (every? #(= :malli.core/extra-key (:error %)) why))
      (is (< (count (pr-str why)) 200))))
  (testing "a key is the client's own, but a long one is not sent back"
    (let [huge (keyword (apply str (repeat 10000 "x")))
          why  (proto/explain-client-message {:type :client/ping huge 1})]
      (is (= [{:in ["…"] :error :malli.core/extra-key}] why))))
  (testing "nor a whitelist for a :type the protocol does not know"
    (is (< (count (pr-str (proto/explain-client-message {:type :client/nonsense}))) 200))))

(deftest a-deck-is-bounded-on-the-wire
  (testing "the deck is the one command payload that is a whole map, so its
            shape is held at the door: counts a table could hold, keyed by
            keywords, and no more entries than a deck has roles to name. What
            those keywords may BE is the engine's business — see
            `roles/deck-errors` — because the registry is not wire vocabulary"
    (doseq [deck [{:werwolf -1}
                  {:werwolf 19}
                  {:werwolf 1.5}
                  {"werwolf" 1}
                  {:werwolf nil}
                  [[:werwolf 1]]
                  (into {} (map (fn [i] [(keyword (str "r" i)) 1])) (range 17))]]
      (is (not (proto/valid-client-message? (as-wire {:type :gm/set-deck :deck deck})))
          (pr-str deck)))
    (is (proto/valid-client-message?
         (as-wire {:type :gm/set-deck :deck {:werwolf 18}})))))

(deftest a-seat-on-the-wire-is-a-seat
  (testing "bounded like every other number a frame carries: a revive of a seat
            past 2^53 once left every page of the room blank"
    (doseq [cmd [{:type :gm/revive :target 9007199254740993}
                 {:type :gm/revive :target -1}
                 {:type :vote :target 10000}
                 {:type :amor-link :a 0 :b 10000}
                 {:type :amor-link :a -1 :b 1}]]
      (is (not (proto/valid-client-message? (as-wire cmd))) (pr-str cmd)))
    (is (proto/valid-client-message? (as-wire {:type :gm/revive :target 9999})))))
