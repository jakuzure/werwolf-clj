(ns werwolf.engine.rng
  "A tiny deterministic PRNG that produces IDENTICAL results in Clojure and
   ClojureScript.

   Why not java.util.Random: the engine is .cljc and the test suite runs on both
   platforms, so a JVM-only RNG would make results platform-dependent. Why not
   bit-ops: CLJS bit operations are 32-bit signed while Clojure's are 64-bit, so
   they diverge.

   This is a Numerical-Recipes LCG using plain integer arithmetic. The largest
   intermediate product is 1664525 * (2^32-1) ~= 7.15e15, which is below 2^53
   and therefore exactly representable as a JS double — so both platforms agree
   on every bit.

   Functional style: every call returns [new-seed value]. No mutable state, so
   a shuffle is reproducible from its seed forever."
  (:refer-clojure :exclude [shuffle rand-int rand-nth]))

(def ^:const modulus 4294967296) ; 2^32
(def ^:const multiplier 1664525)
(def ^:const increment 1013904223)

(defn next-seed [seed]
  (mod (+ (* (mod seed modulus) multiplier) increment) modulus))

(defn rand-int
  "Return [seed' n] where 0 <= n < bound. Uses the high bits, which have a much
   longer period than the low bits in an LCG."
  [seed bound]
  (let [s' (next-seed seed)]
    [s' (if (<= bound 1) 0 (mod (quot s' 65536) bound))]))

(defn rand-nth [seed coll]
  (let [v (vec coll)
        [s' i] (rand-int seed (count v))]
    [s' (nth v i)]))

(defn shuffle
  "Fisher-Yates. Returns [seed' shuffled-vector]."
  [seed coll]
  (let [v (vec coll)]
    (loop [i (dec (count v)), s seed, acc v]
      (if (pos? i)
        (let [[s' j] (rand-int s (inc i))]
          (recur (dec i) s' (assoc acc i (acc j) j (acc i))))
        [s acc]))))
