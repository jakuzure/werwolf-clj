(ns werwolf.lifecycle-test
  "JVM-only: when an unfinished room is given up on for good.

   Sweeping a room only forgets it; the log brings it back on the next boot. A
   game nobody returns to therefore used to be reloaded on every boot forever —
   one was found two months after anybody had last played it. These are about
   the point at which the server stops waiting."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [werwolf.config :as config]
            [werwolf.engine.core :as engine]
            [werwolf.engine.schema :as schema]
            [werwolf.i18n.core :as i18n]
            [werwolf.room :as room]
            [werwolf.store :as store]
            [werwolf.timer :as timer]))

;; ------------------------------------------------------------- fixtures ---

(def ^:private hour (* 60 60 1000))

(def ^:private game-cfg
  {:narrated? false :first-night-kill? true :first-day-trial? true
   :discussion-secs 300 :nomination-secs 90 :vote-secs 60 :night-secs 45
   :election-secs 60 :reaction-secs 30})

(defn- tmp-path []
  (let [f (java.io.File/createTempFile "werwolf-lifecycle" ".db")]
    (.delete f)
    (.getAbsolutePath f)))

(defn- cleanup! [path]
  (doseq [suffix ["" "-wal" "-shm"]]
    (io/delete-file (str path suffix) true)))

(defn- ctx-on
  "A registry over the database at `path` — a fresh one over the same file IS a
   restart. With the clock installed, as in production."
  ([path] (ctx-on path {}))
  ([path cfg]
   (let [game (merge game-cfg cfg)]
     (room/registry {:ds (store/open {:dbtype "sqlite" :dbname path})
                     :send! (fn [s msg] (swap! s conj msg))
                     :game game
                     :after-commit (timer/hook game)}))))

