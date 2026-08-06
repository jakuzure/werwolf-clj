(ns werwolf.protocol-test
  "The wire protocol, driven with atoms for sockets.

   Sockets being opaque to the protocol layer is what makes this possible: every
   assertion below is 'what would this client actually have received', which is
   the only question a hidden-role game cares about."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [werwolf.i18n.core :as i18n]
            [werwolf.proto :as proto]
            [werwolf.protocol :as protocol]
            [werwolf.room :as room]
            [werwolf.store :as store]
            [werwolf.timer :as timer]
            [werwolf.token :as token]))

(def game-cfg
  {:discussion-secs 300 :nomination-secs 90 :vote-secs 60
   :night-secs 45 :reaction-secs 30})

(def tokens-cfg {:secret "test-secret-that-is-long-enough" :ttl-mins 720})

(defn- tmp-path []
  (let [f (java.io.File/createTempFile "werwolf-proto" ".db")]
    (.delete f) (.getAbsolutePath f)))

(defn- cleanup! [path]
  (doseq [suffix ["" "-wal" "-shm"]] (io/delete-file (str path suffix) true)))

(defn- deps-on [path]
  (let [rooms (room/registry {:ds (store/open {:dbtype "sqlite" :dbname path})
                              :send! (fn [sock msg] (swap! sock conj msg))
                              :after-commit (timer/hook game-cfg)})]
    {:rooms rooms
     :tokens tokens-cfg
     :send! (fn [sock msg] (swap! sock conj msg))}))

