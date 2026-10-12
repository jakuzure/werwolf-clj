(ns werwolf.kick-protocol-test
  "The host removing a player, as the sockets see it.

   The engine half is in engine/kick_test. This is the half that only exists on
   the server: the removed player's pages are unbound and told why, their token
   stops seating them, the call forgets them, and everybody else's view moves on
   without them."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [werwolf.proto :as proto]
            [werwolf.protocol :as protocol]
            [werwolf.room :as room]
            [werwolf.store :as store]
            [werwolf.timer :as timer]))

(def ^:private game-cfg
  {:election-secs 60 :discussion-secs 300 :nomination-secs 90 :vote-secs 60
   :night-secs 45 :reaction-secs 30})

(defn- tmp-path []
  (let [f (java.io.File/createTempFile "werwolf-kick" ".db")]
    (.delete f) (.getAbsolutePath f)))

(defn- cleanup! [path]
  (doseq [suffix ["" "-wal" "-shm"]] (io/delete-file (str path suffix) true)))

(defn- deps-on [path]
  {:rooms (room/registry {:ds (store/open {:dbtype "sqlite" :dbname path})
                          :send! (fn [sock msg] (swap! sock conj msg))
                          :game {:narrated? false}
                          :after-commit (timer/hook game-cfg)})
   :tokens {:secret "test-secret-that-is-long-enough" :ttl-mins 720}
   :send! (fn [sock msg] (swap! sock conj msg))})

(defmacro ^:private with-deps [[binding] & body]
  `(let [path# (tmp-path), ~binding (deps-on path#)]
     (try ~@body (finally (cleanup! path#)))))

(defn- sock [] (atom []))
(defn- got [s] @s)
(defn- of-type [s t] (filterv #(= t (:type %)) (got s)))
(defn- last-of [s t] (last (of-type s t)))
(defn- codes [s] (mapv :code (of-type s :server/error)))
(defn- view [s] (:view (last-of s :room/view)))

(defn- seat! [deps msg]
  (let [s (sock)]
    (protocol/on-open deps s)
    (protocol/dispatch deps s msg)
    s))

(defn- kick [target] {:type :client/command :cmd {:type :gm/kick :target target}})

(deftest kicked-is-on-the-wire
  (is (contains? proto/server-message-types :server/kicked))
  (is (proto/valid-client-message? (kick 2)))
  (testing "it names a seat, so a kick without one is malformed rather than
            a guess at whom"
    (is (proto/valid-client-message? {:type :client/command :cmd {:type :gm/kick}})
        "the schema allows a missing target as it does for every command…")
    (with-deps [deps]
      (let [host (seat! deps {:type :client/create :name "Anna"})
            room (:room-id (last-of host :server/welcome))]
        (seat! deps {:type :client/join :room-id room :name "Bob"})
        (protocol/dispatch deps host {:type :client/command :cmd {:type :gm/kick}})
        (is (= [:invalid-target] (codes host)) "…and the engine refuses it")))))

(deftest the-host-removes-a-player-from-the-lobby
  (with-deps [deps]
    (let [host (seat! deps {:type :client/create :name "Anna"})
          room (:room-id (last-of host :server/welcome))
          bob  (seat! deps {:type :client/join :room-id room :name "Bob"})
          {bob-pid :player-id token :token} (last-of bob :server/welcome)
          tab2 (sock)
          cem  (seat! deps {:type :client/join :room-id room :name "Cem"})]
      (protocol/dispatch deps tab2 {:type :client/resume :room-id room :token token})
      (protocol/dispatch deps tab2 {:type :client/voice :joined? true})
      (is (= #{bob-pid} (:voice (view cem))) "Bob is in the call to begin with")
      (swap! bob empty) (swap! tab2 empty)

      (protocol/dispatch deps host (kick bob-pid))
      (is (empty? (codes host)))

      (testing "every page of the seat is told, and unbound"
        (doseq [s [bob tab2]]
          (is (= {:type :server/kicked :room-id room} (last (got s))))
          (is (empty? (of-type s :room/view))
              "and gets no last look at the table it was taken from")
          (is (nil? (room/whereis (:rooms deps) s)))))

      (testing "everybody else sees the table without him"
        (doseq [s [host cem]]
          (let [v (view s)]
            (is (not (contains? (:players v) bob-pid)))
            (is (= {:type :player/kicked :player bob-pid :name "Bob"} (peek (:log v))))
            (is (= #{} (:voice v)) "and no headset left on a seat that is gone"))))

      (testing "his token no longer seats him"
        (let [again (sock)]
          (protocol/dispatch deps again {:type :client/resume :room-id room :token token})
          (is (= [:seat-gone] (codes again)))
          (is (nil? (room/whereis (:rooms deps) again)))))

      (testing "a page he still holds cannot act in the room"
        (protocol/dispatch deps bob {:type :client/chat :text "hallo?"})
        (is (= [:not-in-room] (codes bob))))

      (testing "no ban: the same page may join again, on a fresh seat"
        (swap! bob empty)
        (protocol/dispatch deps bob {:type :client/join :room-id room :name "Bob"})
        (is (empty? (codes bob)))
        (let [pid' (:player-id (last-of bob :server/welcome))]
          (is (some? pid'))
          (is (> pid' (:player-id (last-of cem :server/welcome)))
              "the old number is never handed out again — his old token names it"))))))

(deftest only-the-host-and-only-in-the-lobby
  (with-deps [deps]
    (let [host (seat! deps {:type :client/create :name "Anna"})
          room (:room-id (last-of host :server/welcome))
          guests (mapv #(seat! deps {:type :client/join :room-id room :name %})
                       ["Bob" "Cem" "Dana" "Eve"])
          bob (first guests)]
      (testing "a guest is refused, and nobody is told anything"
        (swap! (second guests) empty)
        (protocol/dispatch deps bob (kick 2))
        (is (= [:not-allowed] (codes bob)))
        (is (empty? (of-type (second guests) :server/kicked)))
        (is (= 5 (count (:players (room/state-of (:rooms deps) room))))))
      (testing "the host may not remove himself"
        (protocol/dispatch deps host (kick 0))
        (is (= [:invalid-target] (codes host))))
      (testing "and once the game is under way, not at all"
        (swap! host empty)
        (protocol/dispatch deps host {:type :client/command :cmd {:type :start-game}})
        (is (not= :lobby (:phase (room/state-of (:rooms deps) room))))
        (is (not (contains? (set (:actions (view host))) :gm/kick)))
        (protocol/dispatch deps host (kick 1))
        (is (= [:not-allowed] (codes host)))
        (is (empty? (of-type bob :server/kicked)))))))

(deftest the-lobby-view-offers-it-to-the-host-alone
  (with-deps [deps]
    (let [host (seat! deps {:type :client/create :name "Anna"})
          room (:room-id (last-of host :server/welcome))
          bob  (seat! deps {:type :client/join :room-id room :name "Bob"})]
      (is (contains? (set (:actions (view host))) :gm/kick))
      (is (= [1] (get-in (view host) [:targets :gm/kick])))
      (is (not (contains? (set (:actions (view bob))) :gm/kick))))))
