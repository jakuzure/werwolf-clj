(ns werwolf.metrics
  "What the server is doing, for Prometheus: a `/metrics` page on a port of its
   own, bound to loopback, off unless `METRICS_PORT` is set.

   A port of its own rather than a route on the game's: the game's port is the
   one Caddy hands to the internet, and how many rooms are open, who is
   connected and how the JVM is doing are nobody's business but the host's.

   Two kinds of numbers:

   * **Counted as it happens** — messages, refusals, games started and won.
     The counters are module-level, like a logger: the protocol and the rooms
     call `message!`, `refused!` and `events!` without carrying a metrics
     object around, and a REPL reset keeps counting instead of registering
     everything twice. A server without a metrics port still counts; it just
     never shows anyone.
   * **Read at scrape time** — rooms by phase, players present or absent. Those
     are a look at the rooms as they are, so they are callbacks into the room
     registry, registered when the system starts and dropped when it stops.

   Plus the JVM's own: heap, GC pauses, threads."
  (:require [clojure.string :as str]
            [werwolf.room :as room])
  (:import [io.prometheus.metrics.core.metrics Counter GaugeWithCallback GaugeWithCallback$Callback Histogram]
           [io.prometheus.metrics.exporter.httpserver HTTPServer]
           [io.prometheus.metrics.instrumentation.jvm JvmMetrics]
           [io.prometheus.metrics.model.registry PrometheusRegistry]
           [java.util.function Consumer]))

(defonce ^PrometheusRegistry registry (PrometheusRegistry.))

(defn- label ^String [x]
  (cond (keyword? x) (str/replace (subs (str x) 1) #"[/.-]" "_")
        (nil? x)     "none"
        :else        (str x)))

(defn- labels ^"[Ljava.lang.String;" [& xs]
  (into-array String (map label xs)))

(defonce ^:private messages
  (-> (Counter/builder)
      (.name "werwolf_messages")
      (.help "Messages received from clients, by type; commands by their command type.")
      (.labelNames (labels "type"))
      (.register registry)))

(defonce ^:private message-seconds
  (-> (Histogram/builder)
      (.name "werwolf_message_seconds")
      (.help "Time to handle one client message, including the broadcast it caused.")
      (.labelNames (labels "type"))
      (.register registry)))

(defonce ^:private refusals
  (-> (Counter/builder)
      (.name "werwolf_refusals")
      (.help "Messages answered with an error, by error code.")
      (.labelNames (labels "code"))
      (.register registry)))

(defonce ^:private games-started
  (-> (Counter/builder)
      (.name "werwolf_games_started")
      (.help "Games dealt.")
      (.register registry)))

(defonce ^:private games-ended
  (-> (Counter/builder)
      (.name "werwolf_games_ended")
      (.help "Games decided, by who won.")
      (.labelNames (labels "winner"))
      (.register registry)))

(defonce ^:private jvm
  (.register (JvmMetrics/builder) registry))

;; ------------------------------------------------------------- counting ---

(defn message-type
  "The label for a client message: a command is counted by what it commands,
   since `client_command` alone would say nothing about what the table does."
  [msg]
  (if (= :client/command (:type msg))
    (get-in msg [:cmd :type] :client/command)
    (:type msg :invalid)))

(defn message!
  "One client message handled, in `nanos`."
  [msg nanos]
  (let [t (labels (message-type msg))]
    (.inc (.labelValues messages t))
    (.observe (.labelValues message-seconds t) (/ (double nanos) 1e9))))

(defn refused!
  "A message answered with an error."
  [code]
  (.inc (.labelValues refusals (labels code))))

(defn events!
  "Committed events, as a room's log grows. Replay on boot never comes through
   here, so a restart does not count yesterday's games again."
  [events]
  (doseq [{:keys [type winner]} events]
    (case type
      :game/started (.inc games-started)
      :game/ended   (.inc (.labelValues games-ended (labels winner)))
      nil)))

;; ------------------------------------------------------------ the rooms ---

(defn- gauge
  "A gauge read at scrape time: `f` returns {label-values value}."
  [name help label-names f]
  (-> (GaugeWithCallback/builder)
      (.name name)
      (.help help)
      (.labelNames (apply labels label-names))
      (.callback (reify Consumer
                   (accept [_ cb]
                     (doseq [[vs v] (f)]
                       (.call ^GaugeWithCallback$Callback cb (double v) (apply labels vs))))))
      (.register registry)))

(defn- room-gauges
  "Rooms by phase, and the people in them, as the registry holds them now."
  [rooms]
  (let [states #(keep (partial room/state-of rooms) (room/room-ids rooms))]
    [(gauge "werwolf_rooms" "Rooms in memory, by phase." ["phase"]
            #(frequencies (map (fn [s] [(:phase s)]) (states))))
     (gauge "werwolf_players" "Seated players, by whether a socket holds their seat."
            ["state"]
            #(merge {["connected"] 0 ["absent"] 0}
                    (frequencies
                     (for [s (states) p (vals (:players s))]
                       [(if (false? (:connected? p)) "absent" "connected")]))))]))

(defn- build-info-gauge [version commit]
  (gauge "werwolf_build_info" "The running build; always 1." ["version" "commit"]
         (constantly {[version (or commit "unknown")] 1})))

;; ------------------------------------------------------------- lifecycle ---

(defn start!
  "Register the room gauges and serve them, with everything counted, on
   `host`:`port`. A port of 0 or nil serves nothing and registers nothing."
  [{:keys [port host rooms version commit]}]
  (when (and port (pos? port))
    (let [gauges (conj (room-gauges rooms) (build-info-gauge version commit))
          server (-> (HTTPServer/builder)
                     (.hostname host)
                     (.port (int port))
                     (.registry registry)
                     (.buildAndStart))]
      {:server server :gauges gauges})))

(defn stop! [{:keys [^HTTPServer server gauges]}]
  (when server (.close server))
  (doseq [g gauges] (.unregister registry g)))
