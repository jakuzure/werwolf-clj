(ns werwolf.engine.rng-test
  "The cross-platform contract for the engine's PRNG.

   These tests run on BOTH the JVM (kaocha) and Node (shadow-cljs :test-node).
   The hardcoded vectors below are the whole point: if Clojure and ClojureScript
   ever disagree, dealing roles becomes platform-dependent and replay breaks."
  (:require [clojure.test :refer [deftest is testing]]
            [werwolf.engine.rng :as rng]))

(deftest shuffle-is-deterministic
  (testing "same seed yields the same permutation"
    (is (= (second (rng/shuffle 42 (range 10)))
           (second (rng/shuffle 42 (range 10))))))
  (testing "different seeds generally differ"
    (is (not= (second (rng/shuffle 42 (range 10)))
              (second (rng/shuffle 43 (range 10)))))))

(deftest shuffle-preserves-elements
  (doseq [seed [0 1 42 9999 4294967295]]
    (let [[_ shuffled] (rng/shuffle seed (range 25))]
      (is (= (sort shuffled) (range 25))
          (str "seed " seed " must produce a permutation")))))

(deftest cross-platform-exact-values
  (testing "shuffle of (range 10) with seed 42 — pinned across clj and cljs"
    (is (= [3 9 2 8 5 1 4 0 6 7]
           (vec (second (rng/shuffle 42 (range 10)))))))
  (testing "next-seed sequence from 1 — pinned across clj and cljs"
    (is (= [1 1015568748 1586005467 2165703038]
           (vec (take 4 (iterate rng/next-seed 1)))))))

(deftest rand-int-stays-in-range
  (loop [s 7, n 0]
    (when (< n 500)
      (let [[s' v] (rng/rand-int s 6)]
        (is (and (<= 0 v) (< v 6)))
        (recur s' (inc n))))))

(deftest rand-int-handles-degenerate-bounds
  (is (= 0 (second (rng/rand-int 5 1))))
  (is (= 0 (second (rng/rand-int 5 0)))))
