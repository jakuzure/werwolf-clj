(ns werwolf.client-test
  "ClojureScript-only: the client's state reducer.

   Light on purpose — the rules live in the shared engine and are covered there.
   What is worth testing here is exactly what the client decides for itself, and
   that is a short list: which message replaces which state, and when a session
   is worth throwing away.

   `handle-server` is pure, so none of this needs a browser, a socket or
   localStorage."
  (:require [cljs.test :refer [deftest is testing]]
            [werwolf.events :as ev]
            [werwolf.i18n.core :as i18n]
            [werwolf.subs :as sub]))

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