(defmacro with-deps [[binding] & body]
  `(let [path# (tmp-path), ~binding (deps-on path#)]
     (try ~@body (finally (cleanup! path#)))))

(defn- sock [] (atom []))
(defn- got [s] @s)
(defn- of-type [s t] (filterv #(= t (:type %)) (got s)))
(defn- last-of [s t] (last (of-type s t)))
(defn- errors [s] (of-type s :server/error))
(defn- codes [s] (mapv :code (errors s)))
(defn- views [s] (of-type s :room/view))

(defn- seat!
  "Run a create or join to completion and return the socket."
  [deps msg]
  (let [s (sock)]
    (protocol/on-open deps s)
    (protocol/dispatch deps s msg)
    s))

;; ------------------------------------------------------------------ hello ---

(deftest a-new-socket-is-told-the-protocol-version
  (testing "a client has to know whether it can talk to this server at all"
    (with-deps [deps]
      (let [s (sock)]
        (protocol/on-open deps s)
        (is (= {:type :server/hello :protocol proto/protocol-version}
               (first (got s))))))))

;; ------------------------------------------------------------ create/join ---

(deftest creating-a-room-returns-a-seat-and-a-token
  (with-deps [deps]
    (let [s (seat! deps {:type :client/create :name "Anna" :locale :de})
          w (last-of s :server/welcome)]
      (is (some? w))
      (is (= 0 (:player-id w)))
      (is (true? (:host? w)))
      (is (re-matches #"[A-Z2-9]{4}" (:room-id w)))
      (testing "the token names this seat in this room"
        (is (= {:room-id (:room-id w) :player-id 0 :host? true}
               (token/verify tokens-cfg (:token w)
                             {:expect-room (:room-id w)
                              :now-ms (System/currentTimeMillis)}))))
      (testing "and the welcome arrives BEFORE the first view, so the client
                never has to guess which player it is"
        (let [types (mapv :type (got s))]
          (is (< (.indexOf types :server/welcome) (.indexOf types :room/view))))))))

(deftest joining-by-code-is-case-insensitive
  (testing "nobody types a room code the way it was printed"
    (with-deps [deps]
      (let [host (seat! deps {:type :client/create :name "Anna"})
            code (:room-id (last-of host :server/welcome))
            guest (seat! deps {:type :client/join
                               :room-id (clojure.string/lower-case code)
                               :name "Bob"})]
        (is (= 1 (:player-id (last-of guest :server/welcome))))
        (is (false? (:host? (last-of guest :server/welcome))))
        (is (empty? (errors guest)))))))

(deftest joining-a-room-that-does-not-exist-is-an-error
  (with-deps [deps]
    (let [s (seat! deps {:type :client/join :room-id "ZZZZ" :name "Bob"})]
      (is (= [:no-such-room] (codes s)))
      (is (empty? (views s)) "and no state leaked"))))

(deftest one-socket-cannot-seat-itself-twice
  (testing "every seat but the last would be a ghost nothing can reconnect to"
    (with-deps [deps]
      (let [s (seat! deps {:type :client/create :name "Anna"})]
        (protocol/dispatch deps s {:type :client/create :name "Anna Again"})
        (is (= [:already-in-room] (codes s)))))))

;; ------------------------------------------------------------- validation ---

(deftest junk-is-refused-before-it-reaches-the-engine
  (with-deps [deps]
    (let [s (sock)]
      (doseq [bad [nil
                   "a string"
                   42
                   {}
                   {:type :client/nonsense}
                   {:type :client/create}                       ; no name
                   {:type :client/create :name ""}              ; empty name
                   {:type :client/create :name "x" :extra 1}    ; closed map
                   {:type :client/join :room-id "abc" :name "x"} ; bad code
                   {:type :client/chat}                         ; no text
                   {:type :client/resume :room-id "ABCD"}]]     ; no token
        (swap! s empty)
        (protocol/dispatch deps s bad)
        (is (= [:malformed] (codes s)) (str "accepted: " (pr-str bad))))
      (testing "and nothing was created along the way"
        (is (empty? (room/room-ids (:rooms deps))))))))

(deftest the-command-whitelist-is-a-whitelist
  (with-deps [deps]
    (let [s (seat! deps {:type :client/create :name "Anna"})]
      (swap! s empty)
      (testing "a command type the protocol does not name is refused outright,
                whatever the engine might have done with it"
        (doseq [bad [{:type :join :name "Ghost"}
                     {:type :chat :text "hi"}
                     {:type :gm/nuke}]]
          (swap! s empty)
          (protocol/dispatch deps s {:type :client/command :cmd bad})
          (is (= [:malformed] (codes s)) (str "accepted: " (pr-str bad)))))
      (testing ":player is rejected rather than silently ignored — the server
                overwrites it anyway, and pretending otherwise is dishonest"
        (swap! s empty)
        (protocol/dispatch deps s {:type :client/command
                                   :cmd {:type :start-game :player 3}})
        (is (= [:malformed] (codes s)))))))

(deftest an-error-reply-never-echoes-the-payload
  (testing "a rejected :client/resume payload contains a token"
    (with-deps [deps]
      (let [s (sock)]
        (protocol/dispatch deps s {:type :client/resume :room-id "ABCD"
                                   :token "secret-value" :junk true})
        (is (= [:malformed] (codes s)))
        (is (not (clojure.string/includes? (pr-str (got s)) "secret-value")))))))

;; ---------------------------------------------------------------- commands ---

(deftest an-unseated-socket-cannot-command-or-chat
  (with-deps [deps]
    (let [s (sock)]
      (protocol/dispatch deps s {:type :client/command :cmd {:type :start-game}})
      (protocol/dispatch deps s {:type :client/chat :text "hallo"})
      (is (= [:not-in-room :not-in-room] (codes s))))))

(deftest a-rejected-command-is-told-only-to-the-offender
  (testing "an illegal action is often an accidental confession of a role"
    (with-deps [deps]
      (let [host (seat! deps {:type :client/create :name "Anna"})
            code (:room-id (last-of host :server/welcome))
            guest (seat! deps {:type :client/join :room-id code :name "Bob"})]
        (swap! host empty)
        (protocol/dispatch deps guest {:type :client/command :cmd {:type :start-game}})
        (is (seq (errors guest)) "the offender was told")
        (is (empty? (errors host)) "and nobody else was")))))

(deftest a-full-game-can-be-driven-entirely-over-the-protocol
  (with-deps [deps]
    (let [host (seat! deps {:type :client/create :name "P0"})
          code (:room-id (last-of host :server/welcome))
          guests (mapv #(seat! deps {:type :client/join :room-id code :name (str "P" %)})
                       (range 1 7))
          all (into [host] guests)]
      (protocol/dispatch deps host {:type :client/command :cmd {:type :start-game}})
      (let [state (room/state-of (:rooms deps) code)]
        (is (= :night (:phase state)))
        (testing "every socket got its own view, and only its own role"
          (doseq [[pid s] (map-indexed vector all)]
            (let [v (:view (last-of s :room/view))]
              (is (= pid (:id (:me v))))
              (is (some? (:role (:me v))) "you always know your own role")
              (is (every? #(not (contains? % :role)) (vals (:players v)))
                  "and never anybody else's from the public map"))))
        (testing "the deadline is in the view, absolute"
          (is (= (:deadline state) (:deadline (:view (last-of host :room/view))))))))))

;; ------------------------------------------------------------------ resume ---

(deftest a-dropped-socket-can-be-replaced-with-the-token
  (with-deps [deps]
    (let [host (seat! deps {:type :client/create :name "Anna"})
          {:keys [room-id token]} (last-of host :server/welcome)]
      (protocol/dispatch deps host {:type :client/join :room-id room-id :name "x"})
      (protocol/on-close deps host)
      (is (false? (get-in (room/state-of (:rooms deps) room-id)
                          [:players 0 :connected?])))
      (let [s2 (sock)]
        (protocol/dispatch deps s2 {:type :client/resume :room-id room-id :token token})
        (is (empty? (errors s2)))
        (is (= 0 (:player-id (last-of s2 :server/welcome))))
        (is (true? (get-in (room/state-of (:rooms deps) room-id)
                           [:players 0 :connected?])))
        (testing "and it is handed a fresh token, so a long game does not end
                  with everyone holding an expired one"
          (is (some? (:token (last-of s2 :server/welcome)))))
        (testing "plus the current state and its chat backlog"
          (is (some? (last-of s2 :room/view)))
          (is (some? (last-of s2 :room/chat-history))))))))

(deftest a-forged-or-foreign-token-is-refused
  (with-deps [deps]
    (let [host (seat! deps {:type :client/create :name "Anna"})
          {:keys [room-id token]} (last-of host :server/welcome)
          other (seat! deps {:type :client/create :name "Bea"})
          other-room (:room-id (last-of other :server/welcome))]
      (testing "a token for another room, even with a perfect signature"
        (let [s (sock)]
          (protocol/dispatch deps s {:type :client/resume
                                     :room-id other-room :token token})
          (is (= [:wrong-room] (codes s)))))
      (testing "a token signed with the wrong secret"
        (let [s (sock)
              forged (token/issue {:secret "not-the-real-secret-at-all" :ttl-mins 60}
                                  {:room-id room-id :player-id 0
                                   :now-ms (System/currentTimeMillis)})]
          (protocol/dispatch deps s {:type :client/resume :room-id room-id :token forged})
          (is (= [:bad-signature] (codes s)))))
      (testing "an expired token"
        (let [s (sock)
              stale (token/issue tokens-cfg {:room-id room-id :player-id 0
                                             :now-ms (- (System/currentTimeMillis)
                                                        (* 1000 60 60 24))})]
          (protocol/dispatch deps s {:type :client/resume :room-id room-id :token stale})
          (is (= [:expired] (codes s)))))
      (testing "and a genuine token for a seat that no longer exists"
        (let [s (sock)
              ghost (token/issue tokens-cfg {:room-id room-id :player-id 9
                                             :now-ms (System/currentTimeMillis)})]
          (protocol/dispatch deps s {:type :client/resume :room-id room-id :token ghost})
          (is (= [:seat-gone] (codes s))))))))

;; -------------------------------------------------------------------- chat ---

(deftest chat-is-relayed-to-its-audience-only
  (with-deps [deps]
    (let [host (seat! deps {:type :client/create :name "P0"})
          code (:room-id (last-of host :server/welcome))
          guest (seat! deps {:type :client/join :room-id code :name "P1"})]
      (protocol/dispatch deps guest {:type :client/chat :text "  hallo  "})
      (doseq [s [host guest]]
        (is (= "hallo" (:text (last-of s :room/chat))) "trimmed and delivered"))
      (testing "recipient sets are never sent to clients"
        (is (not-any? :to (of-type host :room/chat)))))))

;; ------------------------------------------------------------ mixed locale ---

(deftest two-players-can-read-the-same-room-in-different-languages
  (testing "the reason the wire carries structured events and never sentences.
            Each player's locale is their own game state, and the identical log
            renders differently for each of them."
    (with-deps [deps]
      (let [de (seat! deps {:type :client/create :name "Anna" :locale :de})
            code (:room-id (last-of de :server/welcome))
            en (seat! deps {:type :client/join :room-id code :name "Bob" :locale :en})]
        (testing "each view carries its OWN locale, not the room's"
          (is (= :de (:locale (:me (:view (last-of de :room/view))))))
          (is (= :en (:locale (:me (:view (last-of en :room/view)))))))

        (testing "and both received the very same public log"
          (is (= (:log (:view (last-of de :room/view)))
                 (:log (:view (last-of en :room/view))))))

        (let [log (:log (:view (last-of de :room/view)))
              players (:players (room/state-of (:rooms deps) code))]
          (testing "which renders as different sentences"
            (is (not= (i18n/chronicle :de players log {:show-all? true})
                      (i18n/chronicle :en players log {:show-all? true}))))
          (testing "while names are identical, because a name is data not language"
            (doseq [locale [:de :en]]
              (is (str/includes?
                   (str/join " " (map :text (i18n/chronicle locale players log
                                                            {:show-all? true})))
                   "Anna")))))

        (testing "and switching language mid-game is a command like any other"
          (is (:ok? (room/submit! (:rooms deps) code 1
                                  {:type :set-locale :locale :de})))
          (is (= :de (:locale (:me (:view (last-of en :room/view)))))
              "the change comes back in that player's own view"))))))

(deftest language-can-be-changed-in-any-phase
  (testing "it was lobby-only, which meant a player who joined in English was
            stuck in it for the whole game"
    (with-deps [deps]
      (let [host (seat! deps {:type :client/create :name "P0"})
            code (:room-id (last-of host :server/welcome))
            guests (mapv #(seat! deps {:type :client/join :room-id code
                                       :name (str "P" %)})
                         (range 1 7))]
        (protocol/dispatch deps host {:type :client/command :cmd {:type :start-game}})
        (is (= :night (:phase (room/state-of (:rooms deps) code))))
        (doseq [pid (range 7)]
          (is (:ok? (room/submit! (:rooms deps) code pid
                                  {:type :set-locale :locale :en}))
              (str "player " pid " must be able to switch at night")))
        (is (every? #(= :en (:locale %))
                    (vals (:players (room/state-of (:rooms deps) code)))))
        (testing "and switching language does not advance the game — it is a
                  preference, not a move. The night still waits for the Seherin,
                  which is correct; that :set-locale cannot make an exhausted
                  role look busy forever is pinned in the engine tests."
          (is (= :night (:phase (room/state-of (:rooms deps) code)))))
        (is (seq guests))))))

;; ------------------------------------------------------------- robustness ---

(deftest a-handler-that-throws-costs-one-message-not-the-connection
  (testing "dispatch runs on the socket's read loop; a throw there takes the
            connection with it"
    (with-deps [deps]
      (let [s (seat! deps {:type :client/create :name "Anna"})
            code (:room-id (last-of s :server/welcome))]
        ;; Corrupt the room so the engine cannot reason about it at all.
        (swap! (:rooms (:rooms deps)) assoc-in [code :state :phase] :nonsense-phase)
        (swap! s empty)
        (protocol/dispatch deps s {:type :client/command :cmd {:type :vote :target 1}})
        (is (seq (codes s)) "the client got an answer rather than a dead socket")
        (testing "and the socket still works afterwards"
          (swap! s empty)
          (protocol/dispatch deps s {:type :client/ping})
          (is (= [:server/pong] (mapv :type (got s)))))))))

(deftest ping-is-answered
  (with-deps [deps]
    (let [s (sock)]
      (protocol/dispatch deps s {:type :client/ping})
      (is (= [{:type :server/pong}] (got s))))))
