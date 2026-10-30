(ns werwolf.protocol-test
  "The wire protocol, driven with atoms for sockets.

   Sockets being opaque to the protocol layer is what makes this possible: every
   assertion below is 'what would this client actually have received', which is
   the only question a hidden-role game cares about."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [buddy.sign.jwt :as jwt]
            [werwolf.i18n.core :as i18n]
            [werwolf.livekit :as livekit]
            [werwolf.media :as media]
            [werwolf.proto :as proto]
            [werwolf.protocol :as protocol]
            [werwolf.room :as room]
            [werwolf.store :as store]
            [werwolf.timer :as timer]
            [werwolf.token :as token]))

(def game-cfg
  {:election-secs 60 :discussion-secs 300 :nomination-secs 90 :vote-secs 60
   :night-secs 45 :reaction-secs 30 :verdict-secs 10})

(def tokens-cfg {:secret "test-secret-that-is-long-enough" :ttl-mins 720})

(defn- tmp-path []
  (let [f (java.io.File/createTempFile "werwolf-proto" ".db")]
    (.delete f) (.getAbsolutePath f)))

(defn- cleanup! [path]
  (doseq [suffix ["" "-wal" "-shm"]] (io/delete-file (str path suffix) true)))

(defn- deps-on [path]
  (let [rooms (room/registry {:ds (store/open {:dbtype "sqlite" :dbname path})
                              :send! (fn [sock msg] (swap! sock conj msg))
                              ;; The creator PLAYS here: these tests deal a
                              ;; table and count its seats, and were written
                              ;; when the creator was always one of them.
                              :game {:narrated? false}
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
      (let [s (seat! deps {:type :client/create :name "Anna"})
            {:keys [room-id]} (last-of s :server/welcome)]
        (protocol/dispatch deps s {:type :client/create :name "Anna Again"})
        (is (= [room-id] (room/room-ids (:rooms deps))) "no second room")
        (is (= 1 (count (:players (room/state-of (:rooms deps) room-id)))))
        (testing "a socket seated in one room asking to join ANOTHER is a real
                  conflict, and is still refused"
          (let [other (:room-id (last-of (seat! deps {:type :client/create
                                                      :name "Bea"})
                                         :server/welcome))]
            (swap! s empty)
            (protocol/dispatch deps s {:type :client/join :room-id other
                                       :name "Anna"})
            (is (= [:already-in-room] (codes s)))
            (is (= 1 (count (:players (room/state-of (:rooms deps) other)))))))))))

(deftest a-repeated-create-or-join-is-answered-with-the-seat-you-have
  (testing "a double tap, or a join queued while the socket was reconnecting,
            reached the server twice. The first seated the player and the second
            was answered :already-in-room — so the client showed an error over
            the room it had in fact just entered"
    (with-deps [deps]
      (let [host (seat! deps {:type :client/create :name "Anna"})
            code (:room-id (last-of host :server/welcome))
            guest (seat! deps {:type :client/join :room-id code :name "Bob"})]
        (protocol/dispatch deps guest {:type :client/join :room-id code :name "Bob"})
        (is (empty? (errors guest)))
        (is (= [1 1] (mapv :player-id (of-type guest :server/welcome)))
            "the same seat, confirmed again")
        (is (= 2 (count (:players (room/state-of (:rooms deps) code))))
            "and no ghost seat beside it")
        (testing "each welcome is followed by a view, since a welcome makes the
                  client drop the one it had"
          (is (= :room/view (:type (last (got guest))))))
        (testing "a double-tapped create likewise"
          (protocol/dispatch deps host {:type :client/create :name "Anna"})
          (is (empty? (errors host)))
          (is (= [code code] (mapv :room-id (of-type host :server/welcome))))
          (is (= [code] (room/room-ids (:rooms deps)))))))))

;; ------------------------------------------------------------------- leave ---

(def ^:private leave {:type :client/command :cmd {:type :leave}})

(deftest after-leaving-the-same-page-can-create-or-join-again
  (testing "the reported 'you are already in a round': leave is a game command,
            and nothing on that path unbound the socket, so the page that had
            just left was refused every create and join until it reloaded"
    (with-deps [deps]
      (let [s (seat! deps {:type :client/create :name "Anna"})
            first-room (:room-id (last-of s :server/welcome))]
        (protocol/dispatch deps s leave)
        (swap! s empty)
        (protocol/dispatch deps s {:type :client/create :name "Anna"})
        (is (empty? (errors s)))
        (let [second-room (:room-id (last-of s :server/welcome))]
          (is (some? second-room))
          (is (not= first-room second-room))
          (testing "and joining someone else's room works the same way"
            (let [bea (seat! deps {:type :client/create :name "Bea"})
                  code (:room-id (last-of bea :server/welcome))]
              (protocol/dispatch deps s leave)
              (swap! s empty)
              (protocol/dispatch deps s {:type :client/join :room-id code :name "Anna"})
              (is (empty? (errors s)))
              (is (= code (:room-id (last-of s :server/welcome))))
              (is (= 2 (count (:players (room/state-of (:rooms deps) code))))))))))))

(deftest leaving-in-one-tab-releases-the-other
  (with-deps [deps]
    (let [a (seat! deps {:type :client/create :name "Anna"})
          {:keys [room-id token]} (last-of a :server/welcome)
          b (sock)]
      (protocol/dispatch deps b {:type :client/resume :room-id room-id :token token})
      (is (empty? (errors b)))
      (protocol/dispatch deps a leave)
      (is (= {:type :server/left :room-id room-id} (last (got b)))
          "the other tab is told its seat is gone rather than left on a frozen lobby")
      (swap! b empty)
      (protocol/dispatch deps b {:type :client/create :name "Anna"})
      (is (empty? (errors b)) "and is free to start somewhere else"))))

(deftest leaving-mid-game-frees-the-page-and-keeps-the-seat
  (with-deps [deps]
    (let [host (seat! deps {:type :client/create :name "P0"})
          code (:room-id (last-of host :server/welcome))
          guests (mapv #(seat! deps {:type :client/join :room-id code :name (str "P" %)})
                       (range 1 5))
          leaver (first guests)
          {:keys [token]} (last-of leaver :server/welcome)]
      (protocol/dispatch deps host {:type :client/command :cmd {:type :start-game}})
      (protocol/dispatch deps leaver leave)
      (is (room/seated? (:rooms deps) code 1) "the role still matters to the game")
      (swap! leaver empty)
      (protocol/dispatch deps leaver {:type :client/create :name "P1"})
      (is (empty? (errors leaver)))
      (testing "and the token still names the seat, so walking back in works"
        (let [back (sock)]
          (protocol/dispatch deps back {:type :client/resume :room-id code :token token})
          (is (empty? (errors back)))
          (is (true? (get-in (room/state-of (:rooms deps) code)
                             [:players 1 :connected?]))))))))

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
        (is (nil? (protocol/dispatch deps s bad))
            "nil, so the metrics count it as invalid and not by its :type")
        (is (= [:malformed] (codes s)) (str "accepted: " (pr-str bad))))
      (testing "and nothing was created along the way"
        (is (empty? (room/room-ids (:rooms deps)))))
      (testing "what it accepts it returns, as it read it"
        (is (= {:type :client/ping} (protocol/dispatch deps s {:type :client/ping})))
        (is (= "ABCD" (:room-id (protocol/dispatch deps s {:type :client/join :room-id "abcd"
                                                            :name "x"}))))))))

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

