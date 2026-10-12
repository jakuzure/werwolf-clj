(ns werwolf.client-deck-test
  "The host's half-sent deck, and the room it belongs to.

   `:deck/draft` is the count a stepper tap asked for and the server has not yet
   shown back. It is per-room, like the view, so every way out of a seat has
   to drop it — or the next room a host opens starts from the last one's tap."
  (:require [cljs.test :refer [deftest is testing]]
            [werwolf.events :as ev]))

(def ^:private host-mid-tap
  "A host in a lobby whose last tap is still on its way."
  (let [seated (ev/handle-server ev/initial-db
                                 {:type :server/welcome :room-id "ABCD" :player-id 0
                                  :host? true :token "t"})
        db     (assoc seated :view {:phase :lobby :deck {:werwolf 1}})]
    (:db (ev/deck-step-fx {:db db} [::ev/deck-step :werwolf 1 nil]))))

(deftest every-way-out-of-a-seat-drops-the-draft
  (is (= {:werwolf 2} (:deck/draft host-mid-tap)) "the precondition: a tap in flight")
  (testing "leaving"
    (is (nil? (:deck/draft (:db (ev/leave-fx {:db host-mid-tap} nil))))))
  (testing "being released by a leave in another tab"
    (is (nil? (:deck/draft (ev/handle-server host-mid-tap
                                             {:type :server/left :room-id "ABCD"})))))
  (testing "a fatal error throwing the session away"
    (is (nil? (:deck/draft (ev/handle-server host-mid-tap
                                             {:type :server/error :code :seat-gone})))))
  (testing "nor does a draft survive into the next room's welcome"
    (is (nil? (:deck/draft (ev/handle-server host-mid-tap
                                             {:type :server/welcome :room-id "WXYZ"
                                              :player-id 0 :host? true :token "u"}))))))
