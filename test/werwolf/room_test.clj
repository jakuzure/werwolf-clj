(ns werwolf.room-test
  "JVM-only: rooms are the concurrent shell around the engine.

   Sockets are replaced by 'sinks' — plain atoms collecting whatever the room
   sent them. That is the whole reason the room layer takes an injected
   `:send!`: every assertion below is about what a specific player received,
   which is the only question that matters for a hidden-role game."
  (:require [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [werwolf.engine.core :as engine]
            [werwolf.room :as room]
            [werwolf.store :as store]))

;; ------------------------------------------------------------- fixtures ---

(defn- tmp-path []
  (let [f (java.io.File/createTempFile "werwolf-room" ".db")]
    (.delete f)
    (.getAbsolutePath f)))

(defn- cleanup! [path]
  (doseq [suffix ["" "-wal" "-shm"]]
    (io/delete-file (str path suffix) true)))

(defn- mk-sink
  "A fake socket: an atom accumulating messages, newest last."
  [] (atom []))

(defn- ctx-on
  "A registry over a fresh database.

   Opts in to the CLASSIC first night, because several tests here walk to the
   werewolves' turn to check chat routing and would otherwise have to play
   through a whole day first. The shipped default (no night-1 kill) is covered
   by `the-first-night-is-peaceful-by-default` below."
  [path]
  (room/registry {:ds (store/open {:dbtype "sqlite" :dbname path})
                  :send! (fn [s msg] (swap! s conj msg))
                  ;; The creator plays unless a test says otherwise: most of
                  ;; these were written when the host was always dealt a card.
                  ;; Narrated rooms are opted into per room — see
                  ;; `create!`'s :narrated? — by the tests about them.
                  :game {:first-night-kill? true :first-day-trial? true :narrated? false}}))

(defn- msgs [s] @s)
(defn- views [s] (filter #(= :room/view (:type %)) (msgs s)))
(defn- last-view [s] (:view (last (views s))))
(defn- chats [s] (filter #(= :room/chat (:type %)) (msgs s)))
(defn- chat-counts [seats]
  (into {} (map (fn [{:keys [pid sink]}] [pid (count (chats sink))])) seats))

(defmacro with-ctx
  "Bind a registry over a fresh on-disk database, and clean up after."
  [[binding] & body]
  `(let [path# (tmp-path)
         ~binding (ctx-on path#)]
     (try ~@body (finally (cleanup! path#)))))

(defn- host-seat!
  "Create a room and attach the host's socket."
  [ctx nm]
  (let [{:keys [room-id player-id]} (room/create! ctx {:name nm})
        s (mk-sink)]
    (room/attach! ctx room-id player-id s)
    {:room-id room-id :pid player-id :sink s}))

(defn- guest-seat! [ctx room-id nm]
  (let [{:keys [player-id]} (room/join! ctx room-id {:name nm})
        s (mk-sink)]
    (room/attach! ctx room-id player-id s)
    {:room-id room-id :pid player-id :sink s}))

(defn- table
  "A room with `n` seated, attached players. Returns {:room-id .. :seats [..]}."
  [ctx n]
  (let [host (host-seat! ctx "P0")]
    {:room-id (:room-id host)
     :seats (into [host] (mapv #(guest-seat! ctx (:room-id host) (str "P" %)) (range 1 n)))}))

(defn- start! [ctx {:keys [room-id seats]}]
  (room/submit! ctx room-id (:pid (first seats)) {:type :start-game}))

(defn- advance-to-wolves!
  "Skip the night roles ahead of the werewolves using the GM override, so chat
   tests do not depend on how the Seherin happens to play."
  [ctx room-id]
  (loop [guard 0]
    (let [st (room/state-of ctx room-id)]
      (when (and (= :night (:phase st))
                 (not= :werwolf (:night-cursor st))
                 (< guard 10))
        (room/submit! ctx room-id (:host st) {:type :gm/skip-role})
        (recur (inc guard))))))

(defn- age-room!
  "Backdate a room's last activity, so idleness can be tested without waiting."
  [ctx room-id ms]
  (swap! (:rooms ctx) update room-id update :last-activity - ms))

(defn- wolves-in [state]
  (set (filter #(= :werewolf (engine/team-of-player state %)) (engine/living-ids state))))

;; ------------------------------------------------------------- the basics ---

(deftest creating-a-room-seats-the-host-as-player-zero
  (with-ctx [ctx]
    (let [{:keys [room-id player-id]} (room/create! ctx {:name "Anna" :locale :de})]
      (is (= 0 player-id))
      (is (re-matches #"[A-Z2-9]{4}" room-id))
      (testing "codes avoid characters that get misheard on a voice call"
        (is (not (re-find #"[IO01]" room-id))))
      (is (= :lobby (:phase (room/state-of ctx room-id))))
      (testing "and it is on disk immediately, not only in memory"
        (is (store/room-exists? (:ds ctx) room-id))))))

(deftest table-size-limits-come-from-config
  (testing "MIN_PLAYERS was read into config and then never plumbed to
            create!, so the threshold was silently always 5 whatever you set"
    (let [path (tmp-path)]
      (try
        (let [ctx (room/registry {:ds (store/open {:dbtype "sqlite" :dbname path})
                                  :send! (fn [s m] (swap! s conj m))
                                  :game {:min-players 3 :max-players 4}})
              ;; Anna plays: the counts below are of a table she sits at.
              {:keys [room-id]} (room/create! ctx {:name "Anna" :narrated? false})]
          (is (= 3 (:min-players (room/state-of ctx room-id))))
          (room/join! ctx room-id {:name "Bob"})
          (testing "and the host can start once the DECK can make a game"
            (is (not (contains? (engine/legal-actions (room/state-of ctx room-id) 0)
                                :start-game))
                "two is a wolf and his dinner, not a game")
            (room/join! ctx room-id {:name "Cem"})
            (is (contains? (engine/legal-actions (room/state-of ctx room-id) 0)
                           :start-game)))
          (testing "and the maximum is enforced too, also from config"
            (room/join! ctx room-id {:name "Dana"})
            (is (= :room-full (:error (room/join! ctx room-id {:name "Eve"}))))))
        (finally (cleanup! path))))))

(deftest the-host-may-start-under-the-recommended-size
  (testing "MIN_PLAYERS is the recommended table, not a gate. Four people who
            want to play now must not be held back by a number set for a bigger
            evening — the UI says more is better and the host decides"
    (with-ctx [ctx]
      (let [{:keys [room-id]} (room/create! ctx {:name "Anna" :min-players 8})
            actions #(engine/legal-actions (room/state-of ctx room-id) 0)]
        (is (= 8 (:min-players (room/state-of ctx room-id))))
        (room/join! ctx room-id {:name "Bob"})
        (is (not (contains? (actions) :start-game)) "two is below the deck's floor")
        (room/join! ctx room-id {:name "Cem"})
        (is (contains? (actions) :start-game)
            "three is a game, even though eight was asked for")
        (is (:ok? (room/submit! ctx room-id 0 {:type :start-game})))
        (is (= :night (:phase (room/state-of ctx room-id))))))))

(deftest a-nonsensical-minimum-is-clamped-not-obeyed
  (testing "below three the roles cannot make a game that is not already decided"
    (with-ctx [ctx]
      (let [ctx' (assoc ctx :game {:min-players 1})
            {:keys [room-id]} (room/create! ctx' {:name "Anna"})]
        (is (= 3 (:min-players (room/state-of ctx' room-id))))))))

(deftest the-room-keeps-the-limits-it-was-opened-with
  (testing "a config change must never make a game in progress unstartable"
    (let [path (tmp-path)]
      (try
        (let [ds (store/open {:dbtype "sqlite" :dbname path})
              ctx (room/registry {:ds ds :send! (fn [s m] (swap! s conj m))
                                  :game {:min-players 3}})
              {:keys [room-id]} (room/create! ctx {:name "Anna"})
              ;; The operator raises the minimum and restarts.
              ctx2 (room/registry {:ds ds :send! (fn [s m] (swap! s conj m))
                                   :game {:min-players 12}})]
          (room/rehydrate! ctx2)
          (is (= 3 (:min-players (room/state-of ctx2 room-id)))
              "the rehydrated room still has its own threshold"))
        (finally (cleanup! path))))))

(deftest a-three-player-game-is-playable
  (testing "the smallest table the deck composes: one wolf, a Seherin and a
            villager. This is what MIN_PLAYERS=3 is for, so it had better work"
    (let [path (tmp-path)]
      (try
        (let [ctx (room/registry {:ds (store/open {:dbtype "sqlite" :dbname path})
                                  :send! (fn [s m] (swap! s conj m))
                                  :game {:min-players 3}})
              {:keys [room-id]} (room/create! ctx {:name "P0" :narrated? false})]
          (room/join! ctx room-id {:name "P1"})
          (room/join! ctx room-id {:name "P2"})
          (is (:ok? (room/submit! ctx room-id 0 {:type :start-game})))
          (let [st (room/state-of ctx room-id)]
            (is (= :night (:phase st)))
            (is (= #{:werwolf :seherin :dorfbewohner}
                   (set (map :role (vals (:players st)))))
                "one of each — no duplicate wolves at this size")
            (is (= 1 (count (wolves-in st))))))
        (finally (cleanup! path))))))

(deftest the-first-night-is-peaceful-by-default
  (testing "the shipped rule, end to end through the room layer: nobody is
            eliminated before they have said a word"
    (let [path (tmp-path)]
      (try
        (let [ctx (room/registry {:ds (store/open {:dbtype "sqlite" :dbname path})
                                  :send! (fn [s m] (swap! s conj m))})
              ;; Explicitly a playing host, so "no config" still means no
              ;; config for the rule under test.
              {:keys [room-id]} (room/create! ctx {:name "P0" :narrated? false})]
          (doseq [i (range 1 7)] (room/join! ctx room-id {:name (str "P" i)}))
          (room/submit! ctx room-id 0 {:type :start-game})
          (is (false? (:first-night-kill? (room/state-of ctx room-id)))
              "no config, so the variant is the default")
          ;; Let the night run itself out with GM skips.
          (loop [guard 0]
            (when (and (= :night (:phase (room/state-of ctx room-id))) (< guard 12))
              (room/submit! ctx room-id 0 {:type :gm/skip-role})
              (recur (inc guard))))
          (let [st (room/state-of ctx room-id)]
            (is (= 7 (count (engine/living-ids st)))
                "the whole village saw the first day")
            (is (some #(= :night/quiet (:type %)) (:log st))
                "and the Chronik says the night was quiet")))
        (finally (cleanup! path))))))

(deftest joining-allocates-fresh-seats-and-never-recycles-them
  (with-ctx [ctx]
    (let [{:keys [room-id]} (room/create! ctx {:name "Anna"})]
      (is (= 1 (:player-id (room/join! ctx room-id {:name "Bob"}))))
      (is (= 2 (:player-id (room/join! ctx room-id {:name "Cem"}))))
      (room/submit! ctx room-id 1 {:type :leave})
      (testing "seat 1 left, but the next player gets seat 3 — recycling a seat
                would let the leaver's reconnect token drive a stranger"
        (is (= 3 (:player-id (room/join! ctx room-id {:name "Dana"}))))))))

(deftest a-player-who-leaves-gets-no-further-view
  (with-ctx [ctx]
    (let [{:keys [room-id seats]} (table ctx 3)
          leaver (second seats)
          stayer (first seats)
          before-leaver (count (views (:sink leaver)))
          before-stayer (count (views (:sink stayer)))]
      (is (:ok? (room/submit! ctx room-id (:pid leaver) {:type :leave})))
      (testing "a socket leaves :sinks when it CLOSES, not when its player
                leaves, so the commit recording :player/left used to broadcast
                one last view to the player who had just walked out. Harmless
                looking, but the client treated an arriving view as proof it was
                in a room, so leaving took two clicks."
        (is (= before-leaver (count (views (:sink leaver))))
            "the departing player received no view of the room they left"))
      (testing "while everyone still seated is told that they are gone"
        (is (> (count (views (:sink stayer))) before-stayer))
        (is (not (contains? (:players (last-view (:sink stayer))) (:pid leaver))))))))

(deftest joining-a-room-that-does-not-exist-is-an-error-not-a-crash
  (with-ctx [ctx]
    (is (= {:error :no-such-room} (room/join! ctx "ZZZZ" {:name "Nobody"})))
    (is (= {:error :no-such-room} (room/submit! ctx "ZZZZ" 0 {:type :start-game})))
    (is (nil? (room/state-of ctx "ZZZZ")))))

(deftest a-nameless-player-is-refused-by-the-engine
  (with-ctx [ctx]
    (let [{:keys [room-id]} (room/create! ctx {:name "Anna"})]
      (is (= :name-required (:error (room/join! ctx room-id {:name "  "})))))))

(deftest the-open-room-list-shows-only-rooms-you-could-actually-join
  (testing "so nobody has to read a code off someone else's phone — and so the
            list never offers a door that is already locked"
    (with-ctx [ctx]
      (let [waiting (host-seat! ctx "Anna")
            playing (table ctx 5)]
        (guest-seat! ctx (:room-id waiting) "Bob")
        (is (= [{:room-id (:room-id waiting) :players 2 :max-players 18 :host "Anna"}]
               (filterv #(= (:room-id waiting) (:room-id %)) (room/open-rooms ctx)))
            "a lobby is listed with its host and how many are waiting")
        (testing "a game in progress drops off the list — it cannot be joined,
                  and offering it would be an invitation to a locked door"
          (is (some #(= (:room-id playing) (:room-id %)) (room/open-rooms ctx)))
          (start! ctx playing)
          (is (not-any? #(= (:room-id playing) (:room-id %)) (room/open-rooms ctx))))
        (testing "and a full lobby drops off too"
          ;; Both limits: max is clamped up to min, so asking for a 3-seat room
          ;; while the config still recommends 5 gives you a 5-seat room.
          (let [{:keys [room-id]} (room/create! ctx {:name "Cem" :min-players 3
                                                     :max-players 3})]
            (room/join! ctx room-id {:name "Dana"})
            (is (some #(= room-id (:room-id %)) (room/open-rooms ctx)))
            (room/join! ctx room-id {:name "Eve"})
            (is (not-any? #(= room-id (:room-id %)) (room/open-rooms ctx)))))
        (testing "nothing about who else is in a room, and certainly no roles"
          (doseq [entry (room/open-rooms ctx)]
            (is (= #{:room-id :players :max-players :host} (set (keys entry))))))))))

(deftest a-finished-game-can-be-walked-out-of
  (testing "at :game-over the engine offered nothing at all, so the last screen
            of a game was one the players could only leave by closing the tab"
    (with-ctx [ctx]
      (let [{:keys [room-id] :as t} (table ctx 5)]
        (start! ctx t)
        ;; Kill every wolf: the village wins and the game is over.
        (let [st (room/state-of ctx room-id)]
          (doseq [w (wolves-in st)]
            (room/submit! ctx room-id (:host st) {:type :gm/force-kill :target w})))
        (is (= :game-over (:phase (room/state-of ctx room-id))))
        (doseq [pid (keys (:players (room/state-of ctx room-id)))]
          (is (contains? (engine/legal-actions (room/state-of ctx room-id) pid) :leave)
              (str "player " pid " is stuck in a finished game")))
        (testing "and leaving actually works"
          (is (:ok? (room/submit! ctx room-id 1 {:type :leave}))))))))

;; -------------------------------------------------------------- broadcast ---

(deftest every-player-gets-their-own-view-on-every-change
  (with-ctx [ctx]
    (let [{:keys [seats]} (table ctx 5)]
      (doseq [{:keys [sink]} seats]
        (is (seq (views sink)) "everyone was painted"))
      (testing "each view is addressed to its own player, never a shared payload"
        (doseq [{:keys [pid sink]} seats]
          (is (= pid (:id (:me (last-view sink))))))))))

(deftest the-view-carries-a-sequence-number-so-clients-can-drop-stale-state
  (with-ctx [ctx]
    (let [{:keys [seats]} (table ctx 5)
          seqs (map :seq (views (:sink (first seats))))]
      (is (apply <= seqs) (str "seq went backwards: " (pr-str seqs))))))

(deftest a-second-tab-gets-its-own-copy
  (with-ctx [ctx]
    (let [{:keys [room-id pid sink]} (host-seat! ctx "Anna")
          tab2 (mk-sink)]
      (room/attach! ctx room-id pid tab2)
      (room/join! ctx room-id {:name "Bob"})
      (is (seq (views tab2)))
      (is (= (:phase (last-view sink)) (:phase (last-view tab2)))))))

(deftest broadcast-never-leaks-a-foreign-role
  (testing "the property the whole engine was shaped around, asserted against
            what actually crossed the wire rather than against view-for"
    (with-ctx [ctx]
      (let [{:keys [room-id seats] :as t} (table ctx 7)]
        (start! ctx t)
        ;; Every payload that ever crossed the wire, audited against the
        ;; player's entitlement. (Judged against current entitlement, which
        ;; only ever grows — so this is if anything too generous, never too
        ;; strict.)
        (doseq [{:keys [pid sink]} seats
                v (map :view (views sink))]
          (is (empty? (engine/redaction-leaks? (room/state-of ctx room-id) pid v))
              (str "player " pid " received a foreign role")))
        (testing "and nothing but views and chat ever went out"
          (doseq [{:keys [sink]} seats
                  m (msgs sink)]
            (is (contains? #{:room/view :room/chat :room/chat-history} (:type m)))
            (when (= :room/view (:type m))
              (is (every? #(not (contains? % :role)) (vals (:players (:view m))))
                  "the public player map carries no roles at all"))))))))

(deftest reassigning-a-role-leaks-nothing-onto-the-wire
  (testing "the override that changes who somebody IS, audited against what
            actually reached each socket — the log crosses the wire inside
            every view, so a role named in it would reach the whole table"
    (with-ctx [ctx]
      (let [{:keys [room-id seats] :as t} (table ctx 7)]
        (start! ctx t)
        (let [st (room/state-of ctx room-id)
              ;; Somebody who is not already a wolf, so the change is real.
              victim (first (remove (wolves-in st) (engine/living-ids st)))]
          (is (:ok? (room/submit! ctx room-id (:host st)
                                  {:type :gm/reassign-role :target victim
                                   :role :werwolf})))
          (doseq [{:keys [pid sink]} seats
                  v (map :view (views sink))]
            (is (empty? (engine/redaction-leaks? (room/state-of ctx room-id) pid v))
                (str "player " pid " received a foreign role")))
          (testing "and the table was told that something happened"
            (is (some #(= :gm/forced (:type %))
                      (:log (last-view (:sink (first seats)))))))
          (testing "while the role it dealt appears in no log entry anyone
                    received. (:gm/skip-role does name a role, legitimately —
                    which role is being called is already public — so this is
                    scoped to the one override whose noun is a secret.)"
            (doseq [{:keys [sink]} seats
                    v (map :view (views sink))
                    entry (:log v)
                    :when (= :reassign-role (:action entry))]
              (is (not (contains? (:detail entry) :role))
                  (str "a reassignment named the role it dealt: " (pr-str entry))))))))))

;; ---------------------------------------------------------------- commands ---

(deftest a-command-cannot-be-cast-as-another-player
  (testing "the payload's :player is overwritten with the authenticated seat,
            so a hand-crafted frame cannot act as somebody else"
    (with-ctx [ctx]
      (let [{:keys [room-id] :as t} (table ctx 5)]
        (let [res (room/submit! ctx room-id 1 {:type :start-game :player 0})]
          (is (:error res) "impersonation refused")
          (is (= :lobby (:phase (room/state-of ctx room-id)))))
        (testing "while the real host succeeds"
          (is (:ok? (start! ctx t)))
          (is (not= :lobby (:phase (room/state-of ctx room-id)))))))))

(deftest an-illegal-command-changes-nothing
  (with-ctx [ctx]
    (let [{:keys [room-id seats]} (table ctx 5)
          before (room/state-of ctx room-id)
          counts (mapv #(count (msgs (:sink %))) seats)]
      (is (:error (room/submit! ctx room-id 1 {:type :vote :target 2})))
      (is (= before (room/state-of ctx room-id)) "state untouched")
      (is (= counts (mapv #(count (msgs (:sink %))) seats))
          "and nobody was re-broadcast to"))))

(deftest an-unknown-player-cannot-command
  (with-ctx [ctx]
    (let [{:keys [room-id]} (host-seat! ctx "Anna")]
      (is (= {:error :unknown-player} (room/submit! ctx room-id 99 {:type :start-game}))))))

(deftest the-host-leaving-the-lobby-hands-over-the-powers
  (testing "otherwise the room is unstartable and no command can rescue it"
    (with-ctx [ctx]
      (let [{:keys [room-id]} (table ctx 5)]
        (is (:ok? (room/submit! ctx room-id 0 {:type :leave})))
        (let [state (room/state-of ctx room-id)]
          (is (= 1 (:host state)) "the lowest remaining seat inherits")
          (is (some #(= :host/changed (:type %)) (:log state))
              "and it is public, because GM powers must be auditable"))
        (testing "the heir really can start the game"
          (room/join! ctx room-id {:name "Extra"})
          (is (:ok? (room/submit! ctx room-id 1 {:type :start-game}))))))))

;; ----------------------------------------------------------------- leaving ---

(deftest the-highest-seat-is-not-reissued-when-it-leaves
  (testing "seat numbers were computed from the seats still TAKEN, so the
            newest player walking out handed their number to the next arrival —
            and with it their reconnect token, which would then drive a stranger"
    (with-ctx [ctx]
      (let [{:keys [room-id]} (room/create! ctx {:name "Anna"})]
        (room/join! ctx room-id {:name "Bob"})
        (is (= 2 (:player-id (room/join! ctx room-id {:name "Cem"}))))
        (room/submit! ctx room-id 2 {:type :leave})
        (is (= 3 (:player-id (room/join! ctx room-id {:name "Dana"}))))
        (testing "nor when everybody has gone, which used to restart at seat 0"
          (doseq [pid [0 1 3]] (room/submit! ctx room-id pid {:type :leave}))
          (is (= 4 (:player-id (room/join! ctx room-id {:name "Eve"})))))))))

(deftest leaving-releases-every-socket-of-the-seat
  (testing "a socket left the registry only when it CLOSED, so after tapping
            leave the page was still bound to the seat it had just given up, and
            every create or join from it was refused as :already-in-room until
            the page was reloaded"
    (with-ctx [ctx]
      (let [{:keys [room-id seats]} (table ctx 3)
            {:keys [pid sink]} (second seats)
            other-tab (mk-sink)]
        (room/attach! ctx room-id pid other-tab)
        (is (:ok? (room/submit! ctx room-id pid {:type :leave})))
        (is (nil? (room/whereis ctx sink)))
        (is (nil? (room/whereis ctx other-tab)) "the second tab is released too")
        (testing "and each is told, so a second tab does not sit on a frozen
                  lobby it can no longer act in"
          (doseq [s [sink other-tab]]
            (is (= {:type :server/left :room-id room-id} (last (msgs s))))))
        (testing "while everyone still seated stays bound"
          (is (= [room-id 0] (room/whereis ctx (:sink (first seats))))))
        (testing "and the socket closing later is a no-op for the room"
          (let [seq0 (:seq (get @(:rooms ctx) room-id))]
            (room/detach! ctx sink)
            (is (= seq0 (:seq (get @(:rooms ctx) room-id))))))))))

(deftest leaving-mid-game-keeps-the-seat-but-not-the-socket
  (testing "the role still matters to the outcome, so the seat stays and reads
            as absent — and absent means no socket, the same thing a closed tab
            means, so a later reconnect is announced like any other"
    (with-ctx [ctx]
      (let [{:keys [room-id seats] :as t} (table ctx 5)
            {:keys [pid sink]} (second seats)]
        (start! ctx t)
        (is (:ok? (room/submit! ctx room-id pid {:type :leave})))
        (let [state (room/state-of ctx room-id)]
          (is (room/seated? ctx room-id pid))
          (is (false? (get-in state [:players pid :connected?]))))
        (is (nil? (room/whereis ctx sink)))
        (let [before (count (views sink))]
          (is (:ok? (room/submit! ctx room-id 0 {:type :gm/pause})))
          (is (= before (count (views sink))) "no more views for a page that left"))
        (testing "the seat can still be taken back"
          (let [back (mk-sink)]
            (room/attach! ctx room-id pid back)
            (is (true? (get-in (room/state-of ctx room-id)
                               [:players pid :connected?])))))))))

(deftest an-emptied-lobby-is-unlisted-but-its-code-still-works
  (testing "with nobody left there is nobody to recognise it by and nothing to
            join — but the code may already be on its way to a friend, and the
            idle sweep is what retires a room, not the last person leaving"
    (with-ctx [ctx]
      (let [{:keys [room-id sink]} (host-seat! ctx "Anna")]
        (room/submit! ctx room-id 0 {:type :leave})
        (is (not-any? #(= room-id (:room-id %)) (room/open-rooms ctx)))
        (testing "whoever walks in first holds the powers, or the room could
                  never be started: succession used to run only after a command,
                  and an empty room has nobody left to issue one"
          (let [{:keys [player-id]} (room/join! ctx room-id {:name "Bob"})]
            (is (= 1 player-id))
            (is (= 1 (:host (room/state-of ctx room-id))))
            (is (= "Bob" (:host (first (filter #(= room-id (:room-id %))
                                               (room/open-rooms ctx))))))
            (room/join! ctx room-id {:name "Cem"})
            (room/join! ctx room-id {:name "Dana"})
            (is (:ok? (room/submit! ctx room-id 1 {:type :start-game})))))
        (testing "and the leaver's still-open page is no longer bound to it"
          (is (nil? (room/whereis ctx sink))))))))

;; -------------------------------------------------------------------- chat ---

(deftest lobby-chat-reaches-everyone
  (with-ctx [ctx]
    (let [{:keys [room-id seats]} (table ctx 5)
          res (room/chat! ctx room-id 0 "  Hallo zusammen  ")]
      (is (= :all (:channel res)))
      (doseq [{:keys [sink]} seats]
        (is (= "Hallo zusammen" (:text (last (chats sink)))) "trimmed and delivered")))))

(deftest empty-chat-is-refused
  (with-ctx [ctx]
    (let [{:keys [room-id]} (host-seat! ctx "Anna")]
      (is (= :empty-message (:error (room/chat! ctx room-id 0 "   "))))
      (is (= :empty-message (:error (room/chat! ctx room-id 0 nil)))))))

(deftest overlong-chat-is-truncated-not-rejected
  (with-ctx [ctx]
    (let [{:keys [room-id sink]} (host-seat! ctx "Anna")]
      (room/chat! ctx room-id 0 (str/join (repeat 2000 "x")))
      (is (= 500 (count (:text (last (chats sink)))))))))

(deftest night-chat-reaches-only-the-wolves
  (testing "the leak that would end the game instantly"
    (with-ctx [ctx]
      (let [{:keys [room-id seats] :as t} (table ctx 7)]
        (start! ctx t)
        (is (= :night (:phase (room/state-of ctx room-id))))
        (advance-to-wolves! ctx room-id)
        (let [state (room/state-of ctx room-id)
              wolves (wolves-in state)]
          (is (= :werwolf (:night-cursor state)) "reached the wolves' turn")
          (is (seq wolves))
          (let [before (chat-counts seats)
                res (room/chat! ctx room-id (first (sort wolves)) "wir nehmen die Seherin")]
            (is (= :wolves (:channel res)))
            (is (= wolves (:recipients res)))
            (doseq [{:keys [pid sink]} seats]
              (if (contains? wolves pid)
                (is (= "wir nehmen die Seherin" (:text (last (chats sink))))
                    (str "wolf " pid " should hear the pack"))
                (is (= (get before pid) (count (chats sink)))
                    (str "player " pid " must not hear the pack"))))))))))

(deftest a-villager-cannot-talk-at-night-at-all
  (with-ctx [ctx]
    (let [{:keys [room-id] :as t} (table ctx 7)]
      (start! ctx t)
      (advance-to-wolves! ctx room-id)
      (let [state (room/state-of ctx room-id)
            villager (first (set/difference (set (engine/living-ids state)) (wolves-in state)))]
        (is (= :chat-not-allowed (:error (room/chat! ctx room-id villager "psst"))))))))

(deftest the-dead-talk-only-among-themselves
  (with-ctx [ctx]
    (let [{:keys [room-id seats] :as t} (table ctx 7)]
      (start! ctx t)
      ;; The GM kills someone outright — the shortest honest route to a corpse.
      ;; Not the Jäger: his death opens a reaction phase, and this test is about
      ;; chat routing, not about interrupts.
      (let [st0 (room/state-of ctx room-id)
            victim (first (remove #(= :jaeger (get-in st0 [:players % :role]))
                                  (rest (engine/living-ids st0))))]
        (room/submit! ctx room-id 0 {:type :gm/force-kill :target victim})
        (let [state (room/state-of ctx room-id)
              before (chat-counts seats)]
          (is (not (get-in state [:players victim :alive?])))
          (let [res (room/chat! ctx room-id victim "der Wolf ist Nummer 3")]
            (is (= :dead (:channel res)))
            (is (contains? (:recipients res) victim))
            (doseq [{:keys [pid sink]} seats
                    :when (get-in state [:players pid :alive?])]
              (is (= (get before pid) (count (chats sink)))
                  (str "living player " pid " heard a ghost")))))))))

(deftest reconnecting-replays-only-the-chat-you-were-entitled-to
  (with-ctx [ctx]
    (let [{:keys [room-id] :as t} (table ctx 7)]
      (start! ctx t)
      (advance-to-wolves! ctx room-id)
      (let [state (room/state-of ctx room-id)
            wolves (wolves-in state)
            villager (first (set/difference (set (engine/living-ids state)) wolves))]
        (room/chat! ctx room-id (first (sort wolves)) "geheim")
        (let [fresh (mk-sink)]
          (room/attach! ctx room-id villager fresh)
          (let [history (:messages (first (filter #(= :room/chat-history (:type %)) (msgs fresh))))]
            (is (some? history) "a reconnecting client is handed its backlog")
            (is (not-any? #(= "geheim" (:text %)) history)
                "filtered per player, not merely per phase")
            (is (not-any? :to history) "recipient sets stay server-side")))))))

;; --------------------------------------------------- connection lifecycle ---

(deftest a-closing-socket-marks-the-player-absent-not-dead
  (with-ctx [ctx]
    (let [{:keys [room-id seats] :as t} (table ctx 5)
          {:keys [pid sink]} (second seats)]
      (start! ctx t)
      (room/detach! ctx sink)
      (let [state (room/state-of ctx room-id)]
        (is (false? (get-in state [:players pid :connected?])))
        (is (true? (get-in state [:players pid :alive?]))
            "disconnected is never dead — the role still matters to the outcome")
        (is (some #(and (= :player/disconnected (:type %)) (= pid (:player %))) (:log state)))))))

(deftest a-closing-socket-is-stamped-with-when
  (testing "the engine has no clock, so the room stamps the disconnect — it is
            how the timer tells a phone that blinked from a seat nobody is
            coming back to"
    (with-ctx [ctx]
      (let [{:keys [room-id seats]} (table ctx 5)
            {:keys [pid sink]} (second seats)
            before (System/currentTimeMillis)]
        (room/detach! ctx sink)
        (let [since (get-in (room/state-of ctx room-id) [:players pid :absent-since])]
          (is (int? since))
          (is (<= before since (System/currentTimeMillis))))
        (is (some #(and (= :player/connection (:type %)) (false? (:connected? %)) (int? (:at %)))
                  (store/load-events (:ds ctx) room-id))
            "and the stamp is in the log, so a replay measures the same absence")
        (testing "coming back forgets it"
          (room/attach! ctx room-id pid (mk-sink))
          (is (nil? (get-in (room/state-of ctx room-id) [:players pid :absent-since]))))))))

(deftest a-restart-measures-absence-from-the-boot
  (testing "time nobody could play through is not time"
    (let [path (tmp-path)]
      (try
        (let [ctx (ctx-on path)
              {:keys [room-id] :as t} (table ctx 5)]
          (start! ctx t)
          (let [ctx2 (ctx-on path)
                boot (System/currentTimeMillis)]
            (room/rehydrate! ctx2)
            (is (every? #(<= boot (:absent-since %))
                        (vals (:players (room/state-of ctx2 room-id)))))))
        (finally (cleanup! path))))))

(deftest the-servers-own-commits-are-not-activity
  (testing "a clock firing in front of an empty table used to push the sweep
            back with every timeout, and an abandoned game ran on for months"
    (with-ctx [ctx]
      (let [{:keys [room-id seats] :as t} (table ctx 5)]
        (start! ctx t)
        (doseq [{:keys [sink]} seats] (room/detach! ctx sink))
        (age-room! ctx room-id (* 2 60 60 1000))
        (let [aged (:last-activity (get @(:rooms ctx) room-id))]
          (room/narrate! ctx room-id (fn [_] [{:type :phase/timed-out :phase :night}]))
          (is (= aged (:last-activity (get @(:rooms ctx) room-id))))
          (is (= [room-id] (room/sweep-idle! ctx (System/currentTimeMillis)
                                             {:room-idle-ms 1000
                                              :game-idle-ms (* 30 60 1000)}))))))))

(deftest closing-one-of-two-tabs-is-not-an-absence
  (with-ctx [ctx]
    (let [{:keys [room-id pid]} (host-seat! ctx "Anna")
          tab2 (mk-sink)]
      (room/attach! ctx room-id pid tab2)
      (room/detach! ctx tab2)
      (is (true? (get-in (room/state-of ctx room-id) [:players pid :connected?]))))))

(deftest reattaching-announces-the-return
  (with-ctx [ctx]
    (let [{:keys [room-id seats]} (table ctx 5)
          {:keys [pid sink]} (second seats)
          fresh (mk-sink)]
      (room/detach! ctx sink)
      (room/attach! ctx room-id pid fresh)
      (is (true? (get-in (room/state-of ctx room-id) [:players pid :connected?])))
      (is (some #(and (= :player/reconnected (:type %)) (= pid (:player %)))
                (:log (room/state-of ctx room-id))))
      (testing "and the returning tab is painted with the current state"
        (is (some? (last-view fresh)))))))

(deftest attaching-to-a-seat-that-does-not-exist-is-refused
  (with-ctx [ctx]
    (let [{:keys [room-id]} (host-seat! ctx "Anna")]
      (is (= {:error :unknown-player} (room/attach! ctx room-id 42 (mk-sink)))))))

;; ------------------------------------------------------------- persistence ---

(deftest a-restart-rehydrates-the-game-exactly
  (testing "verification step 5 of the plan, minus the browser: kill the
            server mid-game and every room comes back in the right phase"
    (let [path (tmp-path)]
      (try
        (let [ctx (ctx-on path)
              {:keys [room-id] :as t} (table ctx 7)]
          (start! ctx t)
          (let [live (room/state-of ctx room-id)
                ;; A brand new registry over the same file IS the restart.
                ctx2 (ctx-on path)
                restored (room/rehydrate! ctx2)
                after (room/state-of ctx2 room-id)]
            (is (= [room-id] restored))
            (is (= (:phase live) (:phase after)))
            (is (= (dissoc live :players :log) (dissoc after :players :log))
                "everything but connection bookkeeping is identical")
            (testing "roles survived, so the game can actually continue"
              (is (= (into {} (map (fn [[id p]] [id (:role p)])) (:players live))
                     (into {} (map (fn [[id p]] [id (:role p)])) (:players after)))))
            (testing "and everyone is marked absent — the sockets really did die"
              (is (every? #(false? (:connected? %)) (vals (:players after)))))
            (testing "the log still folds back to the live state"
              (is (= after (engine/replay (store/load-events (:ds ctx2) room-id)))))))
        (finally (cleanup! path))))))

(deftest a-finished-game-is-not-resurrected
  (let [path (tmp-path)]
    (try
      (let [ctx (ctx-on path)
            {:keys [room-id] :as t} (table ctx 5)]
        (start! ctx t)
        ;; Drive to game over the blunt way: the GM removes the village.
        (loop [guard 0]
          (let [st (room/state-of ctx room-id)]
            (when (and (not= :game-over (:phase st)) (< guard 30))
              (when-let [victim (first (set/difference (set (engine/living-ids st))
                                                      (wolves-in st)))]
                (room/submit! ctx room-id (:host st) {:type :gm/force-kill :target victim}))
              (recur (inc guard)))))
        (is (= :game-over (:phase (room/state-of ctx room-id))))
        (let [ctx2 (ctx-on path)]
          (is (empty? (room/rehydrate! ctx2)))
          (is (nil? (room/state-of ctx2 room-id)))
          (testing "but the log is still there for a recap"
            (is (seq (store/load-events (:ds ctx2) room-id))))))
      (finally (cleanup! path)))))

(deftest forgetting-a-room-clears-its-sockets-from-the-index
  (with-ctx [ctx]
    (let [{:keys [room-id sink]} (host-seat! ctx "Anna")]
      (is (true? (room/forget-room! ctx room-id)))
      (is (nil? (room/state-of ctx room-id)))
      (testing "a socket closing afterwards is a no-op, not an exception"
        (is (nil? (room/detach! ctx sink)))))))

;; ------------------------------------------------------------- concurrency ---

(deftest concurrent-commands-produce-one-total-order
  (testing "the reason every mutation runs under the room's lock: N threads
            joining at once must produce N distinct seats and a log with no
            gaps, not a lost update"
    (with-ctx [ctx]
      (let [{:keys [room-id]} (room/create! ctx {:name "Host"})
            n 17
            results (->> (range n)
                         (mapv (fn [i] (future (room/join! ctx room-id {:name (str "P" i)}))))
                         (mapv deref))
            pids (keep :player-id results)]
        (is (= n (count pids)) (str "some joins failed: " (pr-str results)))
        (is (= (count pids) (count (set pids))) "no two players got the same seat")
        (let [state (room/state-of ctx room-id)
              events (store/load-events (:ds ctx) room-id)]
          (is (= 18 (count (:players state))))
          (testing "and the persisted log replays to exactly the live state"
            (is (= state (engine/replay events)))))))))

(deftest concurrent-chat-does-not-lose-messages
  (with-ctx [ctx]
    (let [{:keys [room-id seats]} (table ctx 5)
          sends (->> (range 40)
                     (mapv (fn [i] (future (room/chat! ctx room-id (mod i 5) (str "m" i)))))
                     (mapv deref))]
      (is (every? :ok? sends))
      (doseq [{:keys [sink]} seats]
        (is (= 40 (count (chats sink))) "everyone saw every public message")))))

;; --------------------------------------------------------- idle sweeping ---

(deftest an-abandoned-lobby-is-dropped
  (with-ctx [ctx]
    (let [{:keys [room-id sink]} (host-seat! ctx "Anna")]
      (testing "while somebody is connected it stays, however quiet it gets"
        (age-room! ctx room-id (* 60 60 1000))
        (is (empty? (room/sweep-idle! ctx (System/currentTimeMillis)
                                      {:room-idle-ms 1000 :game-idle-ms 1000})))
        (is (some? (room/state-of ctx room-id))))

      (testing "but once the last socket has gone and it has sat idle, it goes"
        (room/detach! ctx sink)
        (age-room! ctx room-id (* 60 60 1000))
        (is (= [room-id] (room/sweep-idle! ctx (System/currentTimeMillis)
                                           {:room-idle-ms 1000 :game-idle-ms 1000})))
        (is (nil? (room/state-of ctx room-id)) "gone from memory"))

      (testing "and it is no longer offered to anyone arriving"
        (is (not-any? #(= room-id (:room-id %)) (room/open-rooms ctx)))))))

(deftest an-emptied-lobby-is-swept-though-the-leavers-page-is-open
  (testing "the departed player's socket stayed in :sinks until it closed, so a
            room everybody had walked out of still counted as occupied for as
            long as one of their tabs stayed open"
    (with-ctx [ctx]
      (let [{:keys [room-id]} (host-seat! ctx "Anna")]
        (room/submit! ctx room-id 0 {:type :leave})
        (age-room! ctx room-id (* 60 60 1000))
        (is (= [room-id] (room/sweep-idle! ctx (System/currentTimeMillis)
                                           {:room-idle-ms 1000
                                            :game-idle-ms 1000})))))))

(deftest a-game-in-progress-gets-a-longer-grace
  (testing "a locked phone drops its socket, so a table that put their phones
            down for five minutes looks exactly like an abandoned game — and
            destroying one mid-evening is far worse than keeping a dead room"
    (with-ctx [ctx]
      (let [{:keys [room-id seats]} (table ctx 5)]
        (start! ctx {:room-id room-id :seats seats})
        (doseq [{:keys [sink]} seats] (room/detach! ctx sink))
        (age-room! ctx room-id 60000)
        (is (empty? (room/sweep-idle! ctx (System/currentTimeMillis)
                                      {:room-idle-ms 1000 :game-idle-ms (* 30 60 1000)}))
            "a minute of silence must not end a real game")
        (is (some? (room/state-of ctx room-id)))

        (testing "though it does go eventually"
          (age-room! ctx room-id (* 2 60 60 1000))
          (is (= [room-id] (room/sweep-idle! ctx (System/currentTimeMillis)
                                             {:room-idle-ms 1000
                                              :game-idle-ms (* 30 60 1000)}))))))))

(deftest a-restart-does-not-refresh-an-abandoned-room
  (testing "a boot is not activity. A game nobody has touched for hours must be
            swept by the restart that finds it, not handed a fresh half hour —
            a server that restarts more often than the threshold would otherwise
            never sweep anything at all"
    (let [path (tmp-path)]
      (try
        (let [ctx (ctx-on path)
              {:keys [room-id seats] :as t} (table ctx 5)
              hours-ago (- (System/currentTimeMillis) (* 2 60 60 1000))]
          (start! ctx t)
          (doseq [{:keys [sink]} seats] (room/detach! ctx sink))
          ;; Backdate the LOG, not the in-memory room: the point is what a
          ;; brand new registry over this file believes.
          (jdbc/execute-one! (:ds ctx)
                             ["UPDATE rooms SET updated_at = ?, active_at = ? WHERE room_id = ?"
                              hours-ago hours-ago room-id])
          (let [ctx2 (ctx-on path)]
            (is (contains? (set (room/rehydrate! ctx2)) room-id))
            (is (< (Math/abs (- (:last-activity (get @(:rooms ctx2) room-id)) hours-ago))
                   1000)
                "the room remembers when it was really last touched")
            (is (= [room-id] (room/sweep-idle! ctx2 (System/currentTimeMillis)
                                               {:room-idle-ms (* 5 60 1000)
                                                :game-idle-ms (* 30 60 1000)}))
                "and goes on the first sweep, at the real thresholds")))
        (finally (cleanup! path))))))

(deftest sweeping-forgets-memory-but-keeps-the-log
  (testing "an unfinished room that was swept comes back on the next boot —
            forgetting is not deleting, and the event log is the truth"
    (let [path (tmp-path)]
      (try
        (let [ctx (ctx-on path)
              {:keys [room-id sink seats]} (let [t (table ctx 5)]
                                             (assoc t :sink (:sink (first (:seats t)))))]
          (start! ctx {:room-id room-id :seats seats})
          (doseq [{:keys [sink]} seats] (room/detach! ctx sink))
          (age-room! ctx room-id (* 2 60 60 1000))
          (is (= [room-id] (room/sweep-idle! ctx (System/currentTimeMillis)
                                             {:room-idle-ms 1 :game-idle-ms 1})))
          (is (nil? (room/state-of ctx room-id)))
          ;; A brand new registry over the same file IS the restart.
          (let [ctx2 (ctx-on path)]
            (is (contains? (set (room/rehydrate! ctx2)) room-id)
                "the swept game is restored from the log")))
        (finally (cleanup! path))))))

;; ------------------------------------------------------------ Spielleiter ---

(deftest a-new-room-is-narrated-unless-config-says-otherwise
  (testing "the creator is the Spielleiter by default, with no clock — and a
            deployment, or a single room, can ask for the old table instead"
    (with-ctx [ctx]
      (let [bare (assoc ctx :game {})
            narrated (room/state-of bare (:room-id (room/create! bare {:name "SL"})))]
        (is (true? (:narrated? narrated)))
        (is (false? (:timer? narrated)) "a narrated room starts without a clock")
        (is (= 0 (engine/narrator narrated))))
      (let [playing (room/state-of ctx (:room-id (room/create! ctx {:name "Anna"})))]
        (is (false? (:narrated? playing)) "config said no")
        (is (true? (:timer? playing))))
      (let [asked (room/state-of ctx (:room-id (room/create! ctx {:name "SL" :narrated? true})))]
        (is (true? (:narrated? asked)) "and the room's own option wins over config")))))

(defn- narrated-table
  "A narrated room: the Spielleiter in seat 0 and `n` attached players, already
   through setup and into night 1."
  [ctx n]
  (let [{:keys [room-id player-id]} (room/create! ctx {:name "SL" :narrated? true})
        sl {:room-id room-id :pid player-id :sink (mk-sink)}]
    (room/attach! ctx room-id player-id (:sink sl))
    (let [seats (into [sl] (mapv #(guest-seat! ctx room-id (str "P" %)) (range 1 (inc n))))]
      (room/submit! ctx room-id 0 {:type :start-game})
      (room/submit! ctx room-id 0 {:type :begin-night})
      {:room-id room-id :seats seats})))

(deftest the-narrator-hears-every-channel-and-speaks-to-everyone
  (with-ctx [ctx]
    (let [{:keys [room-id seats]} (narrated-table ctx 7)]
      (is (= :night (:phase (room/state-of ctx room-id))))
      (advance-to-wolves! ctx room-id)
      (let [state (room/state-of ctx room-id)
            wolves (wolves-in state)]
        (is (= :werwolf (:night-cursor state)))
        (testing "the wolves' whisper reaches the narrator too, and nobody else"
          (let [res (room/chat! ctx room-id (first (sort wolves)) "die Seherin")]
            (is (= :wolves (:channel res)))
            (is (= (conj wolves 0) (:recipients res)))))
        (testing "he may talk at night, and the whole table hears him"
          (is (contains? (engine/legal-actions state 0) :chat))
          (let [res (room/chat! ctx room-id 0 "Die Werwölfe erwachen")]
            (is (= :narrator (:channel res)))
            (is (= (set (map :pid seats)) (:recipients res)))
            (doseq [{:keys [sink]} seats]
              (is (= "Die Werwölfe erwachen" (:text (last (chats sink))))))))
        (testing "the dead talk among themselves — and to the narrator"
          (let [victim (first (remove wolves (engine/living-ids state)))]
            (room/submit! ctx room-id 0 {:type :gm/force-kill :target victim})
            (let [res (room/chat! ctx room-id victim "huhu")]
              (is (= :dead (:channel res)))
              (is (= #{victim 0} (:recipients res))))))))))

(deftest the-narrator-is-never-dealt-a-card-through-the-room
  (with-ctx [ctx]
    (let [{:keys [room-id]} (narrated-table ctx 5)
          st (room/state-of ctx room-id)]
      (is (nil? (get-in st [:players 0 :role])))
      (is (= 5 (count (keep :role (vals (:players st))))))
      (testing "and the view he is sent carries every card, which no one else's does"
        (is (= 5 (count (:known-roles (engine/view-for st 0)))))
        (doseq [pid (range 6)]
          (is (nil? (engine/redaction-leaks? st pid))))))))

(deftest a-narrator-who-leaves-the-lobby-hands-on-the-narration
  (testing "succession moves the host's seat, and the Spielleitung is that seat:
            whoever inherits it is the narrator now, and the view says so"
    (with-ctx [ctx]
      (let [{:keys [room-id]} (room/create! ctx {:name "SL" :narrated? true})
            heir (guest-seat! ctx room-id "Bea")]
        (guest-seat! ctx room-id "Cem")
        (room/submit! ctx room-id 0 {:type :leave})
        (let [st (room/state-of ctx room-id)]
          (is (= (:pid heir) (engine/narrator st)))
          (is (= (:pid heir) (:narrator (last-view (:sink heir))))))))))

(deftest the-host-can-hand-the-narration-to-somebody-else
  (with-ctx [ctx]
    (let [{:keys [room-id]} (room/create! ctx {:name "SL" :narrated? true})
          bea (guest-seat! ctx room-id "Bea")]
      (is (:ok? (room/submit! ctx room-id 0 {:type :gm/transfer-host :target (:pid bea)})))
      (let [st (room/state-of ctx room-id)]
        (is (= (:pid bea) (:host st)))
        (is (= (:pid bea) (:narrator (last-view (:sink bea)))))))))

;; ------------------------------------------------------------ the password ---

(defn- raw-log
  "Everything the database holds for a room, as one string — the file a backup
   copies and somebody eventually opens by hand."
  [ctx room-id]
  (pr-str (store/load-events (:ds ctx) room-id)))

(deftest a-room-can-be-opened-with-a-password
  (with-ctx [ctx]
    (let [{:keys [room-id]} (room/create! ctx {:name "Anna" :password "Vollmond"})
          st (room/state-of ctx room-id)]
      (is (string? (:password-hash st)))
      (testing "and the password itself is nowhere: not in state, not on disk"
        (is (not (str/includes? (pr-str st) "Vollmond")))
        (is (not (str/includes? (str/lower-case (raw-log ctx room-id)) "vollmond"))))
      (testing "the table sees that it is locked, the Chronik that it became so"
        (is (true? (:locked? (engine/view-for st 0))))
        (is (some #(= {:type :room/locked} %) (:log st))))
      (testing "the list still shows it — friends have to be able to find it"
        (is (= [{:room-id room-id :players 1 :max-players 18 :host "Anna" :locked? true}]
               (filterv #(= room-id (:room-id %)) (room/open-rooms ctx))))))))

(deftest a-blank-password-opens-an-ordinary-room
  (with-ctx [ctx]
    (let [{:keys [room-id]} (room/create! ctx {:name "Anna" :password "   "})]
      (is (not (contains? (room/state-of ctx room-id) :password-hash)))
      (is (= {:player-id 1 :room-id room-id} (room/join! ctx room-id {:name "Bob"}))))))

(deftest a-locked-room-wants-the-right-password
  (with-ctx [ctx]
    (let [{:keys [room-id]} (room/create! ctx {:name "Anna" :password "Vollmond"})]
      (is (= :password-required (:error (room/join! ctx room-id {:name "Bob"}))))
      (is (= :password-required (:error (room/join! ctx room-id {:name "Bob" :password " "}))))
      (is (= :wrong-password (:error (room/join! ctx room-id {:name "Bob" :password "Neumond"}))))
      (testing "nobody was seated by any of that"
        (is (= #{0} (set (keys (:players (room/state-of ctx room-id)))))))
      (testing "the refusal says nothing back about what was tried"
        (let [res (room/join! ctx room-id {:name "Bob" :password "Neumond"})]
          (is (not (str/includes? (pr-str res) "Neumond")))))
      (testing "and the right one, however a phone typed it, gets you in"
        (is (= {:room-id room-id :player-id 1}
               (room/join! ctx room-id {:name "Bob" :password "vollmond "})))))))

(deftest guessing-is-slowed-down-per-socket
  (with-ctx [ctx]
    (let [{:keys [room-id]} (room/create! ctx {:name "Anna" :password "Vollmond"})
          mallory (mk-sink)
          bob     (mk-sink)
          t0      1000000
          try!    (fn [sink pw t] (room/join! ctx room-id {:name "M" :password pw
                                                           :sink sink :now-ms t}))]
      (dotimes [i 5]
        (is (= :wrong-password (:error (try! mallory "falsch" (+ t0 i))))))
      (testing "the sixth guess in a minute is not even checked"
        (is (= :too-many-attempts (:error (try! mallory "falsch" (+ t0 10)))))
        (is (= :too-many-attempts (:error (try! mallory "Vollmond" (+ t0 20))))
            "not even a right one, or the limit would be an oracle"))
      (testing "a missing password is not a guess — it is the first step of joining"
        (is (= :password-required (:error (room/join! ctx room-id {:name "B" :sink bob :now-ms t0})))))
      (testing "somebody else's socket is not punished for it"
        (is (= 1 (:player-id (try! bob "Vollmond" (+ t0 30))))))
      (testing "and a minute later the same socket may try again"
        (is (= 2 (:player-id (try! mallory "Vollmond" (+ t0 60001)))))))))

(deftest a-socket-that-closes-takes-its-count-with-it
  (with-ctx [ctx]
    (let [{:keys [room-id]} (room/create! ctx {:name "Anna" :password "Vollmond"})
          s (mk-sink)]
      (dotimes [_ 5] (room/join! ctx room-id {:name "M" :password "falsch" :sink s}))
      (is (seq @(:attempts ctx)))
      (room/forget-attempts! ctx s)
      (is (empty? @(:attempts ctx)) "nothing kept for a socket that no longer exists"))))

(deftest only-the-host-may-change-the-lock
  (with-ctx [ctx]
    (let [{:keys [room-id seats]} (table ctx 3)
          [host guest] seats]
      (testing "a guest cannot lock the room, or open it"
        (is (= :not-host (:error (room/set-password! ctx room-id (:pid guest) "meins"))))
        (is (not (contains? (room/state-of ctx room-id) :password-hash))))
      (testing "the host can"
        (is (:ok? (room/set-password! ctx room-id (:pid host) "Vollmond")))
        (is (true? (:locked? (last-view (:sink guest)))) "and everyone sees the 🔒")
        (is (not (str/includes? (pr-str (msgs (:sink guest))) "pbkdf2"))
            "but no frame on any socket carries the hash")
        (is (= :wrong-password (:error (room/join! ctx room-id {:name "X" :password "meins"})))))
      (testing "change it"
        (is (:ok? (room/set-password! ctx room-id (:pid host) "Neumond")))
        (is (= :wrong-password (:error (room/join! ctx room-id {:name "X" :password "Vollmond"}))))
        (is (:player-id (room/join! ctx room-id {:name "X" :password "Neumond"}))))
      (testing "a guest cannot open it either"
        (is (= :not-host (:error (room/set-password! ctx room-id (:pid guest) nil)))))
      (testing "and open it again"
        (is (:ok? (room/set-password! ctx room-id (:pid host) nil)))
        (is (false? (:locked? (last-view (:sink guest)))))
        (is (= :room/unlocked (:type (last (:log (room/state-of ctx room-id))))))
        (is (:player-id (room/join! ctx room-id {:name "Y"}))))
      (testing "opening an open room changes nothing and logs nothing"
        (let [n (count (:log (room/state-of ctx room-id)))]
          (is (:ok? (room/set-password! ctx room-id (:pid host) nil)))
          (is (= n (count (:log (room/state-of ctx room-id)))))))
      (testing "nor does the password ever reach the log on disk"
        (let [raw (str/lower-case (raw-log ctx room-id))]
          (is (not (str/includes? raw "vollmond")))
          (is (not (str/includes? raw "neumond"))))))))

(deftest the-lock-is-a-lobby-switch
  (with-ctx [ctx]
    (let [tbl (table ctx 3)]
      (start! ctx tbl)
      (is (= :not-allowed (:error (room/set-password! ctx (:room-id tbl) 0 "Vollmond")))
          "nobody can join a game in progress, so a lock there would only be noise"))))

(deftest a-restart-keeps-the-door-locked
  (let [path (tmp-path)]
    (try
      (let [ds  (store/open {:dbtype "sqlite" :dbname path})
            ctx (room/registry {:ds ds :send! (fn [s m] (swap! s conj m))})
            {:keys [room-id]} (room/create! ctx {:name "Anna" :password "Vollmond"})
            ctx2 (room/registry {:ds ds :send! (fn [s m] (swap! s conj m))})]
        (room/rehydrate! ctx2)
        (is (= (:password-hash (room/state-of ctx room-id))
               (:password-hash (room/state-of ctx2 room-id))))
        (is (= :password-required (:error (room/join! ctx2 room-id {:name "Bob"}))))
        (is (:player-id (room/join! ctx2 room-id {:name "Bob" :password "Vollmond"})))
        (testing "and an opened door stays open"
          (room/set-password! ctx2 room-id 0 nil)
          (let [ctx3 (room/registry {:ds ds :send! (fn [s m] (swap! s conj m))})]
            (room/rehydrate! ctx3)
            (is (not (contains? (room/state-of ctx3 room-id) :password-hash))))))
      (finally (cleanup! path)))))
