(ns werwolf.client-test
  "ClojureScript-only: the client's state reducer.

   Light on purpose — the rules live in the shared engine and are covered there.
   What is worth testing here is exactly what the client decides for itself, and
   that is a short list: which message replaces which state, and when a session
   is worth throwing away.

   `handle-server` is pure, so none of this needs a browser, a socket or
   localStorage."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [werwolf.config :as cfg]
            [werwolf.events :as ev]
            [werwolf.i18n.core :as i18n]
            [werwolf.proto :as proto]
            [werwolf.subs :as sub]
            ;; Required for its side effect of being COMPILED. Nothing here
            ;; calls into it — rendering needs a DOM — but the test build
            ;; otherwise never sees the view layer at all, and a client that
            ;; does not compile passed `bb test:all` green. It was a local
            ;; binding named `set!`, which is a special form.
            [werwolf.views]))

(def db ev/initial-db)

(defn- apply* [db & msgs]
  (reduce ev/handle-server db msgs))

(deftest a-welcome-seats-the-player
  (let [db' (apply* db {:type :server/welcome :room-id "ABCD" :player-id 2
                        :host? false :token "t"})]
    (is (= :room (:screen db')))
    (is (= {:room-id "ABCD" :player-id 2 :host? false} (:me db')))
    (testing "the token is NOT kept in app-db — it lives in localStorage,
              because app-db is what re-frame-10x happily prints to the console"
      (is (not (contains? (:me db') :token))))))

(deftest views-replace-state-but-never-go-backwards
  (let [v1 {:type :room/view :seq 5 :view {:phase :night}}
        v2 {:type :room/view :seq 9 :view {:phase :discussion}}]
    (is (= :discussion (get-in (apply* db v1 v2) [:view :phase])))
    (testing "an older view arriving late must not overwrite a newer one —
              the room broadcasts outside a single atomic step, so this happens"
      (is (= :discussion (get-in (apply* db v2 v1) [:view :phase]))))))

(deftest chat-history-replaces-and-live-chat-appends
  (let [db' (apply* db {:type :room/chat-history
                        :messages [{:from 0 :text "alt"}]})]
    (is (= 1 (count (:chat db'))))
    (let [db'' (apply* db' {:type :room/chat :from 1 :text "neu"})]
      (is (= ["alt" "neu"] (mapv :text (:chat db'')))))))

(deftest the-chat-buffer-is-bounded
  (testing "a long game must not turn into an unbounded array"
    (let [db' (apply* db (assoc {:type :room/chat-history} :messages []))
          filled (reduce (fn [d i] (ev/handle-server d {:type :room/chat :text (str i)}))
                         db' (range 400))]
      (is (= 300 (count (:chat filled))))
      (is (= "399" (:text (last (:chat filled)))) "the newest survives"))))

(deftest a-fatal-error-throws-the-session-away
  (doseq [code ev/fatal-errors]
    (let [db' (apply* db
                      {:type :server/welcome :room-id "ABCD" :player-id 0 :host? true}
                      {:type :room/view :seq 1 :view {:phase :night}}
                      {:type :server/error :code code})]
      (is (= :landing (:screen db')) (str code " should return to the landing screen"))
      (is (nil? (:me db')))
      (is (nil? (:view db')) "and the stale game must not stay on screen"))))

(deftest an-ordinary-rejection-keeps-you-in-the-game
  (testing "an illegal move is a message, not a logout"
    (let [db' (apply* db
                      {:type :server/welcome :room-id "ABCD" :player-id 0 :host? true}
                      {:type :room/view :seq 1 :view {:phase :vote}}
                      {:type :server/error :code :invalid-target})]
      (is (= :room (:screen db')))
      (is (= :invalid-target (:error db')))
      (is (some? (:view db'))))))

(deftest an-unknown-message-is-ignored-not-fatal
  (testing "a newer server may say things this client has never heard of"
    (is (= db (apply* db {:type :server/something-from-the-future :payload 42})))))

;; ------------------------------------------------------- the armed action ---

(deftest an-empty-form-field-does-not-look-like-an-armed-action
  (testing "the phantom target dialog: ::form-field defaults to \"\" so it can
            back controlled inputs, and \"\" is TRUTHY in ClojureScript. Reading
            the armed action through it made `when-let` fire on every render, so
            the amber 'choose a target' bar appeared before anyone had clicked."
    (is (nil? (sub/armed-action (:form ev/initial-db)))
        "a fresh form arms nothing")
    (is (nil? (sub/armed-action {}))
        "and neither does a missing key")
    (testing "the exact value that broke it"
      (is (nil? (sub/armed-action {:pending ""})))
      (is (some? "") "\"\" is truthy in ClojureScript — that WAS the bug"))
    (testing "and any other wrong shape"
      (is (nil? (sub/armed-action {:pending :vote})))
      (is (nil? (sub/armed-action {:pending [:vote]}))))
    (testing "while a real armed action comes through untouched"
      (is (= {:action :vote :picks []}
             (sub/armed-action {:pending {:action :vote :picks []}}))))))

;; -------------------------------------------------------------- room list ---

(deftest the-room-list-replaces-wholesale
  (testing "a list that merged would keep showing a game that has started"
    (let [db' (apply* db {:type :server/rooms
                          :rooms [{:room-id "ABCD" :players 2 :max-players 18
                                   :host "Anna"}]})]
      (is (= 1 (count (:rooms db'))))
      (is (= [] (:rooms (apply* db' {:type :server/rooms :rooms []})))))))

(deftest joining-from-the-list-still-needs-a-name
  (testing "the same guard as typing the code, or the tap silently does nothing"
    (is (= :name-required (:error (:db (ev/join-listed-fx {:db db} [nil "ABCD"])))))
    (let [named (assoc-in db [:form :name] "Anna")
          out (ev/join-listed-fx {:db named} [nil "ABCD"])]
      (is (= "ABCD" (:room-id (:werwolf.ws/send out))))
      (is (= "Anna" (:name (:werwolf.ws/send out))))
      (is (= "ABCD" (get-in out [:db :form :code]))
          "the code field follows the tap, so the screen agrees with itself"))))

;; ------------------------------------------------------------ where we dial ---

(deftest the-socket-follows-the-page-never-localhost
  (testing "the dev build pinned ws://localhost:3000, which works on exactly the
            machine running the server. On a phone the client dialled the phone,
            the socket never opened, and every tap went into the outbox instead
            of onto the wire — the buttons looked broken"
    (is (= "ws://192.168.1.40:3000/api/ws"
           (cfg/ws-url-for {:protocol "http:" :hostname "192.168.1.40" :port "3000"})))
    (testing "https means wss, or a browser refuses the upgrade outright"
      (is (= "wss://werwolf.example.com/api/ws"
             (cfg/ws-url-for {:protocol "https:" :hostname "werwolf.example.com"
                              :port ""}))))
    (testing "shadow's dev-http serves on 8080 and proxies /api, so the socket
              hops to the game server — by hostname, still not by localhost"
      (is (= "ws://192.168.1.40:3000/api/ws"
             (cfg/ws-url-for {:protocol "http:" :hostname "192.168.1.40" :port "8080"}))))
    (testing "and no host reachable from another device ever resolves to this one"
      (doseq [host ["192.168.1.40" "werwolf.example.com" "mac-mini.local"]]
        (is (not (str/includes? (cfg/ws-url-for {:protocol "http:" :hostname host
                                                 :port "8080"})
                                "localhost")))))))

(deftest an-empty-field-says-so-instead-of-doing-nothing
  (testing "tapping a button that silently drops the click is indistinguishable
            from a broken button, which is how it was reported"
    (is (= :name-required (:error (:db (ev/create-room-fx {:db db} nil)))))
    (is (= :name-required (:error (:db (ev/join-room-fx {:db db} nil)))))
    (testing "a name but no code is the other half of the same trap"
      (let [named (assoc-in db [:form :name] "Anna")]
        (is (= :code-required (:error (:db (ev/join-room-fx {:db named} nil)))))
        (testing "and with both, it actually sends"
          (let [ready (assoc-in named [:form :code] "abcd")
                out (ev/join-room-fx {:db ready} nil)]
            (is (nil? (:error (:db out))))
            (is (= "ABCD" (:room-id (:werwolf.ws/send out)))
                "upper-cased, because nobody types a code the way it was printed")))))
    (testing "and a real name creates a room rather than complaining"
      (let [out (ev/create-room-fx {:db (assoc-in db [:form :name] "Anna")} nil)]
        (is (nil? (:error (:db out))))
        (is (= "Anna" (:name (:werwolf.ws/send out))))
        (is (= :de (:locale (:werwolf.ws/send out))) "German by default")))))

;; ---------------------------------------------------------------- GM console ---

(deftest the-gm-console-guards-its-scratch-state-too
  (testing "same trap as the armed action, one panel over: \"\" is truthy, and
            the GM console reads maps out of the same form map"
    (is (= {} (sub/gm-console (:form ev/initial-db))))
    (is (= {} (sub/gm-console {:gm ""})))
    (is (= {} (sub/gm-console {:gm :open})))
    (is (= {:open? true} (sub/gm-console {:gm {:open? true}})))))

(deftest an-overrides-choice-travels-under-the-key-the-protocol-names
  (testing ":target means a player id everywhere. The overrides that name a
            phase or a role need a different key, and the client must not be
            the place that decides which — it reads proto/choice-key"
    (is (= :phase (proto/choice-key :gm/set-phase)))
    (is (= :role (proto/choice-key :gm/skip-role)))
    (is (= :role (proto/choice-key :gm/reassign-role)))
    (testing "and an override that names only a seat has no choice key at all"
      (is (nil? (proto/choice-key :gm/force-kill)))
      (is (nil? (proto/choice-key :gm/revive))))
    (testing "every key it names is one the wire schema actually carries"
      (doseq [[action k] proto/choice-key]
        (is (contains? proto/command-types action) (str action " is not a command"))
        (is (proto/valid-client-message?
             {:type :client/command :cmd {:type action k (if (= :phase k) :vote :hexe)}})
            (str action " cannot be expressed with " k))))))

;; ------------------------------------------------------------- the Chronik ---

(def ^:private quiet-day
  "What a real six-player day with no accusation actually emits. Taken from a
   dump of the engine's log, which is where the 'duplicated history' complaint
   came from: ten near-identical lines around one that matters."
  [{:type :night/ended :night 1}
   {:type :player/died :player 3 :cause :werwolf :role :dorfbewohner}
   {:type :day/began :day 1}
   {:type :player/ready :player 0} {:type :player/ready :player 1}
   {:type :player/ready :player 2} {:type :player/ready :player 4}
   {:type :player/ready :player 5}
   {:type :nominate/passed :player 0} {:type :nominate/passed :player 1}
   {:type :nominate/passed :player 2} {:type :nominate/passed :player 4}
   {:type :nominate/passed :player 5}
   {:type :day/no-accusation :day 1}
   {:type :vote/resolved :target nil :tie? false}
   {:type :night/began :night 2}])

(def ^:private names
  (into {} (map (fn [i] [i {:name (str "P" i)}])) (range 6)))

(deftest the-chronicle-hides-bookkeeping-by-default
  (let [rows (i18n/chronicle :de names quiet-day {})
        texts (mapv :text rows)]
    (is (not-any? #(re-find #"ist fertig|sind fertig" %) texts)
        "ten ready/pass lines must not bury the one death")
    ;; Asserted by tone and name rather than by a German word: the death lines
    ;; are whole sentences per cause, so the wording is the dictionary's business
    ;; and this test's business is that the death survived the filter.
    (is (some #(and (= :death (:tone %)) (re-find #"P3" (:text %))) rows)
        "the one death is still there")
    (testing "and the redundant verdict is demoted — :day/no-accusation already
              said nobody died, so :vote/resolved with no target repeats it"
      (is (= 1 (count (filter #(re-find #"niemanden an|keiner" %) texts)))))))

(deftest the-chronicle-collapses-runs-and-names-them
  (let [rows (i18n/chronicle :de names quiet-day {:show-all? true})
        ready (first (filter #(re-find #"sind fertig" (:text %)) rows))]
    (is (some? ready) "the run became one row")
    (is (= 5 (:count ready)))
    (is (= "P0, P1, P2, P4, P5 sind fertig" (:text ready))
        "named rather than counted: '5 × P5 ist fertig' reads as a stutter")
    (testing "and nothing is lost — every entry is still reachable"
      (is (some #(re-find #"klagen niemanden an" (:text %)) rows)))))

(deftest the-chronicle-is-newest-first
  (let [rows (i18n/chronicle :de names quiet-day {})]
    (is (re-find #"Nacht" (:text (first rows))))))

(deftest a-tie-is-loud-but-an-empty-vote-is-not
  (testing "a tie is a real outcome the table needs to see; 'nobody voted' is not"
    (is (= :verdict (i18n/log-tone {:type :vote/resolved :target nil :tie? true})))
    (is (= :verdict (i18n/log-tone {:type :vote/resolved :target 2 :tie? false})))
    (is (= :muted (i18n/log-tone {:type :vote/resolved :target nil :tie? false})))))

;; The dictionaries themselves — completeness, both languages, and coverage of
;; every event a real game logs — are tested in werwolf.i18n-test, which is .cljc
;; and therefore runs here too. Nothing about them is client-specific, so
;; restating a weaker version of those checks in this file would only give two
;; places to update.

(deftest names-are-data-not-language
  (testing "the log holds ids, so renaming yourself must not rewrite history —
            and an unnamed id still has to be identifiable"
    (is (= "#7 ist beigetreten"
           (i18n/log-line :de {} {:type :player/joined :player 7})))
    (is (= "#7 joined"
           (i18n/log-line :en {} {:type :player/joined :player 7})))))
