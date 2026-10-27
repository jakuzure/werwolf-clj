(ns werwolf.e2e-test
  "End to end over real WebSockets, against the real integrant system.

   Everything else in the suite drives the layers directly. This one goes
   through actual TCP, an actual upgrade handshake and actual transit frames,
   because the interesting failures live exactly where the layers meet: a
   listener that Ring cannot adapt, a frame that arrives in two pieces, a
   room-id that survives every unit test as a keyword.

   The client is `java.net.http.WebSocket` from the JDK — no new dependency, and
   deliberately not our own cljs client, since a test that shares its bugs with
   the code under test proves nothing.

   The centrepiece is `killing-the-server-mid-game-loses-nothing`: verification
   step 5 of the plan, minus the browser."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [org.httpkit.server :as hk]
            [werwolf.main :as main]
            [werwolf.ws :as wire])
  (:import (java.net URI)
           (java.net.http HttpClient WebSocket WebSocket$Listener)
           (java.util.concurrent TimeUnit)))

(def ^:private timeout-ms 5000)

;; ------------------------------------------------------------- the server ---

(defn- tmp-path []
  (let [f (java.io.File/createTempFile "werwolf-e2e" ".db")]
    (.delete f) (.getAbsolutePath f)))

(defn- cleanup! [path]
  (doseq [suffix ["" "-wal" "-shm"]] (io/delete-file (str path suffix) true)))

(defn- boot!
  "Start the whole system on an ephemeral port against `db-path`."
  [db-path]
  (let [sys (ig/init (main/system-config
                      {:server {:port 0 :host "127.0.0.1"}
                       :db     {:dbtype "sqlite" :dbname db-path}
                       :tokens {:secret "e2e-secret-that-is-long-enough" :ttl-mins 720}
                       :game   {:election-secs 60 :discussion-secs 300 :nomination-secs 90 :vote-secs 60
                                :night-secs 45 :reaction-secs 30 :verdict-secs 10
                                ;; Classic first night, so Amor and the wolves
                                ;; are both awake in the same night.
                                :first-night-kill? true
                                ;; And the classic first day: its vote is scripted below.
                                :first-day-trial? true
                                ;; The creator plays: the scripted game below
                                ;; deals a card to every socket it opens.
                                :narrated? false}}))]
    {:system sys
     :port (hk/server-port (::main/server sys))}))

(defn- shutdown! [{:keys [system]}]
  (ig/halt! system))

;; ------------------------------------------------------------- the client ---

(defn- client
  "Connect a real WebSocket. Returns {:ws .. :inbox atom-of-decoded-messages}.

   Text is accumulated until `last?`: the JDK splits a large frame across
   several onText calls, and parsing a half-message is exactly the kind of bug
   that only ever shows up on the one big payload."
  [port]
  (let [inbox (atom [])
        buf (StringBuilder.)
        listener (reify WebSocket$Listener
                   (onOpen [_ ws] (.request ws 1))
                   (onText [_ ws data last?]
                     (.append buf (str data))
                     (when last?
                       (let [s (.toString buf)]
                         (.setLength buf 0)
                         (when-let [msg (wire/read-transit s)]
                           (swap! inbox conj msg))))
                     (.request ws 1)
                     nil)
                   (onError [_ _ws _err] nil)
                   (onClose [_ _ws _code _reason] nil))
        ws (-> (HttpClient/newHttpClient)
               (.newWebSocketBuilder)
               (.connectTimeout (java.time.Duration/ofSeconds 5))
               (.buildAsync (URI/create (str "ws://127.0.0.1:" port "/api/ws")) listener)
               (.get timeout-ms TimeUnit/MILLISECONDS))]
    {:ws ws :inbox inbox}))

(defn- send! [{:keys [ws]} msg]
  (.get (.sendText ws (wire/write-transit msg) true) timeout-ms TimeUnit/MILLISECONDS))

(defn- close! [{:keys [ws]}]
  (try (.get (.sendClose ws WebSocket/NORMAL_CLOSURE "bye") 1 TimeUnit/SECONDS)
       (catch Exception _ nil)))

(defn- await-msg
  "Block until a message matching `pred` arrives. Polling rather than a promise
   per message type, because the interesting assertions are about ORDER and a
   promise would hide it."
  [{:keys [inbox]} pred]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (if-let [hit (first (filter pred @inbox))]
        hit
        (if (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep 10) (recur))
          (throw (ex-info "timed out waiting for a message"
                          {:inbox (mapv :type @inbox)})))))))

