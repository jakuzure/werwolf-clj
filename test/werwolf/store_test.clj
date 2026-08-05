(ns werwolf.store-test
  "JVM-only: SQLite persistence. Deliberately .clj, not .cljc — there is no
   ClojureScript counterpart to test."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [werwolf.engine.core :as engine]
            [werwolf.sim.harness :as h]
            [werwolf.store :as store]))

(defn- tmp-ds
  "A fresh on-disk database per test. On disk rather than :memory: because WAL
   mode and reopening are part of what we are testing."
  []
  (let [f (java.io.File/createTempFile "werwolf-test" ".db")]
    (.delete f)
    {:ds (store/open {:dbtype "sqlite" :dbname (.getAbsolutePath f)})
     :path (.getAbsolutePath f)}))

(defn- cleanup! [path]
  (doseq [suffix ["" "-wal" "-shm"]]
    (io/delete-file (str path suffix) true)))

(def genesis
  [{:type :game/created :room-id "ABC" :host 0 :seed 42 :min-players 5}
   {:type :player/joined :player 0 :name "Anna" :locale :de}
   {:type :player/joined :player 1 :name "Bob" :locale :en}])

(deftest statement-splitting-tolerates-semicolons-in-comments
  (testing "prose in a comment must not cut a statement in half — this was a
            real bug: 'the log is the truth; state is a fold over it' split a
            CREATE TABLE into gibberish"
    (let [sql "-- one; two; three\nCREATE TABLE a (x INT);\n-- more; prose\nCREATE TABLE b (y INT);"
          stmts (vec (store/split-statements sql))]
      (is (= 2 (count stmts)))
      (is (every? #(re-find #"(?i)^create table" %) stmts))
      (is (not-any? #(re-find #"--" %) stmts) "comments are stripped"))))

(deftest round-trips-events-losslessly
  (let [{:keys [ds path]} (tmp-ds)]
    (try
      (store/append-events! ds "K" 0
                            [{:type :amor/linked :a 1 :b 2
                              :set #{:x :y} :nested {:k [:a/b]} :n 9}]
                            1)
      (let [ev (first (store/load-events ds "K"))]
        (testing "EDN preserves what JSON would flatten"
          (is (= :amor/linked (:type ev)) "namespaced keyword")
          (is (= #{:x :y} (:set ev)) "set stays a set")
          (is (= {:k [:a/b]} (:nested ev)) "nested namespaced keyword")
          (is (int? (:n ev)) "integer stays an integer")))
      (finally (cleanup! path)))))

(deftest replays-to-the-same-state-as-the-engine
  (let [{:keys [ds path]} (tmp-ds)]
    (try
      (store/append-events! ds "ABC" 0 genesis 1700000000000)
      (let [{:keys [state seq]} (store/load-game ds "ABC")]
        (is (= 3 seq))
        (is (= (engine/replay genesis) state)))
      (finally (cleanup! path)))))

(deftest appends-are-sequential-and-duplicates-rejected
  (let [{:keys [ds path]} (tmp-ds)]
    (try
      (store/append-events! ds "ABC" 0 genesis 1)
      (store/append-events! ds "ABC" 3 [{:type :player/joined :player 2 :name "Cem" :locale :de}] 2)
      (is (= 4 (:seq (store/load-game ds "ABC"))))
      (testing "re-using a seq is a hard error, never a silent duplicate"
        (is (thrown? Exception
                     (store/append-events! ds "ABC" 0 [{:type :player/left :player 0}] 3))))
      (finally (cleanup! path)))))

(deftest survives-a-simulated-restart
  (testing "a full game persisted, then reloaded from a REOPENED database,
            must fold back to exactly the state the live server held"
    (let [{:keys [ds path]} (tmp-ds)
          game (h/play-game 9 4242)]
      (try
        (is (:ok? game))
        (store/append-events! ds "SIM" 0 (:log game) 1)
        (store/upsert-room! ds "SIM" (:phase (:state game)) true 1)
        ;; Reopen as a brand new datasource — this is the restart.
        (let [ds2 (store/open {:dbtype "sqlite" :dbname path})
              {:keys [state seq]} (store/load-game ds2 "SIM")]
          (is (= (count (:log game)) seq))
          (is (= (:state game) state) "rehydrated state is identical")
          (is (= (:winner game) (:winner state))))
        (finally (cleanup! path))))))

(deftest unfinished-rooms-are-listed-finished-ones-are-not
  (let [{:keys [ds path]} (tmp-ds)]
    (try
      (store/append-events! ds "LIVE" 0 genesis 1)
      (store/upsert-room! ds "LIVE" :night false 1)
      (store/append-events! ds "DONE" 0 genesis 1)
      (store/upsert-room! ds "DONE" :game-over true 1)
      (let [ids (set (store/unfinished-room-ids ds))]
        (is (contains? ids "LIVE"))
        (is (not (contains? ids "DONE")) "finished games stay on disk but are not resurrected"))
      (finally (cleanup! path)))))

(deftest purging-removes-events-and-room
  (let [{:keys [ds path]} (tmp-ds)]
    (try
      (store/append-events! ds "ABC" 0 genesis 1)
      (store/upsert-room! ds "ABC" :lobby false 1)
      (is (store/room-exists? ds "ABC"))
      (store/purge-room! ds "ABC")
      (is (not (store/room-exists? ds "ABC")))
      (is (empty? (store/load-events ds "ABC")))
      (finally (cleanup! path)))))

(deftest purge-finished-respects-the-cutoff
  (let [{:keys [ds path]} (tmp-ds)]
    (try
      (store/append-events! ds "OLD" 0 genesis 1)
      (store/upsert-room! ds "OLD" :game-over true 1000)
      (store/append-events! ds "NEW" 0 genesis 1)
      (store/upsert-room! ds "NEW" :game-over true 9000)
      (is (= 1 (store/purge-finished-before! ds 5000)))
      (is (not (store/room-exists? ds "OLD")))
      (is (store/room-exists? ds "NEW"))
      (finally (cleanup! path)))))
