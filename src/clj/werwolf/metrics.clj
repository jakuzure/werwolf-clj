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
            [werwolf.proto :as proto]
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

(def ^:private fine-buckets
  ;; Down to 0.1 ms: the default buckets start at 5 ms, where a message or one
  ;; step of it mostly falls into the first bucket. The p95 is then only
  ;; interpolated inside it, about 4.75 ms for every message type alike.
  [0.0001 0.00025 0.0005 0.001 0.0025 0.005 0.01 0.025 0.05 0.1 0.25 0.5 1 2.5])

(defonce ^:private message-seconds
  (-> (Histogram/builder)
      (.name "werwolf_message_seconds")
      (.help "Time to handle one client message, including the broadcast it caused.")
      (.classicUpperBounds (double-array fine-buckets))
      (.labelNames (labels "type"))
      (.register registry)))

(defonce ^:private op-seconds
  (-> (Histogram/builder)
      (.name "werwolf_op_seconds")
      (.help "Time spent in one step of handling a message: engine, SQLite, fan-out, a bot's decision.")
      (.classicUpperBounds (double-array fine-buckets))
      (.labelNames (labels "op"))
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

(def ^:private countable
  "Every label `message-type` may return: the protocol's own names and
   `:invalid`. Closed, because each label is a Counter and a Histogram child
   the registry keeps for ever. When the label came straight off the wire, one
   socket with no seat could put 60 KB of new keyword in every frame and grow
   the heap until the JVM died, in about two seconds at -Xmx320m (security
   review, 2026-10-30)."
  (into (conj proto/client-message-types :invalid) proto/command-types))

(defn message-type
  "The label for a client message: a command is counted by what it commands,
   since `client_command` alone would say nothing about what the table does.
   Anything the protocol does not name is `:invalid`."
  [msg]
  (let [t (if (= :client/command (:type msg)) (get-in msg [:cmd :type]) (:type msg))]
    (if (contains? countable t) t :invalid)))

(defn message!
  "One client message handled, in `nanos`. `msg` is what the protocol accepted,
   nil for a frame it refused, so a refused frame is `:invalid` whatever its
   :type said."
  [msg nanos]
  (let [t (labels (message-type msg))]
    (.inc (.labelValues messages t))
    (.observe (.labelValues message-seconds t) (/ (double nanos) 1e9))))

(def ops
  "The steps `werwolf.room/timed` measures. Closed for the same reason as
   `countable`: every label is a Histogram child kept for ever, so a label that
   could come from anywhere else is a slow leak (security review, 2026-10-30).

   * `:engine`: `engine/execute` for one command or join.
   * `:persist`: the SQLite append and the room row, inside the room's lock.
   * `:broadcast`: a `view-for` per seated player, transit-encoded and handed
     to http-kit's queue. Encoding happens in `ws/send!`, so it counts here.
   * `:bot`: one bot choosing its move, before it is submitted."
  #{:engine :persist :broadcast :bot})

(defn op!
  "One step of `op` took `nanos`. An op outside `ops` is dropped."
  [op nanos]
  (when (contains? ops op)
    (.observe (.labelValues op-seconds (labels op)) (/ (double nanos) 1e9))))

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
