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
            [werwolf.text :as text]))

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

;; ------------------------------------------------------------------ strings ---

(deftest every-phase-and-role-has-a-name
  (doseq [p [:lobby :night :day-reveal :discussion :nomination :vote
             :day-resolve :reaction :game-over]]
    (is (not (re-find #"^:" (text/phase-name p))) (str p " has no translation")))
  (doseq [r [:werwolf :dorfbewohner :seherin :hexe :jaeger :amor]]
    (is (not (re-find #"^:" (text/role-name r))) (str r " has no translation"))))

(deftest every-action-the-server-can-offer-has-a-label
  (testing "an unlabelled button is a button nobody dares press"
    (doseq [a [:start-game :leave :set-locale :chat
               :amor-link :seherin-inspect :werewolf-vote
               :hexe-heal :hexe-poison :hexe-pass
               :jaeger-shoot :nominate :vote :advance-phase
               :gm/force-kill :gm/revive :gm/skip-role :gm/set-phase]]
      (is (not (re-find #"^:" (text/action-name a)))
          (str a " has no label")))))

(deftest the-log-renders-structured-events-as-sentences
  (let [players {0 {:name "Anna"} 1 {:name "Bob"}}]
    (is (= "Anna ist beigetreten"
           (text/log-line players {:type :player/joined :player 0})))
    (is (= "Bob ist gestorben — von Werwölfen gerissen"
           (text/log-line players {:type :player/died :player 1 :cause :werwolf})))
    (is (= "Das Dorf kann sich nicht einigen — niemand stirbt"
           (text/log-line players {:type :vote/resolved :target nil :tie? true})))
    (testing "an unnamed player is still identifiable — the log holds ids, so a
              name change must not be able to rewrite history"
      (is (= "#7 ist beigetreten"
             (text/log-line players {:type :player/joined :player 7}))))))
