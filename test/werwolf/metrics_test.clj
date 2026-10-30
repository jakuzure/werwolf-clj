(ns werwolf.metrics-test
  "The metrics page, read the way Prometheus reads it: over HTTP, as text."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [werwolf.metrics :as metrics]
            [werwolf.room :as room]
            [werwolf.store :as store]))

(defn- scrape [port]
  (slurp (str "http://127.0.0.1:" port "/metrics")))

(defn- value
  "The value of the sample whose line starts with `prefix`, or nil."
  [text prefix]
  (some->> (str/split-lines text)
           (some #(when (str/starts-with? % prefix) %))
           (re-find #"\s(\S+)$")
           second
           parse-double))

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))

(deftest only-the-protocol's-own-names-become-labels
  (testing "each label is a counter the registry keeps for ever, so one taken
            off the wire was heap a stranger could fill"
    (is (= :vote (metrics/message-type {:type :client/command :cmd {:type :vote}})))
    (is (= :client/ping (metrics/message-type {:type :client/ping})))
    (doseq [junk [nil {} {:type :client/hack} {:type "client/ping"}
                  {:type (keyword (apply str (repeat 1000 "x")))}
                  {:type :client/command} {:type :client/command :cmd {:type :gm/nuke}}]]
      (is (= :invalid (metrics/message-type junk)) (pr-str junk)))))

(deftest no-port-serves-nothing
  (is (nil? (metrics/start! {:port 0 :host "127.0.0.1"})))
  (is (nil? (metrics/start! {:port nil :host "127.0.0.1"}))))

(deftest the-page-shows-the-rooms-the-games-and-the-jvm
  (let [path (str (java.io.File/createTempFile "metrics" ".db"))
        ctx  (room/registry {:ds (store/open {:dbtype "sqlite" :dbname path})
                             :send! (fn [_ _])
                             :on-events metrics/events!})
        port (free-port)
        m    (metrics/start! {:rooms ctx :port port :host "127.0.0.1"
                              :version "1.2.3" :commit "abcdef0"})]
    (try
      (let [before (or (value (scrape port) "werwolf_games_ended_total{winner=\"village\"}") 0.0)]
        (let [{:keys [room-id player-id]} (room/create! ctx {:name "Anna"})]
          (room/attach! ctx room-id player-id (atom [])))
        (metrics/events! [{:type :game/ended :winner :village}])
        (metrics/message! {:type :client/command :cmd {:type :vote}} 2000000)
        (metrics/refused! :invalid-target)
        (let [text (scrape port)]
          (testing "what the registry holds now, read at scrape time"
            (is (= 1.0 (value text "werwolf_rooms{phase=\"lobby\"}")))
            (is (= 1.0 (value text "werwolf_players{state=\"connected\"}"))))
          (testing "what was counted as it happened"
            (is (= (inc before) (value text "werwolf_games_ended_total{winner=\"village\"}")))
            (is (pos? (value text "werwolf_messages_total{type=\"vote\"}"))
                "a command is counted by what it commands")
            (is (pos? (value text "werwolf_refusals_total{code=\"invalid_target\"}"))))
          (testing "which build, and the JVM's own"
            (is (= 1.0 (value text "werwolf_build_info{commit=\"abcdef0\",version=\"1.2.3\"}")))
            (is (value text "jvm_memory_used_bytes{area=\"heap\"}")))))
      (finally
        (metrics/stop! m)))
    (testing "stopping drops the room gauges with the server"
      (is (thrown? java.io.IOException (scrape port))))))
