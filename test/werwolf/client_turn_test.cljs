(ns werwolf.client-turn-test
  "Whose turn it is, as the client decides it from the offered actions."
  (:require [cljs.test :refer [deftest is testing]]
            [werwolf.subs :as sub]))

(deftest the-spielleitung-powers-are-never-your-turn
  (testing "a host who plays along holds GM powers all night; with nothing of
            their own to do, it is not their turn — the Seherin's is"
    (is (empty? (sub/player-moves #{:chat :leave :set-locale :advance-phase
                                    :gm/force-kill :gm/skip-role :gm/pause}))))
  (testing "their own night move still counts, and only it is listed"
    (is (= [:seherin-inspect]
           (sub/player-moves #{:chat :seherin-inspect :advance-phase :gm/pause}))))
  (testing "claiming an absent host's seat is offered to all and waited on by none"
    (is (empty? (sub/player-moves #{:chat :claim-host}))))
  (testing "sorted, so the action bar's order is stable"
    (is (= [:vote :vote-abstain] (sub/player-moves #{:vote-abstain :vote :chat})))))
