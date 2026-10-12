(ns werwolf.password-test
  "JVM-only: the one place a room password exists as plaintext is the moment
   it is hashed or checked, and this is that place."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [werwolf.password :as password]))

(deftest a-hash-verifies-its-own-password-and-no-other
  (let [h (password/hash-password "Vollmond")]
    (is (password/verify "Vollmond" h))
    (is (not (password/verify "Neumond" h)))
    (is (not (password/verify "" h)))
    (is (not (password/verify nil h)))))

(deftest the-hash-is-not-the-password
  (let [h (password/hash-password "Vollmond")]
    (is (not (str/includes? (str/lower-case h) "vollmond")))
    (testing "and says how it was made, so the work factor can change later
              without orphaning every room that already has a password"
      (let [[scheme iter salt hash :as parts] (str/split h #"\$")]
        (is (= 4 (count parts)))
        (is (= "pbkdf2-sha256" scheme))
        (is (>= (parse-long iter) 100000))
        (is (= 16 (count (.decode (java.util.Base64/getDecoder) ^String salt))))
        (is (= 32 (count (.decode (java.util.Base64/getDecoder) ^String hash))))))))

(deftest every-hash-has-its-own-salt
  (testing "the same password twice must not hash the same, or one leaked log
            says which rooms share a password"
    (let [hs (repeatedly 5 #(password/hash-password "Vollmond"))]
      (is (= 5 (count (set hs))))
      (is (= 5 (count (set (map #(nth (str/split % #"\$") 2) hs)))))
      (is (every? #(password/verify "Vollmond" %) hs)))))

(deftest what-a-phone-keyboard-does-to-a-word-does-not-lock-you-out
  (testing "a password is read aloud and typed on a phone, which capitalises the
            first letter and sometimes adds a space after an accepted suggestion"
    (let [h (password/hash-password "vollmond")]
      (is (password/verify "Vollmond" h))
      (is (password/verify "  VOLLMOND " h)))
    (testing "and an umlaut is the same umlaut however the keyboard composed it"
      (let [h (password/hash-password "Mühle")]       ; precomposed ü
        (is (password/verify "Mühle" h))))))         ; u + combining diaeresis

(deftest blank-is-not-a-password
  (is (password/blank? nil))
  (is (password/blank? "   "))
  (is (not (password/blank? " a ")))
  (is (thrown? IllegalArgumentException (password/hash-password "  "))))

(deftest a-malformed-hash-verifies-nothing-and-throws-nothing
  (testing "the hash comes from our own log, but a check that can throw would
            take a join — and the room's lock — down with it"
    (doseq [bad [nil "" "x" "plain-text-password"
                 "pbkdf2-sha256$abc$AAAA$AAAA"
                 "pbkdf2-sha256$100000$not base64!$AAAA"
                 "pbkdf2-sha256$100000$AAAAAAAAAAAAAAAAAAAAAA==$"
                 "md5$100000$AAAAAAAAAAAAAAAAAAAAAA==$AAAA"
                 ;; A work factor nobody chose: verifying it would pin a CPU.
                 "pbkdf2-sha256$2000000000$AAAAAAAAAAAAAAAAAAAAAA==$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
                 "pbkdf2-sha256$0$AAAAAAAAAAAAAAAAAAAAAA==$AAAA"]]
      (is (false? (password/verify "Vollmond" bad)) (pr-str bad)))))