(defmacro with-db [[binding] & body]
  `(let [~binding (tmp-path)]
     (try ~@body (finally (cleanup! ~binding)))))

(defn- table!
  "A room with `n` attached players. Returns {:room-id .. :sinks [..]}."
  [ctx n]
  (let [{:keys [room-id player-id]} (room/create! ctx {:name "P0"})
        host (atom [])]
    (room/attach! ctx room-id player-id host)
    {:room-id room-id
     :sinks (into [host]
                  (for [i (range 1 n)]
                    (let [{:keys [player-id]} (room/join! ctx room-id {:name (str "P" i)})
                          s (atom [])]
                      (room/attach! ctx room-id player-id s)
                      s)))}))

(defn- game!
  "A started game everybody has since walked away from."
  [ctx]
  (let [{:keys [room-id sinks]} (table! ctx 5)]
    (room/submit! ctx room-id 0 {:type :start-game})
    (doseq [s sinks] (room/detach! ctx s))
    room-id))

(defn- lobby!
  "A lobby its host has closed the tab on."
  [ctx]
  (let [{:keys [room-id sinks]} (table! ctx 1)]
    (doseq [s sinks] (room/detach! ctx s))
    room-id))

(defn- backdate!
  "Pretend the room was last written, and last played, `ms` ago — on disk,
   which is all a brand new registry knows."
  [ctx room-id ms]
  (let [t (- (System/currentTimeMillis) ms)]
    (jdbc/execute-one! (:ds ctx) ["UPDATE rooms SET updated_at = ?, active_at = ? WHERE room_id = ?"
                                  t t room-id])))

(defn- retired? [ds room-id]
  (let [events (store/load-events ds room-id)]
    (and (= :game/abandoned (:type (peek events)))
         (not (contains? (set (store/unfinished-room-ids ds)) room-id)))))

;; ---------------------------------------------------------------- on boot ---

(deftest a-long-abandoned-game-is-retired-on-boot
  (with-db [path]
    (let [ctx (ctx-on path)
          room-id (game! ctx)]
      (backdate! ctx room-id (* 25 hour))
      (let [ctx2 (ctx-on path)]
        (is (not (contains? (set (room/rehydrate! ctx2)) room-id)))
        (is (nil? (room/state-of ctx2 room-id)) "not loaded")
        (is (retired? (:ds ctx2) room-id) "ended in the log and marked finished")
        (testing "the log is kept, and replays to the truth: over, and nobody won"
          (let [st (engine/replay (store/load-events (:ds ctx2) room-id))]
            (is (= :game-over (:phase st)))
            (is (nil? (:winner st)))
            (is (nil? (:deadline st)))
            (is (schema/valid-state? st) (pr-str (schema/explain-state st)))
            (is (= {:type :game/abandoned} (peek (:log st))))
            (is (some :role (vals (:players st))) "the deal survives for a recap")))
        (testing "and the boot after that does not even look at it"
          (let [n (count (store/load-events (:ds ctx2) room-id))]
            (is (empty? (room/rehydrate! (ctx-on path))))
            (is (= n (count (store/load-events (:ds ctx2) room-id))))))))))

(deftest a-recently-abandoned-game-is-still-resumed
  (testing "a table that broke off last night may well come back tonight"
    (with-db [path]
      (let [ctx (ctx-on path)
            room-id (game! ctx)]
        (backdate! ctx room-id (* 20 hour))
        (let [ctx2 (ctx-on path)]
          (is (= [room-id] (room/rehydrate! ctx2)))
          (is (= :night (:phase (room/state-of ctx2 room-id))))
          (is (contains? (set (store/unfinished-room-ids (:ds ctx2))) room-id)))))))

(deftest the-threshold-comes-from-config
  (with-db [path]
    (let [ctx (ctx-on path)
          room-id (game! ctx)]
      (backdate! ctx room-id (* 2 hour))
      (is (empty? (room/rehydrate! (ctx-on path {:abandon-after-hours 1}))))
      (is (retired? (:ds ctx) room-id)))
    (with-db [path]
      (let [ctx (ctx-on path)
            room-id (game! ctx)]
        (backdate! ctx room-id (* 2 hour))
        (is (= [room-id] (room/rehydrate! (ctx-on path {:abandon-after-hours 3}))))))
    (testing "and ships at a day, overridable from the environment"
      (is (= 24 (get-in (config/load-config :test) [:game :abandon-after-hours]))))))

(deftest the-servers-own-writes-do-not-keep-a-game-alive
  (testing "the clock, a seat marked absent, a boot: none of them is a player.
            What is measured is the last thing a person did, and the log's
            last write only stands in for it on rooms from before it was kept"
    (with-db [path]
      (let [ctx (ctx-on path)
            room-id (game! ctx)]
        ;; Last played two days ago; the server wrote something just now.
        (swap! (:rooms ctx) update room-id assoc :last-activity
               (- (System/currentTimeMillis) (* 48 hour)))
        (room/narrate! ctx room-id (fn [_] [{:type :phase/timed-out :phase :night}]))
        (is (empty? (room/rehydrate! (ctx-on path))))
        (is (retired? (:ds ctx) room-id))))
    (testing "a room from before activity was recorded falls back to the last write"
      (with-db [path]
        (let [ctx (ctx-on path)
              old (game! ctx)
              recent (game! ctx)]
          (jdbc/execute-one! (:ds ctx) ["UPDATE rooms SET active_at = NULL"])
          (jdbc/execute-one! (:ds ctx) ["UPDATE rooms SET updated_at = ? WHERE room_id = ?"
                                        (- (System/currentTimeMillis) (* 48 hour)) old])
          (is (= [recent] (room/rehydrate! (ctx-on path))))
          (is (retired? (:ds ctx) old)))))))

(deftest a-game-the-server-walked-out-on-is-not-abandoned
  (testing "players still seated when the process died did not leave — the
            server did. However long it was down, they get to come back"
    (with-db [path]
      (let [ctx (ctx-on path)
            {:keys [room-id]} (table! ctx 5)]
        (room/submit! ctx room-id 0 {:type :start-game})
        (backdate! ctx room-id (* 48 hour))
        (let [boot (System/currentTimeMillis)
              ctx2 (ctx-on path)]
          (is (= [room-id] (room/rehydrate! ctx2 boot)))
          (testing "but only once: the boot marked them gone, and a boot is not
                    activity, so if nobody comes back the next one retires it"
            (is (empty? (room/rehydrate! (ctx-on path) (+ boot (* 25 hour)))))
            (is (retired? (:ds ctx2) room-id))))))))

;; ------------------------------------------------------------------ sweep ---

(deftest the-sweep-retires-a-game-past-the-threshold
  (with-db [path]
    (let [ctx (ctx-on path)
          room-id (game! ctx)]
      (swap! (:rooms ctx) update room-id update :last-activity - (* 25 hour))
      (is (= [room-id] (room/sweep-idle! ctx (System/currentTimeMillis))))
      (is (nil? (room/state-of ctx room-id)))
      (is (retired? (:ds ctx) room-id))
      (is (empty? (room/rehydrate! (ctx-on path))) "and the next boot leaves it be"))))

(deftest the-sweep-only-forgets-a-game-short-of-it
  (with-db [path]
    (let [ctx (ctx-on path)
          room-id (game! ctx)]
      (swap! (:rooms ctx) update room-id update :last-activity - (* 2 hour))
      (is (= [room-id] (room/sweep-idle! ctx (System/currentTimeMillis))))
      (is (not (retired? (:ds ctx) room-id)))
      (is (= [room-id] (room/rehydrate! (ctx-on path))) "resumable after a boot")))
  (testing "configurably"
    (with-db [path]
      (let [ctx (ctx-on path {:abandon-after-hours 1})
            room-id (game! ctx)]
        (swap! (:rooms ctx) update room-id update :last-activity - (* 2 hour))
        (is (= [room-id] (room/sweep-idle! ctx (System/currentTimeMillis))))
        (is (retired? (:ds ctx) room-id))))))

;; ---------------------------------------------------------------- lobbies ---

(deftest a-swept-lobby-is-not-resurrected
  (testing "once swept it cannot be joined anyway; bringing it back on the next
            boot only gave the sweep something to drop again"
    (with-db [path]
      (let [ctx (ctx-on path)
            room-id (lobby! ctx)]
        (swap! (:rooms ctx) update room-id update :last-activity - (* 10 60 1000))
        (is (= [room-id] (room/sweep-idle! ctx (System/currentTimeMillis))))
        (is (retired? (:ds ctx) room-id))
        (is (empty? (room/rehydrate! (ctx-on path))))))))

(deftest a-lobby-nobody-is-in-goes-on-boot-at-the-lobby-threshold
  (with-db [path]
    (let [ctx (ctx-on path)
          stale (lobby! ctx)
          fresh (lobby! ctx)]
      (backdate! ctx stale (* 10 60 1000))
      (backdate! ctx fresh (* 60 1000))
      (is (= [fresh] (room/rehydrate! (ctx-on path)))
          "a minute is somebody fetching a coffee — the room is still theirs")
      (is (retired? (:ds ctx) stale))))
  (testing "but not a lobby people were sitting in when the server went down"
    (with-db [path]
      (let [ctx (ctx-on path)
            {:keys [room-id]} (table! ctx 3)]
        (backdate! ctx room-id (* 10 60 1000))
        (is (= [room-id] (room/rehydrate! (ctx-on path))))))))

;; ------------------------------------------------------------- chronicle ---

(deftest an-abandoned-game-reads-in-the-chronik
  (let [lines (into {} (for [locale i18n/locales]
                          [locale (i18n/log-line locale {} {:type :game/abandoned})]))]
    (doseq [[locale line] lines]
      (is (not (re-find #"(?i)missing|game/abandoned|:log/" line))
          (str "untranslated in " locale)))
    (is (apply distinct? (vals lines)) "and not by falling back to German"))
  (is (= :verdict (i18n/log-tone {:type :game/abandoned}))
      "it is how the game ended"))
