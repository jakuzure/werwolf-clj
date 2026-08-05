(ns werwolf.token-test
  "JVM-only: signing is a server concern; the client only stores the opaque
   string it was handed."
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [werwolf.token :as token]))

(def cfg {:secret "test-secret-that-is-long-enough" :ttl-mins 720})

(def t0 1700000000000)

(defn- mint
  ([] (mint {}))
  ([overrides]
   (token/issue cfg (merge {:room-id "ABC" :player-id 3 :host? false :now-ms t0}
                           overrides))))

(defn- mint-with-other-secret []
  (token/issue (assoc cfg :secret "a-completely-different-secret")
               {:room-id "ABC" :player-id 3 :now-ms t0}))

(defn- b64url [^String s]
  (-> (java.util.Base64/getUrlEncoder) .withoutPadding (.encodeToString (.getBytes s))))

(deftest round-trips-identity
  (let [tok (mint {:host? true})]
    (is (= {:room-id "ABC" :player-id 3 :host? true}
           (token/verify cfg tok {:expect-room "ABC" :now-ms t0})))))

(deftest player-zero-survives
  (testing "player 0 is a real id and `int?` must not be confused with truthy —
            the host is player 0 in every game, so getting this wrong would
            break exactly the person who created the room"
    (let [tok (mint {:player-id 0 :host? true})]
      (is (= 0 (:player-id (token/verify cfg tok {:expect-room "ABC" :now-ms t0})))))))

(deftest tampering-is-rejected
  (testing "a token is not a claim the holder gets to edit"
    (let [tok (mint)
          [header payload sig] (str/split tok #"\.")]
      (are [label bad] (= {:error :bad-signature}
                          (token/verify cfg bad {:expect-room "ABC" :now-ms t0}))
        "flipped signature"  (str header "." payload "." (str/reverse sig))
        "re-signed payload"  (mint-with-other-secret)
        "swapped payload"    (str header "."
                                  (b64url "{\"room\":\"ABC\",\"pid\":99,\"exp\":9999999999}")
                                  "." sig)))))

(deftest garbage-is-rejected-not-thrown
  (testing "a hostile client will send whatever it likes; verify must return,
            never throw, or one bad frame kills the read loop"
    (are [bad] (contains? #{:malformed :bad-signature}
                          (:error (token/verify cfg bad {:expect-room "ABC" :now-ms t0})))
      nil
      ""
      "not-a-jwt"
      "a.b.c"
      {:room "ABC" :pid 3}
      12345)))

(deftest expiry-is-enforced
  (let [tok (mint)
        ttl-ms (* 720 60 1000)]
    (testing "valid right up to the deadline"
      (is (nil? (:error (token/verify cfg tok {:expect-room "ABC"
                                               :now-ms (+ t0 ttl-ms -1000)})))))
    (testing "and refused after it"
      (is (= {:error :expired}
             (token/verify cfg tok {:expect-room "ABC" :now-ms (+ t0 ttl-ms 1000)}))))))

(deftest a-token-is-bound-to-its-room
  (testing "the signature is valid; the seat is not. Without this check a
            player could carry seat 3 into a room they were never dealt into"
    (let [tok (mint)]
      (is (= {:error :wrong-room}
             (token/verify cfg tok {:expect-room "XYZ" :now-ms t0}))))))

(deftest different-players-get-different-tokens
  (is (not= (mint {:player-id 1}) (mint {:player-id 2})))
  (is (not= (mint {:room-id "ABC"}) (mint {:room-id "DEF"}))))

(deftest the-dev-secret-is-recognised-as-insecure
  (testing "so the server can refuse to boot with it in production"
    (is (token/insecure-secret? {:secret token/default-secret}))
    (is (token/insecure-secret? {:secret nil}))
    (is (token/insecure-secret? {:secret "short"}))
    (is (not (token/insecure-secret? cfg)))))
