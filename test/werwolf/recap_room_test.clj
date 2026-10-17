(ns werwolf.recap-room-test
  "JVM-only: the Rückblick on the wire.

   The engine tests show what a recap says; these show who receives it and
   when — every seat, at :game-over, and not one frame earlier — and that the
   room's password hash, which sits in the very log the recap is read from,
   never travels with it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [werwolf.engine.core :as engine]
            [werwolf.room :as room]
            [werwolf.store :as store]))

(defn- tmp-path []
  (let [f (java.io.File/createTempFile "werwolf-recap" ".db")]
    (.delete f)
    (.getAbsolutePath f)))

(defn- cleanup! [path]
  (doseq [suffix ["" "-wal" "-shm"]]
    (io/delete-file (str path suffix) true)))

(defmacro ^:private with-ctx [[binding] & body]
  `(let [path# (tmp-path)
         ~binding (room/registry {:ds (store/open {:dbtype "sqlite" :dbname path#})
                                  :send! (fn [s# msg#] (swap! s# conj msg#))
                                  :game {:first-night-kill? true :first-day-trial? true :narrated? false}})]
     (try ~@body (finally (cleanup! path#)))))

(defn- views [sink] (keep #(when (= :room/view (:type %)) (:view %)) @sink))

(defn- locked-table
  "A five-seat room opened with a password, every seat attached."
  [ctx]
  (let [{:keys [room-id]} (room/create! ctx {:name "P0" :password "geheim"})
        host  (atom [])
        _     (room/attach! ctx room-id 0 host)
        seats (into [{:pid 0 :sink host}]
                    (for [i (range 1 5)]
                      (let [{:keys [player-id]} (room/join! ctx room-id {:name (str "P" i)
                                                                         :password "geheim"})
                            s (atom [])]
                        (room/attach! ctx room-id player-id s)
                        {:pid player-id :sink s})))]
    {:room-id room-id :seats seats}))

(defn- end-the-game!
  "Every wolf removed by the host: the shortest honest road to :game-over."
  [ctx room-id]
  (let [st (room/state-of ctx room-id)]
    (doseq [w (filter #(= :werewolf (engine/team-of-player st %)) (engine/living-ids st))]
      (room/submit! ctx room-id (:host st) {:type :gm/force-kill :target w}))))

(deftest the-recap-arrives-at-the-end-for-everyone-and-not-before
  (with-ctx [ctx]
    (let [{:keys [room-id seats]} (locked-table ctx)]
      (room/submit! ctx room-id 0 {:type :start-game})
      (let [before (into {} (map (fn [{:keys [pid sink]}] [pid (count @sink)])) seats)]
        (testing "no view in the lobby or during the game carries one"
          (doseq [{:keys [pid sink]} seats]
            (is (not-any? #(contains? % :recap) (views sink)) (str "seat " pid))))
        (end-the-game! ctx room-id)
        (is (= :game-over (:phase (room/state-of ctx room-id))))
        (doseq [{:keys [pid sink]} seats
                :let [later (map :view (filter #(= :room/view (:type %))
                                               (drop (get before pid) @sink)))
                      v     (last later)]]
          (testing (str "seat " pid)
            (is (= :game-over (:phase v)))
            (is (= (set (engine/participant-ids (room/state-of ctx room-id)))
                   (set (map :id (:players (:recap v))))))
            (testing "and only views of the finished game carry it"
              (is (every? #(= (contains? % :recap) (= :game-over (:phase %))) later)))
            (testing "the leak detector, run on what was actually sent"
              (is (nil? (engine/redaction-leaks? (room/state-of ctx room-id) pid v))))))))))

(deftest the-recap-is-built-once-and-kept
  (with-ctx [ctx]
    (let [{:keys [room-id]} (locked-table ctx)]
      (room/submit! ctx room-id 0 {:type :start-game})
      (end-the-game! ctx room-id)
      (let [rc (:recap (get @(:rooms ctx) room-id))]
        (is (some? rc))
        (testing "a reconnect is painted from the cache, the same recap"
          (let [s (atom [])]
            (room/attach! ctx room-id 2 s)
            (is (identical? rc (:recap (last (views s)))))))
        (testing "a later commit keeps it rather than reading the log again"
          (room/submit! ctx room-id 3 {:type :leave})
          (is (identical? rc (:recap (get @(:rooms ctx) room-id)))))))))

(deftest the-password-hash-never-travels-with-the-recap
  (with-ctx [ctx]
    (let [{:keys [room-id seats]} (locked-table ctx)
          h (:password-hash (room/state-of ctx room-id))]
      (is (string? h) "the room really is locked")
      (is (some #(= h (:hash %)) (store/load-events (:ds ctx) room-id))
          "and the hash really is in the log the recap is read from")
      (room/submit! ctx room-id 0 {:type :start-game})
      (end-the-game! ctx room-id)
      (doseq [{:keys [pid sink]} seats]
        (is (some :recap (views sink)) (str "seat " pid " got a recap"))
        (is (not (str/includes? (pr-str @sink) h)) (str "seat " pid))
        (is (empty? (filter string? (tree-seq coll? seq (:recap (last (views sink))))))
            "no string at all in a recap, so no hash and no prose")))))
