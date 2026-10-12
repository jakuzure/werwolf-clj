(ns werwolf.client-kick-test
  "The page of a player the host removed: what it shows, what it forgets, and
   that it hangs up. Pure, like the rest of the client's reducer tests."
  (:require [cljs.test :refer [deftest is testing]]
            [werwolf.events :as ev]
            [werwolf.i18n.core :as i18n]))

(defn- apply* [db & msgs]
  (reduce ev/handle-server db msgs))

(def ^:private seated
  (apply* ev/initial-db
          {:type :server/welcome :room-id "ABCD" :player-id 2 :host? false :token "t"}
          {:type :room/view :seq 3 :view {:phase :lobby}}))

(deftest being-removed-lands-you-on-the-landing-screen-and-says-why
  (let [db' (apply* seated {:type :server/kicked :room-id "ABCD"})]
    (is (= :landing (:screen db')))
    (is (nil? (:me db')))
    (is (nil? (:view db')))
    (is (nil? (:view/seq db')) "or the next room's views are dropped as old")
    (is (= :kicked (:error db'))
        "arriving on the start screen unasked, without a reason, reads as a crash")
    (testing "in the player's own language"
      (is (= "Du wurdest aus der Runde entfernt" (i18n/t :de :error/kicked)))
      (is (= "You were removed from the game" (i18n/t :en :error/kicked))))))

(deftest being-removed-forgets-the-seat-and-hangs-up
  (let [out (ev/server-message-fx {:db seated}
                                  [nil {:type :server/kicked :room-id "ABCD"}])]
    (is (true? (:werwolf.ws/forget-seat out))
        "the token names a seat that is gone; the next load must not try it")
    (is (true? (:werwolf.audio/disconnect out))
        "a live microphone in a room you were taken out of is the worst bug there is")
    (is (= [::ev/refresh-rooms] (:dispatch out)))
    (is (nil? (:werwolf.ws/send out))
        "nothing to tell the server: it did this")))

(deftest a-removal-from-a-room-you-are-not-in-changes-nothing
  (let [elsewhere (apply* ev/initial-db
                          {:type :server/welcome :room-id "WXYZ" :player-id 1
                           :host? false :token "t"})]
    (is (= elsewhere (apply* elsewhere {:type :server/kicked :room-id "ABCD"})))
    (is (nil? (:werwolf.ws/forget-seat
               (ev/server-message-fx {:db elsewhere}
                                     [nil {:type :server/kicked :room-id "ABCD"}]))))))

(deftest a-half-confirmed-removal-does-not-follow-the-host-into-the-next-room
  (testing "it names a seat number, and the next room has one of those too"
    (let [armed (assoc-in seated [:form :gm] {:confirm-kick 3})
          left  (:db (ev/leave-fx {:db armed} nil))]
      (is (nil? (get-in left [:form :gm :confirm-kick]))))))