(defn- await-type [c t] (await-msg c #(= t (:type %))))

(defn- msgs [{:keys [inbox]}] @inbox)
(defn- views [c] (filterv #(= :room/view (:type %)) (msgs c)))

(defn- latest-view
  "The newest view, chosen by :seq rather than arrival order — the client is
   explicitly allowed to receive them out of order, which is why :seq exists."
  [c]
  (:view (last (sort-by :seq (views c)))))

(defn- await-phase [c phase]
  (await-msg c #(and (= :room/view (:type %)) (= phase (get-in % [:view :phase])))))

(defn- await-deadline
  "The clock is armed by a SECOND commit right after the phase change (see the
   :after-commit hook), so the view that announces a phase and the view that
   carries its deadline are two different frames. Waiting for the phase is not
   waiting for the clock."
  [c]
  (get-in (await-msg c #(and (= :room/view (:type %))
                             (some? (get-in % [:view :deadline]))))
          [:view :deadline]))

(defmacro with-server [[binding db-path] & body]
  `(let [~binding (boot! ~db-path)]
     (try ~@body (finally (shutdown! ~binding)))))

;; ------------------------------------------------------------------- tests ---

(deftest the-server-answers-http-and-upgrades-websockets
  (let [path (tmp-path)]
    (try
      (with-server [srv path]
        (is (= "ok" (slurp (str "http://127.0.0.1:" (:port srv) "/health"))))
        (let [c (client (:port srv))]
          (try
            (is (= 1 (:protocol (await-type c :server/hello))))
            (finally (close! c)))))
      (finally (cleanup! path)))))

(deftest a-real-game-runs-over-real-sockets
  (let [path (tmp-path)]
    (try
      (with-server [srv path]
        (let [host (client (:port srv))
              _ (await-type host :server/hello)
              _ (send! host {:type :client/create :name "Anna" :locale :de})
              welcome (await-type host :server/welcome)
              code (:room-id welcome)
              guests (doall (for [n ["Bob" "Cem" "Dana" "Eve" "Finn" "Gil"]]
                              (let [c (client (:port srv))]
                                (await-type c :server/hello)
                                (send! c {:type :client/join :room-id code :name n})
                                (await-type c :server/welcome)
                                c)))
              all (cons host guests)]
          (try
            (is (= 0 (:player-id welcome)))
            (is (true? (:host? welcome)))

            (testing "the lobby fills up and everyone sees it"
              (await-msg host #(and (= :room/view (:type %))
                                    (= 7 (count (get-in % [:view :players])))))
              (is (= 7 (count (:players (latest-view host))))))

            (testing "chat crosses the wire and reaches everyone"
              (send! host {:type :client/chat :text "wer ist dabei?"})
              (doseq [c all]
                (is (= "wer ist dabei?" (:text (await-type c :room/chat))))))

            (send! host {:type :client/command :cmd {:type :start-game}})
            (doseq [c all] (await-phase c :night))

            (testing "each client knows its own role and no other"
              (doseq [c all]
                (let [v (latest-view c)]
                  (is (some? (:role (:me v))))
                  (is (every? #(not (contains? % :role)) (vals (:players v)))))))

            (testing "and no raw frame anywhere contains another player's role"
              ;; The DevTools check from the plan's verification step 3, done
              ;; against the bytes rather than by eye.
              (doseq [c all]
                (let [pid (:id (:me (latest-view c)))
                      others (dissoc (:players (latest-view c)) pid)]
                  (doseq [m (views c)]
                    (is (nil? (some :role (vals (get-in m [:view :players]))))
                        (str "player " pid " received roles for " (keys others)))))))

            (testing "the deadline is an absolute instant in the future"
              (let [d (await-deadline host)]
                (is (int? d))
                (is (> d (System/currentTimeMillis)))))

            (testing "an illegal command is answered, and only to its sender"
              (let [victim (second guests)]
                (send! victim {:type :client/command :cmd {:type :start-game}})
                (is (some? (await-type victim :server/error)))
                (is (not-any? #(= :server/error (:type %)) (msgs host)))))

            (finally (doseq [c all] (close! c))))))
      (finally (cleanup! path)))))

(deftest amor-can-actually-link-a-couple
  (testing "the bug a playtest found and the whole suite missed: :amor-link
            carries :a and :b, the wire schema is closed, and it listed neither.
            The engine was right, the room layer was right, the protocol layer
            correctly rejected the message — and Amor was unplayable.

            Note what this asserts beyond 'no error': that the client can learn
            the action needs two targets from :arity, without knowing that Amor
            is the role which needs it."
    (let [path (tmp-path)]
      (try
        (with-server [srv path]
          (let [host (client (:port srv))
                _ (await-type host :server/hello)
                _ (send! host {:type :client/create :name "P0"})
                code (:room-id (await-type host :server/welcome))
                guests (doall (for [i (range 1 9)]
                                (let [c (client (:port srv))]
                                  (await-type c :server/hello)
                                  (send! c {:type :client/join :room-id code :name (str "P" i)})
                                  {:pid (:player-id (await-type c :server/welcome)) :client c})))
                all (cons {:pid 0 :client host} guests)]
            (try
              (send! host {:type :client/command :cmd {:type :start-game}})
              (doseq [{:keys [client]} all] (await-phase client :night))
              (let [amor (first (filter #(= :amor (:role (:me (latest-view (:client %))))) all))]
                (is (some? amor) "a nine-player deck contains Amor")
                (let [v (latest-view (:client amor))
                      [a b] (take 2 (get-in v [:targets :amor-link]))]
                  (is (= 2 (get-in v [:arity :amor-link]))
                      "the server tells the client how many to pick")
                  (send! (:client amor) {:type :client/command
                                         :cmd {:type :amor-link :a a :b b}})
                  ;; The linked player learning who they love is the proof it took.
                  (let [lover-client (:client (first (filter #(= a (:pid %)) all)))]
                    (is (some? (await-msg lover-client
                                          #(and (= :room/view (:type %))
                                                (= b (get-in % [:view :me :lover])))))))
                  (is (empty? (filter #(= :server/error (:type %)) (msgs (:client amor))))
                      "and no rejection anywhere")
                  (testing "while a third party learns only that lovers exist"
                    ;; `(set [...])` rather than a set literal: Amor is allowed
                    ;; to be one of the lovers — the official rules let him shoot
                    ;; himself — so these three ids can legitimately collide, and
                    ;; #{} would throw on the duplicate.
                    ;; AWAITED, not read: every seat has its own socket and the
                    ;; server fans out to them one after another, so the
                    ;; lover's view arriving says nothing about this one's. Read
                    ;; at once, a slow runner sometimes still held the view
                    ;; from before the link.
                    (let [involved (set [(:pid amor) a b])
                          other (first (remove #(involved (:pid %)) all))
                          ov (:view (await-msg (:client other)
                                               #(and (= :room/view (:type %))
                                                     (get-in % [:view :lovers-exist?]))))]
                      (is (true? (:lovers-exist? ov)))
                      (is (nil? (:lover (:me ov))))))))
              (finally (doseq [{:keys [client]} all] (close! client))))))
        (finally (cleanup! path))))))

(deftest every-gm-override-works-and-lands-in-the-public-log
  (testing "verification step 6 of the plan, over real sockets. Two of these
            could not be issued at all until now: :gm/set-phase and
            :gm/skip-role name a phase and a role rather than a seat, and the
            client's only way to say so was :target, which the wire schema
            declares an :int"
    (let [path (tmp-path)]
      (try
        (with-server [srv path]
          (let [host (client (:port srv))
                _ (await-type host :server/hello)
                _ (send! host {:type :client/create :name "Anna" :locale :de})
                code (:room-id (await-type host :server/welcome))
                guests (doall (for [n ["Bob" "Cem" "Dana" "Eve"]]
                                (let [c (client (:port srv))]
                                  (await-type c :server/hello)
                                  (send! c {:type :client/join :room-id code :name n})
                                  (await-type c :server/welcome)
                                  c)))
                all (cons host guests)
                gm! (fn [cmd] (send! host {:type :client/command :cmd cmd}))
                forced (fn [c] (filterv #(= :gm/forced (:type %)) (:log (latest-view c))))
                ;; Every wait here is on the OVERRIDE LOG growing, never on a
                ;; flag flipping. `await-msg` scans the whole inbox from the
                ;; start, so "wait until alive? is true again" matched a view
                ;; from before the kill and the test raced past the revive it
                ;; was supposed to be waiting for. The log only ever grows.
                await-actions (fn [c expected]
                                (await-msg c #(and (= :room/view (:type %))
                                                   (every? (into #{} (keep :action)
                                                                 (get-in % [:view :log]))
                                                           expected))))]
            (try
              (send! host {:type :client/command :cmd {:type :start-game}})
              (doseq [c all] (await-phase c :night))

              (testing "the host is offered every override, with something legal
                        to point at for each"
                ;; The clock is armed by a second commit after the phase change,
                ;; and :gm/add-time is only offered once there is a deadline to
                ;; add to — waiting for the phase is not waiting for the clock.
                (await-deadline host)
                (let [v (latest-view host)
                      offered (set (:actions v))]
                  (doseq [a [:gm/force-kill :gm/revive :gm/set-phase :gm/skip-role
                             :gm/reassign-role :gm/pause :gm/add-time]]
                    ;; :gm/revive needs a corpse and :gm/add-time a clock, so
                    ;; both are conditional; the rest must always be there.
                    (when-not (#{:gm/revive} a)
                      (is (contains? offered a) (str a " was not offered"))))
                  (testing "and the choices arrive as choices, not as targets"
                    (is (= [:seherin] (get-in v [:choices :gm/skip-role]))
                        "exactly the role being called")
                    (is (seq (get-in v [:choices :gm/reassign-role])))
                    (is (empty? (get-in v [:targets :gm/set-phase]))
                        "a phase is not a seat"))))

              ;; Nothing below may depend on WHO holds which role. The deck is
              ;; dealt from a random per-room seed and the host cannot see it —
              ;; that is the whole point of the engine — so a test that reassigns
              ;; one seat to one fixed role fails whenever the deal already put
              ;; it there. It did, about one run in four.
              ;;
              ;; Two seats, both made werewolves. At five players there is one
              ;; wolf, so at most one of these is a no-op and at least one lands.
              ;; And ADDING a wolf can never decide the game, while removing the
              ;; last one ends it and would have every later override refused.
              (let [victims (vec (take 2 (sort (remove #{0} (keys (:players (latest-view host)))))))
                    victim (first victims)]
                (testing "every override, issued in one run"
                  (gm! {:type :gm/add-time :secs 30})
                  (gm! {:type :gm/pause})
                  (gm! {:type :gm/resume})
                  ;; Named, not targeted. This is the pair that could not be
                  ;; sent at all before :choices existed.
                  (gm! {:type :gm/skip-role :role :seherin})
                  (doseq [v victims]
                    (gm! {:type :gm/reassign-role :target v :role :werwolf}))
                  ;; Certainly a wolf by now, and certainly not the last one.
                  (gm! {:type :gm/force-kill :target victim})
                  (gm! {:type :gm/revive :target victim})
                  (gm! {:type :gm/set-phase :phase :discussion}))

                (let [all-actions #{:add-time :pause :resume :skip-role
                                    :reassign-role :force-kill :revive :set-phase}]
                  (testing "every one of them lands in the log the whole table
                            reads — an override only the host can see audits
                            nothing"
                    (doseq [c all]
                      (await-actions c all-actions)
                      (is (= all-actions (into #{} (keep :action) (forced c)))
                          "someone is missing an override")))

                  (testing "and each did what it said"
                    (let [v (latest-view host)]
                      (is (= :discussion (:phase v)))
                      (is (false? (:paused? v)))
                      (is (true? (get-in v [:players victim :alive?]))
                          "killed and brought back")))

                  (testing "the reassignment told the table that it happened and
                            nothing whatsoever about what it did"
                    (doseq [c all
                            entry (filterv #(= :reassign-role (:action %)) (forced c))]
                      (is (= [:target] (keys (:detail entry)))
                          "the seat, never the role")
                      (is (contains? (set victims) (:target (:detail entry))))))))

              (finally (doseq [c all] (close! c))))))
        (finally (cleanup! path))))))

(deftest killing-the-server-mid-game-loses-nothing
  (testing "verification step 5: kill the server mid-game and restart. Clients
            reconnect from their tokens and land in the correct phase with the
            correct state — which exercises the event log end to end."
    (let [path (tmp-path)]
      (try
        (let [;; --- first life ---
              srv1 (boot! path)
              host (client (:port srv1))
              _ (await-type host :server/hello)
              _ (send! host {:type :client/create :name "Anna"})
              w0 (await-type host :server/welcome)
              code (:room-id w0)
              guests (doall (for [n ["Bob" "Cem" "Dana" "Eve"]]
                              (let [c (client (:port srv1))]
                                (await-type c :server/hello)
                                (send! c {:type :client/join :room-id code :name n})
                                {:client c :welcome (await-type c :server/welcome)})))
              _ (send! host {:type :client/command :cmd {:type :start-game}})
              before (do (await-phase host :night) (latest-view host))
              tokens (into {0 (:token w0)}
                           (map (fn [{:keys [welcome]}] [(:player-id welcome) (:token welcome)]))
                           guests)]
          (is (= :night (:phase before)))
          (is (= 5 (count tokens)))
          (doseq [c (cons host (map :client guests))] (close! c))
          (shutdown! srv1)

          ;; --- second life: same database, brand new process state ---
          (let [srv2 (boot! path)]
            (try
              (let [reconnected
                    (doall (for [[pid tok] (sort tokens)]
                             (let [c (client (:port srv2))]
                               (await-type c :server/hello)
                               (send! c {:type :client/resume :room-id code :token tok})
                               {:pid pid
                                :client c
                                :welcome (await-type c :server/welcome)
                                :view (:view (await-type c :room/view))})))]
                (try
                  (testing "every token still names its own seat"
                    (doseq [{:keys [pid welcome]} reconnected]
                      (is (= pid (:player-id welcome)))
                      (is (empty? (filterv #(= :server/error (:type %))
                                           (msgs (:client (first reconnected))))))))

                  (testing "the game is in the phase it was in when the lights went out"
                    (doseq [{:keys [view]} reconnected]
                      (is (= (:phase before) (:phase view)))
                      (is (= (:night before) (:night view)))))

                  (testing "and every player has the role they were dealt"
                    (doseq [{:keys [pid client]} reconnected]
                      (let [v (latest-view client)]
                        (is (= pid (:id (:me v))))
                        (is (some? (:role (:me v)))
                            "a reconnected player who has forgotten their role is
                             worse than useless"))))

                  (testing "the clock was re-armed rather than fired — nobody is
                            punished for an outage they had no part in"
                    (let [d (await-deadline (:client (first reconnected)))]
                      (is (int? d))
                      (is (> d (System/currentTimeMillis)))))

                  (testing "and the restored game still plays"
                    (let [host2 (:client (first reconnected))]
                      (send! host2 {:type :client/command
                                    :cmd {:type :gm/skip-role}})
                      (is (some? (await-msg host2
                                            #(and (= :room/view (:type %))
                                                  (seq (filter (fn [e] (= :gm/forced (:type e)))
                                                               (get-in % [:view :log]))))))
                          "a GM override after the restart is accepted and logged")))

                  (finally (doseq [{:keys [client]} reconnected] (close! client)))))
              (finally (shutdown! srv2)))))
        (finally (cleanup! path))))))

(deftest garbage-on-the-wire-does-not-kill-the-connection
  (let [path (tmp-path)]
    (try
      (with-server [srv path]
        (let [c (client (:port srv))]
          (try
            (await-type c :server/hello)
            (.get (.sendText (:ws c) "this is not transit at all" true)
                  timeout-ms TimeUnit/MILLISECONDS)
            (is (= :malformed (:code (await-type c :server/error))))
            (testing "and the socket is still usable"
              (send! c {:type :client/ping})
              (is (some? (await-type c :server/pong))))
            (finally (close! c)))))
      (finally (cleanup! path)))))

(deftest a-closing-socket-marks-its-player-absent-to-everyone-else
  (let [path (tmp-path)]
    (try
      (with-server [srv path]
        (let [host (client (:port srv))
              _ (await-type host :server/hello)
              _ (send! host {:type :client/create :name "Anna"})
              code (:room-id (await-type host :server/welcome))
              guest (client (:port srv))]
          (try
            (await-type guest :server/hello)
            (send! guest {:type :client/join :room-id code :name "Bob"})
            (await-type guest :server/welcome)
            (close! guest)
            (is (some? (await-msg host #(and (= :room/view (:type %))
                                             (false? (get-in % [:view :players 1 :connected?])))))
                "the host sees Bob as absent")
            (testing "absent, not gone: the seat is still there"
              (is (contains? (:players (latest-view host)) 1)))
            (finally (close! host)))))
      (finally (cleanup! path)))))
