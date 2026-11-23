(ns werwolf.replays-test
  "Every log in the corpus replays to what it said when it was written. See
   werwolf.replays for what the corpus is, and for `pin!`, which rewrites it
   when a change to the replay is meant."
  (:require [clojure.test :refer [deftest is testing]]
            [werwolf.engine.roles :as roles]
            [werwolf.replays :as replays]))

(deftest every-stored-game-replays-as-it-was-written
  (let [corpus (replays/load-corpus)]
    (is (= (set (map first replays/corpus)) (set (keys corpus)))
        "one file per game, none lost and none left over")
    (doseq [[nm {:keys [log pinned]}] corpus]
      (testing nm
        (let [now (replays/pinned log)]
          (doseq [k [:winner :phase :seats-issued :seats :public]]
            (is (= (get pinned k) (get now k))
                (str nm " replays to a different " k
                     ". If that is meant, re-pin with (werwolf.replays/pin!)"
                     " and say so in the commit."))))))
    (testing "between them the games still deal every role"
      (is (= (set roles/all-ids)
             (into #{} (comp (mapcat (comp vals :seats :pinned)) (keep first))
                   (vals corpus)))))))