(deftest an-unseated-socket-may-ask-what-there-is-to-join
  (testing "the one question that needs no seat — it is how you get one"
    (with-deps [deps]
      (let [asker (sock)]
        (protocol/dispatch deps asker {:type :client/rooms})
        (is (= [:server/rooms] (mapv :type (got asker))))
        (is (= [] (:rooms (last (got asker)))) "nothing open yet")

        (let [host (seat! deps {:type :client/create :name "Anna"})]
          (swap! asker empty)
          (protocol/dispatch deps asker {:type :client/rooms})
          (let [listed (:rooms (last (got asker)))]
            (is (= 1 (count listed)))
            (is (= "Anna" (:host (first listed))))
            (is (= 1 (:players (first listed))))
            (testing "and the answer goes to the asker alone — a room list
                      pushed at everyone would be a side channel into a game
                      you are not in"
              (is (not-any? #(= :server/rooms (:type %)) (got host)))))

          (testing "the code it hands out really does admit you"
            (let [code (:room-id (first (:rooms (last (got asker)))))
                  guest (sock)]
              (protocol/dispatch deps guest {:type :client/join
                                             :room-id code :name "Bob"})
              (is (some #(= :server/welcome (:type %)) (got guest))))))))))

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

(deftest the-spielleiter-chooses-the-deck-over-the-wire
  (with-deps [deps]
    (let [host (seat! deps {:type :client/create :name "P0"})
          code (:room-id (last-of host :server/welcome))
          guests (mapv #(seat! deps {:type :client/join :room-id code :name (str "P" %)})
                       (range 1 5))
          deck {:werwolf 2 :seherin 1}]
      (protocol/dispatch deps host {:type :client/command
                                    :cmd {:type :gm/set-deck :deck deck}})
      (is (empty? (errors host)))
      (testing "the whole table sees the deck it is going to be dealt"
        (doseq [s (into [host] guests)]
          (let [v (:view (last-of s :room/view))]
            (is (= deck (:deck v)))
            (is (= {:werwolf 2 :seherin 1 :dorfbewohner 2}
                   (get-in v [:deck-preview :dealt]))))))
      (testing "a malformed deck is refused at the door, before the engine"
        (doseq [bad [{:werwolf -1} {:werwolf 19} {"werwolf" 1} {:werwolf "2"}]]
          (swap! host empty)
          (protocol/dispatch deps host {:type :client/command
                                        :cmd {:type :gm/set-deck :deck bad}})
          (is (= [:malformed] (codes host)) (pr-str bad))))
      (testing "a well-formed deck the registry forbids is the engine's refusal"
        (swap! host empty)
        (protocol/dispatch deps host {:type :client/command
                                      :cmd {:type :gm/set-deck :deck {:seherin 2}}})
        (is (= [:too-many-of-role] (codes host))))
      (testing "and only the host may choose"
        (let [g (first guests)]
          (swap! g empty)
          (protocol/dispatch deps g {:type :client/command
                                     :cmd {:type :gm/set-deck :deck {:werwolf 1}}})
          (is (= [:not-allowed] (codes g)))))
      (protocol/dispatch deps host {:type :client/command :cmd {:type :start-game}})
      (is (= {:werwolf 2 :seherin 1 :dorfbewohner 2}
             (frequencies (keep :role (vals (:players (room/state-of (:rooms deps) code))))))
          "the deal is the deck"))))

(deftest a-socket-that-closed-before-its-create-leaves-no-room-behind
  (testing "http-kit runs the close handler as a task of its own, so it can
            overtake the create still queued ahead of it. The room that create
            made was bound to a dead socket, never swept and always listed,
            and a hundred of them filled every room the server allows"
    (with-deps [deps]
      (let [closed (atom #{})
            deps (assoc deps :open? #(not (contains? @closed %)))
            rooms (:rooms deps)
            ;; A socket whose close handler has already run.
            dead! #(let [s (sock)] (swap! closed conj s) (protocol/on-close deps s) s)
            swept? #(do (room/sweep-idle! rooms (+ (System/currentTimeMillis) 60000)
                                          {:room-idle-ms 1000})
                        (empty? (room/room-ids rooms)))]
        (testing "a create"
          (let [s (dead!)]
            (protocol/dispatch deps s {:type :client/create :name "Anna"})
            (let [[code] (room/room-ids rooms)]
              (is (nil? (room/whereis rooms s)) "the dead socket is bound to nothing")
              (is (false? (get-in (room/state-of rooms code) [:players 0 :connected?]))
                  "its seat is absent, not held")
              (is (empty? (room/open-rooms rooms)) "so nobody is offered the room")
              (is (swept?) "and the sweep retires it"))))
        (testing "a join and a resume"
          (let [host (seat! deps {:type :client/create :name "Anna"})
                {:keys [room-id token]} (last-of host :server/welcome)
                bob (dead!)]
            (protocol/dispatch deps bob {:type :client/join :room-id room-id :name "Bob"})
            (is (nil? (room/whereis rooms bob)))
            (is (false? (get-in (room/state-of rooms room-id) [:players 1 :connected?])))
            (protocol/on-close deps host)
            (let [anna (dead!)]
              (protocol/dispatch deps anna {:type :client/resume :room-id room-id :token token})
              (is (nil? (room/whereis rooms anna)))
              (is (false? (get-in (room/state-of rooms room-id) [:players 0 :connected?]))))
            (is (swept?))))))))

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

;; ------------------------------------------------------------------- media ---
;;
;; Audio is a plain group call — every player may talk, always — so what matters
;; here is not a permission table but who can obtain a credential at all.

(def livekit-cfg
  {:provider :livekit :url "ws://localhost:7880"
   :api-key "devkey" :api-secret "secret"})

(defn- with-media [deps cfg]
  (assoc deps :media (:provider (livekit/provider cfg))))

(deftest a-seated-player-gets-credentials-for-their-own-room
  (with-deps [deps]
    (let [deps (with-media deps livekit-cfg)
          s    (seat! deps {:type :client/create :name "Anna" :locale :de})
          room (:room-id (last-of s :server/welcome))]
      (protocol/dispatch deps s {:type :client/media})
      (let [m (last-of s :server/media)]
        (is (true? (:enabled? m)))
        (is (= (media/room-name room) (:room m)))
        (is (= "ws://localhost:7880" (:url m)))
        (testing "and the token grants exactly that room"
          (is (= (media/room-name room)
                 (get-in (jwt/unsign (:token m) "secret" {:alg :hs256})
                         [:video :room]))))))))

(deftest a-media-token-is-never-broadcast
  (testing "it is a bearer credential for a microphone, so it belongs to one
            socket — a token in a broadcast would hand every player at the table
            the ability to speak as somebody else"
    (with-deps [deps]
      (let [deps (with-media deps livekit-cfg)
            host (seat! deps {:type :client/create :name "Anna" :locale :de})
            room (:room-id (last-of host :server/welcome))
            guest (seat! deps {:type :client/join :room-id room :name "Bob"})]
        (protocol/dispatch deps guest {:type :client/media})
        (is (some? (last-of guest :server/media)))
        (is (empty? (of-type host :server/media))
            "the host must not receive the guest's credential")
        (testing "and no other frame anywhere carries a token-looking string"
          (is (empty? (filterv #(and (not= :server/media (:type %))
                                     (not= :server/welcome (:type %))
                                     (str/includes? (pr-str %) "eyJ"))
                               (got guest)))))))))

(deftest an-unseated-socket-cannot-obtain-a-microphone
  (testing "the message carries no room and no identity precisely so that this is
            the only possible answer: a client cannot ask for a token naming a
            game it is not in"
    (with-deps [deps]
      (let [deps (with-media deps livekit-cfg)
            s (sock)]
        (protocol/on-open deps s)
        (protocol/dispatch deps s {:type :client/media})
        (is (= [:not-in-room] (codes s)))
        (is (empty? (of-type s :server/media)))))))

(deftest with-no-provider-audio-is-off-rather-than-broken
  (testing "a text-only server is a supported configuration, not a failure, so
            the client is told audio is off instead of shown an error"
    (with-deps [deps]
      (let [s (seat! deps {:type :client/create :name "Anna" :locale :de})]
        (protocol/dispatch deps s {:type :client/media})
        (let [m (last-of s :server/media)]
          (is (= {:type :server/media :enabled? false} m))
          (is (empty? (errors s))))))))

(deftest client-media-typechecks-and-takes-no-arguments
  (is (proto/valid-client-message? {:type :client/media}))
  (testing "a room or identity smuggled in must be rejected by the schema, not
            merely ignored by the handler"
    (is (not (proto/valid-client-message?
              {:type :client/media :room "werwolf-zzzz"})))
    (is (not (proto/valid-client-message?
              {:type :client/media :identity "werwolf-abcd-p0"})))))

(deftest who-is-in-the-call-is-shown-to-the-whole-table
  (with-deps [deps]
    (let [host  (seat! deps {:type :client/create :name "Anna" :locale :de})
          room  (:room-id (last-of host :server/welcome))
          guest (seat! deps {:type :client/join :room-id room :name "Bob"})
          voice #(:voice (:view (last-of % :room/view)))]
      (is (= #{} (voice guest)) "nobody yet")
      (protocol/dispatch deps host {:type :client/voice :joined? true})
      (testing "the guest, who is not in the call, sees that the host is"
        (is (= #{0} (voice guest))))
      (testing "a second tab hanging up does not take the player out of the call"
        (let [tab2 (sock)]
          (protocol/dispatch deps tab2 {:type :client/resume :room-id room
                                        :token (:token (last-of host :server/welcome))})
          (protocol/dispatch deps tab2 {:type :client/voice :joined? false})
          (is (= #{0} (voice guest)))
          (protocol/on-close deps tab2)))
      (testing "the socket in the call closing ends it"
        (protocol/on-close deps host)
        (is (= #{} (voice guest))))))
  (testing "an unseated socket has no seat to report for"
    (with-deps [deps]
      (let [s (sock)]
        (protocol/dispatch deps s {:type :client/voice :joined? true})
        (is (= [:not-in-room] (codes s))))))
  (testing "the message names nobody — only its sender's own seat"
    (is (not (proto/valid-client-message? {:type :client/voice :joined? true :player 3})))))

;; ------------------------------------------------------------ an absent host ---

(deftest a-lobby-whose-host-vanished-can-be-claimed
  (testing "closing the tab used to leave the lobby unstartable until he came
            back; succession only ever ran on an explicit leave"
    (with-deps [deps]
      (let [host (seat! deps {:type :client/create :name "P0"})
            code (:room-id (last-of host :server/welcome))
            guests (mapv #(seat! deps {:type :client/join :room-id code :name (str "P" %)})
                         (range 1 5))
            heir (second guests)
            gone (System/currentTimeMillis)]
        (protocol/on-close deps host)
        (timer/tick! (:rooms deps) (+ gone 60000))
        (let [v (:view (last-of heir :room/view))]
          (is (true? (:host-absent? v)))
          (is (contains? (:actions v) :claim-host) "offered, so the client can draw it"))
        (protocol/dispatch deps heir {:type :client/command :cmd {:type :claim-host}})
        (is (empty? (errors heir)))
        (let [v (:view (last-of heir :room/view))]
          (is (= 2 (:host v)))
          (is (false? (:host-absent? v)))
          (is (contains? (:actions v) :start-game) "and the room can start again"))
        (testing "a second claim is refused: nobody is missing now"
          (let [other (first guests)]
            (protocol/dispatch deps other {:type :client/command :cmd {:type :claim-host}})
            (is (= [:not-allowed] (codes other)))))))))

(deftest anybody-can-walk-out-of-a-game-under-way
  (testing "leaving was lobby and game-over only; mid-game there was no way out
            at all, and a reload only resumed the seat"
    (with-deps [deps]
      (let [host (seat! deps {:type :client/create :name "P0"})
            code (:room-id (last-of host :server/welcome))
            _ (mapv #(seat! deps {:type :client/join :room-id code :name (str "P" %)})
                    (range 1 5))]
        (protocol/dispatch deps host {:type :client/command :cmd {:type :start-game}})
        (is (contains? (:actions (:view (last-of host :room/view))) :leave))
        (protocol/dispatch deps host {:type :client/command :cmd {:type :leave}})
        (is (empty? (errors host)))
        (is (= {:type :server/left :room-id code} (last (got host))))
        (testing "and a host who walks out is absent at once — his seat is offered"
          (is (true? (:host-absent? (room/state-of (:rooms deps) code)))))))))

;; ------------------------------------------------------------ the password ---

(defn- said-anywhere?
  "Did any frame this socket received contain `s`, in any case?"
  [sk s]
  (str/includes? (str/lower-case (pr-str (got sk))) (str/lower-case s)))

(deftest a-locked-room-over-the-wire
  (with-deps [deps]
    (let [host (seat! deps {:type :client/create :name "Anna" :password "Vollmond"})
          {:keys [room-id]} (last-of host :server/welcome)]
      (is (true? (:locked? (:view (last-of host :room/view)))))
      (testing "the list says it is locked, so the client knows to ask first"
        (let [asker (sock)]
          (protocol/dispatch deps asker {:type :client/rooms})
          (is (true? (:locked? (first (:rooms (last-of asker :server/rooms))))))))
      (testing "no password, wrong password: refused, and told which"
        (let [bob (seat! deps {:type :client/join :room-id room-id :name "Bob"})]
          (is (= [:password-required] (codes bob)))
          (protocol/dispatch deps bob {:type :client/join :room-id room-id :name "Bob"
                                       :password "Neumond"})
          (is (= [:password-required :wrong-password] (codes bob)))
          (is (nil? (room/whereis (:rooms deps) bob)) "and not seated")
          (testing "with no detail at all — certainly not the guess"
            (is (every? #(not (contains? % :detail)) (errors bob)))
            (is (not (said-anywhere? bob "Neumond"))))
          (testing "then the right one seats them on the same socket"
            (protocol/dispatch deps bob {:type :client/join :room-id room-id :name "Bob"
                                         :password "vollmond"})
            (is (= 1 (:player-id (last-of bob :server/welcome)))))))
      (testing "nothing that went to anybody carries the password or its hash"
        (is (not (said-anywhere? host "Vollmond")))
        (is (not (said-anywhere? host "pbkdf2")))))))

(deftest a-reconnect-token-needs-no-password
  (testing "the token already proves the seat — asking again would lock a
            player out of their own game every time their wifi dropped"
    (with-deps [deps]
      (let [host (seat! deps {:type :client/create :name "Anna" :password "Vollmond"})
            {:keys [room-id]} (last-of host :server/welcome)
            bob  (seat! deps {:type :client/join :room-id room-id :name "Bob"
                              :password "Vollmond"})
            {:keys [token]} (last-of bob :server/welcome)]
        (protocol/on-close deps bob)
        (let [again (seat! deps {:type :client/resume :room-id room-id :token token})]
          (is (empty? (errors again)))
          (is (= 1 (:player-id (last-of again :server/welcome)))))))))

(deftest only-the-host-can-lock-the-door
  (with-deps [deps]
    (let [host (seat! deps {:type :client/create :name "Anna"})
          {:keys [room-id]} (last-of host :server/welcome)
          bob  (seat! deps {:type :client/join :room-id room-id :name "Bob"})
          stranger (sock)]
      (testing "a guest is refused, and the room stays open"
        (protocol/dispatch deps bob {:type :client/set-password :password "meins"})
        (is (= [:not-host] (codes bob)))
        (is (false? (:locked? (:view (last-of bob :room/view))))))
      (testing "an unseated socket has no room to lock"
        (protocol/dispatch deps stranger {:type :client/set-password :password "x"})
        (is (= [:not-in-room] (codes stranger))))
      (testing "the host locks it; everybody's next view says so"
        (protocol/dispatch deps host {:type :client/set-password :password "Vollmond"})
        (is (empty? (errors host)))
        (is (true? (:locked? (:view (last-of bob :room/view)))))
        (is (= [:password-required]
               (codes (seat! deps {:type :client/join :room-id room-id :name "Cem"})))))
      (testing "and opens it again with nil"
        (protocol/dispatch deps host {:type :client/set-password :password nil})
        (is (false? (:locked? (:view (last-of bob :room/view)))))
        (is (empty? (errors (seat! deps {:type :client/join :room-id room-id :name "Cem"})))))
      (testing "the password went to no socket, the hash neither"
        (doseq [s [host bob]]
          (is (not (said-anywhere? s "Vollmond")))
          (is (not (said-anywhere? s "pbkdf2"))))))))

(deftest a-guesser-is-slowed-down-and-a-new-socket-starts-afresh
  (with-deps [deps]
    (let [host (seat! deps {:type :client/create :name "Anna" :password "Vollmond"})
          {:keys [room-id]} (last-of host :server/welcome)
          guess {:type :client/join :room-id room-id :name "M" :password "falsch"}
          m (sock)]
      (dotimes [_ 6] (protocol/dispatch deps m guess))
      (is (= (conj (vec (repeat 5 :wrong-password)) :too-many-attempts) (codes m)))
      (protocol/dispatch deps m (assoc guess :password "Vollmond"))
      (is (= :too-many-attempts (last (codes m))) "even the right one, for now")
      (testing "the count lives and dies with the socket"
        (protocol/on-close deps m)
        (is (empty? @(:attempts (:rooms deps))))))))

(deftest a-malformed-password-is-refused-without-being-repeated
  (with-deps [deps]
    (let [secret (apply str "Geheim" (repeat 80 "x"))
          s (seat! deps {:type :client/create :name "Anna" :password secret})]
      (is (= [:malformed] (codes s)))
      (is (not (said-anywhere? s "Geheim"))))))
